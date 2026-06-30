"""Global model distribution + FL round-summary intake"""
from __future__ import annotations

import io
import json
import struct
from datetime import datetime, timezone
from pathlib import Path
from typing import Annotated

import numpy as np
from fastapi import APIRouter, Depends, HTTPException, UploadFile, File, Form, status
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

# Mobile FL weight submission (HTTP-based, bypasses Flower gRPC)
def _parse_weights_bin(raw: bytes) -> list[np.ndarray]:
    """
    Deserialise the binary format written by FLTrainingWorker.saveWeights():
      [count: int32] ([size: int32] [size x float32]) x count
    """
    buf = io.BytesIO(raw)
    header = buf.read(4)
    if len(header) != 4:
        raise ValueError("Missing tensor count")
    (count,) = struct.unpack(">i", header)
    if count <= 0 or count > 100:
        raise ValueError(f"Invalid tensor count: {count}")

    arrays: list[np.ndarray] = []
    for i in range(count):
        size_raw = buf.read(4)
        if len(size_raw) != 4:
            raise ValueError(f"Missing size for tensor {i}")
        (size,) = struct.unpack(">i", size_raw)
        if size <= 0 or size > 10_000_000:
            raise ValueError(f"Invalid tensor {i} size: {size}")
        data = buf.read(size * 4)
        if len(data) != size * 4:
            raise ValueError(f"Tensor {i} is truncated")
        floats = struct.unpack(f">{size}f", data)
        arrays.append(np.array(floats, dtype=np.float32))
    return arrays


def _round_root(settings: Settings) -> Path:
    root = Path(settings.models_dir) / "pending_rounds"
    root.mkdir(parents=True, exist_ok=True)
    return root


def _round_dir(settings: Settings, base_version: int) -> Path:
    path = _round_root(settings) / f"base_{base_version}"
    path.mkdir(parents=True, exist_ok=True)
    return path


def _round_meta_path(settings: Settings, base_version: int) -> Path:
    return _round_dir(settings, base_version) / "round.json"


def _submissions_path(settings: Settings, base_version: int) -> Path:
    return _round_dir(settings, base_version) / "submissions.json"


def _now_ts() -> float:
    return datetime.now(timezone.utc).timestamp()


def _load_json(path: Path, default):
    if not path.exists():
        return default
    return json.loads(path.read_text(encoding="utf-8"))


def _write_json(path: Path, payload) -> None:
    path.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")


def _create_round(settings: Settings, base_version: int) -> dict:
    now = _now_ts()
    interval_s = max(settings.fl_round_interval_minutes, 1) * 60
    meta = {
        "round_id": f"base{base_version}_{int(now)}",
        "base_model_version": base_version,
        "opened_at": now,
        "deadline_at": now + interval_s,
        "extended_count": 0,
        "status": "open",
    }
    _write_json(_round_meta_path(settings, base_version), meta)
    _write_json(_submissions_path(settings, base_version), {})
    return meta


def _active_round(settings: Settings, base_version: int) -> dict:
    meta = _load_json(_round_meta_path(settings, base_version), None)
    if meta is None or meta.get("status") in {"aggregated", "deferred"}:
        return _create_round(settings, base_version)
    return meta


def _aggregate_weight_lists(weighted_lists: list[tuple[list[np.ndarray], int]]) -> list[np.ndarray]:
    total_examples = sum(count for _, count in weighted_lists)
    if total_examples <= 0:
        raise ValueError("Total sample count must be positive")

    reference = weighted_lists[0][0]
    aggregated: list[np.ndarray] = []
    for tensor_idx in range(len(reference)):
        weighted_sum = np.zeros_like(reference[tensor_idx], dtype=np.float64)
        for weights, count in weighted_lists:
            if len(weights) != len(reference):
                raise ValueError("Client tensor count mismatch")
            if weights[tensor_idx].shape != reference[tensor_idx].shape:
                raise ValueError(f"Client tensor {tensor_idx} shape mismatch")
            weighted_sum += weights[tensor_idx].astype(np.float64) * count
        aggregated.append((weighted_sum / total_examples).astype(np.float32))
    return aggregated


async def _try_close_round(
    *,
    meta: dict,
    submissions: dict,
    db: AsyncSession,
    settings: Settings,
) -> dict | None:
    now = _now_ts()
    if now < float(meta["deadline_at"]):
        return None

    min_clients = settings.fl_min_clients_for_aggregation
    base_version = int(meta["base_model_version"])
    if len(submissions) < min_clients:
        if int(meta.get("extended_count", 0)) < settings.fl_round_max_extensions:
            meta["extended_count"] = int(meta.get("extended_count", 0)) + 1
            meta["deadline_at"] = now + max(settings.fl_round_interval_minutes, 1) * 60
            meta["status"] = "extended"
            _write_json(_round_meta_path(settings, base_version), meta)
            return {
                "status": "extended",
                "round_id": meta["round_id"],
                "pending": len(submissions),
                "needed": min_clients,
                "deadline_at": meta["deadline_at"],
            }
        meta["status"] = "deferred"
        meta["closed_at"] = now
        _write_json(_round_meta_path(settings, base_version), meta)
        return {
            "status": "deferred",
            "round_id": meta["round_id"],
            "pending": len(submissions),
            "needed": min_clients,
        }

    weighted_lists: list[tuple[list[np.ndarray], int]] = []
    for info in submissions.values():
        raw = Path(info["path"]).read_bytes()
        weighted_lists.append((_parse_weights_bin(raw), int(info["sample_count"])))
    aggregated = _aggregate_weight_lists(weighted_lists)

    last_version = (
        await db.execute(select(func.max(ModelVersion.version)))
    ).scalar_one_or_none() or 0
    next_version = last_version + 1
    out_filename = f"fl_global_v{next_version}.npz"
    out_path = Path(settings.models_dir) / out_filename
    out_path.parent.mkdir(parents=True, exist_ok=True)
    np.savez(str(out_path), *aggregated)

    total_examples = sum(int(info["sample_count"]) for info in submissions.values())
    mv = ModelVersion(
        version=next_version,
        num_clients=len(submissions),
        mean_loss=None,
        mean_accuracy=None,
        weights_path=out_filename,
        notes=(
            f"HTTP sample-weighted FedAvg from {len(submissions)} client(s), "
            f"base model v{base_version}, {total_examples} local examples"
        ),
    )
    db.add(mv)
    await db.commit()

    meta["status"] = "aggregated"
    meta["closed_at"] = now
    meta["published_model_version"] = next_version
    _write_json(_round_meta_path(settings, base_version), meta)

    return {
        "status": "aggregated",
        "round_id": meta["round_id"],
        "version": next_version,
        "num_clients": len(submissions),
        "total_examples": total_examples,
        "weights_file": out_filename,
    }


@router.post("/fl/submit-weights", status_code=status.HTTP_202_ACCEPTED)
async def submit_weights(
    weights_file: Annotated[UploadFile, File(description="fl_weights.bin produced by FLTrainingWorker")],
    db: Annotated[AsyncSession, Depends(get_db)],
    settings: Annotated[Settings, Depends(get_settings)],
    current_user: Annotated[User, Depends(get_current_user)],
    sample_count: Annotated[int, Form(ge=1)] = 1,
    base_model_version: Annotated[int | None, Form()] = None,
) -> dict:
    """
    Accept a client's fine-tuned parameter tensors and store them in the active
    time-bounded FL round for the submitted base model version.
    """
    raw = await weights_file.read()
    if not raw:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Empty weights file")

    try:
        _parse_weights_bin(raw)
    except Exception as exc:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY,
                            f"Cannot parse weights binary: {exc}")

    latest_version = (
        await db.execute(select(func.max(ModelVersion.version)))
    ).scalar_one_or_none() or 0
    base_version = latest_version if base_model_version is None else base_model_version
    if base_version != latest_version:
        raise HTTPException(
            status.HTTP_409_CONFLICT,
            f"Stale base model version {base_version}; latest is {latest_version}",
        )

    meta = _active_round(settings, base_version)
    submissions = _load_json(_submissions_path(settings, base_version), {})

    close_result = await _try_close_round(meta=meta, submissions=submissions, db=db, settings=settings)
    if close_result and close_result["status"] == "aggregated":
        raise HTTPException(
            status.HTTP_409_CONFLICT,
            "The submitted update targets a round that has already published a new global model",
        )
    if close_result and close_result["status"] == "deferred":
        meta = _create_round(settings, base_version)
        submissions = {}
    elif close_result and close_result["status"] == "extended":
        meta = _active_round(settings, base_version)

    round_dir = _round_dir(settings, base_version) / meta["round_id"]
    round_dir.mkdir(parents=True, exist_ok=True)
    dest = round_dir / f"user_{current_user.id}.bin"
    dest.write_bytes(raw)

    submissions[str(current_user.id)] = {
        "path": str(dest),
        "sample_count": sample_count,
        "submitted_at": _now_ts(),
        "user_id": current_user.id,
    }
    _write_json(_submissions_path(settings, base_version), submissions)

    close_result = await _try_close_round(meta=meta, submissions=submissions, db=db, settings=settings)
    if close_result:
        return close_result

    return {
        "status": "queued",
        "round_id": meta["round_id"],
        "base_model_version": base_version,
        "pending": len(submissions),
        "needed": settings.fl_min_clients_for_aggregation,
        "deadline_at": meta["deadline_at"],
    }


