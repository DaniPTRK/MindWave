"""Test audit logging"""
from __future__ import annotations

import pytest
from httpx import AsyncClient
from sqlalchemy import select

from .conftest import TestSessionLocal
from app.models import AuditLog


@pytest.mark.asyncio
class TestAuditLogging:
    """Verify that the audit middleware captures state-changing requests."""

    async def test_post_creates_audit_entry(self, client: AsyncClient, admin_token: str):
        """A POST request should produce an audit log entry."""
        await client.post("/auth/register", json={
            "email": "audit-test@example.com",
            "password": "AuditTest1",
            "role": "user",
        }, headers={"Authorization": f"Bearer {admin_token}"})

        # Check audit log
        async with TestSessionLocal() as db:
            result = await db.execute(
                select(AuditLog).where(AuditLog.resource == "/auth/register")
            )
            logs = result.scalars().all()
            assert len(logs) >= 1
            log = logs[-1]
            assert log.action == "POST"
            assert log.resource == "/auth/register"

    async def test_get_does_not_create_audit_entry(self, client: AsyncClient, admin_token: str):
        """GET requests should NOT produce audit entries."""
        await client.get("/auth/me", headers={
            "Authorization": f"Bearer {admin_token}",
        })

        async with TestSessionLocal() as db:
            result = await db.execute(
                select(AuditLog).where(AuditLog.resource == "/auth/me")
            )
            logs = result.scalars().all()
            assert len(logs) == 0

    async def test_audit_captures_user_id(self, client: AsyncClient, admin_token: str, admin_user):
        """Audit entry should link to the authenticated user."""
        await client.post("/auth/register", json={
            "email": "audit-user@example.com",
            "password": "AuditUser1",
            "role": "user",
        }, headers={"Authorization": f"Bearer {admin_token}"})

        async with TestSessionLocal() as db:
            result = await db.execute(
                select(AuditLog).where(AuditLog.resource == "/auth/register")
            )
            logs = result.scalars().all()
            assert any(log.user_id == admin_user.id for log in logs)

    async def test_unauthenticated_post_still_logged(self, client: AsyncClient):
        """Faile posts should have audit"""
        await client.post("/auth/register", json={
            "email": "noauth@example.com",
            "password": "NoAuth123",
        })

        async with TestSessionLocal() as db:
            result = await db.execute(
                select(AuditLog).where(AuditLog.resource == "/auth/register")
            )
            logs = result.scalars().all()
            assert any(log.user_id is None for log in logs)

