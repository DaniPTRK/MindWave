"""Test k-anonymity enforcement on aggregate stats.

The core privacy test: POST /stats/aggregate with n_users < k_anon_threshold (5)
must be rejected, while n_users >= 5 is accepted.
"""
from __future__ import annotations

from datetime import datetime, timezone

import pytest
from httpx import AsyncClient

from .conftest import TestSessionLocal
from app.models import Organization


@pytest.mark.asyncio
class TestKAnonymity:
    """K-anonymity constraint validation on /stats/aggregate."""

    @pytest.fixture(autouse=True)
    async def _seed_org(self):
        """Create an organization for stats."""
        async with TestSessionLocal() as db:
            db.add(Organization(id=1, name="Test Corp"))
            await db.commit()

    async def test_n_users_below_threshold_rejected(self, client: AsyncClient, admin_token: str):
        """n_users = 4 < K_ANON_THRESHOLD(5), err"""
        resp = await client.post("/stats/aggregate", json={
            "organization_id": 1,
            "period_start": "2025-01-01T00:00:00Z",
            "period_end": "2025-01-02T00:00:00Z",
            "period_type": "day",
            "mean_stress_score": 0.6,
            "std_stress": 0.1,
            "n_users": 4,
        }, headers={"Authorization": f"Bearer {admin_token}"})
        assert resp.status_code == 422
        assert "k-anonymity" in str(resp.json()).lower() or "n_users" in str(resp.json()).lower()

    async def test_n_users_at_threshold_accepted(self, client: AsyncClient, admin_token: str):
        """n_users = 5 == K_ANON_THRESHOLD, accepted."""
        resp = await client.post("/stats/aggregate", json={
            "organization_id": 1,
            "period_start": "2025-01-01T00:00:00Z",
            "period_end": "2025-01-02T00:00:00Z",
            "period_type": "day",
            "mean_stress_score": 0.55,
            "std_stress": 0.12,
            "n_users": 5,
        }, headers={"Authorization": f"Bearer {admin_token}"})
        assert resp.status_code == 201

    async def test_n_users_above_threshold_accepted(self, client: AsyncClient, admin_token: str):
        """n_users = 50 > K_ANON_THRESHOLD, accepted."""
        resp = await client.post("/stats/aggregate", json={
            "organization_id": 1,
            "period_start": "2025-02-01T00:00:00Z",
            "period_end": "2025-02-02T00:00:00Z",
            "period_type": "day",
            "mean_stress_score": 0.4,
            "std_stress": 0.08,
            "n_users": 50,
        }, headers={"Authorization": f"Bearer {admin_token}"})
        assert resp.status_code == 201

    async def test_n_users_1_rejected(self, client: AsyncClient, admin_token: str):
        """Single-user rollup (n_users=1) must ALWAYS be rejected."""
        resp = await client.post("/stats/aggregate", json={
            "organization_id": 1,
            "period_start": "2025-03-01T00:00:00Z",
            "period_end": "2025-03-02T00:00:00Z",
            "period_type": "day",
            "mean_stress_score": 0.9,
            "std_stress": 0.0,
            "n_users": 1,
        }, headers={"Authorization": f"Bearer {admin_token}"})
        assert resp.status_code == 422

    async def test_cross_org_rejected(self, client: AsyncClient, user_token: str):
        """Normal user cannot post stats for another organization."""
        resp = await client.post("/stats/aggregate", json={
            "organization_id": 999,  # not user's org
            "period_start": "2025-01-01T00:00:00Z",
            "period_end": "2025-01-02T00:00:00Z",
            "period_type": "day",
            "mean_stress_score": 0.5,
            "std_stress": 0.1,
            "n_users": 10,
        }, headers={"Authorization": f"Bearer {user_token}"})
        assert resp.status_code == 403


@pytest.mark.asyncio
class TestStatsRetrieval:
    """GET /stats/organization/{org_id}"""

    @pytest.fixture(autouse=True)
    async def _seed_org(self):
        async with TestSessionLocal() as db:
            db.add(Organization(id=1, name="Test Corp"))
            await db.commit()

    async def test_requires_hr_or_admin_role(self, client: AsyncClient, user_token: str):
        """Normal users cannot list org stats."""
        resp = await client.get("/stats/organization/1", headers={
            "Authorization": f"Bearer {user_token}",
        })
        assert resp.status_code == 403

    async def test_admin_can_list(self, client: AsyncClient, admin_token: str):
        resp = await client.get("/stats/organization/1", headers={
            "Authorization": f"Bearer {admin_token}",
        })
        assert resp.status_code == 200
        assert isinstance(resp.json(), list)
