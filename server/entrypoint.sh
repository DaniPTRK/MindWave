#!/bin/sh
# Container entrypoint.
#   Default  : wait for postgres -> migrate -> serve
#   --migrate-only : wait for postgres -> migrate -> exit todo: for k8s
set -e

: "${DATABASE_URL_SYNC:?DATABASE_URL_SYNC must be set}"

echo "[entrypoint] Waiting for PostgreSQL to be reachable…"
python3 - <<'PYEOF'
import os, re, sys, time
import psycopg2

raw = os.environ["DATABASE_URL_SYNC"]
# psycopg2 does not understand SQLAlchemy's "dialect+driver://" prefix.
# Strip it: postgresql+psycopg2://... -> postgresql://...
url = re.sub(r'^([a-z]+)\+\w+://', r'\1://', raw)

for i in range(30):
    try:
        psycopg2.connect(url).close()
        print(f"[entrypoint] PostgreSQL ready (attempt {i + 1})")
        sys.exit(0)
    except psycopg2.OperationalError as exc:
        print(f"[entrypoint] attempt {i + 1}/30: {str(exc)[:120]}")
        time.sleep(2)

print("[entrypoint] PostgreSQL did not become available within 60 s", file=sys.stderr)
sys.exit(1)
PYEOF

echo "[entrypoint] Running: alembic upgrade head"
alembic upgrade head

if [ "${1}" = "--migrate-only" ]; then
    echo "[entrypoint] Migration complete — exiting (migrate-only mode)."
    exit 0
fi

echo "[entrypoint] Starting uvicorn…"
exec uvicorn app.main:app --host 0.0.0.0 --port 8000
