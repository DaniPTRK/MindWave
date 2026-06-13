"""ORM models — strictly anonymized data only.

NO raw biometric samples or per-user predictions are stored server-side.
The server only persists:

  * Organization, User             — minimal account info (no biometric data)
  * ModelVersion                   — successive global model snapshots from FL
  * AggregateStressStat            — k-anonymized rollups for HR dashboards

The CHECK constraint ``n_users >= 5`` on AggregateStressStat enforces
k-anonymity at the database level — no individual employee can be
identified by inspecting the table.
"""
from __future__ import annotations

import enum
from datetime import datetime

from sqlalchemy import (
    CheckConstraint,
    DateTime,
    Enum as SAEnum,
    Float,
    ForeignKey,
    Integer,
    String,
    UniqueConstraint,
    func,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .database import Base


# ---------------------------------------------------------------------------
class UserRole(str, enum.Enum):
    user = "user"     # regular employee using the watch
    hr = "hr"         # can read aggregate stats for their organization
    admin = "admin"   # full backend control + model uploads


class PeriodType(str, enum.Enum):
    hour = "hour"
    day = "day"
    week = "week"


# ---------------------------------------------------------------------------
class Organization(Base):
    __tablename__ = "organizations"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    name: Mapped[str] = mapped_column(String(120), unique=True, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )

    users: Mapped[list["User"]] = relationship(back_populates="organization")
    stats: Mapped[list["AggregateStressStat"]] = relationship(
        back_populates="organization"
    )


class User(Base):
    __tablename__ = "users"
    __table_args__ = (UniqueConstraint("email", name="uq_users_email"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    email: Mapped[str] = mapped_column(String(254), nullable=False)
    hashed_password: Mapped[str] = mapped_column(String(128), nullable=False)
    role: Mapped[UserRole] = mapped_column(
        SAEnum(UserRole, name="user_role"), default=UserRole.user, nullable=False
    )
    organization_id: Mapped[int | None] = mapped_column(
        ForeignKey("organizations.id", ondelete="SET NULL"), nullable=True
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )

    organization: Mapped[Organization | None] = relationship(back_populates="users")


# ---------------------------------------------------------------------------
class ModelVersion(Base):
    """One row per global model version produced by the FL aggregator."""

    __tablename__ = "model_versions"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    version: Mapped[int] = mapped_column(Integer, unique=True, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    num_clients: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    mean_loss: Mapped[float | None] = mapped_column(Float, nullable=True)
    mean_accuracy: Mapped[float | None] = mapped_column(Float, nullable=True)
    weights_path: Mapped[str] = mapped_column(String(512), nullable=False)
    notes: Mapped[str | None] = mapped_column(String(1024), nullable=True)


# ---------------------------------------------------------------------------
class AggregateStressStat(Base):
    """K-anonymized rollup of stress scores for an organization slice.

    *No user_id column exists.* The CHECK constraint guarantees that every
    row aggregates over at least K (default 5) users.
    """

    __tablename__ = "aggregate_stress_stats"
    __table_args__ = (
        CheckConstraint("n_users >= 5", name="ck_kanon_min_users"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    organization_id: Mapped[int] = mapped_column(
        ForeignKey("organizations.id", ondelete="CASCADE"), nullable=False
    )
    department_label: Mapped[str | None] = mapped_column(String(120), nullable=True)
    period_start: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    period_end: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    period_type: Mapped[PeriodType] = mapped_column(
        SAEnum(PeriodType, name="period_type"), nullable=False
    )
    mean_stress_score: Mapped[float] = mapped_column(Float, nullable=False)
    std_stress: Mapped[float] = mapped_column(Float, default=0.0, nullable=False)
    n_users: Mapped[int] = mapped_column(Integer, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )

    organization: Mapped[Organization] = relationship(back_populates="stats")


# ---------------------------------------------------------------------------
class AuditLog(Base):
    """Append-only audit trail for GDPR compliance (Gap 7)."""

    __tablename__ = "audit_log"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int | None] = mapped_column(Integer, nullable=True)
    action: Mapped[str] = mapped_column(String(64), nullable=False)
    resource: Mapped[str] = mapped_column(String(256), nullable=False)
    detail: Mapped[str | None] = mapped_column(String(1024), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )


class TokenBlocklist(Base):
    """Revoked JWT tracking for account deletion / logout (Gap 8)."""

    __tablename__ = "token_blocklist"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    jti: Mapped[str] = mapped_column(String(256), unique=True, nullable=False)
    user_id: Mapped[int] = mapped_column(Integer, nullable=False)
    revoked_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
