"""FastAPI application entry point."""
from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from prometheus_fastapi_instrumentator import Instrumentator
from sqlalchemy import select

from .config import get_settings
from .database import AsyncSessionLocal, Base, engine
from .models import AuditLog, User, UserRole
from .routers import admin, auth, health, model, stats
from .security import hash_password

settings = get_settings()

@asynccontextmanager
async def lifespan(_app: FastAPI):
    # Production deployments rely on Alembic. We create tables if they dont exist.
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    # Seed the first admin account if ADMIN_SEED_EMAIL / ADMIN_SEED_PASSWORD
    # are set in the environment.
    if settings.admin_seed_email and settings.admin_seed_password:
        async with AsyncSessionLocal() as db:
            result = await db.execute(
                select(User).where(User.email == settings.admin_seed_email)
            )
            if result.scalar_one_or_none() is None:
                db.add(User(
                    email=settings.admin_seed_email,
                    hashed_password=hash_password(settings.admin_seed_password),
                    role=UserRole.admin,
                ))
                await db.commit()
                print(f"[seed] Admin account created: {settings.admin_seed_email}")

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

# CORS config
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

# Error handler
@app.exception_handler(RequestValidationError)
async def validation_exception_handler(
    _request: Request, exc: RequestValidationError
) -> JSONResponse:
    """Convert Pydantic validation errors into a flat, readable list."""
    messages = []
    for err in exc.errors():
        loc = " → ".join(str(p) for p in err["loc"] if p != "body")
        msg = err["msg"]
        messages.append(f"{loc}: {msg}" if loc else msg)
    return JSONResponse(
        status_code=422,
        content={"detail": messages},
    )


app.include_router(health.router)
app.include_router(auth.router)
app.include_router(admin.router)
app.include_router(model.router)
app.include_router(stats.router)

# Expose Prometheus metrics at /metrics, with some basic defaults.
Instrumentator(
    should_group_status_codes=True,
    excluded_handlers=["/metrics", "/health"],
).instrument(app).expose(app, include_in_schema=False, tags=["monitoring"])

# Audit logs for state-changing requests
@app.middleware("http")
async def audit_middleware(request: Request, call_next):
    response = await call_next(request)
    if request.method in ("POST", "PUT", "PATCH", "DELETE"):
        try:
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
            pass
    return response