"""TensorFlow Lite conversion utilities (float and int8)."""
from __future__ import annotations

from pathlib import Path
from typing import Iterable

import numpy as np
import tensorflow as tf


def convert_float(model: tf.keras.Model, out_path: str | Path) -> Path:
    """"Convert a Keras model to a TFLite file with float32 weights and IO."""
    out_path = Path(out_path)
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    converter.experimental_new_converter = True
    tflite_bytes = converter.convert()
    out_path.write_bytes(tflite_bytes)
    return out_path


def convert_int8(
    model: tf.keras.Model,
    out_path: str | Path,
    representative_data: np.ndarray | None = None,
    n_samples: int = 200,
) -> Path:
    """ Dynamic-range int8 quantization. Useful for size reduction, suitable for mobile deployment """
    out_path = Path(out_path)
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    tflite_bytes = converter.convert()
    out_path.write_bytes(tflite_bytes)
    return out_path


def run_tflite(tflite_path: str | Path, X: np.ndarray) -> np.ndarray:
    """Run a TFLite model batch-by-batch (TFLite has no native batch dim)"""
    interpreter = tf.lite.Interpreter(model_path=str(tflite_path))
    interpreter.allocate_tensors()
    in_idx = interpreter.get_input_details()[0]["index"]
    out_idx = interpreter.get_output_details()[0]["index"]

    outputs = []
    for x in X:
        interpreter.set_tensor(in_idx, x[None, ...].astype(np.float32))
        interpreter.invoke()
        outputs.append(interpreter.get_tensor(out_idx)[0])
    return np.stack(outputs)


__all__ = ["convert_float", "convert_int8", "run_tflite"]

