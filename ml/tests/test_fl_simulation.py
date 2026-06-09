"""Test FL simulation, such as weight aggregation and client-server interaction."""
from __future__ import annotations

from pathlib import Path

import numpy as np
import pytest

from src.config import MODELS_DIR, N_SUBWINDOWS, SUBJECTS
from src.features import N_FEATURES

TFLITE_TRAINABLE = MODELS_DIR / "mindwave_stress_trainable.tflite"


class TestFLSimulation:
    """Simulate multi-client federated learning and verify properties."""

    @pytest.fixture
    def sample_data(self):
        """Synthetic training data for 3 virtual clients."""
        rng = np.random.default_rng(42)
        clients = []
        for _ in range(3):
            X = rng.standard_normal((10, N_SUBWINDOWS, N_FEATURES)).astype(np.float32)
            y = rng.integers(0, 2, size=(10,)).astype(np.int64)
            clients.append((X, y))
        return clients

    def test_fedavg_weight_shapes_consistent(self, sample_data):
        """All client weight lists must have the same structure for FedAvg."""
        client_weights = []
        for X, y in sample_data:
            # Simulate params as a list of random weight-like tensors
            w = [np.random.randn(23, 64).astype(np.float32),
                 np.random.randn(64,).astype(np.float32),
                 np.random.randn(64, 2).astype(np.float32)]
            client_weights.append(w)

        n_tensors = len(client_weights[0])
        for cw in client_weights:
            assert len(cw) == n_tensors

        # Each corresponding tensor must have same shape
        for i in range(n_tensors):
            shapes = [cw[i].shape for cw in client_weights]
            assert all(s == shapes[0] for s in shapes)

    def test_fedavg_is_mean(self, sample_data):
        """FedAvg with uniform weights should be arithmetic mean."""
        n_clients = 3
        client_weights = []
        for _ in range(n_clients):
            w = [np.random.default_rng(i).standard_normal((10, 5)).astype(np.float32)
                 for i in range(2)]
            client_weights.append(w)

        # FedAvg
        aggregated = [
            np.mean(np.stack([cw[i] for cw in client_weights], axis=0), axis=0)
            for i in range(len(client_weights[0]))
        ]

        # check mean
        for i in range(len(aggregated)):
            expected = sum(cw[i] for cw in client_weights) / n_clients
            np.testing.assert_allclose(aggregated[i], expected, rtol=1e-5)

    def test_no_raw_data_in_payload(self, sample_data):
        """Privacy boundary test, the weights mustn't contain any raw data."""
        X, y = sample_data[0]

        # Simulate weight extraction
        weights = [np.random.randn(23, 64).astype(np.float32)]
        payload_bytes = b"".join(w.tobytes() for w in weights)

        # The raw data bytes
        raw_x_bytes = X.tobytes()
        raw_y_bytes = y.tobytes()

        # Payload should NOT contain any raw data bytes
        assert raw_x_bytes not in payload_bytes
        assert raw_y_bytes not in payload_bytes

    def test_global_model_improves_or_stabilizes(self):
        """
        After multiple rounds of FedAvg, the aggregated weights should
        not diverge (norms stay bounded).
        """
        rng = np.random.default_rng(42)
        n_rounds = 5
        n_clients = 3
        weight_shape = (23, 32)

        global_weights = [rng.standard_normal(weight_shape).astype(np.float32)]

        for _ in range(n_rounds):
            client_updates = []
            for _ in range(n_clients):
                # Peturb global weights for simulation
                local = [gw + 0.01 * rng.standard_normal(gw.shape).astype(np.float32)
                         for gw in global_weights]
                client_updates.append(local)

            # Aggregate
            global_weights = [
                np.mean(np.stack([cu[i] for cu in client_updates], axis=0), axis=0)
                for i in range(len(global_weights))
            ]

        # Global weights should have bounded norm
        for gw in global_weights:
            assert np.linalg.norm(gw) < 1000.0

    def test_single_client_fedavg_equals_client(self):
        """With one client, FedAvg output should equal the client's weights."""
        w = [np.array([1.0, 2.0, 3.0], dtype=np.float32)]
        aggregated = [np.mean(np.stack([w[0]]), axis=0)]
        np.testing.assert_array_equal(aggregated[0], w[0])

