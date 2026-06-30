"""Export a 5-signature trainable TFLite model for on-device FL training + XAI.

tflite_export.py converts the Keras model to an inference-only TFLite. This
module wraps it in a tf.Module and exports 5 concrete tf.function signatures:
  - infer:      forward pass (softmax probabilities)
  - train:      one vanilla-SGD mini-batch, returns loss
  - parameters: read all trainable weight tensors (for FL aggregation)
  - restore:    write weight tensors back into the model (apply global update)
  - explain:    vanilla-gradient saliency map (input x [1,12,23] -> importances [1,23])

Why vanilla SGD in ``train`` (not tf.keras.optimizers.SGD):
  Having TWO GradientTape subgraphs in the same SavedModel — one in ``train``
  and one in ``explain`` — is only stable in TFLite MLIR lowering when the
  ``train`` subgraph carries no Keras optimizer slot variables.  The legacy
  ``tf.keras.optimizers.SGD`` API creates iteration-counter and slot tensors
  that interact poorly with the second tape during MLIR compilation.
  Plain ``var.assign_sub(lr * grad)`` avoids this entirely: no optimizer
  object, no slot variables, no MLIR collision — and the weight update is
  numerically identical to SGD with momentum=0.

Key requirements for TFLite on-device training (TF 2.16 / Keras 3):
 1. ``experimental_enable_resource_variables = True`` on the TFLiteConverter.
 2. ALL variables (model weights) must be registered as module-level attributes
    so TFLite's converter places them in the shared resource map accessible
    from all signature subgraphs.
 3. Do NOT run a fake training step before export; variable values are
    initialized from the trained Keras model via set_weights().
 4. The model is rebuilt with ``dropout=0.0`` before export so Dropout layers
    are absent from the TFLite graph (they are no-ops at inference / FL time).
"""
from __future__ import annotations

import tempfile
from pathlib import Path

import numpy as np
import tensorflow as tf

from .config import MODELS_DIR
from .features import N_FEATURES, SUBWINDOW_FEATURE_NAMES
from .model import build_lstm

# Number of LSTM time steps (sub-windows per window).
N_STEPS = 12


class TrainableModule(tf.Module):
    """Wraps build_lstm with infer/train/parameters/restore/explain signatures.

    All model variables (trainable + non-trainable) are registered as explicit
    module-level attributes so that TFLite's converter serializes them into the
    shared resource map accessible from ALL signature subgraphs.

    ``train`` uses manual vanilla SGD (``var.assign_sub(lr * grad)``) with no
    Keras optimizer object and no slot variables.  This keeps the ``train``
    subgraph simple enough that a second GradientTape subgraph (``explain``)
    can coexist in the same SavedModel without triggering MLIR lowering
    failures during TFLite conversion.  The weight update is numerically
    identical to SGD with momentum=0 and learning_rate=lr.
    """

    def __init__(self, keras_model: tf.keras.Model, learning_rate: float = 1e-3):
        super().__init__(name="mindwave_trainable")
        self.model = keras_model

        # Ensure all internal states exist by running a dummy forward pass.
        dummy = tf.zeros((1, N_STEPS, N_FEATURES), dtype=tf.float32)
        self.model(dummy, training=False)

        # Track ALL model variables (trainable + non-trainable like BN stats).
        # This ensures TFLite serializes every variable the graph may read.
        self.all_model_vars = list(keras_model.variables)
        for i, v in enumerate(self.all_model_vars):
            setattr(self, f"model_var_{i}", v)

        # Separately track trainable vars for gradient computation.
        self.trainable_vars = list(keras_model.trainable_variables)

        # Learning rate as a tracked tf.Variable (no Keras optimizer object —
        # see module docstring for why this matters for the explain signature).
        self.lr = tf.Variable(learning_rate, trainable=False, dtype=tf.float32,
                              name="learning_rate")

    @tf.function(input_signature=[
        tf.TensorSpec([None, N_STEPS, N_FEATURES], tf.float32),
        tf.TensorSpec([None], tf.int64),
    ])
    def train(self, x, y):
        """One mini-batch of vanilla SGD, returns the batch loss.

        Uses manual weight update (var -= lr * grad) instead of a Keras
        optimizer so that no slot variables appear in the SavedModel graph.
        This allows the ``explain`` GradientTape signature to coexist without
        MLIR TFLite lowering failures.
        """
        with tf.GradientTape() as tape:
            logits = self.model(x, training=True)
            # build_lstm uses softmax final activation, so from_logits=False.
            loss = tf.reduce_mean(
                tf.keras.losses.sparse_categorical_crossentropy(y, logits)
            )
        grads = tape.gradient(loss, self.trainable_vars)
        # Vanilla SGD: w -= lr * grad  (momentum = 0, no optimizer state)
        for var, grad in zip(self.trainable_vars, grads):
            var.assign_sub(self.lr * grad)
        return {"loss": tf.reshape(loss, [1])}

    @tf.function(input_signature=[
        tf.TensorSpec([None, N_STEPS, N_FEATURES], tf.float32),
    ])
    def infer(self, x):
        """Forward pass — returns softmax probabilities."""
        return {"logits": self.model(x, training=False)}

    @tf.function(input_signature=[
        tf.TensorSpec(shape=(1,), dtype=tf.float32, name="dummy"),
    ])
    def parameters(self, dummy):
        """Read all trainable weights as a dict of tensors.

        The ``dummy`` input (a single float, ignored) is required because the
        TFLite 2.16 Java Interpreter.runSignature() throws
        IllegalArgumentException when the inputs map is empty, even for
        signatures that have no real inputs.  Pass floatArrayOf(0f) from Kotlin.
        """
        return {
            f"var_{i}": tf.identity(v)
            for i, v in enumerate(self.trainable_vars)
        }

    def _restore_signature(self):
        """Build an input_signature list matching trainable_variables shapes.

        Uses positional args (vals_0, vals_1, ...) which is how tf.function
        compiles variadic *args.  The TFLite signature input keys are therefore
        "vals_0", "vals_1", ... — Android must use those same keys when calling
        interp.runSignature(..., "restore").
        """
        return [
            tf.TensorSpec(v.shape, v.dtype, name=f"vals_{i}")
            for i, v in enumerate(self.trainable_vars)
        ]

    def restore(self, *new_values):
        """Overwrite all trainable weights from external arrays (positional)."""
        for v, nv in zip(self.trainable_vars, new_values):
            v.assign(nv)
        return {"status": tf.constant(1)}

    @tf.function(input_signature=[
        tf.TensorSpec([1, N_STEPS, N_FEATURES], tf.float32),
    ])
    def explain(self, x):
        """Vanilla-gradient saliency map for a single window.

        Computes the gradient of the predicted-class probability with respect
        to the input features, then collapses the time axis by taking the mean
        of absolute gradients.  Returns per-feature importance scores.

        Input:  x [1, 12, 23]  float32
        Output: importances [1, 23]  float32  (one score per feature)

        This signature is safe to include alongside ``train`` because ``train``
        uses manual vanilla SGD (no Keras optimizer slot variables), keeping the
        SavedModel graph simple enough for TFLite MLIR lowering to handle both
        GradientTape subgraphs.
        """
        x_var = tf.identity(x)
        with tf.GradientTape() as tape:
            tape.watch(x_var)
            logits = self.model(x_var, training=False)
            predicted_class = tf.argmax(logits, axis=-1)
            prob = tf.reduce_sum(
                logits * tf.one_hot(predicted_class, depth=logits.shape[-1]),
                axis=-1,
            )
        grad = tape.gradient(prob, x_var)
        importances = tf.reduce_mean(tf.abs(grad), axis=1)  # (1, N_FEATURES)
        return {"importances": importances}


def export_trainable_tflite(
    keras_model_path: str | Path | None = None,
    out_path: str | Path | None = None,
    learning_rate: float = 1e-3,
) -> Path:
    """Convert the Keras LSTM into a trainable .tflite with 5 signatures.

    Exported signatures: infer, train, parameters, restore, explain.

    ``train`` uses manual vanilla SGD (var.assign_sub(lr * grad)) with no
    Keras optimizer object.  This keeps the train subgraph free of slot-variable
    ops, allowing the ``explain`` GradientTape signature to be included in the
    same SavedModel without MLIR TFLite lowering failures.

    The model is rebuilt with dropout=0.0 and the trained weights are copied
    in. This strips Dropout layers (no-ops at inference/FL time) while
    preserving every learned parameter exactly.
    """
    keras_model_path = Path(keras_model_path or MODELS_DIR / "mindwave_stress.keras")
    out_path = Path(out_path or MODELS_DIR / "mindwave_stress_trainable.tflite")

    model = tf.keras.models.load_model(keras_model_path, compile=False)

    n_classes = model.output_shape[-1]
    export_model = build_lstm(
        input_shape=model.input_shape[1:],
        n_classes=n_classes,
        dropout=0.0,
    )
    export_model.set_weights(model.get_weights())

    module = TrainableModule(export_model, learning_rate=learning_rate)

    restore_fn = tf.function(
        module.restore,
        input_signature=module._restore_signature(),
    )

    signatures = {
        "infer":      module.infer.get_concrete_function(),
        "train":      module.train.get_concrete_function(),
        "parameters": module.parameters.get_concrete_function(
                          tf.zeros([1], dtype=tf.float32)),
        "restore":    restore_fn.get_concrete_function(),
        "explain":    module.explain.get_concrete_function(),
    }

    with tempfile.TemporaryDirectory() as tmp:
        tf.saved_model.save(module, tmp, signatures=signatures)
        converter = tf.lite.TFLiteConverter.from_saved_model(
            tmp,
            signature_keys=list(signatures.keys()),
        )
        converter.target_spec.supported_ops = [
            tf.lite.OpsSet.TFLITE_BUILTINS,
            tf.lite.OpsSet.SELECT_TF_OPS,
        ]
        converter._experimental_lower_tensor_list_ops = False
        converter.experimental_enable_resource_variables = True
        tflite_bytes = converter.convert()


    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(tflite_bytes)
    print(f"Trainable TFLite ({len(tflite_bytes) / 1024:.1f} KB) at {out_path}")
    return out_path


def export_scaler_params(
    scaler,
    feature_names: list[str],
    output_path: str | Path = MODELS_DIR / "scaler_params.json",
) -> Path:
    """Export a fitted scikit-learn StandardScaler to JSON for on-device use.

    The Android `ScalerNormalizer` reads this file from assets and applies the
    same z-score standardisation that was used during training, so on-device
    features match the distribution the LSTM expects.
    """
    import json

    output_path = Path(output_path)
    params = {
        "mean": scaler.mean_.tolist(),
        "scale": scaler.scale_.tolist(),
        "n_features": int(scaler.n_features_in_),
        "feature_names": list(feature_names),
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w") as f:
        json.dump(params, f, indent=2)
    print(f"Scaler params ({params['n_features']} features) at {output_path}")
    return output_path


__all__ = ["TrainableModule", "export_trainable_tflite", "export_scaler_params"]
