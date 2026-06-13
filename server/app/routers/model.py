"""Global model distribution + FL round-summary intake"""
from __future__ import annotations

import io
import struct
from pathlib import Path
from typing import Annotated

import numpy as np
from fastapi import APIRouter, Depends, HTTPException, UploadFile, File, status
from fastapi.responses import FileResponse
from sqlalchemy import select, func
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


# ---------------------------------------------------------------------------
# Mobile FL weight submission (HTTP-based, bypasses Flower gRPC)
# ---------------------------------------------------------------------------

def _parse_weights_bin(raw: bytes) -> list[np.ndarray]:
    """
    Deserialise the binary format written by FLTrainingWorker.saveWeights():
      [count: int32] ([size: int32] [size × float32]) × count
    """
    buf = io.BytesIO(raw)
    (count,) = struct.unpack(">i", buf.read(4))
    arrays: list[np.ndarray] = []
    for _ in range(count):
        (size,) = struct.unpack(">i", buf.read(4))
        floats = struct.unpack(f">{size}f", buf.read(size * 4))
        arrays.append(np.array(floats, dtype=np.float32))
    return arrays


@router.post("/fl/submit-weights", status_code=status.HTTP_202_ACCEPTED)
async def submit_weights(
    weights_file: Annotated[UploadFile, File(description="fl_weights.bin produced by FLTrainingWorker")],
    db: Annotated[AsyncSession, Depends(get_db)],
    settings: Annotated[Settings, Depends(get_settings)],
    current_user: Annotated[User, Depends(get_current_user)],
) -> dict:
    """
    Accept a client's fine-tuned weight delta, save it to the pending pool,
    then check whether enough clients have submitted to trigger aggregation.

    The pending pool lives at  <models_dir>/pending_weights/user_<id>.bin
    Aggregation fires when  pending_count >= FL_MIN_CLIENTS_FOR_AGGREGATION
    (defaults to 1 so a single-device demo works out of the box).
    """
    raw = await weights_file.read()
    if not raw:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Empty weights file")

    # Validate the binary is parseable before saving
    try:
        _parse_weights_bin(raw)
    except Exception as exc:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY,
                            f"Cannot parse weights binary: {exc}")

    # Save per-user weight file
    pending_dir = Path(settings.models_dir) / "pending_weights"
    pending_dir.mkdir(parents=True, exist_ok=True)
    dest = pending_dir / f"user_{current_user.id}.bin"
    dest.write_bytes(raw)

    # Count how many clients have pending weights
    pending_files = list(pending_dir.glob("user_*.bin"))
    min_clients = getattr(settings, "fl_min_clients_for_aggregation", 1)

    if len(pending_files) < min_clients:
        return {
            "status": "queued",
            "pending": len(pending_files),
            "needed": min_clients,
        }

    # --- Aggregate via FedAvg ---
    all_weight_lists: list[list[np.ndarray]] = []
    for f in pending_files:
        try:
            all_weight_lists.append(_parse_weights_bin(f.read_bytes()))
        except Exception:
            continue  # skip corrupt files

    if not all_weight_lists:
        raise HTTPException(status.HTTP_500_INTERNAL_SERVER_ERROR,
                            "All pending weight files were corrupt")

    # Simple FedAvg: uniform average across clients
    n = len(all_weight_lists)
    aggregated = [
        np.mean(np.stack([wl[i] for wl in all_weight_lists], axis=0), axis=0)
        for i in range(len(all_weight_lists[0]))
    ]

    # Persist aggregated weights as .npz
    last_version = (
        await db.execute(
            select(func.max(ModelVersion.version))
        )
    ).scalar_one_or_none() or 0
    next_version = last_version + 1
    out_filename = f"fl_global_v{next_version}.npz"
    out_path = Path(settings.models_dir) / out_filename
    np.savez(str(out_path), *aggregated)

    # Register new ModelVersion
    mv = ModelVersion(
        version=next_version,
        num_clients=n,
        mean_loss=None,
        mean_accuracy=None,
        weights_path=out_filename,
        notes=f"HTTP FedAvg from {n} client(s)",
    )
    db.add(mv)
    await db.commit()

    # Clean up pending pool
    for f in pending_files:
        f.unlink(missing_ok=True)

    return {
        "status": "aggregated",
        "version": next_version,
        "num_clients": n,
        "weights_file": out_filename,
    }
