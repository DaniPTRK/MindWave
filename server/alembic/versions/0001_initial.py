"""Initial schema: organizations, users, model_versions, aggregate_stress_stats.

Revision ID: 0001_initial
Revises:
Create Date: 2026-05-29 00:00:00
"""
from __future__ import annotations

from alembic import op
import sqlalchemy as sa

revision = "0001_initial"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    user_role = sa.Enum("user", "hr", "admin", name="user_role")
    period_type = sa.Enum("hour", "day", "week", name="period_type")

    op.create_table(
        "organizations",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("name", sa.String(120), nullable=False, unique=True),
        sa.Column("created_at", sa.DateTime(timezone=True),
                  server_default=sa.func.now(), nullable=False),
    )

    op.create_table(
        "users",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("email", sa.String(254), nullable=False),
        sa.Column("hashed_password", sa.String(128), nullable=False),
        sa.Column("role", user_role, nullable=False, server_default="user"),
        sa.Column("organization_id", sa.Integer,
                  sa.ForeignKey("organizations.id", ondelete="SET NULL"),
                  nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True),
                  server_default=sa.func.now(), nullable=False),
        sa.UniqueConstraint("email", name="uq_users_email"),
    )

    op.create_table(
        "model_versions",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("version", sa.Integer, nullable=False, unique=True),
        sa.Column("created_at", sa.DateTime(timezone=True),
                  server_default=sa.func.now(), nullable=False),
        sa.Column("num_clients", sa.Integer, nullable=False, server_default="0"),
        sa.Column("mean_loss", sa.Float, nullable=True),
        sa.Column("mean_accuracy", sa.Float, nullable=True),
        sa.Column("weights_path", sa.String(512), nullable=False),
        sa.Column("notes", sa.String(1024), nullable=True),
    )

    op.create_table(
        "aggregate_stress_stats",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("organization_id", sa.Integer,
                  sa.ForeignKey("organizations.id", ondelete="CASCADE"),
                  nullable=False),
        sa.Column("department_label", sa.String(120), nullable=True),
        sa.Column("period_start", sa.DateTime(timezone=True), nullable=False),
        sa.Column("period_end", sa.DateTime(timezone=True), nullable=False),
        sa.Column("period_type", period_type, nullable=False),
        sa.Column("mean_stress_score", sa.Float, nullable=False),
        sa.Column("std_stress", sa.Float, nullable=False, server_default="0"),
        sa.Column("n_users", sa.Integer, nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True),
                  server_default=sa.func.now(), nullable=False),
        sa.CheckConstraint("n_users >= 5", name="ck_kanon_min_users"),
    )


def downgrade() -> None:
    op.drop_table("aggregate_stress_stats")
    op.drop_table("model_versions")
    op.drop_table("users")
    op.drop_table("organizations")
    sa.Enum(name="period_type").drop(op.get_bind(), checkfirst=True)
    sa.Enum(name="user_role").drop(op.get_bind(), checkfirst=True)