from unittest.mock import AsyncMock, patch
import pytest

from app.config import Settings
from app.providers.openai_realtime import OpenAIRealtimeProvider


@pytest.mark.asyncio
async def test_voice_session_returns_503_when_key_missing(client):
    resp = await client.post(
        "/api/v1/voice/session",
        json={"device_id": "android-device-123456", "language": "en"},
    )
    assert resp.status_code == 503
    assert "not configured" in resp.json()["detail"].lower()


@pytest.mark.asyncio
async def test_openai_realtime_provider_uses_ga_client_secrets_endpoint():
    settings = Settings(
        environment="test",
        openai_api_key="sk-test-1234567890abcdef",
        openai_realtime_model="gpt-realtime",
        openai_realtime_voice="marin",
    )
    provider = OpenAIRealtimeProvider(settings=settings)

    mock_response = AsyncMock()
    mock_response.status_code = 200
    mock_response.json = lambda: {
        "value": "ek_ephemeral_token_abc123",
        "expires_at": 1760000600,
    }

    with patch("httpx.AsyncClient.post", return_value=mock_response) as mock_post:
        res = await provider.create_client_secret(language="sw")

    assert res.client_secret == "ek_ephemeral_token_abc123"
    assert res.expires_at == 1760000600
    assert res.model == "gpt-realtime"
    called_url = mock_post.call_args.args[0]
    called_headers = mock_post.call_args.kwargs["headers"]
    called_json = mock_post.call_args.kwargs["json"]
    assert called_url == "https://api.openai.com/v1/realtime/client_secrets"
    assert "OpenAI-Beta" not in called_headers
    assert called_json["session"]["type"] == "realtime"
