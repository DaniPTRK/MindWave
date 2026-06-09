""" Config file for server's tests, uses a separate sqlite database """
from __future__ import annotations

import asyncio
import os
from typing import AsyncGenerator

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import (
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

# Set required env vars
os.environ["DATABASE_URL"] = "sqlite+aiosqlite:///:memory:"
os.environ["DATABASE_URL_SYNC"] = "sqlite:///:memory:"
os.environ["SECRET_KEY"] = "test-secret-key-for-ci"
os.environ["FL_SERVICE_TOKEN"] = "test-fl-token"
os.environ["K_ANON_THRESHOLD"] = "5"
os.environ["ADMIN_SEED_EMAIL"] = "admin@example.com"
os.environ["ADMIN_SEED_PASSWORD"] = "Admin1234"

from app.database import Base, get_db
from app.main import app
from app.models import User, UserRole
from app.security import hash_password
from app.config import get_settings
import app.main as _app_main

# In-memory test engine
TEST_ENGINE = create_async_engine("sqlite+aiosqlite:///:memory:", echo=False)
TestSessionLocal = async_sessionmaker(bind=TEST_ENGINE, class_=AsyncSession, expire_on_commit=False)

_app_main.AsyncSessionLocal = TestSessionLocal


async def _override_get_db() -> AsyncGenerator[AsyncSession, None]:
    async with TestSessionLocal() as session:
        yield session


app.dependency_overrides[get_db] = _override_get_db


@pytest_asyncio.fixture(autouse=True)
async def setup_db():
    """Create tables before each test, drop after."""
    async with TEST_ENGINE.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield
    async with TEST_ENGINE.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)


@pytest_asyncio.fixture
async def client() -> AsyncGenerator[AsyncClient, None]:
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


@pytest_asyncio.fixture
async def admin_user() -> User:
    """Seed an admin user and return the ORM object."""
    async with TestSessionLocal() as db:
        user = User(
            email="admin@example.com",
            hashed_password=hash_password("Admin1234"),
            role=UserRole.admin,
        )
        db.add(user)
        await db.commit()
        await db.refresh(user)
        return user


@pytest_asyncio.fixture
async def normal_user() -> User:
    """Seed a normal user."""
    async with TestSessionLocal() as db:
        user = User(
            email="user@example.com",
            hashed_password=hash_password("User12345"),
            role=UserRole.user,
            organization_id=1,
        )
        db.add(user)
        await db.commit()
        await db.refresh(user)
        return user


@pytest_asyncio.fixture
async def admin_token(client: AsyncClient, admin_user: User) -> str:
    """Login as admin and return the access token."""
    resp = await client.post("/auth/login", data={
        "username": "admin@example.com",
        "password": "Admin1234",
    })
    assert resp.status_code == 200
    return resp.json()["access_token"]


@pytest_asyncio.fixture
async def user_token(client: AsyncClient, normal_user: User) -> str:
    """Login as normal user and return the access token."""
    resp = await client.post("/auth/login", data={
        "username": "user@example.com",
        "password": "User12345",
    })
    assert resp.status_code == 200
    return resp.json()["access_token"]

