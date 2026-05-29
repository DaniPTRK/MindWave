"""Admin endpoints: org management, role promotion, audit log, account deletion"""
from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field
from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from ..database import get_db
from ..models import (
    AuditLog,
    Organization,
    TokenBlocklist,
    User,
    UserRole,
)
from ..security import (
    create_access_token,
    create_refresh_token,
    decode_token,
    get_current_user,
    require_role,
)

router = APIRouter(tags=["admin"])

# Schemas
class OrgCreate(BaseModel):
    name: str = Field(min_length=1, max_length=120)

class OrgOut(BaseModel):
    id: int
    name: str

    class Config:
        from_attributes = True

class RoleUpdate(BaseModel):
    role: UserRole

class RefreshRequest(BaseModel):
    refresh_token: str

class TokenPairOut(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"
    expires_in: int

class AuditLogOut(BaseModel):
    id: int
    user_id: int | None
    action: str
    resource: str
    detail: str | None
    created_at: str

    class Config:
        from_attributes = True

# Org creation
@router.post("/orgs", response_model=OrgOut, status_code=201)
async def create_organization(
    payload: OrgCreate,
    db: Annotated[AsyncSession, Depends(get_db)],
    _admin: Annotated[User, Depends(require_role(UserRole.admin))],
):
    """Create a new organization (admin only)"""
    existing = await db.execute(
        select(Organization).where(Organization.name == payload.name)
    )
    if existing.scalar_one_or_none():
        raise HTTPException(status.HTTP_409_CONFLICT, "Organization name already exists")
    org = Organization(name=payload.name)
    db.add(org)
    await db.commit()
    await db.refresh(org)
    return org

# Auth refresh
@router.post("/auth/refresh", response_model=TokenPairOut)
async def refresh_token(
    payload: RefreshRequest,
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """Exchange a valid refresh token for a new access + refresh token pair."""
    data = decode_token(payload.refresh_token)
    if data.get("type") != "refresh":
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Not a refresh token")

    # Check blocklist
    jti = data.get("jti", "")
    blocked = await db.execute(
        select(TokenBlocklist).where(TokenBlocklist.jti == jti)
    )
    if blocked.scalar_one_or_none():
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Token has been revoked")

    user_id = int(data["sub"])
    result = await db.execute(select(User).where(User.id == user_id))
    user = result.scalar_one_or_none()
    if user is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "User no longer exists")

    # Revoke old refresh token
    db.add(TokenBlocklist(jti=jti, user_id=user_id))
    await db.commit()

    access, exp = create_access_token(user.id, user.role, user.organization_id)
    refresh, _ = create_refresh_token(user.id, user.role, user.organization_id)
    return TokenPairOut(access_token=access, refresh_token=refresh, expires_in=exp)

# Role promo
@router.patch("/users/{user_id}/role", status_code=200)
async def update_user_role(
    user_id: int,
    payload: RoleUpdate,
    db: Annotated[AsyncSession, Depends(get_db)],
    admin: Annotated[User, Depends(require_role(UserRole.admin))],
):
    """Promote or change a user's role (admin only)"""
    result = await db.execute(select(User).where(User.id == user_id))
    user = result.scalar_one_or_none()
    if user is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "User not found")
    user.role = payload.role
    await db.commit()
    return {"id": user.id, "email": user.email, "role": user.role.value}


# Audit log
@router.get("/audit-log", response_model=list[AuditLogOut])
async def list_audit_log(
    db: Annotated[AsyncSession, Depends(get_db)],
    _admin: Annotated[User, Depends(require_role(UserRole.admin))],
    limit: int = 100,
    offset: int = 0,
):
    """Read audit log"""
    result = await db.execute(
        select(AuditLog)
        .order_by(AuditLog.created_at.desc())
        .limit(limit)
        .offset(offset)
    )
    return result.scalars().all()

# Account deletion
@router.delete("/users/me", status_code=204)
async def delete_own_account(
    db: Annotated[AsyncSession, Depends(get_db)],
    user: Annotated[User, Depends(get_current_user)],
):
    """Delete the current user's account and all associated server-side data"""
    # Audit before del
    db.add(AuditLog(
        user_id=user.id,
        action="account_delete",
        resource=f"users/{user.id}",
        detail="User deleted account",
    ))
    await db.delete(user)
    await db.commit()