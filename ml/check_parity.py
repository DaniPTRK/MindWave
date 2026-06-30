# -*- coding: utf-8 -*-
"""Deep parity check: trained Keras model vs exported TFLite.

Checks:
  A. Layer-by-layer architecture comparison (trained vs export model)
  B. Weight-by-weight exact equality (set_weights copies verbatim)
  C. Forward-pass numerical parity across 16 random windows (threshold 1e-4)
  D. Argmax (predicted class) is identical for all 16 samples
  E. Per-weight layer table printed for full transparency

Run from ml/ with the venv active:
    python check_parity.py
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))

import tensorflow as tf  # noqa: E402

KERAS_PATH  = Path("models/mindwave_stress.keras")
TFLITE_PATH = Path("models/mindwave_stress_trainable.tflite")

PASS = "[PASS]"
FAIL = "[FAIL]"


def main():
    print(f"TF {tf.__version__}\n")

    # ── Load trained Keras model ──────────────────────────────────────────
    print("=== TRAINED KERAS MODEL (mindwave_stress.keras) ===")
    km = tf.keras.models.load_model(str(KERAS_PATH), compile=False)
    print(f"  name         : {km.name}")
    print(f"  input_shape  : {km.input_shape}")
    print(f"  output_shape : {km.output_shape}")
    print(f"  n_params     : {km.count_params()}")
    print(f"  n_layers     : {len(km.layers)}")
    print("  layers:")
    for lay in km.layers:
        out_shape = getattr(lay, "output_shape", "?")
        print(f"    {lay.name:26s} {lay.__class__.__name__:22s} {out_shape}")

    # ── Build export model (dropout=0) ────────────────────────────────────
    print("\n=== EXPORT MODEL (dropout=0.0, weights copied) ===")
    from src.model import build_lstm
    em = build_lstm(
        input_shape=km.input_shape[1:],
        n_classes=km.output_shape[-1],
        dropout=0.0,
    )
    em.set_weights(km.get_weights())
    print(f"  n_params     : {em.count_params()}")
    print(f"  n_layers     : {len(em.layers)}")
    print("  layers:")
    for lay in em.layers:
        out_shape = getattr(lay, "output_shape", "?")
        print(f"    {lay.name:26s} {lay.__class__.__name__:22s} {out_shape}")

    # ── A: Architecture ───────────────────────────────────────────────────
    print("\n=== A. Architecture ===")
    # Strip Dropout and compare by class-name (not Keras auto-generated name
    # which picks up a counter suffix like masking_15 vs masking).
    def layer_sig(layers):
        return [l.__class__.__name__ for l in layers if "dropout" not in l.name.lower()]

    k_sig = layer_sig(km.layers)
    e_sig = layer_sig(em.layers)
    arch_ok = k_sig == e_sig
    print(f"  Keras  non-dropout layer types : {k_sig}")
    print(f"  Export non-dropout layer types : {e_sig}")
    print(f"  {PASS if arch_ok else FAIL}  non-dropout layer types match")

    k_drop = [l.name for l in km.layers if "dropout" in l.name]
    e_drop = [l.name for l in em.layers if "dropout" in l.name]
    print(f"  Keras  has {len(k_drop)} Dropout layer(s): {k_drop}  (stripped on export)")
    print(f"  Export has {len(e_drop)} Dropout layer(s): {e_drop}  (correct — inference-time no-ops removed)")

    # ── B: Weight parity ─────────────────────────────────────────────────
    print("\n=== B. Weight parity (all arrays) ===")
    kw = km.get_weights()
    ew = em.get_weights()
    count_ok = len(kw) == len(ew)
    print(f"  Keras  weight arrays : {len(kw)}")
    print(f"  Export weight arrays : {len(ew)}")
    print(f"  {PASS if count_ok else FAIL}  count matches")

    all_weights_ok = True
    if count_ok:
        print(f"\n  {'#':>3}  {'shape':>18}  {'max_abs_diff':>14}  status")
        print(f"  {'-'*3}  {'-'*18}  {'-'*14}  {'-'*6}")
        for i, (k, e) in enumerate(zip(kw, ew)):
            shape_ok = k.shape == e.shape
            diff = float(np.max(np.abs(k - e))) if shape_ok else float("inf")
            ok = shape_ok and diff == 0.0
            if not ok:
                all_weights_ok = False
            status = PASS if ok else FAIL
            print(f"  {i:>3}  {str(k.shape):>18}  {diff:>14.2e}  {status}")

    overall_b = count_ok and all_weights_ok
    print(f"\n  {PASS if overall_b else FAIL}  {'all weights identical' if overall_b else 'WEIGHT MISMATCH DETECTED'}")

    # ── C+D: Inference parity ─────────────────────────────────────────────
    print("\n=== C+D. Inference parity (Keras export model vs TFLite infer) ===")
    rng = np.random.default_rng(42)
    X = rng.standard_normal((16, 12, 23)).astype(np.float32)

    keras_out = em(tf.constant(X), training=False).numpy()

    interp = tf.lite.Interpreter(str(TFLITE_PATH))
    interp.allocate_tensors()

    # Seed TFLite resource vars with the export model's weights
    restore_runner = interp.get_signature_runner("restore")
    restore_kwargs = {
        f"vals_{i}": v.numpy()
        for i, v in enumerate(em.trainable_variables)
    }
    restore_runner(**restore_kwargs)

    infer_runner = interp.get_signature_runner("infer")
    tflite_out = infer_runner(x=X)["logits"]

    diff = np.abs(keras_out - tflite_out)
    max_diff  = float(diff.max())
    mean_diff = float(diff.mean())
    parity_ok = max_diff < 1e-4
    argmax_ok = bool((keras_out.argmax(1) == tflite_out.argmax(1)).all())

    print(f"  max_abs_diff    : {max_diff:.2e}  (threshold 1e-4)")
    print(f"  mean_abs_diff   : {mean_diff:.2e}")
    print(f"  {PASS if parity_ok else FAIL}  numerical parity")
    print(f"  {PASS if argmax_ok else FAIL}  argmax (predicted class) identical for all 16 samples")

    print("\n  Sample outputs (first 4 windows):")
    print(f"  {'#':>3}  {'keras probs':>25}  {'tflite probs':>25}  {'match':>6}")
    for i in range(4):
        ko = keras_out[i].round(6)
        to = tflite_out[i].round(6)
        match = np.allclose(ko, to, atol=1e-4)
        print(f"  {i:>3}  {str(ko):>25}  {str(to):>25}  {'yes' if match else 'NO':>6}")

    # ── Summary ───────────────────────────────────────────────────────────
    print("\n" + "=" * 55)
    print("PARITY SUMMARY")
    print("=" * 55)
    checks = {
        "A. architecture (non-dropout layers)": arch_ok,
        "B. weight arrays exact equality":      overall_b,
        "C. numerical inference parity <1e-4":  parity_ok,
        "D. argmax predictions identical":      argmax_ok,
    }
    all_ok = True
    for name, ok in checks.items():
        print(f"  {PASS if ok else FAIL}  {name}")
        if not ok:
            all_ok = False

    if all_ok:
        print(f"\nConclusion: the TFLite export is a PERFECT MIRROR of the")
        print(f"trained pipeline model.  Dropout layers stripped (inference")
        print(f"no-ops), all {len(kw)} weight tensors copied bit-for-bit,")
        print(f"max inference diff = {max_diff:.2e} (float32 rounding only).")
        sys.exit(0)
    else:
        print("\nConclusion: PARITY FAILURES detected (see above).")
        sys.exit(1)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        import traceback
        traceback.print_exc()
        sys.exit(1)


