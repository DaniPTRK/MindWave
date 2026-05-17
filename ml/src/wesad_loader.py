# Load wrist-only signals from the WESAD .pkl files

"""
WESAD pickle structure:

    {
      'subject': 'S2',
      'signal': {
          'chest': {...}, # ignored because we use the smartwatch
          'wrist': {
              'BVP':  (N_bvp, 1) float, 64 Hz,
              'EDA':  (N_eda, 1) float, 4 Hz,
              'TEMP': (N_tmp, 1) float, 4 Hz,
              'ACC':  (N_acc, 3) float, 32 Hz,  # xyz
          }
      },
      'label': (N_label,) uint8, 700 Hz
    }
"""
from __future__ import annotations

import pickle
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator

import numpy as np

from .config import LABEL_FS, SUBJECTS, WESAD_DIR, WRIST_FS


@dataclass
class WristRecord:
    # All wrist signals for one subject, with labels aligned per signal
    subject: str
    bvp: np.ndarray   # (N_bvp,)   float32, 64 Hz
    eda: np.ndarray   # (N_eda,)   float32, 4 Hz
    temp: np.ndarray  # (N_tmp,)   float32, 4 Hz
    acc: np.ndarray   # (N_acc, 3) float32, 32 Hz
    label_700hz: np.ndarray  # (N_label,) uint8, 700 Hz


def load_subject(subject_id: str, wesad_root: Path | str = WESAD_DIR) -> WristRecord:
    # Load a single WESAD subject and return only the wrist signals
    wesad_root = Path(wesad_root)
    pkl_path = wesad_root / subject_id / f"{subject_id}.pkl"
    if not pkl_path.exists():
        raise FileNotFoundError(f"Missing WESAD pickle: {pkl_path}")

    # Latin1 encoding for python2 legacy
    with pkl_path.open("rb") as fh:
        data = pickle.load(fh, encoding="latin1")

    wrist = data["signal"]["wrist"]
    return WristRecord(
        subject=subject_id,
        bvp=np.asarray(wrist["BVP"], dtype=np.float32).squeeze(),
        eda=np.asarray(wrist["EDA"], dtype=np.float32).squeeze(),
        temp=np.asarray(wrist["TEMP"], dtype=np.float32).squeeze(),
        acc=np.asarray(wrist["ACC"], dtype=np.float32),
        label_700hz=np.asarray(data["label"], dtype=np.uint8).squeeze(),
    )


def iter_subjects(wesad_root: Path | str = WESAD_DIR) -> Iterator[WristRecord]:
    # Iterate over all available subjects
    for sid in SUBJECTS:
        try:
            yield load_subject(sid, wesad_root)
        except FileNotFoundError:
            continue


def downsample_labels(label_700hz: np.ndarray, target_fs: int, n_samples: int) -> np.ndarray:
    """Map the 700 Hz label vector to ``n_samples`` indices at ``target_fs``.

    Uses nearest-neighbour index mapping (no interpolation: labels are
    categorical). Returns an array of length ``n_samples`` aligned with the
    target signal.
    """
    if target_fs <= 0 or n_samples <= 0:
        raise ValueError("target_fs and n_samples must be positive")

    # Fraction of label samples that correspond to one target sample
    ratio = LABEL_FS / target_fs
    idx = (np.arange(n_samples) * ratio).astype(np.int64)
    idx = np.clip(idx, 0, len(label_700hz) - 1)
    return label_700hz[idx]


def expected_lengths(record: WristRecord) -> dict[str, int]:
    # Return the actual length per wrist signal for sanity checks
    return {
        "BVP": int(record.bvp.shape[0]),
        "EDA": int(record.eda.shape[0]),
        "TEMP": int(record.temp.shape[0]),
        "ACC": int(record.acc.shape[0]),
        "label_700Hz": int(record.label_700hz.shape[0]),
    }


# Exported for notebook
__all__ = [
    "WristRecord",
    "WRIST_FS",
    "LABEL_FS",
    "load_subject",
    "iter_subjects",
    "downsample_labels",
    "expected_lengths",
]

