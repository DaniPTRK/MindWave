"""Test model distribution endpoints: latest, download, round_summary."""
from __future__ import annotations

import pytest
from httpx import AsyncClient

from .conftest import TestSessionLocal
from app.models import ModelVersion


@pytest.mark.asyncio
class TestModelLatest:
    """GET /model/latest"""

    async def test_no_versions_returns_404(self, client: AsyncClient, admin_token: str):
        resp = await client.get("/model/latest", headers={
            "Authorization": f"Bearer {admin_token}",
        })
        assert resp.status_code == 404

    async def test_latest_returns_most_recent(self, client: AsyncClient, admin_token: str):
        # Seed two versions
        async with TestSessionLocal() as db:
            db.add(ModelVersion(version=1, num_clients=2, weights_path="v1.tflite"))
            db.add(ModelVersion(version=2, num_clients=3, weights_path="v2.tflite"))
            await db.commit()

        resp = await client.get("/model/latest", headers={
            "Authorization": f"Bearer {admin_token}",
        })
        assert resp.status_code == 200
        assert resp.json()["version"] == 2

    async def test_requires_auth(self, client: AsyncClient):
        resp = await client.get("/model/latest")
        assert resp.status_code == 401


@pytest.mark.asyncio
class TestModelDownload:
    """GET /model/{version}/download"""

    async def test_unknown_version_returns_404(self, client: AsyncClient, admin_token: str):
        resp = await client.get("/model/999/download", headers={
            "Authorization": f"Bearer {admin_token}",
        })
        assert resp.status_code == 404


@pytest.mark.asyncio
class TestRoundSummary:
    """POST /model/round_summary, called by Flower aggregator."""

    async def test_valid_round_summary(self, client: AsyncClient):
        resp = await client.post("/model/round_summary", json={
            "round": 1,
            "num_clients": 5,
            "mean_loss": 0.42,
            "mean_accuracy": 0.87,
            "weights_filename": "global_v1.tflite",
            "notes": "Test round",
        }, headers={"X-FL-Service-Token": "test-fl-token"})
        assert resp.status_code == 201
        data = resp.json()
        assert data["version"] == 1
        assert data["num_clients"] == 5

    async def test_missing_fl_token_rejected(self, client: AsyncClient):
        resp = await client.post("/model/round_summary", json={
            "round": 1,
            "num_clients": 3,
            "weights_filename": "v1.tflite",
        })
        assert resp.status_code == 401

    async def test_invalid_fl_token_rejected(self, client: AsyncClient):
        resp = await client.post("/model/round_summary", json={
            "round": 1,
            "num_clients": 3,
            "weights_filename": "v1.tflite",
        }, headers={"X-FL-Service-Token": "wrong-token"})
        assert resp.status_code == 401

    async def test_increments_version(self, client: AsyncClient):
        headers = {"X-FL-Service-Token": "test-fl-token"}
        r1 = await client.post("/model/round_summary", json={
            "round": 1, "num_clients": 2, "weights_filename": "v1.tflite",
        }, headers=headers)
        r2 = await client.post("/model/round_summary", json={
            "round": 2, "num_clients": 3, "weights_filename": "v2.tflite",
        }, headers=headers)
        assert r2.json()["version"] == r1.json()["version"] + 1


@pytest.mark.asyncio
class TestFLWeightSubmission:
    """POST /model/fl/submit-weights, mobile HTTP-based FL."""

    async def test_empty_file_rejected(self, client: AsyncClient, admin_token: str):
        import io
        resp = await client.post(
            "/model/fl/submit-weights",
            files={"weights_file": ("fl_weights.bin", io.BytesIO(b""), "application/octet-stream")},
            headers={"Authorization": f"Bearer {admin_token}"},
        )
        assert resp.status_code == 400

    async def test_invalid_binary_rejected(self, client: AsyncClient, admin_token: str):
        import io
        resp = await client.post(
            "/model/fl/submit-weights",
            files={"weights_file": ("fl_weights.bin", io.BytesIO(b"garbage"), "application/octet-stream")},
            headers={"Authorization": f"Bearer {admin_token}"},
        )
        assert resp.status_code == 422

    async def test_requires_auth(self, client: AsyncClient):
        import io
        resp = await client.post(
            "/model/fl/submit-weights",
            files={"weights_file": ("fl_weights.bin", io.BytesIO(b"\x00"), "application/octet-stream")},
        )
        assert resp.status_code == 401

class TestFLAggregationHelpers:
    """Small checks for the HTTP FL aggregation helpers."""

    def _weights_bin(self, *arrays):
        import struct
        payload = bytearray()
        payload.extend(struct.pack(">i", len(arrays)))
        for values in arrays:
            payload.extend(struct.pack(">i", len(values)))
            payload.extend(struct.pack(f">{len(values)}f", *values))
        return bytes(payload)

    def test_parse_weights_binary(self):
        from app.routers.model import _parse_weights_bin

        parsed = _parse_weights_bin(self._weights_bin([1.0, 2.0], [3.0]))
        assert len(parsed) == 2
        assert parsed[0].tolist() == [1.0, 2.0]
        assert parsed[1].tolist() == [3.0]

    def test_sample_weighted_fedavg(self):
        import numpy as np
        from app.routers.model import _aggregate_weight_lists

        aggregated = _aggregate_weight_lists([
            ([np.array([1.0, 3.0], dtype=np.float32)], 1),
            ([np.array([5.0, 7.0], dtype=np.float32)], 3),
        ])

        np.testing.assert_allclose(aggregated[0], np.array([4.0, 6.0], dtype=np.float32))


@pytest.mark.asyncio
class TestFLWeightSubmissionVersioning:
    """Version checks for mobile HTTP-based FL submissions."""

    def _weights_bin(self, *arrays):
        import struct
        payload = bytearray()
        payload.extend(struct.pack(">i", len(arrays)))
        for values in arrays:
            payload.extend(struct.pack(">i", len(values)))
            payload.extend(struct.pack(f">{len(values)}f", *values))
        return bytes(payload)

    async def test_stale_base_model_version_rejected(self, client: AsyncClient, admin_token: str):
        import io

        async with TestSessionLocal() as db:
            db.add(ModelVersion(version=2, num_clients=3, weights_path="v2.tflite"))
            await db.commit()

        resp = await client.post(
            "/model/fl/submit-weights",
            data={"sample_count": "3", "base_model_version": "1"},
            files={"weights_file": ("fl_weights.bin", io.BytesIO(self._weights_bin([1.0])), "application/octet-stream")},
            headers={"Authorization": f"Bearer {admin_token}"},
        )

        assert resp.status_code == 409

@pytest.mark.asyncio
async def test_round_extends_once_then_defers(tmp_path):
    from app.config import get_settings
    from app.routers.model import _create_round, _load_json, _round_meta_path, _try_close_round

    settings = get_settings()
    old_values = (
        settings.models_dir,
        settings.fl_min_clients_for_aggregation,
        settings.fl_round_interval_minutes,
        settings.fl_round_max_extensions,
    )
    try:
        settings.models_dir = str(tmp_path)
        settings.fl_min_clients_for_aggregation = 3
        settings.fl_round_interval_minutes = 60
        settings.fl_round_max_extensions = 1

        meta = _create_round(settings, base_version=0)
        submissions = {"1": {"sample_count": 2, "path": str(tmp_path / "missing.bin")}}
        meta["deadline_at"] = 0

        first = await _try_close_round(meta=meta, submissions=submissions, db=None, settings=settings)
        assert first["status"] == "extended"

        second_meta = _load_json(_round_meta_path(settings, 0), None)
        second_meta["deadline_at"] = 0
        second = await _try_close_round(meta=second_meta, submissions=submissions, db=None, settings=settings)
        assert second["status"] == "deferred"
    finally:
        (
            settings.models_dir,
            settings.fl_min_clients_for_aggregation,
            settings.fl_round_interval_minutes,
            settings.fl_round_max_extensions,
        ) = old_values
