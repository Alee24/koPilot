from datetime import datetime, timezone
import httpx

from app.config.settings import Settings, get_settings
from app.providers.openai_realtime import ProviderConfigurationError, ProviderUpstreamError
from app.schemas import RouteComputeRequest, RouteComputeResponse, RouteOption, RouteStepSummary


def _parse_duration_seconds(duration_str: str | None) -> int:
    if not duration_str:
        return 0
    cleaned = duration_str.rstrip("s")
    try:
        return int(float(cleaned))
    except ValueError:
        return 0


class GoogleRoutesProvider:
    """Computes traffic-aware routes and alternatives using Google Routes API v2."""

    COMPUTE_ROUTES_URL = "https://routes.googleapis.com/directions/v2:computeRoutes"

    def __init__(self, settings: Settings | None = None) -> None:
        self._settings = settings or get_settings()

    async def compute_routes(self, request: RouteComputeRequest) -> RouteComputeResponse:
        if not self._settings.google_maps_api_key:
            raise ProviderConfigurationError(
                "Google Maps API key is not configured on the backend. Cloud routing is unavailable."
            )

        body = {
            "origin": {
                "location": {
                    "latLng": {
                        "latitude": request.origin.latitude,
                        "longitude": request.origin.longitude,
                    }
                }
            },
            "destination": {
                "location": {
                    "latLng": {
                        "latitude": request.destination.latitude,
                        "longitude": request.destination.longitude,
                    }
                }
            },
            "travelMode": "DRIVE",
            "routingPreference": "TRAFFIC_AWARE",
            "computeAlternativeRoutes": request.compute_alternatives,
            "routeModifiers": {
                "avoidTolls": request.avoid_tolls,
                "avoidHighways": request.avoid_highways,
            },
            "languageCode": "en-KE",
            "units": "METRIC",
        }

        headers = {
            "X-Goog-Api-Key": self._settings.google_maps_api_key,
            "X-Goog-FieldMask": (
                "routes.description,routes.distanceMeters,routes.duration,"
                "routes.staticDuration,routes.polyline.encodedPolyline,"
                "routes.legs.steps.navigationInstruction,routes.legs.steps.distanceMeters,"
                "routes.legs.steps.staticDuration"
            ),
            "Content-Type": "application/json",
        }

        async with httpx.AsyncClient(timeout=15.0) as client:
            response = await client.post(self.COMPUTE_ROUTES_URL, json=body, headers=headers)

        if response.status_code >= 400:
            raise ProviderUpstreamError(
                f"Google Routes API failed with HTTP {response.status_code}",
                status_code=502,
            )

        data = response.json()
        raw_routes = data.get("routes", [])
        options: list[RouteOption] = []
        for idx, route in enumerate(raw_routes):
            dur_s = _parse_duration_seconds(route.get("duration"))
            static_s = _parse_duration_seconds(route.get("staticDuration"))
            delay_s = max(0, dur_s - static_s)
            steps: list[RouteStepSummary] = []
            for leg in route.get("legs", []):
                for step in leg.get("steps", []):
                    nav = step.get("navigationInstruction", {})
                    steps.append(
                        RouteStepSummary(
                            instruction=nav.get("instructions", "Continue"),
                            distance_meters=int(step.get("distanceMeters", 0)),
                            duration_seconds=_parse_duration_seconds(step.get("staticDuration")),
                            maneuver=nav.get("maneuver"),
                        )
                    )
            options.append(
                RouteOption(
                    route_id=f"route_{idx}",
                    summary=route.get("description") or f"Route {idx + 1}",
                    distance_meters=int(route.get("distanceMeters", 0)),
                    duration_seconds=dur_s,
                    static_duration_seconds=static_s,
                    traffic_delay_seconds=delay_s,
                    encoded_polyline=route.get("polyline", {}).get("encodedPolyline", ""),
                    steps=steps,
                )
            )

        return RouteComputeResponse(
            routes=options,
            fetched_at=datetime.now(timezone.utc),
        )
