"""Shared pytest fixtures for the MindWave ML test suite."""
from __future__ import annotations

import numpy as np
import pytest

from src.config import N_SUBWINDOWS, SUBJECTS, WINDOW_SECONDS, WRIST_FS


@pytest.fixture
def synthetic_bvp_60s() -> np.ndarray:
    """Synthetic BVP signal (60 s at 64 Hz) simulating ~70 BPM pulsatile waveform."""
    fs = WRIST_FS["BVP"]
    t = np.arange(WINDOW_SECONDS * fs) / fs
    # Simple sinusoidal pulsatile signal with ~1.17 Hz fundamental (70 BPM)
    signal = np.sin(2 * np.pi * 1.17 * t) + 0.3 * np.sin(2 * np.pi * 2.34 * t)
    return signal.astype(np.float32)


@pytest.fixture
def synthetic_eda_60s() -> np.ndarray:
    """Synthetic EDA signal (60 s at 4 Hz) with tonic + phasic components."""
    fs = WRIST_FS["EDA"]
    n = WINDOW_SECONDS * fs
    tonic = np.linspace(2.0, 2.5, n)  # slowly rising tonic
    phasic = 0.1 * np.random.default_rng(42).standard_normal(n)
    return (tonic + phasic).astype(np.float32)


@pytest.fixture
def synthetic_temp_60s() -> np.ndarray:
    """Synthetic skin temperature (60 s at 4 Hz)."""
    fs = WRIST_FS["TEMP"]
    n = WINDOW_SECONDS * fs
    return np.linspace(32.0, 32.5, n).astype(np.float32)


@pytest.fixture
def synthetic_acc_60s() -> np.ndarray:
    """Synthetic ACC magnitude signal (60 s at 32 Hz)."""
    fs = WRIST_FS["ACC"]
    n = WINDOW_SECONDS * fs
    rng = np.random.default_rng(42)
    return (1.0 + 0.05 * rng.standard_normal(n)).astype(np.float32)


@pytest.fixture
def subjects() -> list[str]:
    """All WESAD subjects."""
    return SUBJECTS

