from datetime import datetime, timezone

from fastapi import APIRouter, Depends
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings, get_settings
from app.models import get_db_session
from app.schemas import HealthResponse

router = APIRouter(tags=["health"])


@router.get("/health", response_model=HealthResponse, summary="Service health check")
async def health_check(
    db: AsyncSession = Depends(get_db_session),
    settings: Settings = Depends(get_settings),
) -> HealthResponse:
    db_status = "ok"
    try:
        await db.execute(text("SELECT 1"))
    except Exception:
        db_status = "unavailable"

    redis_status = "disabled" if not settings.redis_enabled else "configured"

    overall = "ok" if db_status == "ok" else "degraded"
    return HealthResponse(
        status=overall,
        version=settings.app_version,
        environment=settings.environment,
        database=db_status,
        redis=redis_status,
        timestamp=datetime.now(timezone.utc),
    )
