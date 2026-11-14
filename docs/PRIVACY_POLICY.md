# KoPilot Privacy Policy (Kenya Data Protection Act, 2019)

**Effective Date:** November 2026

KoPilot is designed on a privacy-first, offline-capable architecture for motorists in Kenya in alignment with the **Kenya Data Protection Act, 2019 (DPA)**.

## 1. Data Minimization & Local Processing
- **No Account Required:** Basic driving assistance (camera preview, on-device road sign observation, GPS speed display, local TTS alerts, and trip recording) operates without creating an account.
- **Road Video:** Camera frames are processed in memory on your Android device. Raw road video is **not** recorded or uploaded to KoPilot servers.
- **GPS Location:** Real-time GPS coordinates are used locally for speed display and navigation. Continuous raw GPS traces are **not** transmitted to the backend. Coordinates are only sent to the backend when you request a route, weather forecast, or nearby place search.

## 2. Optional Cloud Features
- **Voice Assistant:** Audio is streamed directly from your device to OpenAI's Realtime WebRTC endpoint using short-lived ephemeral credentials issued by the KoPilot backend. No permanent API keys are stored on your phone.
- **Trip Summaries & Telemetry:** Trip summaries (distance, duration, speed stats) are stored locally in Room and optionally synced to the backend. Diagnostic telemetry is opt-in.

## 3. Your Rights Under the Kenya DPA
You have the right to access, export, and delete your data at any time:
- **Export:** Use **Settings → Privacy → Export Trip Data** to export local trips as JSON.
- **Delete:** Use **Settings → Privacy → Delete All Data** (or `DELETE /api/v1/account/data`) to permanently erase local and server-stored trip summaries, telemetry, and optional account records.
