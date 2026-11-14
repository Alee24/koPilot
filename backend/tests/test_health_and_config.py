import pytest


@pytest.mark.asyncio
async def test_health_check(client):
    resp = await client.get("/health")
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "ok"
    assert data["database"] == "ok"
    assert resp.headers["X-Content-Type-Options"] == "nosniff"
    assert resp.headers["X-Frame-Options"] == "DENY"


@pytest.mark.asyncio
async def test_client_config(client):
    resp = await client.get("/api/v1/config")
    assert resp.status_code == 200
    data = resp.json()
    assert data["supported_languages"] == ["en", "sw"]
    assert data["providers"]["weather_provider"] == "open-meteo"
    assert data["providers"]["openai_realtime_configured"] is False
    assert "advisory only" in data["safety_disclaimer"]
