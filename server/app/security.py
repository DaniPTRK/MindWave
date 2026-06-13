"""Password hashing, JWT encode/decode, and auth dependencies."""
from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone
from typing import Annotated

from fastapi import Depends, Header, HTTPException, status
from fastapi.security import OAuth2PasswordBearer
from jose import JWTError, jwt
from passlib.context import CryptContext
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from .config import Settings, get_settings
from .database import get_db
from .models import TokenBlocklist, User, UserRole

# bcrypt for password hashing — slow on purpose (~250 ms / verify).
pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")

# OAuth2 scheme — Swagger UI shows a "Authorize" lock icon wired to /auth/login.
oauth2_scheme = OAuth2PasswordBearer(tokenUrl="/auth/login")


# ---------------------------------------------------------------------------
def hash_password(plain: str) -> str:
    return pwd_context.hash(plain)


def verify_password(plain: str, hashed: str) -> bool:
    return pwd_context.verify(plain, hashed)


# ---------------------------------------------------------------------------
def create_access_token(
    sub: int,
    role: UserRole,
    org_id: int | None,
    settings: Settings | None = None,
) -> tuple[str, int]:
    """Return (jwt_string, expires_in_seconds)."""
    settings = settings or get_settings()
    expire_delta = timedelta(minutes=settings.access_token_expire_minutes)
    payload = {
        "sub": str(sub),
        "role": role.value,
        "org_id": org_id,
        "jti": str(uuid.uuid4()),
        "exp": datetime.now(tz=timezone.utc) + expire_delta,
        "iat": datetime.now(tz=timezone.utc),
        "type": "access",
    }
    token = jwt.encode(payload, settings.secret_key, algorithm=settings.algorithm)
    return token, int(expire_delta.total_seconds())


def create_refresh_token(
    sub: int,
    role: UserRole,
    org_id: int | None,
    settings: Settings | None = None,
) -> tuple[str, int]:
    """Return (refresh_jwt_string, expires_in_seconds). Long-lived (7 days)."""
    settings = settings or get_settings()
    expire_delta = timedelta(days=7)
    payload = {
        "sub": str(sub),
        "role": role.value,
        "org_id": org_id,
        "jti": str(uuid.uuid4()),
        "exp": datetime.now(tz=timezone.utc) + expire_delta,
        "iat": datetime.now(tz=timezone.utc),
        "type": "refresh",
    }
    token = jwt.encode(payload, settings.secret_key, algorithm=settings.algorithm)
    return token, int(expire_delta.total_seconds())


def decode_token(token: str, settings: Settings | None = None) -> dict:
    settings = settings or get_settings()
    try:
        return jwt.decode(token, settings.secret_key, algorithms=[settings.algorithm])
    except JWTError as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=f"Invalid token: {exc}",
            headers={"WWW-Authenticate": "Bearer"},
        )


# ---------------------------------------------------------------------------
async def get_current_user(
    token: Annotated[str, Depends(oauth2_scheme)],
    db: Annotated[AsyncSession, Depends(get_db)],
) -> User:
    payload = decode_token(token)
    user_id = int(payload.get("sub", 0))
    if user_id <= 0:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Bad token subject")

    result = await db.execute(select(User).where(User.id == user_id))
    user = result.scalar_one_or_none()
    if user is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "User no longer exists")
    return user


def require_role(*roles: UserRole):
    """Dependency factory: 403 if the current user's role isn't in roles."""
    allowed = {r.value if isinstance(r, UserRole) else r for r in roles}

    async def _checker(user: Annotated[User, Depends(get_current_user)]) -> User:
        if user.role.value not in allowed:
            raise HTTPException(
                status.HTTP_403_FORBIDDEN,
                f"Requires one of roles: {sorted(allowed)}",
            )
        return user

    return _checker


# ---------------------------------------------------------------------------
# Service-to-service auth for the Flower aggregator → API call.
# Deliberately separate from user JWTs.
# ---------------------------------------------------------------------------
def require_fl_service_token(
    x_fl_service_token: Annotated[str | None, Header()] = None,
    settings: Settings = Depends(get_settings),
) -> None:
    if (
        x_fl_service_token is None
        or x_fl_service_token != settings.fl_service_token
    ):
        raise HTTPException(
            status.HTTP_401_UNAUTHORIZED,
            "Invalid or missing X-FL-Service-Token header",
        )


