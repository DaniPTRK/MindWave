# builder
FROM python:3.11-slim AS builder

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /build
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential libpq-dev gcc && rm -rf /var/lib/apt/lists/*

COPY pyproject.toml .

# Extract dependencies from pyproject.toml into a plain requirements.txt
RUN python -c "import tomllib;d=tomllib.load(open('pyproject.toml','rb'));open('req.txt','w').write('\n'.join(d['project']['dependencies']))" && \
    pip install --upgrade pip && \
    pip wheel --wheel-dir /wheels -r req.txt

# Runtime
FROM python:3.11-slim AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
        libpq5 curl && rm -rf /var/lib/apt/lists/* && \
    useradd --create-home --uid 10001 appuser

COPY --from=builder /wheels /wheels
RUN pip install --no-index --find-links=/wheels /wheels/*.whl && rm -rf /wheels

COPY app ./app
COPY alembic ./alembic
COPY alembic.ini ./alembic.ini
COPY pyproject.toml ./pyproject.toml
COPY seed_demo.py ./seed_demo.py
COPY entrypoint.sh ./entrypoint.sh
COPY models/ /models/

RUN chown -R appuser:appuser /app /models && chmod +x /app/entrypoint.sh
USER appuser

EXPOSE 8000

HEALTHCHECK --interval=15s --timeout=3s --retries=5 \
    CMD curl -fsS http://localhost:8000/health || exit 1

CMD ["./entrypoint.sh"]