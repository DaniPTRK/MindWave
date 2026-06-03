# Evaluation metrics + plots, plus Keras-vs-TFLite parity check
from __future__ import annotations

from pathlib import Path
from typing import Sequence

import matplotlib.pyplot as plt
import numpy as np
import seaborn as sns
from sklearn.metrics import (
    classification_report,
    confusion_matrix,
    precision_recall_curve,
    roc_curve,
    auc,
)

from .config import LABEL_NAMES


def metrics_dict(
    y_true: np.ndarray,
    y_pred: np.ndarray,
    label_names: dict | None = None,
) -> dict:
    if label_names is None:
        label_names = LABEL_NAMES
    target_names = [label_names[i] for i in sorted(label_names)]
    rep = classification_report(
        y_true, y_pred, target_names=target_names, output_dict=True, zero_division=0
    )
    return {
        "accuracy": float(rep["accuracy"]),
        "macro_f1": float(rep["macro avg"]["f1-score"]),
        "weighted_f1": float(rep["weighted avg"]["f1-score"]),
        "per_class": {
            name: {
                "precision": float(rep[name]["precision"]),
                "recall": float(rep[name]["recall"]),
                "f1": float(rep[name]["f1-score"]),
                "support": int(rep[name]["support"]),
            }
            for name in target_names
        },
    }


def plot_confusion_matrix(
    y_true: np.ndarray,
    y_pred: np.ndarray,
    normalize: bool = True,
    ax: plt.Axes | None = None,
    title: str = "Confusion matrix",
    label_names: dict | None = None,
) -> plt.Axes:
    if label_names is None:
        label_names = LABEL_NAMES
    cm = confusion_matrix(y_true, y_pred, labels=sorted(label_names))
    if normalize:
        cm = cm.astype(float) / cm.sum(axis=1, keepdims=True).clip(min=1)
    if ax is None:
        _, ax = plt.subplots(figsize=(4, 3.5))
    sns.heatmap(
        cm,
        annot=True,
        fmt=".2f" if normalize else "d",
        cmap="Blues",
        xticklabels=[label_names[i] for i in sorted(label_names)],
        yticklabels=[label_names[i] for i in sorted(label_names)],
        cbar=False,
        ax=ax,
    )
    ax.set_xlabel("Predicted")
    ax.set_ylabel("True")
    ax.set_title(title)
    return ax


def plot_training_curves(history: dict, ax: Sequence[plt.Axes] | None = None):
    if ax is None:
        _, ax = plt.subplots(1, 2, figsize=(10, 3.5))
    ax[0].plot(history["loss"], label="train")
    if "val_loss" in history:
        ax[0].plot(history["val_loss"], label="val")
    ax[0].set_title("Loss"); ax[0].set_xlabel("epoch"); ax[0].legend()
    ax[1].plot(history["accuracy"], label="train")
    if "val_accuracy" in history:
        ax[1].plot(history["val_accuracy"], label="val")
    ax[1].set_title("Accuracy"); ax[1].set_xlabel("epoch"); ax[1].legend()
    return ax


def plot_roc_pr(
    y_true: np.ndarray,
    y_score: np.ndarray,
    label_names: dict | None = None,
) -> plt.Figure:
    if label_names is None:
        label_names = LABEL_NAMES
    n_classes = y_score.shape[1]
    fig, axes = plt.subplots(1, 2, figsize=(11, 4))

    if n_classes == 2:
        # Binary: single ROC + PR curve for the positive (stress) class.
        y_bin = (y_true == 1).astype(int)
        fpr, tpr, _ = roc_curve(y_bin, y_score[:, 1])
        prec, rec, _ = precision_recall_curve(y_bin, y_score[:, 1])
        pos_label = label_names.get(1, "positive")
        axes[0].plot(fpr, tpr, label=f"{pos_label} (AUC={auc(fpr, tpr):.2f})")
        axes[1].plot(rec, prec, label=f"{pos_label} (AP={auc(rec, prec):.2f})")
    else:
        # Multi-class: one-vs-rest curve per class.
        for c in range(n_classes):
            y_bin = (y_true == c).astype(int)
            fpr, tpr, _ = roc_curve(y_bin, y_score[:, c])
            prec, rec, _ = precision_recall_curve(y_bin, y_score[:, c])
            axes[0].plot(fpr, tpr, label=f"{label_names[c]} (AUC={auc(fpr, tpr):.2f})")
            axes[1].plot(rec, prec, label=f"{label_names[c]} (AP={auc(rec, prec):.2f})")

    axes[0].plot([0, 1], [0, 1], "k--", alpha=0.4)
    roc_title = "ROC" if n_classes == 2 else "ROC (one-vs-rest)"
    axes[0].set_title(roc_title); axes[0].set_xlabel("FPR"); axes[0].set_ylabel("TPR"); axes[0].legend()
    axes[1].set_title("Precision-Recall"); axes[1].set_xlabel("Recall"); axes[1].set_ylabel("Precision"); axes[1].legend()
    fig.tight_layout()
    return fig


def compare_keras_vs_tflite(
    keras_preds: np.ndarray,
    tflite_preds: np.ndarray,
) -> dict:
    diff = np.abs(keras_preds - tflite_preds)
    agree = (keras_preds.argmax(1) == tflite_preds.argmax(1)).mean()
    return {
        "max_abs_diff": float(diff.max()),
        "mean_abs_diff": float(diff.mean()),
        "argmax_agreement": float(agree),
    }


__all__ = [
    "metrics_dict",
    "plot_confusion_matrix",
    "plot_training_curves",
    "plot_roc_pr",
    "compare_keras_vs_tflite",
]

