# KoPilot Vision ML Training & Validation Pipeline

Reproducible Python pipeline for training, validating, and exporting quantized on-device models for KoPilot Android (`LiteRtObjectDetector` & `LiteRtSignClassifier`).

## 1. Licensed Datasets
Use only properly licensed datasets and recorded Kenyan road footage with consent:
- **Object Detection:** BDD100K, OpenImages V7 (car, bus, truck, motorcycle, bicycle, pedestrian, animal), plus locally recorded Kenyan dashcam clips.
- **Road Signs:** Mapillary Traffic Sign Dataset (MTSD), GTSRB (bootstrapping), plus Kenyan-specific sign crops (speed limits 30–110, STOP, Give Way, No Entry, Pedestrian Crossing, School Zone, Speed Bump, Roadworks, Dangerous Bend, No Overtaking).
- **Conditions Covered:** `daylight`, `night`, `rain`, `glare`, `occlusion`, `motion_blur`.

## 2. Prevent Train/Val/Test Leakage
`dataset_manifest.py` groups all frames by `video_id` before splitting (`70% train / 15% val / 15% test`) so adjacent frames from the same road clip never appear in both training and evaluation splits.

## 3. Quick Start
```bash
python -m venv .venv && source .venv/bin/activate
pip install tensorflow ultralytics scikit-learn pandas numpy pillow

# 1. Build leak-free dataset manifest & validate annotations
python dataset_manifest.py --data-dir ./data/kenya_signs --out-dir ./manifests

# 2. Train & export INT8 TFLite Road Sign Classifier
python train_sign_classifier.py --manifest ./manifests/manifest.csv --out-dir ./exports

# 3. Train & export YOLOv8n Road Object Detector (TFLite INT8)
python train_object_detector.py --data ./data/kenya_objects/data.yaml --out-dir ./exports

# 4. Evaluate Precision, Recall, F1, Confusion Matrix, mAP & condition breakdown
python evaluate_models.py --predictions ./exports/val_predictions.csv --out-dir ./reports
```

Copy exported `road_sign_classifier.tflite` + `road_sign_labels.txt` and `road_object_detector.tflite` into:
`android/feature/vision/src/main/assets/models/`
