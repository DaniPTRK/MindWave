"""Test StandardScaler"""
from __future__ import annotations

from pathlib import Path

import numpy as np
import pytest

from src.config import MODELS_DIR
from src.features import N_FEATURES

SCALER_PKL = MODELS_DIR / "scaler.pkl"
SCALER_JSON = MODELS_DIR / "scaler_params.json"


@pytest.fixture
def scaler():
    """Load the fitted scaler from disk."""
    import joblib
    if not SCALER_PKL.exists():
        pytest.skip("scaler.pkl not found, run training first")
    return joblib.load(SCALER_PKL)


@pytest.fixture
def scaler_params():
    """Load JSON scaler params (used by Android)."""
    import json
    if not SCALER_JSON.exists():
        pytest.skip("scaler_params.json not found, export it first")
    with open(SCALER_JSON) as f:
        return json.load(f)


class TestScalerPKL:
    """Tests on the sklearn scaler object."""

    def test_n_features_match(self, scaler):
        assert scaler.n_features_in_ == N_FEATURES

    def test_mean_shape(self, scaler):
        assert scaler.mean_.shape == (N_FEATURES,)

    def test_scale_shape(self, scaler):
        assert scaler.scale_.shape == (N_FEATURES,)

    def test_transform_output_shape(self, scaler):
        X = np.random.default_rng(42).standard_normal((10, N_FEATURES)).astype(np.float32)
        out = scaler.transform(X)
        assert out.shape == (10, N_FEATURES)

    def test_inverse_consistency(self, scaler):
        """transform, inverse_transform should recover original."""
        X = np.random.default_rng(42).standard_normal((5, N_FEATURES)).astype(np.float64)
        recovered = scaler.inverse_transform(scaler.transform(X))
        np.testing.assert_allclose(recovered, X, rtol=1e-5)


class TestScalerJSON:
    """Tests on the exported JSON params (used by Android ScalerNormalizer)."""

    def test_keys_present(self, scaler_params):
        assert "mean" in scaler_params
        assert "scale" in scaler_params

    def test_array_lengths(self, scaler_params):
        assert len(scaler_params["mean"]) == N_FEATURES
        assert len(scaler_params["scale"]) == N_FEATURES

    def test_no_zero_scale(self, scaler_params):
        """Zero scale would cause division by zero in Android."""
        for i, s in enumerate(scaler_params["scale"]):
            assert s != 0.0, f"Feature {i} has zero scale"


class TestPKLvsJSON:
    """Ensure the pkl and JSON representations are identical."""

    def test_mean_matches(self, scaler, scaler_params):
        np.testing.assert_allclose(
            scaler.mean_,
            np.array(scaler_params["mean"], dtype=np.float64),
            rtol=1e-6,
        )

    def test_scale_matches(self, scaler, scaler_params):
        np.testing.assert_allclose(
            scaler.scale_,
            np.array(scaler_params["scale"], dtype=np.float64),
            rtol=1e-6,
        )
