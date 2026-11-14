from fastapi import APIRouter, Depends

from app.config import Settings, get_settings
from app.schemas import AppConfigResponse, ProviderStatus

router = APIRouter(prefix="/config", tags=["config"])


@router.get("", response_model=AppConfigResponse, summary="Retrieve client configuration")
async def get_client_config(settings: Settings = Depends(get_settings)) -> AppConfigResponse:
    return AppConfigResponse(
        app_version=settings.app_version,
        min_supported_android_version_code=settings.min_supported_android_version_code,
        safety_disclosure_version=settings.safety_disclosure_version,
        safety_disclaimer=(
            "KoPilot is a driver-assistance companion. Camera-based sign and hazard observations "
            "are advisory only and do not replace road signs, traffic rules, or driver judgement."
        ),
        providers=ProviderStatus(
            openai_realtime_configured=bool(settings.openai_api_key),
            google_maps_configured=bool(settings.google_maps_api_key),
            weather_provider="open-meteo",
        ),
        supported_languages=["en", "sw"],
        openai_realtime_model=settings.openai_realtime_model,
        openai_realtime_voice=settings.openai_realtime_voice,
    )
