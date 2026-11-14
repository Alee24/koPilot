from datetime import datetime
from typing import Any

from pydantic import BaseModel, ConfigDict, EmailStr, Field


class HealthResponse(BaseModel):
    status: str
    version: str
    environment: str
    database: str
    redis: str
    timestamp: datetime


class ProviderStatus(BaseModel):
    openai_realtime_configured: bool
    google_maps_configured: bool
    weather_provider: str = "open-meteo"


class AppConfigResponse(BaseModel):
    app_version: str
    min_supported_android_version_code: int
    safety_disclosure_version: str
    safety_disclaimer: str
    providers: ProviderStatus
    supported_languages: list[str]
    openai_realtime_model: str
    openai_realtime_voice: str


class DeviceRegisterRequest(BaseModel):
    device_id: str = Field(min_length=8, max_length=128)
    app_version: str = Field(default="1.0.0", max_length=32)
    android_sdk: int | None = Field(default=None, ge=21, le=40)
    device_model: str | None = Field(default=None, max_length=128)


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    expires_in_seconds: int
    subject_type: str
    subject_id: str


class UserRegisterRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=8, max_length=128)
    display_name: str | None = Field(default=None, max_length=120)
    device_id: str | None = Field(default=None, min_length=8, max_length=128)


class UserLoginRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=1, max_length=128)


class PreferencesPayload(BaseModel):
    language: str = Field(default="en", pattern="^(en|sw)$")
    distance_unit: str = Field(default="km", pattern="^(km|mi)$")
    voice_name: str = Field(default="marin", max_length=32)
    speed_alert_threshold_kmh: int = Field(default=5, ge=0, le=40)
    hazard_advisories_enabled: bool = False
    telemetry_opt_in: bool = False
    extra: dict[str, Any] = Field(default_factory=dict)


class VoiceSessionRequest(BaseModel):
    device_id: str = Field(min_length=8, max_length=128)
    language: str = Field(default="en", pattern="^(en|sw)$")
    voice: str | None = Field(default=None, max_length=32)


class VoiceSessionResponse(BaseModel):
    client_secret: str
    expires_at: int
    model: str
    voice: str
    webrtc_calls_url: str = "https://api.openai.com/v1/realtime/calls"


class LatLng(BaseModel):
    latitude: float = Field(ge=-90.0, le=90.0)
    longitude: float = Field(ge=-180.0, le=180.0)


class RouteComputeRequest(BaseModel):
    origin: LatLng
    destination: LatLng
    avoid_tolls: bool = False
    avoid_highways: bool = False
    compute_alternatives: bool = True


class RouteStepSummary(BaseModel):
    instruction: str
    distance_meters: int
    duration_seconds: int
    maneuver: str | None = None


class RouteOption(BaseModel):
    route_id: str
    summary: str
    distance_meters: int
    duration_seconds: int
    static_duration_seconds: int
    traffic_delay_seconds: int
    encoded_polyline: str
    steps: list[RouteStepSummary] = Field(default_factory=list)


class RouteComputeResponse(BaseModel):
    provider: str = "google_routes_v2"
    routes: list[RouteOption]
    fetched_at: datetime


class WeatherResponse(BaseModel):
    provider: str = "open-meteo"
    latitude: float
    longitude: float
    temperature_c: float | None = None
    wind_speed_kmh: float | None = None
    precipitation_mm: float | None = None
    rain_probability_percent: int | None = None
    weather_code: int | None = None
    condition_summary: str
    visibility_meters: float | None = None
    severe_weather_coverage_available: bool = False
    coverage_note: str
    hourly_forecast: list[dict[str, Any]] = Field(default_factory=list)
    fetched_at: datetime
    cached: bool = False


class PlaceItem(BaseModel):
    place_id: str
    name: str
    formatted_address: str
    latitude: float
    longitude: float
    types: list[str] = Field(default_factory=list)


class PlacesSearchResponse(BaseModel):
    provider: str = "google_places_v1"
    query: str
    results: list[PlaceItem]
    fetched_at: datetime


class TelemetryEventRequest(BaseModel):
    device_id: str = Field(min_length=8, max_length=128)
    event_type: str = Field(min_length=2, max_length=64)
    severity: str = Field(default="info", pattern="^(debug|info|warn|error)$")
    app_version: str = Field(default="1.0.0", max_length=32)
    payload: dict[str, Any] = Field(default_factory=dict)


class TripCreateRequest(BaseModel):
    device_id: str = Field(min_length=8, max_length=128)
    origin_label: str | None = Field(default=None, max_length=255)
    destination_label: str | None = Field(default=None, max_length=255)
    started_at: datetime
    ended_at: datetime | None = None
    distance_km: float = Field(ge=0.0)
    duration_seconds: int = Field(ge=0)
    max_speed_kmh: float = Field(ge=0.0, default=0.0)
    avg_speed_kmh: float = Field(ge=0.0, default=0.0)
    signs_detected_count: int = Field(ge=0, default=0)
    alerts_triggered_count: int = Field(ge=0, default=0)


class TripResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    device_id: str
    origin_label: str | None
    destination_label: str | None
    started_at: datetime
    ended_at: datetime | None
    distance_km: float
    duration_seconds: int
    max_speed_kmh: float
    avg_speed_kmh: float
    signs_detected_count: int
    alerts_triggered_count: int
    created_at: datetime


class AccountDeleteResponse(BaseModel):
    status: str
    deleted_trips_count: int
    deleted_telemetry_count: int
    deleted_user_account: bool
