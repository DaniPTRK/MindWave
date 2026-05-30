"""Global model distribution + FL round-summary intake"""
from __future__ import annotations

from pathlib import Path
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.responses import FileResponse
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..config import Settings, get_settings
from ..database import get_db
from ..models import ModelVersion, User
from ..schemas import FLRoundSummaryIn, ModelVersionOut
from ..security import get_current_user, require_fl_service_token

router = APIRouter(prefix="/model", tags=["model"])


@router.get("/latest", response_model=ModelVersionOut)
async def latest(
    db: Annotated[AsyncSession, Depends(get_db)],
    _user: Annotated[User, Depends(get_current_user)],
) -> ModelVersion:
    """Get metadata for the latest model version"""
    result = await db.execute(
        select(ModelVersion).order_by(ModelVersion.version.desc()).limit(1)
    )
    mv = result.scalar_one_or_none()
    if mv is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "No model versions yet")
    return mv


@router.get("/{version}/download")
async def download(
    version: int,
    db: Annotated[AsyncSession, Depends(get_db)],
    settings: Annotated[Settings, Depends(get_settings)],
    _user: Annotated[User, Depends(get_current_user)],
):
    """Stream the .tflite (or .keras) file bytes for the given version"""
    result = await db.execute(
        select(ModelVersion).where(ModelVersion.version == version)
    )
    mv = result.scalar_one_or_none()
    if mv is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Unknown version")

    path = Path(mv.weights_path)
    if not path.is_absolute():
        path = Path(settings.models_dir) / path
    if not path.exists():
        raise HTTPException(
            status.HTTP_404_NOT_FOUND,
            f"Weights file missing on disk: {path}",
        )
    return FileResponse(
        path,
        media_type="application/octet-stream",
        filename=path.name,
    )


@router.post(
    "/round_summary",
    response_model=ModelVersionOut,
    status_code=status.HTTP_201_CREATED,
)
async def post_round_summary(
    payload: FLRoundSummaryIn,
    db: Annotated[AsyncSession, Depends(get_db)],
    _service: Annotated[None, Depends(require_fl_service_token)],
) -> ModelVersion:
    """Called by the Flower aggregator at the end of each FL round"""
    last = (
        await db.execute(
            select(ModelVersion.version).order_by(ModelVersion.version.desc()).limit(1)
        )
    ).scalar_one_or_none()
    next_version = (last or 0) + 1

    mv = ModelVersion(
        version=next_version,
        num_clients=payload.num_clients,
        mean_loss=payload.mean_loss,
        mean_accuracy=payload.mean_accuracy,
        weights_path=payload.weights_filename,
        notes=payload.notes,
    )
    db.add(mv)
    await db.commit()
    await db.refresh(mv)
    return mv