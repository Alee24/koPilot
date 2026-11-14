from unittest.mock import AsyncMock, patch
import pytest

from app.providers.open_meteo import OpenMeteoProvider
from app.security.logging_filter import redact_sensitive_text


@pytest.mark.asyncio
async def test_weather_endpoint_and_caching(client):
    mock_resp = AsyncMock()
    mock_resp.status_code = 200
    mock_resp.json = lambda: {
        "current": {
            "temperature_2m": 22.4,
            "precipitation": 0.0,
            "weather_code": 2,
            "wind_speed_10m": 14.2,
        },
        "hourly": {
            "time": ["2026-11-14T20:00"],
            "temperature_2m": [21.5],
            "precipitation_probability": [30],
            "precipitation": [0.1],
            "wind_speed_10m": [12.0],
            "visibility": [10000.0],
        },
    }

    mock_client_instance = AsyncMock()
    mock_client_instance.get = AsyncMock(return_value=mock_resp)
    mock_client_cls = AsyncMock()
    mock_client_cls.__aenter__ = AsyncMock(return_value=mock_client_instance)
    mock_client_cls.__aexit__ = AsyncMock(return_value=False)

    with patch("app.providers.open_meteo.httpx.AsyncClient", return_value=mock_client_cls):
        r1 = await client.get("/api/v1/weather?lat=-1.2921&lng=36.8219")
        r2 = await client.get("/api/v1/weather?lat=-1.2921&lng=36.8219")

    assert r1.status_code == 200
    assert r1.json()["condition_summary"] == "Partly cloudy"
    assert r1.json()["severe_weather_coverage_available"] is False
    assert r1.json()["cached"] is False
    assert r2.status_code == 200
    assert r2.json()["cached"] is True


@pytest.mark.asyncio
async def test_trips_and_telemetry_flow(client):
    headers = {"X-Device-Id": "android-device-xyz789"}
    trip_resp = await client.post(
        "/api/v1/trips",
        headers=headers,
        json={
            "device_id": "android-device-xyz789",
            "origin_label": "Westlands, Nairobi",
            "destination_label": "JKIA, Nairobi",
            "started_at": "2026-11-14T16:00:00Z",
            "ended_at": "2026-11-14T16:35:00Z",
            "distance_km": 18.4,
            "duration_seconds": 2100,
            "max_speed_kmh": 78.0,
            "avg_speed_kmh": 31.5,
            "signs_detected_count": 7,
            "alerts_triggered_count": 1,
        },
    )
    assert trip_resp.status_code == 201
    list_resp = await client.get("/api/v1/trips", headers=headers)
    assert list_resp.status_code == 200
    assert len(list_resp.json()) == 1
    assert list_resp.json()[0]["destination_label"] == "JKIA, Nairobi"

    telem_resp = await client.post(
        "/api/v1/telemetry/events",
        json={
            "device_id": "android-device-xyz789",
            "event_type": "camera_thermal_throttle",
            "severity": "warn",
            "app_version": "1.0.0",
            "payload": {"fps": 5},
        },
    )
    assert telem_resp.status_code == 202


def test_sensitive_log_redaction():
    raw = "Auth: Bearer abc.def.ghi key=sk-1234567890abcdef ek_secret_1234567890 AIzaSyDummyGoogleKey1234567890"
    cleaned = redact_sensitive_text(raw)
    assert "abc.def.ghi" not in cleaned
    assert "sk-1234567890abcdef" not in cleaned
    assert "ek_secret_1234567890" not in cleaned
    assert "AIzaSyDummyGoogleKey1234567890" not in cleaned
