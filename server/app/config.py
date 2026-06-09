"""Application configuration loaded from environment variables."""
from __future__ import annotations

from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """All env-driven configuration in one place.
    
    Required settings:
    - DATABASE_URL, DATABASE_URL_SYNC, SECRET_KEY, FL_SERVICE_TOKEN
    
    Optional settings:
    - All others (can be overridden via env vars if needed)
    """

    # db
    database_url: str = Field(..., alias="DATABASE_URL")
    database_url_sync: str = Field(..., alias="DATABASE_URL_SYNC")

    # jwt auth
    secret_key: str = Field(..., alias="SECRET_KEY")
    algorithm: str = Field(default="HS256")
    access_token_expire_minutes: int = Field(
        default=60, alias="ACCESS_TOKEN_EXPIRE_MINUTES"
    )

    # federated learning
    flower_server_address: str = Field(default="0.0.0.0:9092", alias="FLOWER_SERVER_ADDRESS")
    fl_service_token: str = Field(..., alias="FL_SERVICE_TOKEN")
    fl_num_rounds: int = Field(default=3, alias="FL_NUM_ROUNDS")
    api_base_url: str = Field(default="http://api:8000", alias="API_BASE_URL")
    
    # Minimum clients needed before HTTP-based FedAvg fires (1 = single-device demo)
    fl_min_clients_for_aggregation: int = Field(default=1, alias="FL_MIN_CLIENTS_FOR_AGGREGATION")

    # storage
    models_dir: str = Field(default="/models")

    # privacy
    k_anon_threshold: int = Field(default=5, alias="K_ANON_THRESHOLD")

    # cors
    cors_origins: str = Field(default="*")

    # bootstrap
    admin_seed_email: str = Field(default="", alias="ADMIN_SEED_EMAIL")
    admin_seed_password: str = Field(default="", alias="ADMIN_SEED_PASSWORD")

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        validate_default=True,
        extra="ignore",
    )


@lru_cache
def get_settings() -> Settings:
    """Cache the Settings instance for the lifetime of the process."""
    return Settings()