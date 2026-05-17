"""Per-window feature extractors for HRV, EDA, TEMP and ACC.

All extractors return a 1-D ``np.ndarray`` of fixed length together with a
parallel list of feature names
"""
from __future__ import annotations

import warnings
from typing import Tuple

import numpy as np

# NeuroKit2 prints a lot of harmless warnings=
warnings.filterwarnings("ignore", category=RuntimeWarning)
warnings.filterwarnings("ignore", category=UserWarning)

import neurokit2 as nk

# Feature names
HRV_TIME_NAMES = ["hrv_meanNN", "hrv_SDNN", "hrv_RMSSD", "hrv_pNN50"]
HRV_FREQ_NAMES = ["hrv_LF", "hrv_HF", "hrv_LFHF"]
EDA_NAMES = [
    "eda_scl_mean", "eda_scl_slope",
    "eda_scr_mean", "eda_scr_std", "eda_scr_auc",
    "eda_scr_peaks", "eda_scr_amp_mean",
]
TEMP_NAMES = ["temp_mean", "temp_std", "temp_slope", "temp_min", "temp_max"]
ACC_NAMES = ["acc_mag_mean", "acc_mag_std", "acc_mag_energy", "acc_mag_zcr"]

SUBWINDOW_FEATURE_NAMES = (
    HRV_TIME_NAMES + HRV_FREQ_NAMES + EDA_NAMES + TEMP_NAMES + ACC_NAMES
)
N_FEATURES = len(SUBWINDOW_FEATURE_NAMES)

# HRV (from BVP, 64 Hz)
def _hrv_time_features(bvp: np.ndarray, fs: int = 64) -> np.ndarray:
    """Time-domain HRV on a PPG segment."""
    out = np.zeros(len(HRV_TIME_NAMES), dtype=np.float32)
    if bvp.shape[0] < fs * 2:  # need at least 2 s
        return out
    try:
        peaks = nk.ppg_findpeaks(bvp, sampling_rate=fs, show=False)["PPG_Peaks"]
        if len(peaks) < 3:
            return out
        rri_ms = np.diff(peaks) / fs * 1000.0
        out[0] = np.mean(rri_ms)
        out[1] = np.std(rri_ms, ddof=1) if len(rri_ms) > 1 else 0.0
        diff = np.diff(rri_ms)
        out[2] = np.sqrt(np.mean(diff ** 2)) if diff.size else 0.0
        out[3] = (np.sum(np.abs(diff) > 50) / len(diff) * 100.0) if diff.size else 0.0
    except Exception:
        pass
    return out


def hrv_freq_features(bvp_window: np.ndarray, fs: int = 64) -> np.ndarray:
    """Frequency-domain HRV on the 60 s window"""
    out = np.zeros(len(HRV_FREQ_NAMES), dtype=np.float32)
    if bvp_window.shape[0] < fs * 30:  # need at least 30s for low freq
        return out
    try:
        peaks = nk.ppg_findpeaks(bvp_window, sampling_rate=fs, show=False)["PPG_Peaks"]
        if len(peaks) < 8:
            return out
        # Build a Peaks dict that nk.hrv_frequency expects
        peaks_dict = {"PPG_Peaks": peaks}
        hrv = nk.hrv_frequency(peaks_dict, sampling_rate=fs, show=False, silent=True)
        out[0] = float(hrv.get("HRV_LF", [0.0]).iloc[0] or 0.0)
        out[1] = float(hrv.get("HRV_HF", [0.0]).iloc[0] or 0.0)
        out[2] = float(hrv.get("HRV_LFHF", [0.0]).iloc[0] or 0.0)
    except Exception:
        pass
    # Replace NaN/inf produced by tiny windows with 0
    return np.nan_to_num(out, nan=0.0, posinf=0.0, neginf=0.0).astype(np.float32)


# EDA (4 Hz)
def eda_features(eda: np.ndarray, fs: int = 4, method: str = "highpass") -> np.ndarray:
    """Tonic/phasic decomposition for one 5 s sub-window."""
    out = np.zeros(len(EDA_NAMES), dtype=np.float32)
    if eda.shape[0] < fs * 3:
        return out
    try:
        decomposed = nk.eda_phasic(
            nk.standardize(eda), sampling_rate=fs, method=method
        )
        scl = decomposed["EDA_Tonic"].to_numpy()
        scr = decomposed["EDA_Phasic"].to_numpy()
        # SCL: mean + linear slope.
        out[0] = float(np.mean(scl))
        if scl.size > 1:
            out[1] = float(np.polyfit(np.arange(scl.size), scl, 1)[0])
        # SCR aggregate stats
        out[2] = float(np.mean(scr))
        out[3] = float(np.std(scr, ddof=1)) if scr.size > 1 else 0.0
        # indices 4-6 filled by scr_peak_features broadcast
    except Exception:
        pass
    return np.nan_to_num(out, nan=0.0, posinf=0.0, neginf=0.0).astype(np.float32)


def scr_peak_features(eda_window: np.ndarray, fs: int = 4,
                      method: str = "highpass") -> np.ndarray:
    """Compute SCR peak statistics on the full 60s EDA window."""
    from scipy.signal import find_peaks

    out = np.zeros(3, dtype=np.float32)
    if eda_window.shape[0] < fs * 30:
        return out
    try:
        decomposed = nk.eda_phasic(
            nk.standardize(eda_window), sampling_rate=fs, method=method
        )
        scr = decomposed["EDA_Phasic"].to_numpy()
        out[0] = float(np.trapezoid(np.abs(scr)) / fs) # AUC in seconds

        # find_peaks works well at any fs, we will use 10% of the peak range as min height
        peak_range = scr.max() - scr.min()
        if peak_range > 0:
            min_height = scr.min() + 0.10 * peak_range
            # Minimum distance between peaks is 1 s (typical inter-SCR)
            _, props = find_peaks(scr, height=min_height,
                                          distance=max(1, int(fs * 1.0)))
            amplitudes = props["peak_heights"] - scr.min()
            out[1] = float(len(amplitudes))
            out[2] = float(np.mean(amplitudes)) if len(amplitudes) else 0.0
    except Exception:
        pass
    return np.nan_to_num(out, nan=0.0, posinf=0.0, neginf=0.0).astype(np.float32)


# Temperature (4 Hz)
def temp_features(temp: np.ndarray) -> np.ndarray:
    out = np.zeros(len(TEMP_NAMES), dtype=np.float32)
    if temp.size == 0:
        return out
    out[0] = float(np.mean(temp))
    out[1] = float(np.std(temp, ddof=1)) if temp.size > 1 else 0.0
    if temp.size > 1:
        out[2] = float(np.polyfit(np.arange(temp.size), temp, 1)[0])
    out[3] = float(np.min(temp))
    out[4] = float(np.max(temp))
    return out


# Accelerometer magnitude (32 Hz, already band-passed)
def acc_features(acc_mag: np.ndarray) -> np.ndarray:
    out = np.zeros(len(ACC_NAMES), dtype=np.float32)
    if acc_mag.size == 0:
        return out
    out[0] = float(np.mean(acc_mag))
    out[1] = float(np.std(acc_mag, ddof=1)) if acc_mag.size > 1 else 0.0
    out[2] = float(np.sum(acc_mag ** 2) / acc_mag.size)

    # Zero-crossing rate of the band-passed signal
    centered = acc_mag - np.mean(acc_mag)
    zc = np.sum(np.diff(np.signbit(centered).astype(np.int8)) != 0)
    out[3] = float(zc / acc_mag.size)
    return out


# Composite per-sub-window vector
def subwindow_feature_vector(
    bvp_seg: np.ndarray,
    eda_seg: np.ndarray,
    temp_seg: np.ndarray,
    acc_mag_seg: np.ndarray,
    hrv_freq_static: np.ndarray,
    scr_peak_static: np.ndarray,
    eda_method: str = "highpass",
) -> np.ndarray:
    """Concatenate features for one sub-window.

    Two vectors are computed once on the full 60 s window and broadcast:
    - hrv_freq_static: 3-vector (LF, HF, LF/HF)
    - scr_peak_static: 3-vector (scr_auc, scr_peaks, scr_amp_mean)
    """
    eda_vec = eda_features(eda_seg, method=eda_method)
    eda_vec[4:7] = scr_peak_static
    parts = (
        _hrv_time_features(bvp_seg),
        hrv_freq_static,
        eda_vec,
        temp_features(temp_seg),
        acc_features(acc_mag_seg),
    )
    vec = np.concatenate(parts).astype(np.float32)
    assert vec.shape[0] == N_FEATURES, (vec.shape, N_FEATURES)
    return vec


__all__ = [
    "SUBWINDOW_FEATURE_NAMES",
    "N_FEATURES",
    "hrv_freq_features",
    "eda_features",
    "scr_peak_features",
    "temp_features",
    "acc_features",
    "subwindow_feature_vector",
]

