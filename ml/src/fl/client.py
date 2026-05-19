"""Flower client wrapping the MindWave Keras LSTM.

The whole point of Federated Learning in MindWave is that raw biometric
data never leaves the device. In this implementation:
* the client owns training and test splits, plus a fitted scaler;
* the flower transport only ever exchanges the model weights, a list of
tensors that encode the aggregate of what the network has learnt;
* the server can only see the weights coming back from each client and
the metrics returned after training and evaluating.
"""
from __future__ import annotations

from typing import Callable

import flwr as fl
import numpy as np
import tensorflow as tf

from ..config import set_global_seed
from ..model import build_lstm
from .data_partition import ClientPartition


# NumPyClient
class MindWaveClient(fl.client.NumPyClient):
    """One smartwatch user, simulated by one WESAD subject."""

    def __init__(
        self,
        cid: str,
        model: tf.keras.Model,
        X_train: np.ndarray,
        y_train: np.ndarray,
        X_test: np.ndarray,
        y_test: np.ndarray,
        local_epochs: int = 2,
        batch_size: int = 32,
    ) -> None:
        self.cid = cid
        self.model = model
        self.X_train, self.y_train = X_train, y_train
        self.X_test, self.y_test = X_test, y_test
        self.local_epochs = local_epochs
        self.batch_size = batch_size

    # Flower numpy interface
    def get_parameters(self, config):
        """Return current model weights"""
        return self.model.get_weights()

    def fit(self, parameters, config):
        """Local SGD on this client's private data only"""
        self.model.set_weights(parameters)
        epochs = int(config.get("local_epochs", self.local_epochs))
        batch = int(config.get("batch_size", self.batch_size))

        history = self.model.fit(
            self.X_train, self.y_train,
            epochs=epochs, batch_size=batch, verbose=0,
        )
        # Returned metrics are scalar aggregates over the local epochs.
        loss = float(history.history["loss"][-1])
        acc = float(history.history["accuracy"][-1])
        return self.model.get_weights(), len(self.X_train), {
            "loss": loss, "accuracy": acc,
        }

    def evaluate(self, parameters, config):
        """Evaluate on the held-out 20 % of this client's own data."""
        self.model.set_weights(parameters)
        loss, acc = self.model.evaluate(
            self.X_test, self.y_test, verbose=0,
        )
        return float(loss), len(self.X_test), {"accuracy": float(acc)}


# client_fn factory used by the Flower simulation runtime
def make_client_fn(
    partitions: dict[str, ClientPartition],
    init_weights: list[np.ndarray] | None,
    local_epochs: int = 2,
    batch_size: int = 32,
) -> Callable[[fl.common.Context], fl.client.Client]:
    """Build a client_fn bound to the given partitions.
    Flower's simulation calls this function once per virtual client.
    """
    sorted_subjects = sorted(partitions.keys())
    n_classes = int(
        max(p.y_train.max() for p in partitions.values()) + 1
    )
    input_shape = next(iter(partitions.values())).X_train.shape[1:]

    def client_fn(context: fl.common.Context) -> fl.client.Client:
        set_global_seed()
        partition_id = int(context.node_config["partition-id"])
        sid = sorted_subjects[partition_id]
        part = partitions[sid]

        # Each virtual client builds its own fresh Keras model
        model = build_lstm(input_shape=input_shape, n_classes=n_classes)
        if init_weights is not None:
            model.set_weights(init_weights)

        return MindWaveClient(
            cid=sid,
            model=model,
            X_train=part.X_train, y_train=part.y_train,
            X_test=part.X_test,   y_test=part.y_test,
            local_epochs=local_epochs,
            batch_size=batch_size,
        ).to_client()

    return client_fn


__all__ = ["MindWaveClient", "make_client_fn"]

