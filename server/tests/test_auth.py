"""Test auth endpoints"""
from __future__ import annotations

import pytest
from httpx import AsyncClient


@pytest.mark.asyncio
class TestRegister:
    """POST /auth/register and /auth/register/user"""

    async def test_public_register_success(self, client: AsyncClient):
        resp = await client.post("/auth/register/user", json={
            "email": "new@example.com",
            "password": "Secure123",
        })
        assert resp.status_code == 201
        data = resp.json()
        assert data["email"] == "new@example.com"
        assert data["role"] == "user"

    async def test_duplicate_email_rejected(self, client: AsyncClient):
        await client.post("/auth/register/user", json={
            "email": "dup@example.com",
            "password": "Secure123",
        })
        resp = await client.post("/auth/register/user", json={
            "email": "dup@example.com",
            "password": "Secure456",
        })
        assert resp.status_code == 409

    async def test_weak_password_rejected(self, client: AsyncClient):
        resp = await client.post("/auth/register/user", json={
            "email": "weak@example.com",
            "password": "short",  # too short, no digit
        })
        assert resp.status_code == 422

    async def test_password_requires_digit(self, client: AsyncClient):
        resp = await client.post("/auth/register/user", json={
            "email": "nodigit@example.com",
            "password": "NoDigitHere",
        })
        assert resp.status_code == 422

    async def test_admin_register_requires_auth(self, client: AsyncClient):
        resp = await client.post("/auth/register", json={
            "email": "admin2@example.com",
            "password": "Admin1234",
            "role": "admin",
        })
        assert resp.status_code == 401  # no token


@pytest.mark.asyncio
class TestLogin:
    """POST /auth/login"""

    async def test_login_success(self, client: AsyncClient, admin_user):
        resp = await client.post("/auth/login", data={
            "username": "admin@example.com",
            "password": "Admin1234",
        })
        assert resp.status_code == 200
        data = resp.json()
        assert "access_token" in data
        assert "refresh_token" in data
        assert data["token_type"] == "bearer"
        assert data["expires_in"] > 0

    async def test_login_wrong_password(self, client: AsyncClient, admin_user):
        resp = await client.post("/auth/login", data={
            "username": "admin@example.com",
            "password": "WrongPass1",
        })
        assert resp.status_code == 401

    async def test_login_nonexistent_user(self, client: AsyncClient):
        resp = await client.post("/auth/login", data={
            "username": "ghost@nowhere.com",
            "password": "DoesntMatter1",
        })
        assert resp.status_code == 401


@pytest.mark.asyncio
class TestMe:
    """GET /auth/me"""

    async def test_me_authenticated(self, client: AsyncClient, admin_token: str):
        resp = await client.get("/auth/me", headers={
            "Authorization": f"Bearer {admin_token}",
        })
        assert resp.status_code == 200
        assert resp.json()["email"] == "admin@example.com"

    async def test_me_no_token(self, client: AsyncClient):
        resp = await client.get("/auth/me")
        assert resp.status_code == 401

    async def test_me_invalid_token(self, client: AsyncClient):
        resp = await client.get("/auth/me", headers={
            "Authorization": "Bearer invalid.jwt.token",
        })
        assert resp.status_code == 401


@pytest.mark.asyncio
class TestRoleAccess:
    """Role-based endpoint access."""

    async def test_admin_can_register_users(self, client: AsyncClient, admin_token: str):
        resp = await client.post("/auth/register", json={
            "email": "hr@example.com",
            "password": "HRPass123",
            "role": "hr",
        }, headers={"Authorization": f"Bearer {admin_token}"})
        assert resp.status_code == 201
        assert resp.json()["role"] == "hr"

    async def test_normal_user_cannot_admin_register(self, client: AsyncClient, user_token: str):
        resp = await client.post("/auth/register", json={
            "email": "sneaky@example.com",
            "password": "Sneaky123",
            "role": "admin",
        }, headers={"Authorization": f"Bearer {user_token}"})
        assert resp.status_code == 403
