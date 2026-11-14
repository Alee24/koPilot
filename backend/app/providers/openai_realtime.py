from dataclasses import dataclass
import httpx

from app.config.settings import Settings, get_settings


class ProviderConfigurationError(RuntimeError):
    """Raised when a required external provider credential is missing."""


class ProviderUpstreamError(RuntimeError):
    """Raised when an upstream provider returns an error or times out."""

    def __init__(self, message: str, status_code: int = 502) -> None:
        super().__init__(message)
        self.status_code = status_code


@dataclass
class EphemeralSessionResult:
    client_secret: str
    expires_at: int
    model: str
    voice: str


class OpenAIRealtimeProvider:
    """Issues short-lived GA OpenAI Realtime API client secrets via /v1/realtime/client_secrets."""

    CLIENT_SECRETS_URL = "https://api.openai.com/v1/realtime/client_secrets"

    def __init__(self, settings: Settings | None = None) -> None:
        self._settings = settings or get_settings()

    async def create_client_secret(self, language: str = "en", voice: str | None = None) -> EphemeralSessionResult:
        if not self._settings.openai_api_key:
            raise ProviderConfigurationError(
                "OpenAI API key is not configured on the backend. Voice assistant cloud sessions are unavailable."
            )

        selected_voice = voice or self._settings.openai_realtime_voice
        lang_instruction = (
            "Respond concisely in natural Kiswahili unless the driver switches to English."
            if language == "sw"
            else "Respond concisely in clear Kenyan English. Support switching to Kiswahili if requested."
        )
        instructions = (
            "You are KoPilot, a calm, safety-first AI driving companion for motorists in Kenya. "
            "Keep spoken responses brief (1-2 sentences) so the driver stays focused on the road. "
            "Always call the provided tools for live speed, location, routes, traffic, weather, nearby places, "
            "road sign observations, and vehicle diagnostics. Never invent or guess live readings; if a tool "
            "returns unavailable, state clearly that the data is unavailable. "
            + lang_instruction
        )

        payload = {
            "expires_after": {
                "anchor": "created_at",
                "seconds": self._settings.openai_client_secret_ttl_seconds,
            },
            "session": {
                "type": "realtime",
                "model": self._settings.openai_realtime_model,
                "instructions": instructions,
                "audio": {
                    "output": {
                        "voice": selected_voice,
                    }
                },
            },
        }

        headers = {
            "Authorization": f"Bearer {self._settings.openai_api_key}",
            "Content-Type": "application/json",
        }

        async with httpx.AsyncClient(timeout=15.0) as client:
            response = await client.post(self.CLIENT_SECRETS_URL, json=payload, headers=headers)

        if response.status_code >= 400:
            raise ProviderUpstreamError(
                f"OpenAI Realtime client_secrets failed with HTTP {response.status_code}",
                status_code=502,
            )

        data = response.json()
        secret_value = data.get("value") or data.get("client_secret", {}).get("value")
        expires_at = data.get("expires_at") or data.get("client_secret", {}).get("expires_at") or 0
        if not secret_value:
            raise ProviderUpstreamError("OpenAI Realtime response did not include an ephemeral client secret.")

        return EphemeralSessionResult(
            client_secret=secret_value,
            expires_at=int(expires_at),
            model=self._settings.openai_realtime_model,
            voice=selected_voice,
        )
