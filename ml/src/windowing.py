"""Sliding-window assembly: turns a subject's wrist signals into LSTM tensors.

Each 60 s window is split into 12 contiguous 5 s sub-windows; for every
sub-window we extract a feature vector.
"""
from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path

import numpy as np
from tqdm import tqdm

from .config import (
    KEEP_LABELS,
    LABEL_PURITY_THRESHOLD,
    LABEL_REMAP,
    N_SUBWINDOWS,
    PROCESSED_DIR,
    STRIDE_SECONDS,
    SUBWINDOW_SECONDS,
    WESAD_DIR,
    WINDOW_SECONDS,
    WRIST_FS,
)
from .features import (
    N_FEATURES,
    SUBWINDOW_FEATURE_NAMES,
    hrv_freq_features,
    scr_peak_features,
    subwindow_feature_vector,
)
from .preprocessing import (
    acc_magnitude,
    filter_acc_mag,
    filter_bvp,
    filter_eda,
    filter_temp,
)
from .wesad_loader import WristRecord, iter_subjects, load_subject


def _segment(arr: np.ndarray, start_s: float, length_s: float, fs: int) -> np.ndarray:
    a = int(round(start_s * fs))
    b = a + int(round(length_s * fs))
    return arr[a:b]


def _window_label(label_700hz: np.ndarray, start_s: float, length_s: float) -> tuple[int, float]:
    # Return (majority_label, purity) for a window in 700 Hz label space
    a = int(round(start_s * 700))
    b = a + int(round(length_s * 700))
    seg = label_700hz[a:b]
    if seg.size == 0:
        return -1, 0.0
    counts = Counter(seg.tolist())
    label, count = counts.most_common(1)[0]
    return int(label), count / seg.size


def windows_from_subject(
    record: WristRecord,
    eda_method: str = "highpass",
) -> tuple[np.ndarray, np.ndarray]:
    """Build (X, y) for one subject."""
    #  Filter once per subject
    bvp = filter_bvp(record.bvp, WRIST_FS["BVP"])
    eda = filter_eda(record.eda, WRIST_FS["EDA"])
    temp = filter_temp(record.temp)
    acc_mag = filter_acc_mag(acc_magnitude(record.acc), WRIST_FS["ACC"])

    # Total session length is bounded by the shortest sensor track
    duration_s = min(
        bvp.size / WRIST_FS["BVP"],
        eda.size / WRIST_FS["EDA"],
        temp.size / WRIST_FS["TEMP"],
        acc_mag.size / WRIST_FS["ACC"],
        record.label_700hz.size / 700,
    )

    X_list: list[np.ndarray] = []
    y_list: list[int] = []

    start_s = 0.0
    while start_s + WINDOW_SECONDS <= duration_s:
        # Majority-vote label for the whole 60 s window
        raw_label, purity = _window_label(record.label_700hz, start_s, WINDOW_SECONDS)
        if raw_label in KEEP_LABELS and purity >= LABEL_PURITY_THRESHOLD:
            # Frequency-domain HRV and SCR peaks both need the full 60 s window.
            bvp_win = _segment(bvp, start_s, WINDOW_SECONDS, WRIST_FS["BVP"])
            eda_win = _segment(eda, start_s, WINDOW_SECONDS, WRIST_FS["EDA"])
            hrv_freq_static = hrv_freq_features(bvp_win, WRIST_FS["BVP"])
            scr_peak_static = scr_peak_features(eda_win, WRIST_FS["EDA"],
                                                method=eda_method)

            # 12 sub-windows of 5 s
            window_features = np.zeros((N_SUBWINDOWS, N_FEATURES), dtype=np.float32)
            for k in range(N_SUBWINDOWS):
                s = start_s + k * SUBWINDOW_SECONDS
                window_features[k] = subwindow_feature_vector(
                    _segment(bvp, s, SUBWINDOW_SECONDS, WRIST_FS["BVP"]),
                    _segment(eda, s, SUBWINDOW_SECONDS, WRIST_FS["EDA"]),
                    _segment(temp, s, SUBWINDOW_SECONDS, WRIST_FS["TEMP"]),
                    _segment(acc_mag, s, SUBWINDOW_SECONDS, WRIST_FS["ACC"]),
                    hrv_freq_static,
                    scr_peak_static,
                    eda_method=eda_method,
                )
            X_list.append(window_features)
            y_list.append(LABEL_REMAP[raw_label])

        start_s += STRIDE_SECONDS

    if not X_list:
        return (
            np.zeros((0, N_SUBWINDOWS, N_FEATURES), dtype=np.float32),
            np.zeros((0,), dtype=np.int64),
        )
    return np.stack(X_list), np.asarray(y_list, dtype=np.int64)


def build_dataset(
    wesad_root: Path | str = WESAD_DIR,
    out_path: Path | str | None = None,
    eda_method: str = "highpass",
    subjects: list[str] | None = None,
) -> dict:
    """Iterate every subject, concatenate windows, persist to .npz."""
    out_path = Path(out_path) if out_path else PROCESSED_DIR / "wesad_wrist.npz"

    iterator = (
        (load_subject(sid, wesad_root) for sid in subjects)
        if subjects is not None
        else iter_subjects(wesad_root)
    )

    X_all, y_all, sid_all = [], [], []
    for record in tqdm(iterator, desc="Subjects", total=len(subjects) if subjects else None):
        X, y = windows_from_subject(record, eda_method=eda_method)
        if X.shape[0] == 0:
            continue
        X_all.append(X)
        y_all.append(y)
        sid_all.append(np.array([record.subject] * X.shape[0]))

    X = np.concatenate(X_all, axis=0)
    y = np.concatenate(y_all, axis=0)
    subject_ids = np.concatenate(sid_all, axis=0)

    out_path.parent.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(
        out_path,
        X=X,
        y=y,
        subject_ids=subject_ids,
        feature_names=np.array(SUBWINDOW_FEATURE_NAMES),
    )
    return {
        "out_path": str(out_path),
        "X_shape": X.shape,
        "y_shape": y.shape,
        "n_subjects": len(np.unique(subject_ids)),
        "class_counts": dict(Counter(y.tolist())),
    }


def _main() -> None:
    p = argparse.ArgumentParser(description="Build the windowed WESAD wrist dataset")
    p.add_argument("--wesad", type=str, default=str(WESAD_DIR))
    p.add_argument("--out", type=str, default=str(PROCESSED_DIR / "wesad_wrist.npz"))
    p.add_argument("--eda-method", type=str, default="highpass",
                   choices=["highpass", "cvxEDA", "smoothmedian"])
    args = p.parse_args()

    summary = build_dataset(args.wesad, args.out, eda_method=args.eda_method)
    print("Done:", summary)


if __name__ == "__main__":
    _main()


__all__ = ["windows_from_subject", "build_dataset"]

