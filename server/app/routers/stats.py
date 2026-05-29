"""K-anonymized aggregate stress statistics for HR dashboards"""
from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..database import get_db
from ..models import AggregateStressStat, User, UserRole
from ..schemas import AggregateStatIn, AggregateStatOut
from ..security import get_current_user, require_role

router = APIRouter(prefix="/stats", tags=["stats"])


@router.post(
    "/aggregate",
    response_model=AggregateStatOut,
    status_code=status.HTTP_201_CREATED,
)
async def post_aggregate(
    payload: AggregateStatIn,
    db: Annotated[AsyncSession, Depends(get_db)],
    user: Annotated[User, Depends(get_current_user)],
) -> AggregateStressStat:
    """Submit a k-anonymized rollup."""
    if user.role != UserRole.admin and user.organization_id != payload.organization_id:
        raise HTTPException(
            status.HTTP_403_FORBIDDEN,
            "Cannot post stats for another organization",
        )

    row = AggregateStressStat(**payload.model_dump())
    db.add(row)
    await db.commit()
    await db.refresh(row)
    return row


@router.get(
    "/organization/{org_id}",
    response_model=list[AggregateStatOut],
)
async def list_org_stats(
    org_id: int,
    db: Annotated[AsyncSession, Depends(get_db)],
    user: Annotated[User, Depends(require_role(UserRole.hr, UserRole.admin))],
):
    """Only HR (of that org) and admins can list stats."""
    if user.role != UserRole.admin and user.organization_id != org_id:
        raise HTTPException(
            status.HTTP_403_FORBIDDEN,
            "You can only inspect your own organization",
        )
    result = await db.execute(
        select(AggregateStressStat)
        .where(AggregateStressStat.organization_id == org_id)
        .order_by(AggregateStressStat.period_start.desc())
    )
    return result.scalars().all()