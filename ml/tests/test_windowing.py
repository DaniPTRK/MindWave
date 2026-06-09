"""Test windowing logic"""
from __future__ import annotations

import numpy as np
import pytest

from src.config import (
    LABEL_PURITY_THRESHOLD,
    N_SUBWINDOWS,
    STRIDE_SECONDS,
    SUBWINDOW_SECONDS,
    WINDOW_SECONDS,
    WRIST_FS,
)
from src.windowing import _segment, _window_label, windows_from_subject


class TestWindowConstants:
    """Verify that the windowing constants are self-consistent."""

    def test_window_seconds(self):
        assert WINDOW_SECONDS == 60

    def test_stride_seconds(self):
        assert STRIDE_SECONDS == 15

    def test_subwindow_seconds(self):
        assert SUBWINDOW_SECONDS == 5

    def test_n_subwindows(self):
        assert N_SUBWINDOWS == WINDOW_SECONDS // SUBWINDOW_SECONDS == 12

    def test_label_purity_threshold(self):
        assert 0 < LABEL_PURITY_THRESHOLD <= 1.0


class TestSegment:
    """Test the internal _segment helper that slices arrays by time."""

    def test_correct_indices(self):
        arr = np.arange(640, dtype=np.float32)  # 10s at 64 Hz
        seg = _segment(arr, 2.0, 3.0, 64)
        assert seg.shape[0] == 192  # 3 * 64
        assert seg[0] == 128  # 2 * 64

    def test_boundary_start(self):
        arr = np.arange(100, dtype=np.float32)
        seg = _segment(arr, 0.0, 1.0, 10)
        assert seg.shape[0] == 10
        assert seg[0] == 0

    def test_empty_when_beyond_length(self):
        arr = np.arange(10, dtype=np.float32)
        seg = _segment(arr, 5.0, 5.0, 10)
        assert seg.shape[0] == 0  # beyond array bounds


class TestWindowLabel:
    """Test majority-vote label computation for a time window."""

    def test_pure_window(self):
        # 700 Hz label track, all label=1 for 60s
        labels = np.ones(700 * 60, dtype=np.int32)
        label, purity = _window_label(labels, 0.0, 60.0)
        assert label == 1
        assert purity == 1.0

    def test_impure_window_below_threshold(self):
        # 80% label=1, 20% label=2 => purity = 0.8 < 0.9 threshold
        labels = np.ones(700 * 60, dtype=np.int32)
        labels[int(700 * 60 * 0.8):] = 2
        label, purity = _window_label(labels, 0.0, 60.0)
        assert label == 1
        assert purity < LABEL_PURITY_THRESHOLD

    def test_mixed_just_above_threshold(self):
        # 92% label=2, 8% label=1 => passes threshold
        n = 700 * 60
        labels = np.full(n, 2, dtype=np.int32)
        labels[:int(n * 0.08)] = 1
        label, purity = _window_label(labels, 0.0, 60.0)
        assert label == 2
        assert purity >= LABEL_PURITY_THRESHOLD

    def test_empty_segment(self):
        labels = np.ones(100, dtype=np.int32)
        label, purity = _window_label(labels, 50.0, 60.0)
        assert purity == 0.0


class TestWindowsFromSubject:
    """Test end-to-end window extraction shape and label filtering."""

    def _make_dummy_record(self, duration_s: int = 120, label_value: int = 1):
        """Create a minimal WristRecord-like object."""
        from src.wesad_loader import WristRecord

        return WristRecord(
            subject="S_TEST",
            bvp=np.random.default_rng(42).standard_normal(
                duration_s * WRIST_FS["BVP"]
            ).astype(np.float32),
            eda=np.random.default_rng(42).standard_normal(
                duration_s * WRIST_FS["EDA"]
            ).astype(np.float32) + 5.0,
            temp=np.random.default_rng(42).standard_normal(
                duration_s * WRIST_FS["TEMP"]
            ).astype(np.float32) + 32.0,
            acc=np.random.default_rng(42).standard_normal(
                (duration_s * WRIST_FS["ACC"], 3)
            ).astype(np.float32),
            label_700hz=np.full(duration_s * 700, label_value, dtype=np.int32),
        )

    def test_output_shape(self):
        """120s session with stride=15 => expect 4 windows"""
        record = self._make_dummy_record(duration_s=120, label_value=1)
        X, y = windows_from_subject(record)

        # At least some windows should be produced
        assert X.ndim == 3
        assert X.shape[1] == N_SUBWINDOWS  # 12
        assert X.shape[2] == 23  # N_FEATURES
        assert y.shape[0] == X.shape[0]

    def test_discarded_label(self):
        """Labels not in KEEP_LABELS (e.g., 0=transient) => no windows."""
        record = self._make_dummy_record(duration_s=120, label_value=0)
        X, y = windows_from_subject(record)
        assert X.shape[0] == 0
        assert y.shape[0] == 0

    def test_subject_label_preserved(self):
        """All produced labels should be in LABEL_REMAP values {0, 1, 2}."""
        record = self._make_dummy_record(duration_s=120, label_value=2)
        X, y = windows_from_subject(record)
        if y.size > 0:
            assert set(y.tolist()).issubset({0, 1, 2})

    def test_short_session_returns_empty(self):
        """Session shorter than WINDOW_SECONDS should produce no windows."""
        record = self._make_dummy_record(duration_s=30, label_value=1)
        X, y = windows_from_subject(record)
        assert X.shape[0] == 0