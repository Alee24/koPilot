from fastapi import APIRouter, Depends, HTTPException, Query, Request, status

from app.config import Settings, get_settings
from app.providers import GooglePlacesProvider, ProviderConfigurationError, ProviderUpstreamError
from app.schemas import PlacesSearchResponse
from app.security import RateLimiter, get_rate_limiter

router = APIRouter(prefix="/places", tags=["places"])


@router.get("/search", response_model=PlacesSearchResponse, summary="Search Kenyan places and landmarks")
async def search_places(
    request: Request,
    q: str = Query(min_length=2, max_length=200),
    lat: float | None = Query(default=None, ge=-90.0, le=90.0),
    lng: float | None = Query(default=None, ge=-180.0, le=180.0),
    radius_m: float = Query(default=15000.0, ge=100.0, le=50000.0),
    max_results: int = Query(default=10, ge=1, le=20),
    settings: Settings = Depends(get_settings),
    limiter: RateLimiter = Depends(get_rate_limiter),
) -> PlacesSearchResponse:
    client_ip = request.client.host if request.client else "unknown"
    await limiter.check_limit(
        key=f"places:{client_ip}",
        limit=settings.rate_limit_requests_per_minute,
        window_seconds=60,
    )

    provider = GooglePlacesProvider(settings=settings)
    try:
        return await provider.search_places(
            query=q,
            latitude=lat,
            longitude=lng,
            radius_meters=radius_m,
            max_results=max_results,
        )
    except ProviderConfigurationError as exc:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(exc)) from exc
    except ProviderUpstreamError as exc:
        raise HTTPException(status_code=exc.status_code, detail=str(exc)) from exc
