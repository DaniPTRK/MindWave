"""Export an advanced TFLite model with on-device training signatures.

tflite_export.py contains utilities for converting Keras models to TFLite. The
exported TFLite in that case is inference-only (forward pass). For on-device
training, inference + training signatures, and for the XAI explain signature, we need to
wrap the Keras model inside a tf.Module and export concrete tf.function signatures.
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
    """Wraps build_lstm with train/infer/parameters/restore/explain signatures."""

    def __init__(self, keras_model: tf.keras.Model, learning_rate: float = 1e-3):
        super().__init__(name="mindwave_trainable")
        self.model = keras_model
        self.optimizer = tf.keras.optimizers.SGD(learning_rate)

    @tf.function(input_signature=[
        tf.TensorSpec([None, N_STEPS, N_FEATURES], tf.float32),
        tf.TensorSpec([None], tf.int64),
    ])
    def train(self, x, y):
        """One mini-batch of SGD, returns the batch loss."""
        with tf.GradientTape() as tape:
            logits = self.model(x, training=True)
            loss = tf.reduce_mean(
                tf.keras.losses.sparse_categorical_crossentropy(y, logits)
            )
        grads = tape.gradient(loss, self.model.trainable_variables)
        self.optimizer.apply_gradients(
            zip(grads, self.model.trainable_variables)
        )
        return {"loss": loss}

    @tf.function(input_signature=[
        tf.TensorSpec([None, N_STEPS, N_FEATURES], tf.float32),
    ])
    def infer(self, x):
        """Forward pass — returns softmax probabilities."""
        return {"logits": self.model(x, training=False)}

    @tf.function(input_signature=[])
    def parameters(self):
        """Read all trainable weights as a dict of tensors."""
        return {
            f"var_{i}": tf.identity(v)
            for i, v in enumerate(self.model.trainable_variables)
        }

    def _restore_signature(self):
        """Build an input_signature list matching trainable_variables shapes.

        Uses positional args (vals_0, vals_1, …) which is how tf.function
        compiles variadic *args.  The TFLite signature input keys will therefore
        be "vals_0", "vals_1", … — and Android must use those same keys when
        calling interp.runSignature(..., "restore").

        NOTE: ``parameters()`` outputs "var_0", "var_1", … (different prefix).
        FLTrainingWorker.saveWeights stores them under their original key names
        and restoreWeights must remap them to "vals_0", "vals_1", … before
        calling the restore signature.
        """
        return [
            tf.TensorSpec(v.shape, v.dtype, name=f"vals_{i}")
            for i, v in enumerate(self.model.trainable_variables)
        ]

    def restore(self, *new_values):
        """Overwrite all trainable weights from external arrays (positional)."""
        for v, nv in zip(self.model.trainable_variables, new_values):
            v.assign(nv)
        return {"status": tf.constant(1)}

    @tf.function(input_signature=[
        tf.TensorSpec([1, N_STEPS, N_FEATURES], tf.float32),
    ])
    def explain(self, x):
        """Gradient-based feature importance (single window).

        Computes |dP(predicted_class)/dInput| averaged over the 12 time
        steps, returns a (1, F) importance vector. This is equivalent to
        a vanilla gradient saliency map, useful for xai.
        """
        x_var = tf.identity(x)
        with tf.GradientTape() as tape:
            tape.watch(x_var)
            logits = self.model(x_var, training=False)
            predicted_class = tf.argmax(logits, axis=-1)
            # Gather the probability of the predicted class
            prob = tf.reduce_sum(
                logits * tf.one_hot(predicted_class, depth=logits.shape[-1]),
                axis=-1,
            )
        grad = tape.gradient(prob, x_var)

        # Mean absolute gradient over the time axis
        importances = tf.reduce_mean(tf.abs(grad), axis=1)  # (1, F)
        return {"importances": importances}


def export_trainable_tflite(
    keras_model_path: str | Path | None = None,
    out_path: str | Path | None = None,
    learning_rate: float = 1e-3,
) -> Path:
    """Convert the Keras LSTM into a trainable .tflite with 5 signatures."""
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
        "train":      module.train.get_concrete_function(),
        "infer":      module.infer.get_concrete_function(),
        "parameters": module.parameters.get_concrete_function(),
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

