"""User registration, login (JWT), and identity endpoint."""
from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.security import OAuth2PasswordRequestForm
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..database import get_db
from ..models import User, UserRole
from ..schemas import Token, UserCreate, UserOut
from ..security import (
    create_access_token,
    create_refresh_token,
    get_current_user,
    hash_password,
    require_role,
    verify_password,
)


class TokenWithRefresh(Token):
    refresh_token: str

router = APIRouter(prefix="/auth", tags=["auth"])


@router.post("/register", response_model=UserOut, status_code=201)
async def register(
    payload: UserCreate,
    db: Annotated[AsyncSession, Depends(get_db)],
    # Admin-only by default to prevent open self-signup in production.
    _admin: Annotated[User, Depends(require_role(UserRole.admin))],
) -> User:
    existing = await db.execute(select(User).where(User.email == payload.email))
    if existing.scalar_one_or_none() is not None:
        raise HTTPException(status.HTTP_409_CONFLICT, "Email already registered")

    user = User(
        email=payload.email,
        hashed_password=hash_password(payload.password),
        role=payload.role,
        organization_id=payload.organization_id,
    )
    db.add(user)
    await db.commit()
    await db.refresh(user)
    return user


@router.post("/login", response_model=TokenWithRefresh)
async def login(
    form_data: Annotated[OAuth2PasswordRequestForm, Depends()],
    db: Annotated[AsyncSession, Depends(get_db)],
) -> TokenWithRefresh:
    """Standard OAuth2 password grant — returns access + refresh JWT tokens."""
    result = await db.execute(select(User).where(User.email == form_data.username))
    user = result.scalar_one_or_none()
    if user is None or not verify_password(form_data.password, user.hashed_password):
        raise HTTPException(
            status.HTTP_401_UNAUTHORIZED,
            "Incorrect email or password",
            headers={"WWW-Authenticate": "Bearer"},
        )

    token, expires_in = create_access_token(
        sub=user.id, role=user.role, org_id=user.organization_id
    )
    refresh, _ = create_refresh_token(
        sub=user.id, role=user.role, org_id=user.organization_id
    )
    return TokenWithRefresh(
        access_token=token, expires_in=expires_in, refresh_token=refresh
    )


@router.get("/me", response_model=UserOut)
async def me(user: Annotated[User, Depends(get_current_user)]) -> User:
    return user


