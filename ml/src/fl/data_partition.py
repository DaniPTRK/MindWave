# Per-subject partitioning of the WESAD windowed dataset for Federated Learning.

# Each WESAD subject becomes a flower client. This replicates the real-world
# deployment where every smartwatch user only ever sees their own biometric
# data. The scaler is also fit per client on the client's own
# training split. The server never sees raw feature values.

from __future__ import annotations

import warnings
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable

import numpy as np
import pandas as pd
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import StandardScaler

from ..config import LABEL_NAMES, PROCESSED_DIR, SEED
from ..train import apply_scaler_3d, fit_scaler_3d, load_npz


@dataclass
class ClientPartition:
    """All the data + scaler that one federated client owns."""
    subject: str
    X_train: np.ndarray
    y_train: np.ndarray
    X_test: np.ndarray
    y_test: np.ndarray
    scaler: StandardScaler
    class_counts: dict[int, int] = field(default_factory=dict)

    @property
    def n_train(self) -> int:
        return int(self.X_train.shape[0])

    @property
    def n_test(self) -> int:
        return int(self.X_test.shape[0])


def _stratified_split(X, y, test_size, seed):
    """Stratified split with a non-stratified fallback"""
    try:
        return train_test_split(
            X, y, test_size=test_size, random_state=seed, stratify=y
        )
    except ValueError as exc:
        warnings.warn(
            f"Stratified split failed ({exc}); falling back to random split.",
            RuntimeWarning,
        )
        return train_test_split(X, y, test_size=test_size, random_state=seed)


def partition_by_subject(
    npz_path: Path | str = PROCESSED_DIR / "wesad_wrist.npz",
    subjects: Iterable[str] | None = None,
    test_size: float = 0.2,
    seed: int = SEED,
) -> dict[str, ClientPartition]:
    """Build one partition per WESAD subject."""
    X, y, sids, _ = load_npz(Path(npz_path))

    wanted = set(subjects) if subjects is not None else None
    parts: dict[str, ClientPartition] = {}

    for sid in sorted(np.unique(sids)):
        if wanted is not None and sid not in wanted:
            continue
        mask = sids == sid
        Xs, ys = X[mask], y[mask]
        if Xs.shape[0] < 5:
            warnings.warn(f"Skipping {sid}: only {Xs.shape[0]} windows.")
            continue

        Xtr, Xte, ytr, yte = _stratified_split(Xs, ys, test_size, seed)

        # Per-client scaler
        scaler = fit_scaler_3d(Xtr)
        Xtr_s = apply_scaler_3d(scaler, Xtr)
        Xte_s = apply_scaler_3d(scaler, Xte)

        cls, cnt = np.unique(ys, return_counts=True)
        parts[sid] = ClientPartition(
            subject=sid,
            X_train=Xtr_s,
            y_train=ytr.astype(np.int64),
            X_test=Xte_s,
            y_test=yte.astype(np.int64),
            scaler=scaler,
            class_counts={int(c): int(n) for c, n in zip(cls, cnt)},
        )
    return parts


def summarize_partitions(parts: dict[str, ClientPartition]) -> pd.DataFrame:
    """Tabular overview for notebook"""
    rows = []
    for sid, p in parts.items():
        row = {
            "subject": sid,
            "n_train": p.n_train,
            "n_test": p.n_test,
        }
        for cls_id, cls_name in LABEL_NAMES.items():
            row[cls_name] = p.class_counts.get(cls_id, 0)
        rows.append(row)
    return pd.DataFrame(rows).set_index("subject")


__all__ = [
    "ClientPartition",
    "partition_by_subject",
    "summarize_partitions",
]

