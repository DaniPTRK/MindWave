"""Test Keras vs TFLite inference parity, same input should produce almost identical output """
from __future__ import annotations

from pathlib import Path

import numpy as np
import pytest

from src.config import MODELS_DIR, N_SUBWINDOWS
from src.features import N_FEATURES

KERAS_MODEL = MODELS_DIR / "mindwave_stress.keras"
TFLITE_MODEL = MODELS_DIR / "mindwave_stress.tflite"
TFLITE_INT8 = MODELS_DIR / "mindwave_stress_int8.tflite"
TFLITE_TRAINABLE = MODELS_DIR / "mindwave_stress_trainable.tflite"


@pytest.fixture
def sample_input():
    """Random normalised input tensor"""
    rng = np.random.default_rng(42)
    return rng.standard_normal((1, N_SUBWINDOWS, N_FEATURES)).astype(np.float32)


@pytest.fixture
def keras_model():
    if not KERAS_MODEL.exists():
        pytest.skip("Keras model not found")
    import tensorflow as tf
    return tf.keras.models.load_model(str(KERAS_MODEL))


@pytest.fixture
def tflite_interpreter():
    if not TFLITE_MODEL.exists():
        pytest.skip("TFLite model not found")
    import tensorflow as tf
    interp = tf.lite.Interpreter(model_path=str(TFLITE_MODEL))
    interp.allocate_tensors()
    return interp


class TestKerasModel:
    """Tests for the Keras model."""

    def test_input_shape(self, keras_model):
        shape = keras_model.input_shape
        assert shape[1] == N_SUBWINDOWS
        assert shape[2] == N_FEATURES

    def test_output_shape(self, keras_model, sample_input):
        pred = keras_model.predict(sample_input, verbose=0)
        assert pred.shape[0] == 1
        assert pred.shape[1] in (1, 2)

    def test_output_range(self, keras_model, sample_input):
        pred = keras_model.predict(sample_input, verbose=0)
        assert np.all(pred >= 0.0)
        assert np.all(pred <= 1.0)


class TestTFLiteModel:
    """Tests for the standard TFLite model."""

    def test_input_shape(self, tflite_interpreter):
        inp = tflite_interpreter.get_input_details()[0]
        assert inp["shape"].tolist() == [1, N_SUBWINDOWS, N_FEATURES]

    def test_output_shape(self, tflite_interpreter, sample_input):
        inp = tflite_interpreter.get_input_details()[0]
        out = tflite_interpreter.get_output_details()[0]
        tflite_interpreter.set_tensor(inp["index"], sample_input)
        tflite_interpreter.invoke()
        result = tflite_interpreter.get_tensor(out["index"])
        assert result.shape[0] == 1

    def test_output_deterministic(self, tflite_interpreter, sample_input):
        """Same input, same output (no random dropout at inference)."""
        inp = tflite_interpreter.get_input_details()[0]
        out = tflite_interpreter.get_output_details()[0]

        tflite_interpreter.set_tensor(inp["index"], sample_input)
        tflite_interpreter.invoke()
        r1 = tflite_interpreter.get_tensor(out["index"]).copy()

        tflite_interpreter.set_tensor(inp["index"], sample_input)
        tflite_interpreter.invoke()
        r2 = tflite_interpreter.get_tensor(out["index"]).copy()

        np.testing.assert_array_equal(r1, r2)


class TestKerasVsTFLiteParity:
    """Keras and TFLite should agree within tolerance."""

    def test_output_close(self, keras_model, tflite_interpreter, sample_input):
        # Keras
        keras_pred = keras_model.predict(sample_input, verbose=0)

        # TFLite
        inp = tflite_interpreter.get_input_details()[0]
        out = tflite_interpreter.get_output_details()[0]
        tflite_interpreter.set_tensor(inp["index"], sample_input)
        tflite_interpreter.invoke()
        tflite_pred = tflite_interpreter.get_tensor(out["index"])

        # Should agree within 1e-4
        np.testing.assert_allclose(
            keras_pred.flatten(),
            tflite_pred.flatten(),
            atol=1e-4,
            rtol=1e-3,
            err_msg="Keras vs TFLite prediction mismatch",
        )


class TestTrainableModel:
    """Test the trainable TFLite model has the expected signatures."""

    @pytest.fixture
    def trainable_interp(self):
        if not TFLITE_TRAINABLE.exists():
            pytest.skip("Trainable TFLite not found")
        import tensorflow as tf
        interp = tf.lite.Interpreter(model_path=str(TFLITE_TRAINABLE))
        try:
            interp.allocate_tensors()
        except RuntimeError as exc:
            if "Select TensorFlow op" in str(exc):
                pytest.skip(
                    "Trainable model requires SELECT_TF_OPS (Flex delegate). "
                    "Validated on Android; desktop TFLite interpreter does not "
                    "support gradient ops (FlexReluGrad) without the Flex delegate."
                )
            raise
        return interp

    def test_has_infer_signature(self, trainable_interp):
        sigs = trainable_interp.get_signature_list()
        assert "infer" in sigs

    def test_has_train_signature(self, trainable_interp):
        sigs = trainable_interp.get_signature_list()
        assert "train" in sigs

    def test_has_parameters_signature(self, trainable_interp):
        sigs = trainable_interp.get_signature_list()
        assert "parameters" in sigs

    def test_has_restore_signature(self, trainable_interp):
        sigs = trainable_interp.get_signature_list()
        assert "restore" in sigs

    def test_infer_returns_valid_output(self, trainable_interp, sample_input):
        runner = trainable_interp.get_signature_runner("infer")
        result = runner(x=sample_input)
        # Should return a dict with output tensor
        assert isinstance(result, dict)
        output_key = list(result.keys())[0]
        assert result[output_key].shape[0] == 1


class TestSensorAblation:
    """Ablation: zeroing out one sensor group should still produce valid predictions."""

    @pytest.fixture
    def model(self):
        if not KERAS_MODEL.exists():
            pytest.skip("Keras model not found")
        import tensorflow as tf
        return tf.keras.models.load_model(str(KERAS_MODEL))

    def test_zero_hrv(self, model, sample_input):
        ablated = sample_input.copy()
        ablated[:, :, 0:7] = 0.0  # Zero HRV time + freq
        pred = model.predict(ablated, verbose=0)
        assert pred.shape[0] == 1
        assert np.all(np.isfinite(pred))

    def test_zero_eda(self, model, sample_input):
        ablated = sample_input.copy()
        ablated[:, :, 7:14] = 0.0  # Zero EDA
        pred = model.predict(ablated, verbose=0)
        assert np.all(np.isfinite(pred))

    def test_zero_temp(self, model, sample_input):
        ablated = sample_input.copy()
        ablated[:, :, 14:19] = 0.0  # Zero TEMP
        pred = model.predict(ablated, verbose=0)
        assert np.all(np.isfinite(pred))

    def test_zero_acc(self, model, sample_input):
        ablated = sample_input.copy()
        ablated[:, :, 19:23] = 0.0  # Zero ACC
        pred = model.predict(ablated, verbose=0)
        assert np.all(np.isfinite(pred))

    def test_all_zeros_no_crash(self, model):
        zeros = np.zeros((1, N_SUBWINDOWS, N_FEATURES), dtype=np.float32)
        pred = model.predict(zeros, verbose=0)
        assert np.all(np.isfinite(pred))