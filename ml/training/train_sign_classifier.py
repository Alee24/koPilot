"""Train MobileNetV3-Small Kenyan road-sign classifier and export INT8 TFLite."""
import argparse
from pathlib import Path
import numpy as np
import pandas as pd
import tensorflow as tf


SIGN_CLASSES = [
    "SPEED_LIMIT_30", "SPEED_LIMIT_50", "SPEED_LIMIT_60", "SPEED_LIMIT_80",
    "SPEED_LIMIT_100", "SPEED_LIMIT_110", "STOP", "GIVE_WAY", "NO_ENTRY",
    "PEDESTRIAN_CROSSING", "SCHOOL_ZONE", "ROADWORKS", "DANGEROUS_BEND",
    "SPEED_BUMP", "NO_OVERTAKING", "ROUNDABOUT", "RAILWAY_CROSSING",
]


def load_dataset(df: pd.DataFrame, class_to_idx: dict, img_size: int, batch_size: int, training: bool):
    paths = df["image_path"].tolist()
    labels = [class_to_idx[l] for l in df["label"].tolist()]
    ds = tf.data.Dataset.from_tensor_slices((paths, labels))

    def _decode(path, label):
        raw = tf.io.read_file(path)
        img = tf.image.decode_image(raw, channels=3, expand_animations=False)
        img = tf.image.resize(img, [img_size, img_size])
        if training:
            img = tf.image.random_brightness(img, 0.2)
            img = tf.image.random_contrast(img, 0.8, 1.2)
        return tf.cast(img, tf.float32) / 255.0, label

    ds = ds.map(_decode, num_parallel_calls=tf.data.AUTOTUNE)
    if training:
        ds = ds.shuffle(1024)
    return ds.batch(batch_size).prefetch(tf.data.AUTOTUNE)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--out-dir", required=True)
    parser.add_argument("--img-size", type=int, default=96)
    parser.add_argument("--epochs", type=int, default=20)
    parser.add_argument("--batch-size", type=int, default=32)
    args = parser.parse_args()

    df = pd.read_csv(args.manifest)
    df = df[df["label"].isin(SIGN_CLASSES)].copy()
    class_to_idx = {c: i for i, c in enumerate(SIGN_CLASSES)}

    train_ds = load_dataset(df[df["split"] == "train"], class_to_idx, args.img_size, args.batch_size, True)
    val_ds = load_dataset(df[df["split"] == "val"], class_to_idx, args.img_size, args.batch_size, False)

    base = tf.keras.applications.MobileNetV3Small(
        input_shape=(args.img_size, args.img_size, 3),
        include_top=False,
        weights="imagenet",
        pooling="avg",
    )
    inputs = tf.keras.Input(shape=(args.img_size, args.img_size, 3))
    x = base(inputs, training=True)
    x = tf.keras.layers.Dropout(0.2)(x)
    outputs = tf.keras.layers.Dense(len(SIGN_CLASSES), activation="softmax")(x)
    model = tf.keras.Model(inputs, outputs)

    model.compile(
        optimizer=tf.keras.optimizers.Adam(1e-3),
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"],
    )
    model.fit(train_ds, validation_data=val_ds, epochs=args.epochs)

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    def rep_dataset():
        for images, _ in train_ds.take(20):
            for img in images:
                yield [np.expand_dims(img.numpy(), axis=0)]

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.representative_dataset = rep_dataset
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    converter.inference_input_type = tf.uint8
    converter.inference_output_type = tf.uint8
    tflite_bytes = converter.convert()

    (out_dir / "road_sign_classifier.tflite").write_bytes(tflite_bytes)
    (out_dir / "road_sign_labels.txt").write_text("\n".join(SIGN_CLASSES) + "\n")
    print(f"Exported quantized classifier to {out_dir / 'road_sign_classifier.tflite'}")


if __name__ == "__main__":
    main()
