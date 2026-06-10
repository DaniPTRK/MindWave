"""Train the baseline LSTM stress detector on the windowed WESAD dataset.

Two modes:

* --mode holdout: single subject-wise hold-out (S13 + S16 by default).
  Fast iteration; produces models/holdout_metrics.json.
* --mode loso: full Leave-One-Subject-Out cross-validation.
  Produces models/loso_metrics.json + per-fold details.

In both modes we also train a final model on all subjects and export it
to models/mindwave_stress.keras + float and int8 .tflite files.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Iterable

import joblib
import numpy as np
import tensorflow as tf
from sklearn.preprocessing import StandardScaler
from sklearn.utils.class_weight import compute_class_weight

from .config import LABEL_CONFIGS, LABEL_NAMES, MODELS_DIR, PROCESSED_DIR, set_global_seed
from .evaluate import compare_keras_vs_tflite, metrics_dict
from .model import build_lstm
from .tflite_export import convert_float, convert_int8, run_tflite

DEFAULT_HOLDOUT_SUBJECTS = ("S13", "S16")


# Data helpers
def load_npz(path: Path):
    npz = np.load(path, allow_pickle=True)
    return (
        npz["X"].astype(np.float32),
        npz["y"].astype(np.int64),
        npz["subject_ids"].astype(str),
        npz["feature_names"].astype(str),
    )


def fit_scaler_3d(X_train: np.ndarray) -> StandardScaler:
    """StandardScaler is 2-D — flatten the time axis for fitting."""
    n, t, f = X_train.shape
    scaler = StandardScaler()
    scaler.fit(X_train.reshape(-1, f))
    return scaler


def apply_scaler_3d(scaler: StandardScaler, X: np.ndarray) -> np.ndarray:
    n, t, f = X.shape
    return scaler.transform(X.reshape(-1, f)).reshape(n, t, f).astype(np.float32)


def maybe_binarize(y: np.ndarray, binary: bool):
    """Optional remap to {0=non-stress, 1=stress}."""
    if not binary:
        return y, LABEL_NAMES
    y_bin = (y == 1).astype(np.int64)
    return y_bin, LABEL_NAMES


def apply_label_config(X: np.ndarray, y: np.ndarray, subject_ids: np.ndarray, name: str):
    """Apply a named binary label configuration.

    Operates on the already-remapped npz labels {0=baseline, 1=stress,
    2=amusement}. Returns filtered (X, y, subject_ids, label_names).
    """
    from .config import LABEL_CONFIGS

    cfg = LABEL_CONFIGS[name]
    if name == "binary_strict":
        mask = np.isin(y, [0, 1])
        X, y, subject_ids = X[mask], y[mask], subject_ids[mask]
        y = y.astype(np.int64)
    else:
        y = (y == 1).astype(np.int64)
    print(f"Label config '{name}': {cfg['description']} -> X={X.shape}")
    return X, y, subject_ids, {0: "non_stress", 1: "stress"}


# Time-series augmentation
def _augment_windows(
    X: np.ndarray,
    y: np.ndarray,
    noise_std: float = 0.05,
    rng: np.random.Generator | None = None,
) -> tuple[np.ndarray, np.ndarray]:
    """Return an augmented copy of (X, y) with per-feature Gaussian noise."""
    if rng is None:
        rng = np.random.default_rng()
    noise = rng.standard_normal(X.shape).astype(np.float32) * noise_std
    return X + noise, y.copy()


# Single train/eval round
def train_one_fold(
    X_train: np.ndarray,
    y_train: np.ndarray,
    X_test: np.ndarray,
    y_test: np.ndarray,
    n_classes: int,
    epochs: int,
    batch_size: int,
    verbose: int = 1,
    label_names: dict | None = None,
):
    scaler = fit_scaler_3d(X_train)
    Xtr = apply_scaler_3d(scaler, X_train)
    Xte = apply_scaler_3d(scaler, X_test)

    # Augment training set: original + one noisy copy
    rng = np.random.default_rng(42)
    Xtr_aug, y_aug = _augment_windows(Xtr, y_train, noise_std=0.05, rng=rng)
    Xtr = np.concatenate([Xtr, Xtr_aug], axis=0)
    y_train = np.concatenate([y_train, y_aug], axis=0)

    classes = np.arange(n_classes)
    cw = compute_class_weight("balanced", classes=classes, y=y_train)
    class_weight = {int(c): float(w) for c, w in zip(classes, cw)}

    model = build_lstm(input_shape=Xtr.shape[1:], n_classes=n_classes)
    callbacks = [
        tf.keras.callbacks.EarlyStopping(
            patience=15, restore_best_weights=True, monitor="val_loss"
        ),
        tf.keras.callbacks.ReduceLROnPlateau(
            patience=7, factor=0.5, monitor="val_loss", min_lr=1e-5
        ),
    ]
    history = model.fit(
        Xtr, y_train,
        validation_data=(Xte, y_test),
        epochs=epochs, batch_size=batch_size,
        class_weight=class_weight,
        callbacks=callbacks, verbose=verbose,
    )
    y_score = model.predict(Xte, verbose=0)
    y_pred = y_score.argmax(1)
    metrics = metrics_dict(y_test, y_pred, label_names=label_names)
    return model, scaler, history.history, metrics, y_score


# Modes
def run_holdout(X, y, subject_ids, holdout: Iterable[str], n_classes: int,
                epochs: int, batch_size: int, label_names: dict | None = None):
    holdout = set(holdout)
    test_mask = np.isin(subject_ids, list(holdout))
    train_mask = ~test_mask
    print(f"Hold-out subjects: {sorted(holdout)} | "
          f"train windows={train_mask.sum()} test windows={test_mask.sum()}")
    return train_one_fold(
        X[train_mask], y[train_mask], X[test_mask], y[test_mask],
        n_classes, epochs, batch_size, verbose=2, label_names=label_names,
    )


def run_loso(X, y, subject_ids, n_classes: int, epochs: int, batch_size: int,
             label_names: dict | None = None):
    fold_metrics = {}
    for sid in sorted(np.unique(subject_ids)):
        test_mask = subject_ids == sid
        train_mask = ~test_mask
        if test_mask.sum() == 0 or train_mask.sum() == 0:
            continue
        print(f"\n=== LOSO fold: held-out subject = {sid} "
              f"(train={train_mask.sum()}, test={test_mask.sum()}) ===")
        _, _, _, m, _ = train_one_fold(
            X[train_mask], y[train_mask], X[test_mask], y[test_mask],
            n_classes, epochs, batch_size, verbose=0, label_names=label_names,
        )
        print(f"   accuracy={m['accuracy']:.3f}  macro_f1={m['macro_f1']:.3f}")
        fold_metrics[sid] = m
    accs = [m["accuracy"] for m in fold_metrics.values()]
    f1s = [m["macro_f1"] for m in fold_metrics.values()]
    summary = {
        "per_fold": fold_metrics,
        "mean_accuracy": float(np.mean(accs)),
        "std_accuracy": float(np.std(accs)),
        "mean_macro_f1": float(np.mean(f1s)),
        "std_macro_f1": float(np.std(f1s)),
        "n_folds": len(fold_metrics),
    }
    return summary

# Final model export
def train_final_and_export(X, y, n_classes: int, epochs: int, batch_size: int,
                           label_names: dict, feature_names: np.ndarray):
    print("\n Training final model on all subjects")
    scaler = fit_scaler_3d(X)
    Xs = apply_scaler_3d(scaler, X)
    cw = compute_class_weight("balanced", classes=np.arange(n_classes), y=y)
    class_weight = {int(c): float(w) for c, w in enumerate(cw)}

    model = build_lstm(input_shape=Xs.shape[1:], n_classes=n_classes)
    model.fit(
        Xs, y, epochs=epochs, batch_size=batch_size,
        class_weight=class_weight, verbose=2,
    )

    # Persist artifacts
    keras_path = MODELS_DIR / "mindwave_stress.keras"
    model.save(keras_path)

    tflite_float = convert_float(model, MODELS_DIR / "mindwave_stress.tflite")
    tflite_int8 = convert_int8(
        model, MODELS_DIR / "mindwave_stress_int8.tflite",
        representative_data=Xs, n_samples=200,
    )

    joblib.dump(scaler, MODELS_DIR / "scaler.pkl")
    from .tflite_training_export import export_scaler_params
    export_scaler_params(scaler, list(map(str, feature_names)), MODELS_DIR / "scaler_params.json")
    (MODELS_DIR / "label_map.json").write_text(
        json.dumps({str(k): v for k, v in label_names.items()}, indent=2)
    )
    (MODELS_DIR / "feature_names.json").write_text(
        json.dumps(list(map(str, feature_names)), indent=2)
    )

    # Sanity check: Keras vs TFLite numerical parity on a small sample
    sample = Xs[:64]
    keras_preds = model.predict(sample, verbose=0)
    tflite_preds = run_tflite(tflite_float, sample)
    parity = compare_keras_vs_tflite(keras_preds, tflite_preds)
    parity["tflite_float_kb"] = round(tflite_float.stat().st_size / 1024, 1)
    parity["tflite_int8_kb"] = round(tflite_int8.stat().st_size / 1024, 1)

    print("\nExport summary:", json.dumps(parity, indent=2))
    return {
        "keras": str(keras_path),
        "tflite_float": str(tflite_float),
        "tflite_int8": str(tflite_int8),
        "parity": parity,
    }


# CLI
def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--data", type=str, default=str(PROCESSED_DIR / "wesad_wrist.npz"))
    p.add_argument("--mode", choices=["holdout", "loso"], default="holdout")
    p.add_argument("--binary", action="store_true",
                   help="Remap to {0=non-stress, 1=stress}.")
    p.add_argument("--label-config", choices=list(LABEL_CONFIGS.keys()), default=None,
                   help="Named binary label configuration for the ablation study "
                        "(overrides --binary).")
    p.add_argument("--epochs", type=int, default=60)
    p.add_argument("--batch", type=int, default=64)
    p.add_argument("--holdout-subjects", nargs="+", default=list(DEFAULT_HOLDOUT_SUBJECTS))
    p.add_argument("--no-final", action="store_true",
                   help="Skip the final all-subject model + TFLite export.")
    args = p.parse_args()

    set_global_seed()
    X, y, subject_ids, feature_names = load_npz(Path(args.data))
    if args.label_config is not None:
        X, y, subject_ids, label_names = apply_label_config(
            X, y, subject_ids, args.label_config
        )
    else:
        y, label_names = maybe_binarize(y, args.binary)
    n_classes = len(label_names)
    print(f"Loaded X={X.shape} y={y.shape} subjects={len(np.unique(subject_ids))} "
          f"classes={n_classes}")

    if args.mode == "holdout":
        _, _, _, metrics, _ = run_holdout(
            X, y, subject_ids, args.holdout_subjects, n_classes,
            args.epochs, args.batch, label_names=label_names,
        )
        out = MODELS_DIR / "holdout_metrics.json"
        out.write_text(json.dumps(metrics, indent=2))
        print(f"\nHold-out metrics → {out}")
        print(json.dumps(metrics, indent=2))
    else:
        summary = run_loso(X, y, subject_ids, n_classes, args.epochs, args.batch,
                           label_names=label_names)
        out = MODELS_DIR / "loso_metrics.json"
        out.write_text(json.dumps(summary, indent=2))
        print(f"\nLOSO summary → {out}")
        print(json.dumps(
            {k: v for k, v in summary.items() if k != "per_fold"}, indent=2
        ))

    if not args.no_final:
        train_final_and_export(
            X, y, n_classes, args.epochs, args.batch, label_names, feature_names,
        )


if __name__ == "__main__":
    main()

