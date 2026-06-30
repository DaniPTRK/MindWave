"""Admin endpoints: org management, role promotion, audit log, account deletion"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field
from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from ..database import get_db
from ..models import (
    AggregateStressStat,
    AuditLog,
    ModelVersion,
    Organization,
    PeriodType,
    TokenBlocklist,
    User,
    UserRole,
)
from ..security import (
    create_access_token,
    create_refresh_token,
    decode_token,
    get_current_user,
    hash_password,
    require_fl_service_token,
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
    request: Request,
    db: Annotated[AsyncSession, Depends(get_db)],
    user: Annotated[User, Depends(get_current_user)],
):
    """Delete the current user's account and all associated server-side data.
    Also blocklists the current access token so it cannot be reused."""
    # Blocklist the current access token
    auth_header = request.headers.get("authorization", "")
    if auth_header.startswith("Bearer "):
        try:
            payload = decode_token(auth_header[7:])
            jti = payload.get("jti", "")
            if jti:
                db.add(TokenBlocklist(jti=jti, user_id=user.id))
        except Exception:
            pass

    # Audit before delete
    db.add(AuditLog(
        user_id=user.id,
        action="account_delete",
        resource=f"users/{user.id}",
        detail="User deleted account",
    ))
    await db.delete(user)
    await db.commit()


# ── Demo seed endpoint ─────────────────────────────────────────────────────────

class SeedDemoResponse(BaseModel):
    org_id: int
    users_created: int
    stats_inserted: int
    model_versions_inserted: int


@router.post(
    "/seed-demo",
    response_model=SeedDemoResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Seed demo org, users, aggregate stats and model versions",
    description=(
        "Idempotent endpoint that inserts demo data. "
        "Protected by X-FL-Service-Token so it can be called from CI/scripts "
        "without a user JWT. Safe to call multiple times."
    ),
)
async def seed_demo(
    db: Annotated[AsyncSession, Depends(get_db)],
    _service: Annotated[None, Depends(require_fl_service_token)],
) -> SeedDemoResponse:
    now_utc = datetime.now(tz=timezone.utc)

    # ── 1. Organization ────────────────────────────────────────────────────────
    org_name = "Demo Corp"
    org_result = await db.execute(
        select(Organization).where(Organization.name == org_name)
    )
    org = org_result.scalar_one_or_none()
    if org is None:
        org = Organization(name=org_name)
        db.add(org)
        await db.flush()  # get org.id without full commit
    org_id: int = org.id  # type: ignore[assignment]

    # ── 2. Demo users ──────────────────────────────────────────────────────────
    DEMO_USERS: list[tuple[str, str, UserRole]] = [
        ("adrian@demo.mindwave.app", "Demo1234!", UserRole.user),
        ("maria@demo.mindwave.app",  "Demo1234!", UserRole.user),
        ("elena@demo.mindwave.app",  "Demo1234!", UserRole.hr),
        ("admin@demo.mindwave.app",  "Admin1234!", UserRole.admin),
    ]
    users_created = 0
    for email, password, role in DEMO_USERS:
        existing = (await db.execute(select(User).where(User.email == email))).scalar_one_or_none()
        if existing is None:
            db.add(User(
                email=email,
                hashed_password=hash_password(password),
                role=role,
                organization_id=org_id,
            ))
            users_created += 1

    # ── 3. Aggregate stress stats (30 days × 4 time slots × 3 departments) ────
    departments: list[tuple[str, float, float]] = [
        ("Engineering",   0.05,  0.08),
        ("HR",           -0.05,  0.06),
        ("Student Group", 0.15,  0.12),
    ]
    slot_hours = [9, 12, 15, 18]
    WEEKDAY_FACTORS = [0.10, 0.07, 0.03, -0.02, -0.06, -0.12, -0.10]
    SLOT_FACTORS    = {9: 0.00, 12: 0.04, 15: 0.06, 18: -0.02}

    stats_inserted = 0
    for day_offset in range(30):
        day = now_utc - timedelta(days=29 - day_offset)
        for slot_h in slot_hours:
            period_start = day.replace(hour=slot_h, minute=0, second=0, microsecond=0)
            period_end   = period_start + timedelta(hours=3)
            for dept_label, mean_shift, std_base in departments:
                # Check idempotency
                exists = (await db.execute(
                    select(AggregateStressStat).where(
                        AggregateStressStat.organization_id == org_id,
                        AggregateStressStat.department_label == dept_label,
                        AggregateStressStat.period_start == period_start,
                        AggregateStressStat.period_end   == period_end,
                    )
                )).scalar_one_or_none()
                if exists:
                    continue

                weekday_factor = WEEKDAY_FACTORS[period_start.weekday()]
                slot_factor    = SLOT_FACTORS.get(slot_h, 0.0)
                mean_score = max(0.10, min(0.95,
                    0.45 + mean_shift + weekday_factor + slot_factor
                    + (hash(f"{day_offset}{slot_h}{dept_label}") % 11 - 5) * 0.01
                ))
                std_score = max(0.01,
                    std_base + (hash(f"{slot_h}{dept_label}{day_offset}") % 5 - 2) * 0.01
                )
                n_users = 6 + (hash(f"{dept_label}{day_offset}") % 4)  # 6–9, always ≥ 5

                db.add(AggregateStressStat(
                    organization_id=org_id,
                    department_label=dept_label,
                    period_start=period_start,
                    period_end=period_end,
                    period_type=PeriodType.hour,
                    mean_stress_score=round(mean_score, 4),
                    std_stress=round(std_score, 4),
                    n_users=n_users,
                ))
                stats_inserted += 1

    # ── 4. Model versions (5 FL rounds) ───────────────────────────────────────
    FL_ROUNDS = [
        dict(version=1, num_clients=3, mean_loss=0.4821, mean_accuracy=0.7630,
             weights_path="fl_global_v1.npz", notes="Initial FL round — baseline"),
        dict(version=2, num_clients=5, mean_loss=0.3954, mean_accuracy=0.8012,
             weights_path="fl_global_v2.npz", notes="Round 2 — 5 clients contributed"),
        dict(version=3, num_clients=7, mean_loss=0.3102, mean_accuracy=0.8334,
             weights_path="fl_global_v3.npz", notes="Round 3 — convergence improving"),
        dict(version=4, num_clients=7, mean_loss=0.2765, mean_accuracy=0.8521,
             weights_path="fl_global_v4.npz", notes="Round 4 — loss plateau"),
        dict(version=5, num_clients=8, mean_loss=0.2614, mean_accuracy=0.8703,
             weights_path="fl_global_v5.npz", notes="Round 5 — production candidate"),
    ]
    mv_inserted = 0
    for i, mv in enumerate(FL_ROUNDS):
        exists = (await db.execute(
            select(ModelVersion).where(ModelVersion.version == mv["version"])
        )).scalar_one_or_none()
        if exists:
            continue
        created_at = now_utc - timedelta(days=4 - i, hours=2)
        db.add(ModelVersion(
            version=mv["version"],
            num_clients=mv["num_clients"],
            mean_loss=mv["mean_loss"],
            mean_accuracy=mv["mean_accuracy"],
            weights_path=mv["weights_path"],
            notes=mv["notes"],
            created_at=created_at,
        ))
        mv_inserted += 1

    await db.commit()
    return SeedDemoResponse(
        org_id=org_id,
        users_created=users_created,
        stats_inserted=stats_inserted,
        model_versions_inserted=mv_inserted,
    )

