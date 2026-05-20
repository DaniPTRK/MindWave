"""Explainable AI (XAI) on Edge using gradient-based feature importance

This module provides the implementation of the XAI logic that will run on the
Android device as the TFLite explain signature.

We will use Vanilla Gradient Saliency, which computes the absolute value
of the gradient of the predicted class with respect to the input features.
This method is computationally efficient and straightforward to implement in TFlite,
making it suitable for on-device explanations. The output is a per-feature importance score
that can be used to identify which features contributed most to the model's prediction for a given
input window.
"""
from __future__ import annotations

from typing import Dict

import numpy as np
import tensorflow as tf

from .features import SUBWINDOW_FEATURE_NAMES


def gradient_feature_importance(
    model: tf.keras.Model,
    x_window: np.ndarray,
    y: int | None = None,
) -> Dict[str, float]:
    """Compute per-feature importance via vanilla gradient saliency."""
    if x_window.ndim == 2:
        x_window = x_window[np.newaxis, ...]

    # x_tensor must be created inside the tape scope so the tape records
    # the variable from the first op
    with tf.GradientTape() as tape:
        x_tensor = tf.Variable(x_window, trainable=True, dtype=tf.float32)
        logits = model(x_tensor, training=False)
        target_class = y if y is not None else int(tf.argmax(logits, axis=-1).numpy()[0])
        prob = logits[0, target_class]

    grad = tape.gradient(prob, x_tensor)
    if grad is None:
        return {name: 0.0 for name in SUBWINDOW_FEATURE_NAMES}

    importances = tf.reduce_mean(tf.abs(grad), axis=1).numpy()[0]

    return {
        name: float(importances[i])
        for i, name in enumerate(SUBWINDOW_FEATURE_NAMES)
    }


def top_k_features(importance_dict: Dict[str, float], k: int = 5) -> list[tuple[str, float]]:
    """Return the top-k features sorted by descending importance."""
    return sorted(importance_dict.items(), key=lambda kv: kv[1], reverse=True)[:k]


def normalise(importance_dict: Dict[str, float]) -> Dict[str, float]:
    """L1-normalise so all importances sum to 1."""
    total = sum(importance_dict.values())
    if total == 0:
        return importance_dict
    return {k: v / total for k, v in importance_dict.items()}


__all__ = [
    "gradient_feature_importance",
    "top_k_features",
    "normalise",
]

