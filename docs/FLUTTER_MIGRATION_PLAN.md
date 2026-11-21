# KoPilot — Phase 1 Repository Audit & Phased Flutter Cross-Platform Migration Plan

## 1. Environment & Toolchain Status
- **Current Repository Architecture:** Native Android multi-module Gradle project (`Kotlin`, `Jetpack Compose`, `Hilt`, `Room`, `DataStore`, `CameraX`, `TFLite/YOLO`, `OSMDroid`).
- **Flutter SDK Check:** `flutter` CLI is **not currently installed** in this environment (`Get-Command flutter` returned no binary). Installing Flutter SDK (`>= 3.24`), Dart, and Xcode (macOS for iOS builds) is a prerequisite before executing Phase 2 (`flutter create` / add-to-app module).
- **Guiding Rule:** Preserve the working native Android app (`:app` + 20 `:core:*` and `:feature:*` modules) at every stage; integrate Flutter incrementally as a shared UI layer over typed Pigeon/EventChannel bridges without duplicating or replacing native hardware/AI engines.

---

## 2. Existing Application Architecture Audit

### 2.1 Gradle Modules (`android/settings.gradle.kts`)
- **App Shell:** `:app` (`MainActivity.kt` NavigationHost, `EcuPerformanceDashboardScreen`, `FuelEfficiencyAnalysisScreen`, `SensorFusionDiagnosticsScreen`, `VehicleIntelligenceScreen`, `VehicleMaintenanceScreen`, `SafetySettingsScreen`, `EmergencyCountdownScreen`, `KoPilotVoiceToolExecutor`)
- **Core Modules (5):**
  - `:core:common` — Shared models (`GpsFix`, `AlertMessage`, `VehicleDiagnosticsState`, `TrafficSignObservation`) and dispatcher abstractions
  - `:core:designsystem` — `KoPilotTheme`, `KoPilotClusterTokens`, `KoPilotColors`
  - `:core:database` — Room (`TripEntity`, `VehicleProfileEntity`, `CachedWeatherEntity`, `WearableDeviceEntity`) + DataStore `PreferencesRepository` (`KoPilotSettings`, `MaintenanceCalculator`, `ecuBaselineJson`, alert preferences)
  - `:core:network` — Connectivity observer and REST/weather/route clients
  - `:core:security` — Secure storage & key management
- **Feature Modules (15):**
  - `:feature:dashboard` — `DashboardViewModel`, `DashboardScreen`, `SystemStatusBriefingEngine`, `SystemStatusScreen`, `PerformanceThermalScreen`
  - `:feature:camera` — `CameraPerformanceController`, `RoadCameraPreview` (CameraX)
  - `:feature:vision` — `RoadVisionPipeline` (on-device sign/lane/brake/plate detection)
  - `:feature:location` — `LocationTracker` (GPS latitude/longitude/altitude/vertical accuracy/speed/bearing/monotonic time), `SpeedAlertEngine`, `SpeedZoneMonitor`
  - `:feature:navigation` — `KoPilotNavigationMap` (OSMDroid + OSRM routing + tile cache), `NavigationRepository`
  - `:feature:traffic` — `TrafficAdvisor`
  - `:feature:weather` — `WeatherRepository` + cached observations
  - `:feature:voice` — `OpenAiRealtimeVoiceClient` & offline voice command bridge
  - `:feature:alerts` — `LocalAlertCoordinator`, `AndroidTtsSpeechSink` (priority queue, connection beeps, TTS voice profiles)
  - `:feature:vehicle` — Vehicle profile management
  - `:feature:trips` — `TripRepository`, `TripActivityLogger`, `TripsScreen`, `TripReportBuilder`, `TripReportExporter` (PDF/CSV/JSON), `TripIntelligenceReportScreen`
  - `:feature:settings` — `SettingsViewModel`, `SettingsScreen`
  - `:feature:diagnostics` — `ObdBluetoothManager` (strictly read-only `ObdReadOnlyFirewall`, PID bitmap discovery `0100`–`01A0`, `ObdPidParser`), `EcuPerformanceEngine`
  - `:feature:sensors` — `SensorManagerRepository` (dedicated `HandlerThread`), `PhoneMountCalibrator`, `CompassHeadingEstimator`, `DrivingMotionAnalyzer`, `RealTimeDrivingIntelligence`, `AccidentDetectionEngine`, `SwervingDetector`, `SensorFusionEngine`, `TerrainIntelligenceEngine`, `EvidenceVerificationEngine`, `SmartwatchConnectionManager` (BLE GATT `0x180D`/`0x2A37`), `HeartRateRepository`, `SleepAnalyticsRepository`
  - `:feature:soundstudio` — Engine sound studio

---

## 3. Feature-Preservation & iOS Compatibility Matrix

| Domain | Android Kotlin Implementation | Flutter Shared UI Layer | iOS Swift Equivalent Required |
|---|---|---|---|
| **Camera & Vision** | `CameraPerformanceController` (CameraX) + `RoadVisionPipeline` | Native `Texture` / `PlatformView` (`CameraPreviewPanel`) | `AVFoundation` (`AVCaptureSession`) + CoreML/Vision |
| **OBD-II Telemetry** | `ObdBluetoothManager` (Bluetooth Classic SPP + BLE, `ObdReadOnlyFirewall`) | `Vehicle` & `Drive` Riverpod providers via Pigeon/EventChannel | `CoreBluetooth` BLE ELM327/OBD adapters only (iOS restricts Classic SPP to MFi) |
| **Motion & Barometer** | `SensorManagerRepository` (`HandlerThread`, Accel, Gyro, Mag, RotVec, Barometer) | `SensorFusionPanel` & `TerrainIndicator` | `CoreMotion` (`CMMotionManager`, `CMAltimeter`) |
| **Sensor Fusion & Terrain** | `SensorFusionEngine`, `TerrainIntelligenceEngine`, `EvidenceVerificationEngine` | Shared display tokens & diagnostics view | Port pure-logic fusion or share via Kotlin Multiplatform / Swift package |
| **Crash Detection & SMS** | `AccidentDetectionEngine` (5s verification) + Emergency SMS/Dial | Full-screen `SafetyAlertOverlay` + countdown | `CoreMotion` + `MFMessageComposeViewController` / `tel://` (no silent background SMS on iOS) |
| **Smartwatch & Health** | `SmartwatchConnectionManager` (BLE `0x180D`) + Health Connect | Wellness & HR cards | `CoreBluetooth` (`0x180D`) + `HealthKit` |
| **GPS & Maps** | `LocationTracker` + `KoPilotNavigationMap` (OSMDroid) | `Trips` & `Drive` map panels | `CoreLocation` (`CLLocationManager`) + `MapKit` / `flutter_map` |
| **TTS & Voice** | `AndroidTtsSpeechSink` + `LocalAlertCoordinator` | `VoiceAssistantControl` | `AVSpeechSynthesizer` + `SFSpeechRecognizer` |
| **Persistence & Exports** | Room + DataStore + PDF/CSV/JSON `TripReportExporter` | `Trips` & `Garage` screens | SQLite/Drift or SwiftData + `UIActivityViewController` share sheet |

---

## 4. Target Cross-Platform Architecture

```text
+-------------------------------------------------------------------+
|                   Flutter UI Layer (Dart / Material 3)            |
|  Riverpod State  |  GoRouter (6 Areas)  |  CustomPainter Gauges   |
+-------------------------------------------------------------------+
           ^ Typed Pigeon APIs & Throttled EventStreams (<= 10 Hz)
+-----------------------------------+-------------------------------+
|    Android Native Engine (Kotlin) |   iOS Native Engine (Swift)   |
|  - SensorFusionEngine             |  - CoreMotion / CMAltimeter   |
|  - AccidentDetectionEngine        |  - CoreLocation               |
|  - ObdBluetoothManager (ReadOnly) |  - CoreBluetooth BLE OBD/HR   |
|  - CameraX + RoadVisionPipeline   |  - AVFoundation + Vision      |
|  - Room / DataStore               |  - HealthKit / AVSpeech       |
+-----------------------------------+-------------------------------+
```

### Key Performance & Safety Guardrails
1. **No High-Frequency Raw Frame Transfer:** Keep CameraX/AVFoundation rendering in native `Texture` surfaces and run YOLO/Vision inference off the Flutter UI thread.
2. **Throttled Telemetry Streams:** Sensor fusion & accident detection run at 50 Hz natively; UI `EventChannel` streams emit throttled snapshots (e.g., 4–10 Hz) with explicit states (`connected`, `connecting`, `disconnected`, `unsupported`, `stale`, `error`).
3. **Never Fabricate Data:** Missing PIDs/sensors map to explicit `unsupported` / `unavailable` states rather than `0`.

---

## 5. Phased Migration Plan (10 Stages)

1. **Stage 1 — Toolchain & Add-to-App Bootstrap:** Install Flutter SDK, create `flutter_ui/` package with `pubspec.yaml` (`flutter_riverpod`, `go_router`, `pigeon`), and attach to Android `:app` without altering existing Compose routes.
2. **Stage 2 — Design System & Tokens:** Implement `#090F1B` dark / warm-white light themes, typography, spacing scale (`4, 8, 12, 16, 24, 32`), and `KoPilotScaffold` + `AdaptiveNavigation` (5-tab bottom bar on `<600 dp`, 6-item `NavigationRail` on `>=600 dp`).
3. **Stage 3 — Pigeon Contracts:** Define typed `TelemetryHostApi`, `TripHostApi`, `SafetyHostApi`, `VehicleHostApi`, and `EventChannel` streams in `pigeons/kopilot_bridge.dart`.
4. **Stage 4 — Settings, Notifications & Garage:** Migrate `SettingsScreen` (including Smartwatch, Terrain, and ECU alert toggles) and `Garage` (`VehicleProfile`, official OBD/manual odometer, maintenance schedules).
5. **Stage 5 — Trips & Intelligence Dashboards:** Migrate `TripsScreen` (route maps with exact GPS pins, PDF/CSV/JSON exports), `FuelEfficiencyAnalysisScreen`, `EcuPerformanceDashboardScreen`, and `SensorFusionDiagnosticsScreen`.
6. **Stage 6 — Safety & Smartwatch Interfaces:** Migrate `Safety` dashboard, emergency countdown overlay, and smartwatch HR/sleep screens.
7. **Stage 7 — Pre-Trip System Check:** Migrate `Check All Systems` readiness screen and voice briefing trigger.
8. **Stage 8 — Drive Cockpit & Native Camera Texture:** Migrate `Drive` screen (`SpeedometerWidget`, adjacent `TerrainIndicator`, `CameraPreviewPanel` backed by native texture, `VoiceAssistantControl`).
9. **Stage 9 — iOS Swift Native Bridge:** Implement Swift `CoreLocation`, `CoreMotion`, `CoreBluetooth` (BLE OBD & `0x180D` HR), `AVFoundation`, and `AVSpeechSynthesizer` behind the same Pigeon contracts.
10. **Stage 10 — Profiling & Safety Verification:** Run widget/golden/overflow tests across compact, landscape, tablet, and foldable viewports, plus 30-minute thermal/battery/frame-time profiling on Galaxy A23 and iPhone before switching the default launcher activity.
