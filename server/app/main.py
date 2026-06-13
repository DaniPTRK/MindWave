"""FastAPI application entry point."""
from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware

from .config import get_settings
from .database import AsyncSessionLocal, Base, engine
from .models import AuditLog
from .routers import admin, auth, health, model, stats

settings = get_settings()


@asynccontextmanager
async def lifespan(_app: FastAPI):
    # Production deployments rely on Alembic. This block creates tables only
    # if they do not already exist (handy for dev / first boot of compose).
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield
    await engine.dispose()


app = FastAPI(
    title="MindWave API",
    version="0.1.0",
    description=(
        "Backend for the MindWave wearable stress-detection system. "
        "Handles JWT auth, global model distribution, k-anonymized HR "
        "aggregate stats, and the FL round-summary intake."
    ),
    lifespan=lifespan,
)

# CORS — restrict in production to your HR-dashboard origin.
allow = (
    [o.strip() for o in settings.cors_origins.split(",")]
    if settings.cors_origins != "*"
    else ["*"]
)
app.add_middleware(
    CORSMiddleware,
    allow_origins=allow,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(health.router)
app.include_router(auth.router)
app.include_router(admin.router)
app.include_router(model.router)
app.include_router(stats.router)


# ---------------------------------------------------------------------------
# Audit log middleware (Gap 7) — logs mutating requests
# ---------------------------------------------------------------------------
@app.middleware("http")
async def audit_middleware(request: Request, call_next):
    response = await call_next(request)
    # Only audit state-changing methods
    if request.method in ("POST", "PUT", "PATCH", "DELETE"):
        try:
            # Extract user id from the Authorization header if present
            user_id = None
            auth_header = request.headers.get("authorization", "")
            if auth_header.startswith("Bearer "):
                from .security import decode_token
                try:
                    payload = decode_token(auth_header[7:])
                    user_id = int(payload.get("sub", 0)) or None
                except Exception:
                    pass
            async with AsyncSessionLocal() as session:
                session.add(AuditLog(
                    user_id=user_id,
                    action=request.method,
                    resource=str(request.url.path),
                ))
                await session.commit()
        except Exception:
            pass  # Never let audit logging break the request
    return response


