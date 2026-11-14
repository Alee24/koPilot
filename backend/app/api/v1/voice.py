from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings, get_settings
from app.models import get_db_session
from app.providers import OpenAIRealtimeProvider, ProviderConfigurationError, ProviderUpstreamError
from app.schemas import VoiceSessionRequest, VoiceSessionResponse
from app.security import RateLimiter, get_rate_limiter
from app.services import record_usage

router = APIRouter(prefix="/voice", tags=["voice"])


@router.post("/session", response_model=VoiceSessionResponse, summary="Issue short-lived OpenAI Realtime client secret")
async def create_voice_session(
    payload: VoiceSessionRequest,
    request: Request,
    db: AsyncSession = Depends(get_db_session),
    settings: Settings = Depends(get_settings),
    limiter: RateLimiter = Depends(get_rate_limiter),
) -> VoiceSessionResponse:
    client_ip = request.client.host if request.client else "unknown"
    await limiter.check_limit(
        key=f"voice:{payload.device_id}:{client_ip}",
        limit=settings.voice_session_rate_limit_per_hour,
        window_seconds=3600,
    )

    provider = OpenAIRealtimeProvider(settings=settings)
    try:
        result = await provider.create_client_secret(language=payload.language, voice=payload.voice)
    except ProviderConfigurationError as exc:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(exc)) from exc
    except ProviderUpstreamError as exc:
        raise HTTPException(status_code=exc.status_code, detail=str(exc)) from exc

    await record_usage(db=db, device_id=payload.device_id, resource_type="voice_session", units=1)
    return VoiceSessionResponse(
        client_secret=result.client_secret,
        expires_at=result.expires_at,
        model=result.model,
        voice=result.voice,
    )
