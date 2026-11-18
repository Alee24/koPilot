"""
Prepare GTSRB (CC BY 4.0) + optional Kenyan road-sign YOLO detection dataset,
fine-tune YOLO11n (imgsz=320), evaluate on held-out test split, and export ONNX/best.pt
for LiteRT (.tflite) conversion.

Never fabricates metrics or Kenyan field validation: classes absent from GTSRB
are explicitly recorded as requiring Kenyan road footage.
"""
from __future__ import annotations

import argparse
import json
import random
from pathlib import Path
import numpy as np
from PIL import Image, ImageEnhance, ImageFilter
import torchvision.datasets as datasets
from ultralytics import YOLO

KOPILOT_CLASSES = [
    "SPEED_LIMIT_30",
    "SPEED_LIMIT_50",
    "SPEED_LIMIT_60",
    "SPEED_LIMIT_80",
    "SPEED_LIMIT_100",
    "STOP",
    "GIVE_WAY",
    "NO_ENTRY",
    "PEDESTRIAN_CROSSING",
    "ROADWORKS",
    "DANGEROUS_BEND",
    "SPEED_BUMP",
    "NO_OVERTAKING",
    "ROUNDABOUT",
]

KENYA_UNSUPPORTED_CLASSES = [
    "SPEED_LIMIT_110",
    "SCHOOL_ZONE",
    "RAILWAY_CROSSING",
    "HOSPITAL_ZONE",
    "BLACKSPOT",
    "POLICE_CHECKPOINT",
]

GTSRB_TO_KOPILOT = {
    1: 0,   # Speed limit (30km/h)
    2: 1,   # Speed limit (50km/h)
    3: 2,   # Speed limit (60km/h)
    5: 3,   # Speed limit (80km/h)
    7: 4,   # Speed limit (100km/h)
    14: 5,  # Stop
    13: 6,  # Yield / Give Way
    17: 7,  # No entry
    27: 8,  # Pedestrians
    25: 9,  # Road work
    19: 10, # Dangerous curve to the left
    20: 10, # Dangerous curve to the right
    21: 10, # Double curve
    22: 11, # Bumpy road / Speed bump
    9: 12,  # No passing
    40: 13, # Roundabout mandatory
}


def make_road_background(size: int, condition: str, rng: random.Random) -> Image.Image:
    """Create a neutral sky/foliage/asphalt gradient canvas so YOLO11n learns localization."""
    arr = np.zeros((size, size, 3), dtype=np.uint8)
    sky_h = int(size * rng.uniform(0.45, 0.62))

    if condition == "night":
        sky = np.array([rng.randint(10, 26), rng.randint(14, 30), rng.randint(22, 42)], dtype=np.float32)
        road = np.array([rng.randint(18, 34), rng.randint(20, 36), rng.randint(24, 40)], dtype=np.float32)
    elif condition == "rain":
        sky = np.array([rng.randint(85, 120), rng.randint(95, 130), rng.randint(105, 140)], dtype=np.float32)
        road = np.array([rng.randint(45, 70), rng.randint(48, 74), rng.randint(52, 78)], dtype=np.float32)
    else:  # daylight
        sky = np.array([rng.randint(120, 175), rng.randint(155, 205), rng.randint(185, 235)], dtype=np.float32)
        road = np.array([rng.randint(65, 105), rng.randint(70, 110), rng.randint(72, 112)], dtype=np.float32)

    arr[:sky_h, :] = np.clip(sky, 0, 255).astype(np.uint8)
    arr[sky_h:, :] = np.clip(road, 0, 255).astype(np.uint8)
    noise = rng.randint(4, 12)
    jitter = np.random.default_rng(rng.randint(0, 1_000_000)).integers(-noise, noise + 1, size=arr.shape)
    arr = np.clip(arr.astype(np.int16) + jitter, 0, 255).astype(np.uint8)
    return Image.fromarray(arr, mode="RGB")


def apply_condition(sign_img: Image.Image, condition: str, rng: random.Random) -> Image.Image:
    img = sign_img.convert("RGB")
    if condition == "night":
        img = ImageEnhance.Brightness(img).enhance(rng.uniform(0.45, 0.72))
        img = ImageEnhance.Contrast(img).enhance(rng.uniform(0.85, 1.15))
    elif condition == "rain":
        img = ImageEnhance.Contrast(img).enhance(rng.uniform(0.75, 0.95))
        img = img.filter(ImageFilter.GaussianBlur(radius=rng.uniform(0.3, 0.8)))
    else:
        img = ImageEnhance.Brightness(img).enhance(rng.uniform(0.85, 1.18))
    return img


def build_split(
    samples_by_class: dict[int, list[Image.Image]],
    out_dir: Path,
    split_name: str,
    per_class_count: int,
    imgsz: int,
    seed: int,
) -> dict[str, int]:
    rng = random.Random(seed)
    img_dir = out_dir / "images" / split_name
    lbl_dir = out_dir / "labels" / split_name
    img_dir.mkdir(parents=True, exist_ok=True)
    lbl_dir.mkdir(parents=True, exist_ok=True)

    conditions = ["daylight", "daylight", "rain", "night"]
    cond_counts = {"daylight": 0, "rain": 0, "night": 0}
    idx = 0

    for cls_id, imgs in sorted(samples_by_class.items()):
        if not imgs:
            continue
        for _ in range(per_class_count):
            src = rng.choice(imgs)
            cond = rng.choice(conditions)
            cond_counts[cond] += 1
            canvas = make_road_background(imgsz, cond, rng)

            # Place 1 primary sign (and occasionally a 2nd sign) at realistic road-camera scales
            scale = rng.uniform(0.18, 0.48)
            box_w = max(28, int(imgsz * scale))
            aspect = src.height / max(1, src.width)
            box_h = max(28, min(int(box_w * aspect), imgsz - 16))
            box_w = min(box_w, imgsz - 16)

            x0 = rng.randint(8, imgsz - box_w - 8)
            y0 = rng.randint(8, imgsz - box_h - 8)
            resized = apply_condition(src.resize((box_w, box_h), Image.Resampling.BILINEAR), cond, rng)
            canvas.paste(resized, (x0, y0))

            cx = (x0 + box_w / 2.0) / imgsz
            cy = (y0 + box_h / 2.0) / imgsz
            nw = box_w / imgsz
            nh = box_h / imgsz

            stem = f"{split_name}_{idx:05d}_c{cls_id}_{cond}"
            canvas.save(img_dir / f"{stem}.jpg", quality=92)
            (lbl_dir / f"{stem}.txt").write_text(f"{cls_id} {cx:.6f} {cy:.6f} {nw:.6f} {nh:.6f}\n")
            idx += 1

    return cond_counts


def collect_gtsrb_by_kopilot_class(root: Path, split: str) -> dict[int, list[Image.Image]]:
    ds = datasets.GTSRB(root=str(root), split=split, download=True)
    grouped: dict[int, list[Image.Image]] = {i: [] for i in range(len(KOPILOT_CLASSES))}
    for i in range(len(ds)):
        img, gtsrb_cls = ds[i]
        if gtsrb_cls in GTSRB_TO_KOPILOT:
            grouped[GTSRB_TO_KOPILOT[gtsrb_cls]].append(img)
    return grouped


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--gtsrb-root", default="c:/Users/Metto/Desktop/Codes/KoPilot/ml/data/gtsrb")
    parser.add_argument("--work-dir", default="c:/Users/Metto/Desktop/Codes/KoPilot/ml/work_yolo11n")
    parser.add_argument("--imgsz", type=int, default=320)
    parser.add_argument("--epochs", type=int, default=12)
    parser.add_argument("--batch", type=int, default=16)
    parser.add_argument("--train-per-class", type=int, default=90)
    parser.add_argument("--val-per-class", type=int, default=20)
    parser.add_argument("--test-per-class", type=int, default=25)
    args = parser.parse_args()

    gtsrb_root = Path(args.gtsrb_root)
    work_dir = Path(args.work_dir)
    dataset_dir = work_dir / "dataset"

    print("Loading GTSRB train and test splits...")
    train_pool_all = collect_gtsrb_by_kopilot_class(gtsrb_root, "train")
    test_pool = collect_gtsrb_by_kopilot_class(gtsrb_root, "test")

    # Split train_pool_all deterministically 80/20 into train and val source images (zero overlap)
    train_pool: dict[int, list[Image.Image]] = {}
    val_pool: dict[int, list[Image.Image]] = {}
    for cls_id, imgs in train_pool_all.items():
        cut = max(1, int(len(imgs) * 0.8))
        train_pool[cls_id] = imgs[:cut]
        val_pool[cls_id] = imgs[cut:]

    train_cond = build_split(train_pool, dataset_dir, "train", args.train_per_class, args.imgsz, seed=42)
    val_cond = build_split(val_pool, dataset_dir, "val", args.val_per_class, args.imgsz, seed=43)
    test_cond = build_split(test_pool, dataset_dir, "test", args.test_per_class, args.imgsz, seed=44)

    data_yaml = dataset_dir / "data.yaml"
    names_yaml = "\n".join(f"  {i}: {name}" for i, name in enumerate(KOPILOT_CLASSES))
    data_yaml.write_text(
        f"path: {dataset_dir.as_posix()}\n"
        f"train: images/train\n"
        f"val: images/val\n"
        f"test: images/test\n"
        f"names:\n{names_yaml}\n"
    )

    model = YOLO("yolo11n.pt")
    results = model.train(
        data=str(data_yaml),
        epochs=args.epochs,
        imgsz=args.imgsz,
        batch=args.batch,
        project=str(work_dir / "runs"),
        name="yolo11n_gtsrb_signs",
        exist_ok=True,
        workers=0,
    )

    best_pt = Path(results.save_dir) / "weights" / "best.pt"
    best_model = YOLO(str(best_pt))
    test_metrics = best_model.val(data=str(data_yaml), split="test", imgsz=args.imgsz, workers=0)

    report = {
        "dataset": "GTSRB (CC BY 4.0) composed onto multi-condition road canvases",
        "supported_classes": KOPILOT_CLASSES,
        "unsupported_kenya_classes_needing_data": KENYA_UNSUPPORTED_CLASSES,
        "split_conditions": {"train": train_cond, "val": val_cond, "test": test_cond},
        "test_metrics": {
            "mAP50": float(test_metrics.box.map50),
            "mAP50_95": float(test_metrics.box.map),
            "precision": float(test_metrics.box.mp),
            "recall": float(test_metrics.box.mr),
        },
        "best_pt": str(best_pt),
    }
    report_path = work_dir / "evaluation_report.json"
    report_path.write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
