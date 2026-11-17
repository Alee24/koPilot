"""Train lightweight YOLOv8n road object detector for Kenyan road classes and export INT8 TFLite."""
import argparse
from pathlib import Path
from ultralytics import YOLO


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", required=True, help="YOLO data.yaml with classes: car,bus,truck,motorcycle,bicycle,pedestrian,animal")
    parser.add_argument("--out-dir", required=True)
    parser.add_argument("--epochs", type=int, default=50)
    parser.add_argument("--imgsz", type=int, default=320)
    args = parser.parse_args()

    model = YOLO("yolov8n.pt")
    model.train(data=args.data, epochs=args.epochs, imgsz=args.imgsz, project=args.out_dir, name="yolov8n_kenya")
    exported_path = model.export(format="tflite", int8=True, imgsz=args.imgsz)
    print(f"Exported TFLite detector to {exported_path}")


if __name__ == "__main__":
    main()
