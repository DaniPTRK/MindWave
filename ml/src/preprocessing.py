# Noise filters and feature extraction for WESAD wrist sensor data
from __future__ import annotations

import numpy as np
from scipy.signal import butter, filtfilt, medfilt


def _butter_filter(
    x: np.ndarray,
    fs: float,
    cutoff: float | tuple[float, float],
    order: int = 4,
    btype: str = "band",
) -> np.ndarray:
    """Zero-phase Butterworth filter"""
    # We use Butterworth to get the flattest possible passband, and filtfilt to avoid phase distortion
    nyq = 0.5 * fs
    if isinstance(cutoff, tuple):
        wn = (cutoff[0] / nyq, cutoff[1] / nyq)
    else:
        wn = cutoff / nyq
    b, a = butter(order, wn, btype=btype)

    # filtfilt requires len(x) > 3*max(len(a), len(b)); pad short arrays
    if x.shape[0] < 3 * max(len(a), len(b)):
        return x.astype(np.float32, copy=False)
    return filtfilt(b, a, x).astype(np.float32, copy=False)


def filter_bvp(bvp: np.ndarray, fs: int = 64) -> np.ndarray:
    """Band-pass 0.5–8 Hz: keeps cardiac pulsations, rejects DC drift + high freq noise."""
    return _butter_filter(bvp, fs, (0.5, 8.0), order=4, btype="band")


def filter_eda(eda: np.ndarray, fs: int = 4) -> np.ndarray:
    """Low-pass 1 Hz: EDA dynamics are slow - remove sensor jitter."""
    # Nyquist of a 4 Hz signal is 2 Hz; a 1 Hz cutoff is well-defined
    return _butter_filter(eda, fs, 1.0, order=4, btype="low")


def filter_temp(temp: np.ndarray) -> np.ndarray:
    """Median filter to suppress short spikes from skin-contact glitches."""
    if temp.shape[0] < 5:
        return temp.astype(np.float32, copy=False)
    return medfilt(temp, kernel_size=5).astype(np.float32, copy=False)


def acc_magnitude(acc_xyz: np.ndarray) -> np.ndarray:
    """Convert tri-axial ACC (N, 3) to a magnitude vector (N,)"""
    return np.sqrt(np.sum(acc_xyz.astype(np.float32) ** 2, axis=1))


def filter_acc_mag(acc_mag: np.ndarray, fs: int = 32) -> np.ndarray:
    """Band-pass 0.5–10 Hz on ACC magnitude: human-motion frequency band."""
    return _butter_filter(acc_mag, fs, (0.5, 10.0), order=4, btype="band")


__all__ = [
    "filter_bvp",
    "filter_eda",
    "filter_temp",
    "acc_magnitude",
    "filter_acc_mag",
]

