"""Build leak-free train/val/test dataset manifests grouped by video_id and validate annotations."""
import argparse
import hashlib
import json
from pathlib import Path
import pandas as pd


REQUIRED_COLUMNS = {"image_path", "video_id", "label", "condition"}


def assign_split_by_video(video_id: str) -> str:
    digest = int(hashlib.sha256(video_id.encode("utf-8")).hexdigest()[:8], 16) % 100
    if digest < 70:
        return "train"
    if digest < 85:
        return "val"
    return "test"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--annotations", required=True, help="CSV with image_path,video_id,label,condition[,xmin,ymin,xmax,ymax]")
    parser.add_argument("--out-dir", required=True)
    args = parser.parse_args()

    df = pd.read_csv(args.annotations)
    missing = REQUIRED_COLUMNS - set(df.columns)
    if missing:
        raise ValueError(f"Missing required annotation columns: {sorted(missing)}")

    # Validate bounding boxes if present
    if {"xmin", "ymin", "xmax", "ymax"}.issubset(df.columns):
        invalid_boxes = df[(df["xmax"] <= df["xmin"]) | (df["ymax"] <= df["ymin"])]
        if not invalid_boxes.empty:
            raise ValueError(f"Found {len(invalid_boxes)} invalid bounding boxes")

    df["split"] = df["video_id"].astype(str).map(assign_split_by_video)

    # Verify zero video_id overlap between splits
    splits = {s: set(df[df["split"] == s]["video_id"]) for s in ("train", "val", "test")}
    assert splits["train"].isdisjoint(splits["val"])
    assert splits["train"].isdisjoint(splits["test"])
    assert splits["val"].isdisjoint(splits["test"])

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    df.to_csv(out_dir / "manifest.csv", index=False)

    summary = {
        "total_rows": len(df),
        "split_counts": df["split"].value_counts().to_dict(),
        "condition_counts": df["condition"].value_counts().to_dict(),
        "label_counts": df["label"].value_counts().to_dict(),
    }
    (out_dir / "manifest_summary.json").write_text(json.dumps(summary, indent=2))
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
