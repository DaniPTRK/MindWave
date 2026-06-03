# Project-wide constants, paths and reproducibility helpers
from __future__ import annotations

import os
import random
from pathlib import Path

import numpy as np

# Paths
ML_DIR = Path(__file__).resolve().parent.parent
REPO_ROOT = ML_DIR.parent
WESAD_DIR = REPO_ROOT / "WESAD"
PROCESSED_DIR = ML_DIR / "data" / "processed"
MODELS_DIR = ML_DIR / "models"

PROCESSED_DIR.mkdir(parents=True, exist_ok=True)
MODELS_DIR.mkdir(parents=True, exist_ok=True)

# Empatica E4 wrist sampling rates (Hz)
WRIST_FS = {"BVP": 64, "EDA": 4, "TEMP": 4, "ACC": 32}

# WESAD raw labels are sampled at 700 Hz
LABEL_FS = 700

# Original WESAD label scheme:
#   0 = transient, 1 = baseline, 2 = stress,
#   3 = amusement, 4 = meditation, 5/6/7 = should be ignored
KEEP_LABELS = (1, 2, 3)
LABEL_REMAP = {1: 0, 2: 1, 3: 2}
LABEL_NAMES = {0: "baseline", 1: "stress", 2: "amusement"}

# Subjects shipped with WESAD - S12 is missing
SUBJECTS = [f"S{i}" for i in range(2, 18) if i != 12]

# Windowing
WINDOW_SECONDS = 60
STRIDE_SECONDS = 15
SUBWINDOW_SECONDS = 5
N_SUBWINDOWS = WINDOW_SECONDS // SUBWINDOW_SECONDS  # 12
LABEL_PURITY_THRESHOLD = 0.9

# Reproducibility
SEED = 42


def set_global_seed(seed: int = SEED) -> None:
    # Seed Python, NumPy and TensorFlow RNGs for reproducible runs
    os.environ["PYTHONHASHSEED"] = str(seed)
    random.seed(seed)
    np.random.seed(seed)
    try:
        import tensorflow as tf

        tf.random.set_seed(seed)
    except ImportError:
        # TensorFlow only required for training, not for preprocessing
        pass

