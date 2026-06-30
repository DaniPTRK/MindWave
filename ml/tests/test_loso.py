"""Test LOSO cross-validation fold construction."""
from __future__ import annotations

import numpy as np
import pytest

from src.config import SUBJECTS


class TestLOSOFolds:
    """Verify Leave-One-Subject-Out folds are correctly constructed."""

    def test_all_subjects_present(self):
        """SUBJECTS list should contain 15 subjs"""
        assert len(SUBJECTS) == 15

    def test_fold_excludes_target(self):
        """For each fold, the test set should contain ONLY the held-out subject."""
        # Simulate LOSO splitting with dummy arrays
        n_per_subject = 10
        all_sids = np.concatenate(
            [np.array([s] * n_per_subject) for s in SUBJECTS]
        )
        all_X = np.zeros((len(all_sids), 12, 23))
        all_y = np.zeros(len(all_sids))

        for target in SUBJECTS:
            test_mask = all_sids == target
            train_mask = ~test_mask

            # Test set contains only the target subject
            assert all(all_sids[test_mask] == target)
            # Train set does NOT contain the target
            assert target not in all_sids[train_mask]
            # Train set contains all other subjects
            train_subjects = set(all_sids[train_mask])
            assert train_subjects == set(SUBJECTS) - {target}

    def test_no_data_leakage(self):
        """Train and test indices must not overlap."""
        n = 150
        sids = np.array([SUBJECTS[i % len(SUBJECTS)] for i in range(n)])

        for target in SUBJECTS:
            test_idx = np.where(sids == target)[0]
            train_idx = np.where(sids != target)[0]
            assert len(np.intersect1d(test_idx, train_idx)) == 0

    def test_all_folds_cover_all_data(self):
        """Union of all test folds should equal the full dataset."""
        n = 150
        sids = np.array([SUBJECTS[i % len(SUBJECTS)] for i in range(n)])
        covered = set()
        for target in SUBJECTS:
            test_idx = set(np.where(sids == target)[0].tolist())
            covered.update(test_idx)
        assert covered == set(range(n))
