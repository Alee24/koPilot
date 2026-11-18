# KoPilot — Offline Vehicle Intelligence & Sensor Support

## Overview

KoPilot's **Vehicle Intelligence** system (`VehicleTelemetryRepository`, `VehicleIntelligenceEngine`, `VehicleIntelligenceScreen`) operates **100% offline** using:
- A single shared read-only Bluetooth OBD-II scheduler (`ObdBluetoothManager` + `ObdConnectionManager`)
- Phone GPS, accelerometer, gyroscope, and orientation sensors (`SensorManagerRepository` + `RealTimeDrivingIntelligence`)
- Camera AI sign/hazard observations (`RoadVisionPipeline`)
- Local Room sensor history (`SensorHistoryEntity` / `SensorHistoryDao`, 30-day retention, 15s downsampling)

---

## Strict Read-Only OBD-II Command Firewall

All OBD-II commands pass through `ObdReadOnlyFirewall.evaluate(...)` inside `ObdConnectionManager.sendCommand(...)`.

- **Permitted**: Reviewed ELM327 initialization/query AT commands (`ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATH0`, `ATI`, `ATRV`, `ATDP`, `ATDPN`, `ATSP0/3..9`) and read-only SAE J1979 Modes `01`, `02`, `03`, `07`, `09`, `0A`.
- **Blocked**: Mode `04` (clear DTCs), ECU resets (`11`), security access (`27`), data/memory writes (`2E`, `3B`, `3D`), routine control (`31`), flashing (`34`, `36`, `37`), and unreviewed `AT` / raw CAN header commands.

---

## Supported SAE J1979 Mode 01 PIDs

Capabilities are discovered dynamically from ECU bitmaps (`0100`, `0120`, `0140`, `0160`) and cached locally:
- `0103` Fuel System Status
- `0104` Calculated Engine Load (`%`)
- `0105` Engine Coolant Temperature (`°C`)
- `0106` / `0107` Short-Term & Long-Term Fuel Trim Bank 1 (`%`)
- `0108` / `0109` Short-Term & Long-Term Fuel Trim Bank 2 (`%`)
- `010A` Fuel Pressure (Gauge, `kPa`)
- `010B` Intake Manifold Absolute Pressure (`kPa`)
- `010C` Engine RPM (`rpm`)
- `010D` Vehicle Speed (`km/h`)
- `010E` Ignition Timing Advance (`°`)
- `010F` Intake Air Temperature (`°C`)
- `0110` Mass Airflow Rate (`g/s`)
- `0111` Throttle Position (`%`)
- `0114` O2 Sensor Voltage B1S1 (`V`)
- `011F` Engine Runtime Since Start (`s`)
- `0123` Fuel Rail Gauge Pressure (`kPa`)
- `012F` Fuel Tank Level (`%`)
- `0133` Barometric Pressure (`kPa`)
- `0142` Control Module Voltage (`V`)
- `0143` Absolute Engine Load (`%`)
- `0145` Relative Throttle Position (`%`)
- `0146` Ambient Air Temperature (`°C`)
- `015C` Engine Oil Temperature (`°C`)
- `015E` Engine Fuel Rate (`L/h`)

### Measurement Validity States
Unsupported or disconnected readings are **never** shown as `0`. Every reading tracks:
`AVAILABLE`, `UNSUPPORTED`, `TEMPORARILY_UNAVAILABLE`, `STALE`, `INVALID`, or `NOT_YET_CHECKED`.

### Target Vehicle Notes (2012 Nissan Tiida 1.5L CVT)
- Standard SAE J1979 powertrain PIDs supported by the vehicle's ECU are discovered automatically via `0100`/`0120`/`0140`/`0160`.
- Standard transmission DTCs (`P07xx`–`P09xx`) are monitored via Modes `03`/`07`/`0A`.
- Manufacturer-specific Nissan CVT fluid temperature and line pressure PIDs are marked `REQUIRES_MANUFACTURER_INTEGRATION` and are not fabricated.

---

## Testing Limitations

- Automated JVM unit tests verify `ObdReadOnlyFirewall`, SAE J1979 PID & DTC parsing, range validation, `RealTimeDrivingIntelligence`, `KenyanPlateValidator.formatForSpeech`, and `SoundStudio` DSP/WAV parsing.
- Live Bluetooth HHOBD adapter communication on the 2012 Nissan Tiida and on-device Samsung Galaxy A23 UI/audio checks must be verified physically with the APK installed on the phone.
