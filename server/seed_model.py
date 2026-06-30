"""Seed an initial ModelVersion row so GET /model/latest and /model/{v}/download work.

Runs against any PostgreSQL reachable via DATABASE_URL_SYNC (psycopg2 DSN).

Usage — local Docker Compose:
    docker compose exec api python seed_model.py

Usage — Railway (from your laptop):
    $env:DATABASE_URL_SYNC = "postgresql://postgres:PASS@HOST.railway.app:PORT/railway"
    python seed_model.py

    or pass the DSN as a CLI argument:
    python seed_model.py "postgresql://postgres:PASS@HOST.railway.app:PORT/railway"
"""

from __future__ import annotations

import os
import sys

try:
    import psycopg2
    import psycopg2.extras
except ImportError:
    print("[seed_model] psycopg2-binary is required.  Run:")
    print("    pip install psycopg2-binary")
    sys.exit(1)

def _get_dsn(argv: list[str]) -> str:
    if len(argv) > 1:
        return argv[1]
    dsn = os.environ.get("DATABASE_URL_SYNC") or os.environ.get("DATABASE_URL", "")
    # Strip SQLAlchemy dialect prefixes so psycopg2 accepts the DSN
    dsn = (
        dsn.replace("postgresql+asyncpg://", "postgresql://")
           .replace("postgresql+psycopg2://", "postgresql://")
    )
    if not dsn:
        print("[seed_model] ERROR: No DATABASE_URL found.\n")
        print("  Option 1 — set env var:")
        print('    $env:DATABASE_URL_SYNC = "postgresql://user:pass@host:port/db"')
        print("\n  Option 2 — pass as CLI arg:")
        print('    python seed_model.py "postgresql://user:pass@host:port/db"')
        sys.exit(1)
    return dsn


def seed(dsn: str) -> None:
    import re
    redacted = re.sub(r"(:)[^:@]+(@)", r"\1***\2", dsn)
    print(f"[seed_model] Connecting to: {redacted}")

    conn = psycopg2.connect(dsn)
    conn.autocommit = False
    cur = conn.cursor(cursor_factory=psycopg2.extras.DictCursor)

    try:
        cur.execute("SELECT COUNT(*) FROM model_versions")
        count = cur.fetchone()[0]

        if count > 0:
            cur.execute(
                "SELECT version, weights_path, notes FROM model_versions ORDER BY version DESC LIMIT 1"
            )
            latest = cur.fetchone()
            # If the existing row points at the old keras file, update it in-place.
            if latest["weights_path"] == "mindwave_stress.keras":
                cur.execute(
                    """
                    UPDATE model_versions
                       SET weights_path = %s,
                           notes        = %s
                     WHERE version = %s
                    """,
                    (
                        "mindwave_stress_trainable.tflite",
                        "Initial seed — trainable TFLite export (5 signatures: infer/train/parameters/restore/explain)",
                        latest["version"],
                    ),
                )
                conn.commit()
                print(f"[seed_model] ✓ Updated model_version v{latest['version']}: "
                      f"mindwave_stress.keras → mindwave_stress_trainable.tflite")
            else:
                print(
                    f"[seed_model] model_versions already has {count} row(s). "
                    f"Latest: v{latest['version']} → {latest['weights_path']}"
                )
                print("[seed_model] Nothing to do — skipping.")
            return

        # Fresh insert — no rows yet.
        cur.execute(
            """
            INSERT INTO model_versions
                (version, num_clients, mean_loss, mean_accuracy, weights_path, notes)
            VALUES (%s, %s, %s, %s, %s, %s)
            """,
            (
                1,
                0,
                None,
                None,
                "mindwave_stress_trainable.tflite",
                "Initial seed — trainable TFLite export (5 signatures: infer/train/parameters/restore/explain)",
            ),
        )
        conn.commit()
        print("[seed_model] ✓ Inserted model_version v1 → mindwave_stress_trainable.tflite")
        print("[seed_model] GET /model/latest and GET /model/1/download are now functional.")

    except Exception as exc:
        conn.rollback()
        print(f"[seed_model] ERROR — rolled back: {exc}")
        raise
    finally:
        cur.close()
        conn.close()


if __name__ == "__main__":
    seed(_get_dsn(sys.argv))
