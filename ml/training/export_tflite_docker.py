"""Export trained YOLO11n best.pt to LiteRT (.tflite) inside Linux x86 container."""
from pathlib import Path
from ultralytics import YOLO


def main() -> None:
    weights = Path("/work/work_yolo11n/runs/yolo11n_gtsrb_signs/weights/best.pt")
    model = YOLO(str(weights))
    exported = model.export(format="tflite", imgsz=320)
    print(f"EXPORTED_TFLITE: {exported}")


if __name__ == "__main__":
    main()
