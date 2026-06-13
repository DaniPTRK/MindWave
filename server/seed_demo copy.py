"""Seed demo accounts (and a demo organization) for presentations.

Creates the persona accounts attached to a demo org, so the HR dashboard has
something to show and the admin user can log in to the admin panel.

Run it against a running stack:

    # via docker compose (recommended)
    docker compose exec api python -m seed_demo

    # or locally, with the server venv + DATABASE_URL pointing at Postgres
    python -m seed_demo
"""
from __future__ import annotations

import asyncio

from sqlalchemy import select

from app.database import AsyncSessionLocal, Base, engine
from app.models import Organization, User, UserRole
from app.security import hash_password

# (email, password, role)
DEMO_USERS: list[tuple[str, str, UserRole]] = [
    ("adrian@demo.mindwave.app", "Demo1234!", UserRole.user),   # Software Engineer
    ("maria@demo.mindwave.app", "Demo1234!", UserRole.user),    # Master's student
    ("elena@demo.mindwave.app", "Demo1234!", UserRole.hr),      # HR Manager
    ("admin@demo.mindwave.app", "Admin1234!", UserRole.admin),  # Backend admin
]

DEMO_ORG_NAME = "Demo Corp"


async def seed() -> None:
    # Make sure tables exist (no-op if Alembic already created them).
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    async with AsyncSessionLocal() as db:
        # Organization (so the HR account has something to read aggregates for).
        org_result = await db.execute(
            select(Organization).where(Organization.name == DEMO_ORG_NAME)
        )
        org = org_result.scalar_one_or_none()
        if org is None:
            org = Organization(name=DEMO_ORG_NAME)
            db.add(org)
            await db.commit()
            await db.refresh(org)
            print(f"[seed] Created organization: {DEMO_ORG_NAME} (id={org.id})")
        else:
            print(f"[seed] Organization already exists: {DEMO_ORG_NAME} (id={org.id})")

        created = 0
        for email, password, role in DEMO_USERS:
            existing = await db.execute(select(User).where(User.email == email))
            if existing.scalar_one_or_none() is not None:
                print(f"[seed] Skipping existing user: {email}")
                continue
            db.add(User(
                email=email,
                hashed_password=hash_password(password),
                role=role,
                # Attach everyone to the demo org so HR aggregates make sense.
                organization_id=org.id,
            ))
            created += 1
            print(f"[seed] Created {role.value:5s} account: {email}")

        await db.commit()
        print(f"[seed] Done. {created} new account(s) created.")

    await engine.dispose()


if __name__ == "__main__":
    asyncio.run(seed())
