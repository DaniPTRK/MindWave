"""Pydantic v2 request/response schemas."""
from __future__ import annotations

from datetime import datetime
from typing import Optional

from pydantic import BaseModel, ConfigDict, EmailStr, Field, field_validator

from .config import get_settings
from .models import PeriodType, UserRole

K_ANON = get_settings().k_anon_threshold

# Auth
class UserCreate(BaseModel):
    email: EmailStr
    password: str = Field(min_length=8)
    role: UserRole = UserRole.user
    organization_id: Optional[int] = None

    @field_validator("password")
    @classmethod
    def password_strength(cls, v: str) -> str:
        if len(v) < 8:
            raise ValueError("Password must be at least 8 characters long.")
        if not any(c.isalpha() for c in v):
            raise ValueError("Password must contain at least one letter.")
        if not any(c.isdigit() for c in v):
            raise ValueError("Password must contain at least one number.")
        return v

# Auth response models
class UserOut(BaseModel):
    id: int
    email: EmailStr
    role: UserRole
    organization_id: Optional[int]
    created_at: datetime

    model_config = ConfigDict(from_attributes=True)


class Token(BaseModel):
    access_token: str
    token_type: str = "bearer"
    expires_in: int  # seconds


class TokenData(BaseModel):
    sub: int
    role: UserRole
    org_id: Optional[int] = None


# Model versioning for federated learning
class ModelVersionOut(BaseModel):
    id: int
    version: int
    created_at: datetime
    num_clients: int
    mean_loss: Optional[float]
    mean_accuracy: Optional[float]
    weights_path: str
    notes: Optional[str]

    model_config = ConfigDict(from_attributes=True)


class FLRoundSummaryIn(BaseModel):
    """Posted by the Flower aggregator at the end of each round."""

    round: int = Field(ge=1)
    num_clients: int = Field(ge=1)
    mean_loss: Optional[float] = None
    mean_accuracy: Optional[float] = None
    weights_filename: str
    notes: Optional[str] = None

# K-anonymized aggregate stats for HR dashboard
class AggregateStatIn(BaseModel):
    organization_id: int
    department_label: Optional[str] = None
    period_start: datetime
    period_end: datetime
    period_type: PeriodType
    mean_stress_score: float = Field(ge=0.0, le=1.0)
    std_stress: float = Field(ge=0.0)
    n_users: int

    @field_validator("n_users")
    @classmethod
    def _enforce_k_anon(cls, v: int) -> int:
        if v < K_ANON:
            raise ValueError(
                f"k-anonymity violation: n_users must be >= {K_ANON}"
            )
        return v


class AggregateStatOut(AggregateStatIn):
    id: int
    created_at: datetime

    model_config = ConfigDict(from_attributes=True)