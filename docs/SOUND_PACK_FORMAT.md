# KoPilot Virtual Engine Sound Studio — Sound Pack Format

KoPilot supports **offline custom engine sound packs** stored locally inside the app's private storage (`filesDir/sound_packs/<pack_id>/`).

> **Note:** KoPilot's 10 built-in engine profiles are synthesized offline in real time using multi-harmonic DSP and do not bundle proprietary manufacturer recordings. You can import your own legally obtained `.wav` recordings via **Engine Sound Studio -> Import WAV Pack**.

---

## Directory Layout

Each imported sound pack resides in its own directory:

```text
filesDir/sound_packs/<pack_id>/
  ├── manifest.json
  ├── idle.wav
  ├── low.wav
  ├── medium.wav
  ├── high.wav
  ├── accel.wav
  └── decel.wav
```

---

## `manifest.json` Schema

```json
{
  "id": "custom_1763485000000",
  "name": "My Track Boxer",
  "category": "CUSTOM_IMPORTED",
  "description": "User-imported offline WAV sound pack.",
  "configuration": "Flat-4 Turbo",
  "characteristics": "Imported custom multi-layer WAV samples",
  "idleRpm": 800,
  "redlineRpm": 7200,
  "fundamentalScale": 1.0,
  "exhaustWarmth": 0.6,
  "intakeGrowl": 0.45,
  "turboSpoolAmount": 0.0,
  "isCustomImported": true,
  "sampleLayers": [
    {
      "slot": "IDLE",
      "fileName": "IDLE_idle.wav",
      "minRpm": 600,
      "maxRpm": 1200,
      "baseRpm": 800,
      "sampleRateHz": 44100,
      "durationSec": 2.4
    },
    {
      "slot": "LOW",
      "fileName": "LOW_low.wav",
      "minRpm": 1000,
      "maxRpm": 2600,
      "baseRpm": 1800,
      "sampleRateHz": 44100,
      "durationSec": 2.1
    }
  ]
}
```

---

## Supported Audio Specifications

| Parameter | Supported Values |
| :--- | :--- |
| **Container** | RIFF / WAVE (`.wav`) |
| **Encoding** | Uncompressed PCM (`format = 1`: 16-bit, 24-bit, 32-bit) or IEEE Float (`format = 3`: 32-bit) |
| **Sample Rate** | `8,000 Hz` – `96,000 Hz` (automatically resampled to `44,100 Hz` mono float internally) |
| **Channels** | Mono (`1`) or Stereo (`2`, downmixed to mono) |
| **Clip Duration** | `0.3s` – `30.0s` |
| **Max File Size** | `15 MB` per `.wav` file |

---

## RPM Sample Slots (`RpmSampleSlot`)

| Slot | Purpose | Default RPM Range | Base Pitch RPM |
| :--- | :--- | :--- | :--- |
| `IDLE` | Stationary / idle loop | `600 – 1200` | `800` |
| `LOW` | Low-speed cruising | `1000 – 2600` | `1800` |
| `MEDIUM` | Mid-range load | `2200 – 4500` | `3200` |
| `HIGH` | High-RPM pull | `4000 – 7500` | `5600` |
| `ACCEL` | Positive throttle / rapid RPM climb | `1500 – 6500` | `3500` |
| `DECEL` | Off-throttle overrun / deceleration | `1400 – 5500` | `3000` |
