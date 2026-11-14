from datetime import datetime, timezone
import httpx

from app.config.settings import Settings, get_settings
from app.providers.openai_realtime import ProviderConfigurationError, ProviderUpstreamError
from app.schemas import PlaceItem, PlacesSearchResponse


class GooglePlacesProvider:
    """Searches Kenyan places and landmarks using Google Places API (New)."""

    TEXT_SEARCH_URL = "https://places.googleapis.com/v1/places:searchText"

    def __init__(self, settings: Settings | None = None) -> None:
        self._settings = settings or get_settings()

    async def search_places(
        self,
        query: str,
        latitude: float | None = None,
        longitude: float | None = None,
        radius_meters: float = 15000.0,
        max_results: int = 10,
    ) -> PlacesSearchResponse:
        if not self._settings.google_maps_api_key:
            raise ProviderConfigurationError(
                "Google Maps API key is not configured on the backend. Places search is unavailable."
            )

        body: dict = {
            "textQuery": query,
            "regionCode": "KE",
            "maxResultCount": max(1, min(max_results, 20)),
        }
        if latitude is not None and longitude is not None:
            body["locationBias"] = {
                "circle": {
                    "center": {"latitude": latitude, "longitude": longitude},
                    "radius": radius_meters,
                }
            }

        headers = {
            "X-Goog-Api-Key": self._settings.google_maps_api_key,
            "X-Goog-FieldMask": "places.id,places.displayName,places.formattedAddress,places.location,places.types",
            "Content-Type": "application/json",
        }

        async with httpx.AsyncClient(timeout=15.0) as client:
            response = await client.post(self.TEXT_SEARCH_URL, json=body, headers=headers)

        if response.status_code >= 400:
            raise ProviderUpstreamError(
                f"Google Places API failed with HTTP {response.status_code}",
                status_code=502,
            )

        data = response.json()
        items: list[PlaceItem] = []
        for place in data.get("places", []):
            loc = place.get("location", {})
            items.append(
                PlaceItem(
                    place_id=place.get("id", ""),
                    name=place.get("displayName", {}).get("text", "Unknown Place"),
                    formatted_address=place.get("formattedAddress", ""),
                    latitude=float(loc.get("latitude", 0.0)),
                    longitude=float(loc.get("longitude", 0.0)),
                    types=place.get("types", []),
                )
            )

        return PlacesSearchResponse(
            query=query,
            results=items,
            fetched_at=datetime.now(timezone.utc),
        )
