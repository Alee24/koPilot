# KoPilot Architecture & API Documentation

## 1. System Overview

KoPilot is a native Android AI driving companion for Kenyan motorists paired with a containerized FastAPI backend.

```mermaid
flowchart LR
    subgraph Android["Android Client (Kotlin + Jetpack Compose + Hilt)"]
        UI["feature:dashboard & feature:settings"]
        CAM["feature:camera (CameraX)"]
        VIS["feature:vision (LiteRT)"]
        LOC["feature:location (FusedLocation + SpeedAlertEngine)"]
        NAV["feature:navigation (Maps Compose)"]
        TRF["feature:traffic (TrafficAdvisor)"]
        WTH["feature:weather (Room Cache)"]
        ALR["feature:alerts (LocalAlertCoordinator + TTS)"]
        OBD["feature:diagnostics (Bluetooth SPP OBD-II)"]
        VCE["feature:voice (WebRTC + OpenAI Realtime GA)"]
    end

    subgraph Backend["FastAPI Backend (Docker + Nginx TLS + Postgres + Redis)"]
        GW["API Gateway (/api/v1/*)"]
        OAI["OpenAI Realtime (/v1/realtime/client_secrets)"]
        GRT["Google Routes API v2"]
        GPL["Google Places API (New)"]
        OMT["Open-Meteo Forecast API"]
    end

    CAM --> VIS --> ALR
    LOC --> ALR
    UI --> VCE
    VCE -->|1. Request ephemeral secret| GW
    GW --> OAI
    VCE -->|2. WebRTC SDP + DataChannel| OAI
    NAV --> GW --> GRT
    NAV --> GW --> GPL
    WTH --> GW --> OMT
```

## 2. Android Module Dependency Graph

- `:app` -> `:core:*`, `:feature:*`
- `:feature:dashboard` -> `:core:common`, `:core:designsystem`, `:core:database`, `:core:network`, `:feature:camera`, `:feature:vision`, `:feature:location`, `:feature:navigation`, `:feature:traffic`, `:feature:weather`, `:feature:voice`, `:feature:alerts`, `:feature:diagnostics`, `:feature:trips`
- `:feature:settings` -> `:core:common`, `:core:designsystem`, `:core:database`, `:core:network`, `:core:security`, `:feature:vehicle`, `:feature:diagnostics`, `:feature:trips`
- `:core:network` -> `:core:common`, `:core:security`
- `:core:database` -> `:core:common`

## 3. Backend REST API (`/api/v1`)

| Method | Path | Purpose | Auth |
|---|---|---|---|
| `GET` | `/health` | Service, DB, and Redis health check | Public |
| `GET` | `/api/v1/config` | Client feature flags & safety disclaimer | Public |
| `POST` | `/api/v1/auth/device` | Issue anonymous device session JWT | Public |
| `POST` | `/api/v1/auth/register` | Optional user account registration | Public |
| `POST` | `/api/v1/auth/login` | Optional user login | Public |
| `GET/PUT` | `/api/v1/auth/preferences` | Sync user driving/voice preferences | User JWT |
| `POST` | `/api/v1/voice/session` | Issue short-lived OpenAI Realtime `client_secret` | Rate-limited |
| `POST` | `/api/v1/routes/compute` | Traffic-aware route computation via Google Routes v2 | Rate-limited |
| `GET` | `/api/v1/weather` | Open-Meteo current & 12h forecast (cached) | Rate-limited |
| `GET` | `/api/v1/places/search` | Kenyan place search via Google Places (New) | Rate-limited |
| `POST` | `/api/v1/telemetry/events` | Privacy-conscious operational event ingestion | Public |
| `GET/POST` | `/api/v1/trips` | List or save trip summaries | Device/User |
| `DELETE` | `/api/v1/account/data` | Delete trips, telemetry, and user account | Device/User |
