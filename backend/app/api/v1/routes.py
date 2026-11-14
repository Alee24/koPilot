from fastapi import APIRouter, Depends, HTTPException, Request, status

from app.config import Settings, get_settings
from app.providers import GoogleRoutesProvider, ProviderConfigurationError, ProviderUpstreamError
from app.schemas import RouteComputeRequest, RouteComputeResponse
from app.security import RateLimiter, get_rate_limiter

router = APIRouter(prefix="/routes", tags=["routes"])


@router.post("/compute", response_model=RouteComputeResponse, summary="Compute traffic-aware routes")
async def compute_routes(
    payload: RouteComputeRequest,
    request: Request,
    settings: Settings = Depends(get_settings),
    limiter: RateLimiter = Depends(get_rate_limiter),
) -> RouteComputeResponse:
    client_ip = request.client.host if request.client else "unknown"
    await limiter.check_limit(
        key=f"routes:{client_ip}",
        limit=settings.rate_limit_requests_per_minute,
        window_seconds=60,
    )

    provider = GoogleRoutesProvider(settings=settings)
    try:
        return await provider.compute_routes(payload)
    except ProviderConfigurationError as exc:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(exc)) from exc
    except ProviderUpstreamError as exc:
        raise HTTPException(status_code=exc.status_code, detail=str(exc)) from exc
