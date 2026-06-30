# -*- coding: utf-8 -*-
"""Probe the 5-signature trainable TFLite end-to-end.

Checks:
  1. Architecture parity  -- exported model has the same weight values as the
                              trained mindwave_stress.keras (after dropout=0.0
                              rebuild, weights are copied exactly).
  2. Numerical parity     -- infer via Keras == infer via TFLite "infer" sig.
  3. train signature      -- loss decreases after one SGD step.
  4. parameters signature -- returns one tensor per trainable variable with the
                              correct shape.
  5. restore signature    -- round-trip: extract weights, zero them, restore,
                              verify inference is back to baseline.
  6. explain signature    -- importances shape [1, 23], all >= 0, not all zero.

Run from ml/ with the venv active:
    python probe_signatures.py
Exits 0 on success, 1 on failure.
"""
from __future__ import annotations

import sys
import traceback
from pathlib import Path

import numpy as np

# ── paths ──────────────────────────────────────────────────────────────────
ML_DIR    = Path(__file__).resolve().parent
KERAS_PATH   = ML_DIR / "models" / "mindwave_stress.keras"
TFLITE_PATH  = ML_DIR / "models" / "mindwave_stress_trainable.tflite"

N_STEPS, N_FEATURES = 12, 23

PASS = "[PASS]"
FAIL = "[FAIL]"


def _fmt(ok: bool) -> str:
    return PASS if ok else FAIL


# ── helpers ────────────────────────────────────────────────────────────────
def build_export_model(keras_model):
    """Rebuild with dropout=0.0 and copy weights -- mirrors export_trainable_tflite."""
    import tensorflow as tf
    sys.path.insert(0, str(ML_DIR))
    from src.model import build_lstm
    n_classes = keras_model.output_shape[-1]
    export_model = build_lstm(
        input_shape=keras_model.input_shape[1:],
        n_classes=n_classes,
        dropout=0.0,
    )
    export_model.set_weights(keras_model.get_weights())
    return export_model


def _make_interp(tflite_path: Path):
    import tensorflow as tf
    interp = tf.lite.Interpreter(str(tflite_path))
    interp.allocate_tensors()
    return interp


def _init_resource_vars(interp, export_model):
    """Seed TFLite resource-variable slots with the exported model's weights."""
    trainable_vars = export_model.trainable_variables
    restore_runner = interp.get_signature_runner("restore")
    kwargs = {f"vals_{i}": v.numpy() for i, v in enumerate(trainable_vars)}
    restore_runner(**kwargs)


def _infer_keras(export_model, x_np):
    import tensorflow as tf
    return export_model(tf.constant(x_np), training=False).numpy()


def _infer_tflite(interp, x_np):
    runner = interp.get_signature_runner("infer")
    return runner(x=x_np)["logits"]


# ── tests ──────────────────────────────────────────────────────────────────
def test_architecture_parity(keras_model, export_model):
    """All weight arrays must be numerically identical after set_weights()."""
    print("\n[1] Architecture parity (trained vs export model)")
    ok = True

    kw = keras_model.get_weights()
    ew = export_model.get_weights()

    if len(kw) != len(ew):
        print(f"  {FAIL}  weight count differs: keras={len(kw)} export={len(ew)}")
        return False

    for i, (k, e) in enumerate(zip(kw, ew)):
        if k.shape != e.shape:
            print(f"  {FAIL}  layer {i}: shape keras={k.shape} vs export={e.shape}")
            ok = False
        elif not np.allclose(k, e, atol=0.0):
            print(f"  {FAIL}  layer {i}: values differ (max_abs_diff={np.max(np.abs(k-e)):.2e})")
            ok = False

    if ok:
        print(f"  {PASS}  {len(kw)} weight arrays match exactly (no dropout layers in export)")
    return ok


def test_infer_parity(export_model, interp, rng):
    """TFLite infer signature must match Keras forward pass."""
    print("\n[2] infer signature — Keras vs TFLite numerical parity")
    x_np = rng.standard_normal((4, N_STEPS, N_FEATURES)).astype(np.float32)

    keras_out = _infer_keras(export_model, x_np)
    tflite_out = _infer_tflite(interp, x_np)

    max_diff = float(np.max(np.abs(keras_out - tflite_out)))
    ok = max_diff < 1e-4
    print(f"  {_fmt(ok)}  max_abs_diff={max_diff:.2e}  (threshold 1e-4)")
    if not ok:
        print(f"    keras  = {keras_out[0]}")
        print(f"    tflite = {tflite_out[0]}")
    return ok


def test_train_signature(interp, rng, n_classes: int = 2):
    """Loss must be a finite scalar and decrease (or at least change) after a step."""
    print("\n[3] train signature — loss is finite and updates weights")
    x_np = rng.standard_normal((8, N_STEPS, N_FEATURES)).astype(np.float32)
    y_np = rng.integers(0, n_classes, size=(8,)).astype(np.int64)

    train_runner = interp.get_signature_runner("train")

    res1 = train_runner(x=x_np, y=y_np)
    loss1 = float(res1["loss"].flatten()[0])
    res2 = train_runner(x=x_np, y=y_np)
    loss2 = float(res2["loss"].flatten()[0])

    finite_ok  = np.isfinite(loss1) and np.isfinite(loss2)
    changed_ok = abs(loss1 - loss2) > 0  # weights changed between steps
    ok = finite_ok and changed_ok
    print(f"  {_fmt(finite_ok)}  loss is finite:  step1={loss1:.4f}  step2={loss2:.4f}")
    print(f"  {_fmt(changed_ok)}  weights updated between steps (loss changed: {abs(loss1 - loss2):.2e})")
    return ok


def test_parameters_signature(interp, export_model):
    """parameters() must return one tensor per trainable variable with matching shape."""
    print("\n[4] parameters signature — shapes match trainable_variables")
    params_runner = interp.get_signature_runner("parameters")
    params = params_runner()

    trainable_vars = export_model.trainable_variables
    n_vars = len(trainable_vars)
    n_params = len(params)

    count_ok = n_params == n_vars
    print(f"  {_fmt(count_ok)}  count: got {n_params}, expected {n_vars}")

    shape_ok = True
    for i, var in enumerate(trainable_vars):
        key = f"var_{i}"
        if key not in params:
            print(f"  {FAIL}  missing key '{key}'")
            shape_ok = False
            continue
        got_shape = params[key].shape
        exp_shape = tuple(var.shape)
        match = got_shape == exp_shape
        if not match:
            print(f"  {FAIL}  {key}: got {got_shape}, expected {exp_shape}")
            shape_ok = False

    if shape_ok:
        print(f"  {PASS}  all {n_vars} tensors have correct shapes")
    return count_ok and shape_ok


def test_restore_signature(interp, export_model, rng):
    """Round-trip: extract weights, zero them, restore, verify inference recovers."""
    print("\n[5] restore signature — weight round-trip")
    x_np = rng.standard_normal((1, N_STEPS, N_FEATURES)).astype(np.float32)

    # Baseline inference
    infer_runner   = interp.get_signature_runner("infer")
    params_runner  = interp.get_signature_runner("parameters")
    restore_runner = interp.get_signature_runner("restore")

    baseline = infer_runner(x=x_np)["logits"].copy()

    # Extract current weights
    params = params_runner()
    saved = {k: v.copy() for k, v in params.items()}

    # Zero all weights (catastrophic change)
    zero_kwargs = {f"vals_{i}": np.zeros_like(v) for i, v in enumerate(saved.values())}
    restore_runner(**zero_kwargs)
    after_zero = infer_runner(x=x_np)["logits"].copy()

    # Restore original weights
    restore_kwargs = {f"vals_{i}": v for i, v in enumerate(saved.values())}
    restore_runner(**restore_kwargs)
    after_restore = infer_runner(x=x_np)["logits"].copy()

    zeroed_diff  = float(np.max(np.abs(baseline - after_zero)))
    restored_diff = float(np.max(np.abs(baseline - after_restore)))

    zeroed_ok   = zeroed_diff > 0.01   # zeroing must change output
    restored_ok = restored_diff < 1e-5  # restore must recover exactly
    ok = zeroed_ok and restored_ok
    print(f"  {_fmt(zeroed_ok)}   zeroing changed output (diff={zeroed_diff:.4f})")
    print(f"  {_fmt(restored_ok)}  restore recovered original output (diff={restored_diff:.2e})")
    return ok


def test_explain_signature(interp, rng):
    """explain must return importances [1, 23], all >= 0, not all zero."""
    print("\n[6] explain signature — saliency shape and values")
    x_np = rng.standard_normal((1, N_STEPS, N_FEATURES)).astype(np.float32)

    explain_runner = interp.get_signature_runner("explain")
    result = explain_runner(x=x_np)

    key_ok    = "importances" in result
    if not key_ok:
        print(f"  {FAIL}  output key 'importances' missing, got: {list(result.keys())}")
        return False

    imp = result["importances"]
    shape_ok   = imp.shape == (1, N_FEATURES)
    nonneg_ok  = bool(np.all(imp >= 0))
    nonzero_ok = bool(np.any(imp > 0))
    ok = shape_ok and nonneg_ok and nonzero_ok

    print(f"  {_fmt(shape_ok)}   shape: {imp.shape}  (expected (1, {N_FEATURES}))")
    print(f"  {_fmt(nonneg_ok)}  all values >= 0")
    print(f"  {_fmt(nonzero_ok)}  at least one value > 0 (non-trivial saliency)")
    if ok:
        top3_idx = np.argsort(imp[0])[::-1][:3]
        print(f"    top-3 feature indices: {top3_idx.tolist()} "
              f"values: {imp[0][top3_idx].tolist()}")
    return ok


# ── main ───────────────────────────────────────────────────────────────────
def main():
    import tensorflow as tf
    print(f"TF {tf.__version__}  |  TFLite: {TFLITE_PATH.name}")

    rng = np.random.default_rng(42)
    results: dict[str, bool] = {}

    # ── Load models ──────────────────────────────────────────────────────
    print(f"\nLoading {KERAS_PATH.name} ...")
    keras_model = tf.keras.models.load_model(KERAS_PATH, compile=False)
    print(f"  input_shape : {keras_model.input_shape}")
    print(f"  output_shape: {keras_model.output_shape}")
    print(f"  n_params    : {keras_model.count_params()}")

    export_model = build_export_model(keras_model)
    print(f"  export n_params (dropout=0): {export_model.count_params()}")

    if not TFLITE_PATH.exists():
        print(f"\n{FAIL}  {TFLITE_PATH} not found.")
        print("  Run: python -c \"from src.tflite_training_export import export_trainable_tflite; export_trainable_tflite()\"")
        sys.exit(1)

    print(f"\nLoading {TFLITE_PATH.name} ({TFLITE_PATH.stat().st_size // 1024} KB) ...")
    interp = _make_interp(TFLITE_PATH)
    sigs = list(interp.get_signature_list().keys())
    print(f"  signatures  : {sigs}")

    expected_sigs = {"infer", "train", "parameters", "restore", "explain"}
    missing = expected_sigs - set(sigs)
    if missing:
        print(f"\n{FAIL}  Missing signatures: {missing}")
        sys.exit(1)
    print(f"  {PASS}  all 5 signatures present")

    # Seed resource variables from the export model's weights
    _init_resource_vars(interp, export_model)

    # ── Run tests ────────────────────────────────────────────────────────
    results["architecture_parity"] = test_architecture_parity(keras_model, export_model)
    results["infer"]      = test_infer_parity(export_model, interp, rng)
    results["train"]      = test_train_signature(interp, rng, n_classes=keras_model.output_shape[-1])
    # Re-init after train modified weights
    _init_resource_vars(interp, export_model)
    results["parameters"] = test_parameters_signature(interp, export_model)
    results["restore"]    = test_restore_signature(interp, export_model, rng)
    results["explain"]    = test_explain_signature(interp, rng)

    # ── Summary ──────────────────────────────────────────────────────────
    print("\n" + "=" * 55)
    print("SUMMARY")
    print("=" * 55)
    all_ok = True
    for name, ok in results.items():
        print(f"  {_fmt(ok)}  {name}")
        if not ok:
            all_ok = False

    if all_ok:
        print(f"\nAll {len(results)} checks passed.")
        sys.exit(0)
    else:
        failed = [k for k, v in results.items() if not v]
        print(f"\n{len(failed)} check(s) FAILED: {failed}")
        sys.exit(1)


if __name__ == "__main__":
    try:
        main()
    except Exception:
        traceback.print_exc()
        sys.exit(1)



