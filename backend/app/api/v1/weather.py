from fastapi import APIRouter, Depends, HTTPException, Query, Request

from app.config import Settings, get_settings
from app.providers import OpenMeteoProvider, ProviderUpstreamError
from app.schemas import WeatherResponse
from app.security import RateLimiter, get_rate_limiter

router = APIRouter(prefix="/weather", tags=["weather"])

_weather_provider: OpenMeteoProvider | None = None


def get_weather_provider(settings: Settings = Depends(get_settings)) -> OpenMeteoProvider:
    global _weather_provider
    if _weather_provider is None:
        _weather_provider = OpenMeteoProvider(settings=settings)
    return _weather_provider


@router.get("", response_model=WeatherResponse, summary="Fetch current weather and hourly forecast")
async def get_weather(
    request: Request,
    lat: float = Query(ge=-90.0, le=90.0),
    lng: float = Query(ge=-180.0, le=180.0),
    settings: Settings = Depends(get_settings),
    limiter: RateLimiter = Depends(get_rate_limiter),
    provider: OpenMeteoProvider = Depends(get_weather_provider),
) -> WeatherResponse:
    client_ip = request.client.host if request.client else "unknown"
    await limiter.check_limit(
        key=f"weather:{client_ip}",
        limit=settings.rate_limit_requests_per_minute,
        window_seconds=60,
    )
    try:
        return await provider.get_forecast(latitude=lat, longitude=lng)
    except ProviderUpstreamError as exc:
        raise HTTPException(status_code=exc.status_code, detail=str(exc)) from exc
