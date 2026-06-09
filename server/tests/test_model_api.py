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