from functools import lru_cache
from typing import Literal

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Application settings loaded from environment variables."""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    app_name: str = "KoPilot API"
    app_version: str = "1.0.0"
    environment: Literal["development", "staging", "production", "test"] = "development"
    debug: bool = False

    # Database
    database_url: str = Field(
        default="sqlite+aiosqlite:///./kopilot_dev.db",
        description="Async SQLAlchemy connection string (PostgreSQL in production).",
    )
    database_echo: bool = False

    # Redis
    redis_url: str = Field(default="redis://localhost:6379/0")
    redis_enabled: bool = False

    # Security & JWT
    jwt_secret_key: str = Field(
        default="dev-only-change-in-production-min-32-chars",
        min_length=32,
    )
    jwt_algorithm: str = "HS256"
    jwt_access_expire_minutes: int = 60
    jwt_refresh_expire_days: int = 30
    allowed_origins: list[str] = Field(default_factory=lambda: ["https://kp.kkdes.co.ke"])

    # Rate limiting
    rate_limit_requests_per_minute: int = 60
    voice_session_rate_limit_per_hour: int = 20

    # External Providers (Server-side only; never exposed to mobile clients)
    openai_api_key: str = ""
    openai_realtime_model: str = "gpt-realtime"
    openai_realtime_voice: str = "marin"
    openai_client_secret_ttl_seconds: int = 600

    google_maps_api_key: str = ""
    open_meteo_base_url: str = "https://api.open-meteo.com/v1"
    weather_cache_ttl_seconds: int = 600
    places_cache_ttl_seconds: int = 300

    # Feature flags / Client config
    min_supported_android_version_code: int = 1
    safety_disclosure_version: str = "2026.1"


@lru_cache
def get_settings() -> Settings:
    return Settings()
