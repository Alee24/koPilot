from datetime import datetime, timezone
import time
import httpx

from app.config.settings import Settings, get_settings
from app.providers.openai_realtime import ProviderUpstreamError
from app.schemas import WeatherResponse

_WMO_CODE_DESCRIPTIONS = {
    0: "Clear sky",
    1: "Mainly clear",
    2: "Partly cloudy",
    3: "Overcast",
    45: "Fog",
    48: "Depositing rime fog",
    51: "Light drizzle",
    53: "Moderate drizzle",
    55: "Dense drizzle",
    61: "Slight rain",
    63: "Moderate rain",
    65: "Heavy rain",
    80: "Slight rain showers",
    81: "Moderate rain showers",
    82: "Violent rain showers",
    95: "Thunderstorm",
}


class OpenMeteoProvider:
    """Weather forecast provider backed by Open-Meteo with TTL caching."""

    def __init__(self, settings: Settings | None = None) -> None:
        self._settings = settings or get_settings()
        self._cache: dict[tuple[float, float], tuple[float, WeatherResponse]] = {}

    async def get_forecast(self, latitude: float, longitude: float) -> WeatherResponse:
        cache_key = (round(latitude, 2), round(longitude, 2))
        now = time.time()
        cached_entry = self._cache.get(cache_key)
        if cached_entry and (now - cached_entry[0]) < self._settings.weather_cache_ttl_seconds:
            return cached_entry[1].model_copy(update={"cached": True})

        params = {
            "latitude": latitude,
            "longitude": longitude,
            "current": "temperature_2m,precipitation,weather_code,wind_speed_10m",
            "hourly": "temperature_2m,precipitation_probability,precipitation,weather_code,wind_speed_10m,visibility",
            "forecast_hours": 12,
            "timezone": "Africa/Nairobi",
        }
        url = f"{self._settings.open_meteo_base_url.rstrip('/')}/forecast"

        async with httpx.AsyncClient(timeout=12.0) as client:
            response = await client.get(url, params=params)

        if response.status_code >= 400:
            raise ProviderUpstreamError(
                f"Open-Meteo API failed with HTTP {response.status_code}",
                status_code=502,
            )

        data = response.json()
        current = data.get("current", {})
        hourly = data.get("hourly", {})

        times = hourly.get("time", [])
        temps = hourly.get("temperature_2m", [])
        rain_probs = hourly.get("precipitation_probability", [])
        precips = hourly.get("precipitation", [])
        winds = hourly.get("wind_speed_10m", [])
        visibilities = hourly.get("visibility", [])

        hourly_list = []
        for idx, t in enumerate(times[:12]):
            hourly_list.append(
                {
                    "time": t,
                    "temperature_c": temps[idx] if idx < len(temps) else None,
                    "rain_probability_percent": rain_probs[idx] if idx < len(rain_probs) else None,
                    "precipitation_mm": precips[idx] if idx < len(precips) else None,
                    "wind_speed_kmh": winds[idx] if idx < len(winds) else None,
                    "visibility_meters": visibilities[idx] if idx < len(visibilities) else None,
                }
            )

        wmo_code = current.get("weather_code")
        summary = _WMO_CODE_DESCRIPTIONS.get(wmo_code, "Conditions available")

        result = WeatherResponse(
            latitude=latitude,
            longitude=longitude,
            temperature_c=current.get("temperature_2m"),
            wind_speed_kmh=current.get("wind_speed_10m"),
            precipitation_mm=current.get("precipitation"),
            rain_probability_percent=rain_probs[0] if rain_probs else None,
            weather_code=wmo_code,
            condition_summary=summary,
            visibility_meters=visibilities[0] if visibilities else None,
            severe_weather_coverage_available=False,
            coverage_note=(
                "Regional forecast from Open-Meteo. Official severe-weather warning feeds "
                "are not provided by this endpoint; verify local road conditions directly."
            ),
            hourly_forecast=hourly_list,
            fetched_at=datetime.now(timezone.utc),
            cached=False,
        )
        self._cache[cache_key] = (now, result)
        return result
