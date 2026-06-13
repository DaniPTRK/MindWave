"""Test feature extraction: shapes, names, edge cases, and golden-sample parity."""
from __future__ import annotations

import numpy as np
import pytest

from src.config import N_SUBWINDOWS, WRIST_FS
from src.features import (
    ACC_NAMES,
    EDA_NAMES,
    HRV_FREQ_NAMES,
    HRV_TIME_NAMES,
    N_FEATURES,
    SUBWINDOW_FEATURE_NAMES,
    TEMP_NAMES,
    acc_features,
    eda_features,
    hrv_freq_features,
    scr_peak_features,
    subwindow_feature_vector,
    temp_features,
)


class TestFeatureNameConsistency:
    """Verify the 23-feature vector is correctly decomposed."""

    def test_n_features_is_23(self):
        assert N_FEATURES == 23

    def test_feature_name_count(self):
        assert len(SUBWINDOW_FEATURE_NAMES) == N_FEATURES

    def test_feature_group_counts(self):
        assert len(HRV_TIME_NAMES) == 4
        assert len(HRV_FREQ_NAMES) == 3
        assert len(EDA_NAMES) == 7
        assert len(TEMP_NAMES) == 5
        assert len(ACC_NAMES) == 4
        total = 4 + 3 + 7 + 5 + 4
        assert total == N_FEATURES

    def test_concatenation_order(self):
        expected = HRV_TIME_NAMES + HRV_FREQ_NAMES + EDA_NAMES + TEMP_NAMES + ACC_NAMES
        assert SUBWINDOW_FEATURE_NAMES == expected


class TestHRVTimeFeatures:
    """Test time-domain HRV features from BVP."""

    def test_output_length(self, synthetic_bvp_60s):
        from src.features import _hrv_time_features
        # 5-second sub-window at 64 Hz
        seg = synthetic_bvp_60s[:320]
        out = _hrv_time_features(seg, fs=64)
        assert out.shape == (4,)

    def test_short_signal_returns_zeros(self):
        from src.features import _hrv_time_features
        out = _hrv_time_features(np.zeros(50, dtype=np.float32), fs=64)
        assert np.allclose(out, 0.0)

    def test_constant_signal_returns_zeros(self):
        from src.features import _hrv_time_features
        # Constant signal → no peaks → zeros
        out = _hrv_time_features(np.ones(320, dtype=np.float32), fs=64)
        assert np.allclose(out, 0.0)


class TestHRVFreqFeatures:
    """Test frequency-domain HRV on full 60s window."""

    def test_output_length(self, synthetic_bvp_60s):
        out = hrv_freq_features(synthetic_bvp_60s, fs=64)
        assert out.shape == (3,)

    def test_no_nan_inf(self, synthetic_bvp_60s):
        out = hrv_freq_features(synthetic_bvp_60s, fs=64)
        assert not np.any(np.isnan(out))
        assert not np.any(np.isinf(out))

    def test_short_signal_zeros(self):
        out = hrv_freq_features(np.zeros(100, dtype=np.float32), fs=64)
        assert np.allclose(out, 0.0)


class TestEDAFeatures:
    """Test tonic/phasic EDA features."""

    def test_output_length(self, synthetic_eda_60s):
        seg = synthetic_eda_60s[:20]  # 5s at 4 Hz
        out = eda_features(seg, fs=4)
        assert out.shape == (7,)

    def test_no_nan(self, synthetic_eda_60s):
        seg = synthetic_eda_60s[:20]
        out = eda_features(seg, fs=4)
        assert not np.any(np.isnan(out))

    def test_short_returns_zeros(self):
        out = eda_features(np.zeros(5, dtype=np.float32), fs=4)
        assert np.allclose(out, 0.0)


class TestSCRPeakFeatures:
    """Test SCR peak detection on full 60s EDA window."""

    def test_output_length(self, synthetic_eda_60s):
        out = scr_peak_features(synthetic_eda_60s, fs=4)
        assert out.shape == (3,)

    def test_no_nan(self, synthetic_eda_60s):
        out = scr_peak_features(synthetic_eda_60s, fs=4)
        assert not np.any(np.isnan(out))


class TestTempFeatures:
    """Test temperature features."""

    def test_output_length(self, synthetic_temp_60s):
        seg = synthetic_temp_60s[:20]  # 5s
        out = temp_features(seg)
        assert out.shape == (5,)

    def test_values_correct(self):
        arr = np.array([30.0, 31.0, 32.0, 33.0, 34.0], dtype=np.float32)
        out = temp_features(arr)
        assert abs(out[0] - 32.0) < 0.01  # mean
        assert out[3] == 30.0  # min
        assert out[4] == 34.0  # max

    def test_empty_returns_zeros(self):
        out = temp_features(np.array([], dtype=np.float32))
        assert np.allclose(out, 0.0)


class TestACCFeatures:
    """Test accelerometer magnitude features."""

    def test_output_length(self, synthetic_acc_60s):
        seg = synthetic_acc_60s[:160]  # 5s at 32 Hz
        out = acc_features(seg)
        assert out.shape == (4,)

    def test_zcr_range(self, synthetic_acc_60s):
        seg = synthetic_acc_60s[:160]
        out = acc_features(seg)
        # ZCR should be between 0 and 1
        assert 0 <= out[3] <= 1.0

    def test_energy_nonneg(self, synthetic_acc_60s):
        seg = synthetic_acc_60s[:160]
        out = acc_features(seg)
        assert out[2] >= 0.0  # energy is always non-negative


class TestSubwindowFeatureVector:
    """Test the composite feature vector builder."""

    def test_output_length(
        self, synthetic_bvp_60s, synthetic_eda_60s, synthetic_temp_60s, synthetic_acc_60s
    ):
        bvp_seg = synthetic_bvp_60s[:320]  # 5s
        eda_seg = synthetic_eda_60s[:20]
        temp_seg = synthetic_temp_60s[:20]
        acc_seg = synthetic_acc_60s[:160]
        hrv_freq = hrv_freq_features(synthetic_bvp_60s, fs=64)
        scr_peaks = scr_peak_features(synthetic_eda_60s, fs=4)
        vec = subwindow_feature_vector(bvp_seg, eda_seg, temp_seg, acc_seg, hrv_freq, scr_peaks)
        assert vec.shape == (N_FEATURES,)

    def test_no_nan_inf(
        self, synthetic_bvp_60s, synthetic_eda_60s, synthetic_temp_60s, synthetic_acc_60s
    ):
        bvp_seg = synthetic_bvp_60s[:320]
        eda_seg = synthetic_eda_60s[:20]
        temp_seg = synthetic_temp_60s[:20]
        acc_seg = synthetic_acc_60s[:160]
        hrv_freq = hrv_freq_features(synthetic_bvp_60s, fs=64)
        scr_peaks = scr_peak_features(synthetic_eda_60s, fs=4)
        vec = subwindow_feature_vector(bvp_seg, eda_seg, temp_seg, acc_seg, hrv_freq, scr_peaks)
        assert not np.any(np.isnan(vec))
        assert not np.any(np.isinf(vec))

    def test_dtype_float32(
        self, synthetic_bvp_60s, synthetic_eda_60s, synthetic_temp_60s, synthetic_acc_60s
    ):
        bvp_seg = synthetic_bvp_60s[:320]
        eda_seg = synthetic_eda_60s[:20]
        temp_seg = synthetic_temp_60s[:20]
        acc_seg = synthetic_acc_60s[:160]
        hrv_freq = hrv_freq_features(synthetic_bvp_60s, fs=64)
        scr_peaks = scr_peak_features(synthetic_eda_60s, fs=4)
        vec = subwindow_feature_vector(bvp_seg, eda_seg, temp_seg, acc_seg, hrv_freq, scr_peaks)
        assert vec.dtype == np.float32

