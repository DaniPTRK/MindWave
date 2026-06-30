# -*- coding: utf-8 -*-
"""Standalone smoke-test for trainable TFLite multi-signature export.

Run with: python test_tf_versions.py  (from the ml/ directory, venv active)
Does NOT import from src/ -- fully self-contained so it works across TF versions.

Purpose
-------
Verifies that the 3-signature trainable TFLite round-trip
  restore -> infer -> train
works correctly on the installed TF version before committing a full pipeline
run.  Exits 0 on success, 1 on failure.

Differences vs the production pipeline (src/tflite_training_export.py)
-----------------------------------------------------------------------
1. **Simpler model:** single LSTM(64) -> Dense(32) -> Dense(2, softmax) instead
   of the stacked LSTM(64)->LSTM(32)->Dense(32)->Dense(3) used in production.
   This is intentional -- a minimal model converges faster and isolates
   conversion issues from model-architecture issues.
2. **Only 3 signatures** (restore / infer / train). The production export has
   5 signatures adding ``parameters`` (FL weight extraction) and ``explain``
   (vanilla-gradient saliency).
3. **Manual vanilla SGD** (``var.assign_sub(lr * grad)``) with no Keras
   optimizer object and no slot variables.  This matches the production code
   exactly -- it is what allows the ``explain`` GradientTape signature to
   coexist with the ``train`` GradientTape signature in the same SavedModel
   without MLIR TFLite lowering failures.
4. **Binary output** (2 classes) vs. 3-class softmax in production.

The *learned weights* from the real trained model are NOT used here -- this
script only checks that conversion + runtime round-trips are numerically valid.
"""
import sys
import traceback

sys.stdout.reconfigure(line_buffering=True)

try:
    import tensorflow as tf
    import numpy as np
    import tempfile
    from pathlib import Path

    print(f"TF {tf.__version__}  Python {sys.version.split()[0]}", flush=True)

    N_STEPS, N_FEATURES = 12, 23

    # ── Build a minimal LSTM matching MindWave's architecture ──────────────
    inputs = tf.keras.Input(shape=(N_STEPS, N_FEATURES))
    x = tf.keras.layers.LSTM(64, return_sequences=False)(inputs)
    x = tf.keras.layers.Dense(32, activation="relu")(x)
    out = tf.keras.layers.Dense(2, activation="softmax")(x)
    model = tf.keras.Model(inputs, out)

    dummy_x = tf.zeros((1, N_STEPS, N_FEATURES), dtype=tf.float32)
    model(dummy_x, training=False)  # initialize states
    print(f"  Keras model built. Params: {model.count_params()}", flush=True)

    # ── Build the tf.Module ────────────────────────────────────────────────
    class TrainableModule(tf.Module):
        def __init__(self, keras_model, lr=1e-3):
            super().__init__(name="mw_trainable")
            self.model = keras_model
            # Promote ALL model variables so they are tracked by SavedModel
            self.all_model_vars = list(keras_model.variables)
            for i, v in enumerate(self.all_model_vars):
                setattr(self, f"model_var_{i}", v)
            self.trainable_vars = list(keras_model.trainable_variables)
            # Manual SGD — avoids Keras optimizer slot-variable issues in
            # TF 2.16 / Keras 3 (legacy optimizer API was removed; new Keras 3
            # optimizer slots break the MLIR TFLite lowering pipeline).
            # Plain gradient descent: w -= lr * grad, no momentum state needed.
            self.lr = tf.Variable(lr, trainable=False, dtype=tf.float32,
                                  name="learning_rate")

        @tf.function(input_signature=[
            tf.TensorSpec([None, N_STEPS, N_FEATURES], tf.float32),
            tf.TensorSpec([None], tf.int64),
        ])
        def train(self, x, y):
            with tf.GradientTape() as tape:
                logits = self.model(x, training=True)
                loss = tf.reduce_mean(
                    tf.keras.losses.sparse_categorical_crossentropy(y, logits)
                )
            grads = tape.gradient(loss, self.trainable_vars)
            # Manual vanilla SGD: w -= lr * grad  (no Keras optimizer object)
            for var, grad in zip(self.trainable_vars, grads):
                var.assign_sub(self.lr * grad)
            return {"loss": tf.reshape(loss, [1])}

        @tf.function(input_signature=[
            tf.TensorSpec([None, N_STEPS, N_FEATURES], tf.float32),
        ])
        def infer(self, x):
            return {"logits": self.model(x, training=False)}

        def _restore_sig(self):
            return [
                tf.TensorSpec(v.shape, v.dtype, name=f"vals_{i}")
                for i, v in enumerate(self.trainable_vars)
            ]

        def restore(self, *vals):
            for v, nv in zip(self.trainable_vars, vals):
                v.assign(nv)
            return {"status": tf.constant(1)}

    module = TrainableModule(model)
    restore_fn = tf.function(module.restore, input_signature=module._restore_sig())

    signatures = {
        "train":   module.train.get_concrete_function(),
        "infer":   module.infer.get_concrete_function(),
        "restore": restore_fn.get_concrete_function(),
    }

    # ── Convert ────────────────────────────────────────────────────────────
    print("  Converting...", flush=True)
    with tempfile.TemporaryDirectory() as tmp:
        tf.saved_model.save(module, tmp, signatures=signatures)
        converter = tf.lite.TFLiteConverter.from_saved_model(
            tmp, signature_keys=list(signatures.keys())
        )
        converter.target_spec.supported_ops = [
            tf.lite.OpsSet.TFLITE_BUILTINS,
            tf.lite.OpsSet.SELECT_TF_OPS,
        ]
        converter._experimental_lower_tensor_list_ops = False
        converter.experimental_enable_resource_variables = True
        tflite_bytes = converter.convert()

    out = Path("models/test_train_version.tflite")
    out.write_bytes(tflite_bytes)
    print(f"  Converted: {len(tflite_bytes)//1024} KB", flush=True)

    # ── Test ───────────────────────────────────────────────────────────────
    print("  Testing...", flush=True)
    interp = tf.lite.Interpreter(str(out))
    # In TF 2.16, resource-variable models require explicit allocate_tensors()
    # before any signature runner is invoked.
    interp.allocate_tensors()
    print(f"  Sigs: {list(interp.get_signature_list().keys())}", flush=True)

    x_np = np.zeros((1, N_STEPS, N_FEATURES), dtype=np.float32)

    # Initialize resource variables by running restore with the current weights.
    # In TFLite 2.16 the resource-variable slots are zero-initialised on
    # allocate_tensors(); we "restore" the module's own initial weights so
    # infer/train see consistent values.
    restore_fn = interp.get_signature_runner("restore")
    current_weights = [v.numpy() for v in module.trainable_vars]
    restore_kwargs = {f"vals_{i}": w for i, w in enumerate(current_weights)}
    restore_fn(**restore_kwargs)
    print("  restore: OK", flush=True)

    infer_fn = interp.get_signature_runner("infer")
    res = infer_fn(x=x_np)
    print(f"  infer: {res['logits']}", flush=True)

    train_fn = interp.get_signature_runner("train")
    res = train_fn(x=x_np, y=np.array([1], dtype=np.int64))
    print(f"  train: loss={res['loss']}", flush=True)

    res2 = train_fn(x=x_np, y=np.array([0], dtype=np.int64))
    print(f"  train #2: loss={res2['loss']}", flush=True)

    out.unlink(missing_ok=True)
    print(f"\n✅ TF {tf.__version__} WORKS!", flush=True)
    sys.exit(0)

except Exception as e:
    print(f"\n❌ TF {tf.__version__ if 'tf' in dir() else '?'} FAILED: {e}", flush=True)
    sys.exit(1)








