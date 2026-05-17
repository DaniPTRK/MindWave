# Keras LSTM factory, small enough to run on a wearable via TFLite
from __future__ import annotations

from typing import Sequence

import tensorflow as tf
from tensorflow.keras import layers, models, regularizers


def build_lstm(
    input_shape: tuple[int, int],
    n_classes: int = 3,
    units: Sequence[int] = (64, 32),
    dropout: float = 0.3,
    l2: float = 1e-4,
    learning_rate: float = 1e-3,
) -> tf.keras.Model:
    """Return a compiled LSTM classifier.
    The standard Keras LSTM layer converts cleanly to TFLite's UNIDIRECTIONAL_SEQUENCE_LSTM.
    """
    reg = regularizers.L2(l2)
    inputs = layers.Input(shape=input_shape, name="features")
    x = layers.Masking(mask_value=0.0)(inputs)

    for i, u in enumerate(units):
        return_sequences = i < len(units) - 1
        x = layers.LSTM(
            u,
            return_sequences=return_sequences,
            dropout=0.0, # internal dropout breaks TFLite
            recurrent_dropout=0.0, # same reason
            unroll=True, # unrolling set to true because we deal with short, time-series windows
            kernel_regularizer=reg,
            recurrent_regularizer=reg,
            name=f"lstm_{i}",
        )(x)
        if dropout > 0:
            x = layers.Dropout(dropout, name=f"dropout_{i}")(x)

    x = layers.Dense(32, activation="relu", kernel_regularizer=reg, name="dense_proj")(x)
    if dropout > 0:
        x = layers.Dropout(dropout, name="dropout_head")(x)
    outputs = layers.Dense(n_classes, activation="softmax", name="stress_probs")(x)

    model = models.Model(inputs, outputs, name="mindwave_stress_lstm")
    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=learning_rate),
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"],
    )
    return model


__all__ = ["build_lstm"]

