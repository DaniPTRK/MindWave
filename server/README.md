# MindWave Server

FastAPI backend with JWT auth, PostgreSQL persistence (k-anonymized aggregates
only), HTTP-based mobile FL submissions, a sibling Flower FL aggregator for
simulation/support workflows, Prometheus metrics, and an append-only audit trail.
Deploys with one Docker Compose command.

---

## Architecture

```
┌──────────────┐ HTTPS+JWT  ┌──────────────────────┐  asyncpg   ┌────────────┐
│  Mobile app  ├───────────►│  FastAPI :8000        ├───────────►│ PostgreSQL │
│  (Android)   │◄───────────┤  /auth /model /stats  │◄───────────┤  :5432     │
└──────┬───────┘            │  /admin /health       │            └────────────┘
       │ Flower gRPC        │  /metrics (Prometheus)│
       │ :9092              └────────┬──────────────┘
       ▼                             │ HTTP + X-FL-Service-Token
┌──────────────┐                     │ /model/round_summary
│   Flower     ├─────────────────────┘
│   :9092      │  FedAvgWithSnapshot (vendored)
└──────────────┘
```

Mobile FL submissions go through FastAPI (`/model/fl/submit-weights`). Flower remains available for simulations and service-side round-summary workflows, but the Android client is not coupled directly to Flower gRPC.

---

## Quickstart

```powershell
# 1. Configure secrets (generate with: openssl rand -hex 32)
Copy-Item server\.env.example server\.env
# Edit: SECRET_KEY, DATABASE_PASSWORD, FL_SERVICE_TOKEN, ADMIN_SEED_EMAIL/PASSWORD

# 2. Place baseline Keras model (needed by Flower)
New-Item -ItemType Directory -Force models
Copy-Item ml\models\mindwave_stress.keras models\

# 3. Build and start everything
docker compose up --build -d

# 4. Verify
curl http://localhost:8000/health
# → {"status":"ok"}

# 5. Create demo accounts
docker compose exec api python -m seed_demo
```

- **Swagger UI:** <http://localhost:8000/docs>
- **Prometheus metrics:** <http://localhost:8000/metrics>
- **Health check:** <http://localhost:8000/health>

---

## Endpoint reference

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| GET | `/health` | — | Liveness probe |
| GET | `/metrics` | — | Prometheus scrape (excluded from Swagger) |
| POST | `/auth/register` | admin JWT | Create user with any role (admin-only) |
| POST | `/auth/register/user` | — | **Public** self-registration (role = `user`) |
| POST | `/auth/login` | OAuth2 form | Returns `access_token` + `refresh_token` |
| GET | `/auth/me` | user JWT | Current identity |
| GET | `/model/latest` | user JWT | Latest global model metadata |
| GET | `/model/{version}/download` | user JWT | Download model bytes |
| POST | `/model/round_summary` | `X-FL-Service-Token` | Flower posts round result |
| POST | `/model/fl/submit-weights` | user JWT | Mobile client uploads updated parameter tensors plus `sample_count` and `base_model_version` |
| POST | `/stats/aggregate` | user JWT | Upload k-anonymized org batch (n ≥ 5) |
| GET | `/stats/organization/{org_id}` | hr/admin JWT | HR dashboard aggregates |
| GET | `/admin/users` | admin JWT | List all users |
| DELETE | `/admin/users/{id}` | admin JWT | Delete user |

> **`/auth/register` vs `/auth/register/user`:** `/auth/register` requires an
> admin JWT and can create accounts with any role (including `hr` / `admin`).
> `/auth/register/user` is the public endpoint used by the mobile app's Register
> screen — always creates a `user`-role account, no auth required.

---

## Database schema

No raw biometric data is stored server-side.

| Table | Purpose |
|-------|---------|
| `organizations` | Named tenants (companies / research groups) |
| `users` | Accounts — role `user` / `hr` / `admin`, optional org FK |
| `model_versions` | Global model snapshot per FL round (path to weights file) |
| `aggregate_stress_stats` | K-anonymized org rollups — `CHECK (n_users >= 5)` at DB level |
| `audit_log` | Append-only log of every `POST/PUT/PATCH/DELETE` |
| `token_blocklist` | Revoked JWTs (account deletion / manual logout) |

The `n_users >= 5` constraint is enforced both by a Pydantic validator in
`schemas.py` **and** a PostgreSQL `CHECK` constraint in `models.py`.

---

## Privacy & ethics

See [`app/ethics.md`](app/ethics.md). Summary:

- **No raw biometrics ever leave the device.** The server sees only account
  metadata, updated model parameter tensors, local sample counts, and k-anonymized rollups (n ≥ 5).
- JWTs carry only `sub` (user id), `role`, and `org_id`.
- All state-changing requests are recorded in `audit_log`.
- Revoked JWTs are tracked in `token_blocklist` (account deletion flow).

---

## How the Android client uses it

1. **Register / Login** — mobile app calls `/auth/register/user` on sign-up,
   then `/auth/login` (OAuth2 password form). Returns `access_token` +
   `refresh_token`.
2. **Persist** — tokens stored in `EncryptedSharedPreferences`.
3. **Model sync** — `GET /model/latest` → compare version; if newer, download
   via `/model/{version}/download`, write to `filesDir`, load with TFLite
   `Interpreter`, call `restore` signature to update weights.
4. **FL round** — after local fine-tuning the `FLTrainingWorker` uploads
   updated parameter tensors to `/model/fl/submit-weights`, together with the
   local sample count and the base model version. Compatible submissions are
   collected for 60 minutes; aggregation runs only if at least three clients
   participate, with one additional 60-minute extension before deferral.
5. **HR dashboard** — users with `hr` role call
   `/stats/organization/{org_id}`.

---

## Layout

```
server/
├── pyproject.toml               # Python 3.11, FastAPI, SQLAlchemy, Flower, TF-CPU
├── .env.example                 # secrets template
├── Dockerfile                   # FastAPI image (multi-stage, non-root)
├── Dockerfile.flower            # Flower aggregator image
├── entrypoint.sh                # Runs Alembic then uvicorn
├── alembic.ini
├── alembic/
│   └── versions/0001_initial.py # Creates all 6 tables
├── app/
│   ├── main.py                  # FastAPI app, CORS, Prometheus, audit middleware
│   ├── config.py                # pydantic-settings (reads .env)
│   ├── database.py              # async SQLAlchemy engine + SessionLocal
│   ├── models.py                # 6 ORM models (no raw biometrics)
│   ├── schemas.py               # Pydantic v2 I/O + k-anon validator
│   ├── security.py              # bcrypt + JWT (access + refresh) + role deps
│   ├── routers/
│   │   ├── health.py
│   │   ├── auth.py              # register / register/user / login / me
│   │   ├── admin.py             # admin user management
│   │   ├── model.py             # model latest / download / round_summary
│   │   └── stats.py             # aggregate / organization/{id}
│   └── ethics.md
├── flower_app/
│   └── __init__.py              # FedAvgWithSnapshot strategy (vendored)
├── flower_runner.py             # Starts Flower, posts round summary back
├── seed_demo.py                 # Creates Adrian/Maria/Elena/Admin demo accounts
└── monitoring/                  # Prometheus / Grafana config (optional)
```

---

## Key dependencies

| Package | Version | Why |
|---------|---------|-----|
| `fastapi` | latest | HTTP framework |
| `uvicorn[standard]` | latest | ASGI server |
| `sqlalchemy[asyncio]` | latest | Async ORM |
| `asyncpg` | latest | PostgreSQL async driver |
| `psycopg2-binary` | latest | Alembic offline mode only |
| `alembic` | latest | Schema migrations |
| `python-jose[cryptography]` | latest | JWT sign / verify |
| `passlib[bcrypt]` | `<4.0` | Password hashing (bcrypt compat constraint) |
| `flwr` | `1.11.*` | Flower Federated Learning |
| `tensorflow-cpu` | `2.15.*` | Weight aggregation on server (CPU) |
| `prometheus-fastapi-instrumentator` | latest | `/metrics` endpoint |
| `pydantic-settings` | latest | `.env` config loading |

> **Python 3.11 only.** Flower + Ray do not support 3.12+ on all platforms.

---

## Notes

- `psycopg2-binary` is only used by Alembic in offline mode. Runtime uses `asyncpg`.
- The Flower image does **not** depend on `ml/` — `FedAvgWithSnapshot` is
  vendored under `server/flower_app/` to keep the image small.
- The `models/` directory at repo root is bind-mounted into both `api` and
  `flower` containers so newly aggregated weights are immediately available via
  `/model/{version}/download`.

