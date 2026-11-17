# KoPilot Camera Vision — Production Release Readiness Checklist

## Implemented & Verified in Codebase
- [x] **CameraX Acquisition (`feature/camera`)**: Rear camera, autofocus & road-corridor exposure metering (`FocusMeteringAction`), rotation handling, `STRATEGY_KEEP_ONLY_LATEST`, background executor, guaranteed `ImageProxy.close()`, thermal (`PowerManager`) and battery (`BatteryManager`) throttling, actual FPS / dropped-frame / resolution telemetry.
- [x] **Pipeline A — Object Detection & Tracking (`feature/vision`)**: `LiteRtObjectDetector` (supports bundled `road_object_detector.tflite`) + ML Kit `ObjectDetector` fallback + `MultiObjectTracker` (persistent IoU tracking IDs and timestamps; no uncalibrated distance claims).
- [x] **Pipeline B — Kenyan Road-Sign Recognition (`feature/vision`)**: `SignRoiExtractor`, `SignCandidateParser`, `LiteRtSignClassifier`, `OfflineSymbolSignDetector`, `SignTemporalTracker` (temporal confirmation + uncertainty flagging), and `LocalRoadSignCatalog`.
- [x] **Pipeline C — Kenyan Registration Plate Recognition (`feature/vision`)**: `PlateRecognitionPipeline` & `KenyanPlateValidator` with quality assessment (luminance, contrast, blur), character confidence gating, multi-frame consistency, privacy-masked display (`KDA ***A`), no automatic voice readout, and parked-only AES-256-GCM `EncryptedFile` evidence capture/deletion.
- [x] **Lane Overlay & Sensor Fusion (`feature/vision`, `feature/sensors`, `feature/dashboard`)**: `LaneDetector` yellow lane lines overlay, IMU + camera lane-shift/turn alerts, and parked-only **Vision Diagnostics** modal displaying real measured telemetry.
- [x] **Training & Evaluation Pipeline (`ml/training/`)**: Leak-free `video_id`-grouped manifest builder, MobileNetV3-Small INT8 TFLite sign classifier trainer, YOLOv8n INT8 TFLite detector trainer, and condition-sliced evaluator.

## Remaining Field Validation Required Before Production Release
- [ ] Collect & label licensed Kenyan road footage across daylight, nighttime, rain, glare, matatus/motorcycles, and occluded signs.
- [ ] Train and bundle `road_sign_classifier.tflite` and `road_object_detector.tflite` into `android/feature/vision/src/main/assets/models/`.
- [ ] Run `ml/training/evaluate_models.py` on held-out Kenyan test clips and verify mAP, F1, confusion matrix, and plate exact-match accuracy targets.
- [ ] Record real-device CPU/GPU/NPU benchmarks and 30-minute thermal soak tests on target Android hardware via an observer/dashcam mount (never manual testing while driving).
