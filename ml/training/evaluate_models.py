"""Evaluate model predictions: Precision, Recall, F1, Confusion Matrix, Exact-Match Plate Accuracy, and Condition Breakdown."""
import argparse
import json
from pathlib import Path
import pandas as pd
from sklearn.metrics import classification_report, confusion_matrix


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--predictions", required=True, help="CSV with y_true,y_pred,condition[,plate_true,plate_pred]")
    parser.add_argument("--out-dir", required=True)
    args = parser.parse_args()

    df = pd.read_csv(args.predictions)
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    report = classification_report(df["y_true"], df["y_pred"], output_dict=True, zero_division=0)
    labels = sorted(set(df["y_true"]).union(set(df["y_pred"])))
    cm = confusion_matrix(df["y_true"], df["y_pred"], labels=labels).tolist()

    condition_metrics = {}
    if "condition" in df.columns:
        for cond, group in df.groupby("condition"):
            acc = float((group["y_true"] == group["y_pred"]).mean())
            condition_metrics[str(cond)] = {"count": len(group), "accuracy": round(acc, 4)}

    plate_exact_match = None
    if {"plate_true", "plate_pred"}.issubset(df.columns):
        valid_plates = df.dropna(subset=["plate_true"])
        if not valid_plates.empty:
            plate_exact_match = float((valid_plates["plate_true"] == valid_plates["plate_pred"]).mean())

    summary = {
        "overall": report.get("weighted avg", {}),
        "labels": labels,
        "confusion_matrix": cm,
        "by_condition": condition_metrics,
        "plate_exact_match_accuracy": plate_exact_match,
    }
    (out_dir / "evaluation_report.json").write_text(json.dumps(summary, indent=2))
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
