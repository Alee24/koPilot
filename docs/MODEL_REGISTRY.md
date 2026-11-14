# KoPilot Vision Model Registry

## Registered Models

| Model ID | Version | Asset Path | Input Size | Validation Status |
|---|---|---|---|---|
| `kopilot_ke_signs` | `1.0.0-baseline` | `assets/models/kopilot_ke_signs_v1.tflite` | `64x64x3` RGB | `UNVALIDATED_BASELINE` |

## Supported Classes (16)
`BACKGROUND`, `SPEED_LIMIT_30`, `SPEED_LIMIT_50`, `SPEED_LIMIT_60`, `SPEED_LIMIT_80`, `SPEED_LIMIT_100`, `SPEED_LIMIT_110`, `STOP`, `GIVE_WAY`, `NO_ENTRY`, `PEDESTRIAN_CROSSING`, `SCHOOL_ZONE`, `ROADWORKS`, `DANGEROUS_BEND`, `SPEED_BUMP`, `NO_OVERTAKING`.

## Known Limitations & Safety Boundaries
1. **Unvalidated on Kenyan Road Footage:** This baseline model slot has not yet been trained/fine-tuned and evaluated on Kenyan road signage datasets across daylight, nighttime, rain, glare, partial obstruction, motion blur, varying distances, and windscreen mounting angles.
2. **No Lane Applicability Guarantee:** Monocular camera detections cannot reliably determine whether a speed limit sign applies to a service lane, slip road, or the driver's main carriageway.
3. **Experimental Hazard Observer:** Disabled by default. KoPilot never estimates collision probability, stopping distance, or time-to-collision from monocular imagery.
