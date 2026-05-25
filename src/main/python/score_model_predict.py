#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import csv
import json
import sys
from pathlib import Path

import joblib
import numpy as np
from PIL import Image


def find_project_root():
    for parent in [Path.cwd(), *Path(__file__).resolve().parents]:
        if (parent / "score_model.joblib").exists() and (parent / "nail_styles.csv").exists():
            return parent
    raise FileNotFoundError("score_model.joblib and nail_styles.csv were not found in project root")


ROOT = find_project_root()
MODEL_PATH = ROOT / "score_model.joblib"
STYLE_CSV = ROOT / "nail_styles.csv"


def load_payload():
    return json.loads(sys.stdin.read() or "{}")


def image_features(image_path):
    img = Image.open(image_path).convert("RGB")
    arr = np.asarray(img).astype(np.float32)
    gray = arr.mean(axis=2)
    h, w = arr.shape[:2]
    center = gray[h // 4: h * 3 // 4, w // 4: w * 3 // 4]
    # A lightweight skin-like mask ratio. The training data is hand-back images,
    # so this keeps the same feature shape even without MediaPipe in deployment.
    r, g, b = arr[:, :, 0], arr[:, :, 1], arr[:, :, 2]
    skin_mask = (r > 95) & (g > 40) & (b > 20) & ((r - g) > 8) & (r > b)
    return {
        "img_width": float(w),
        "img_height": float(h),
        "aspect_ratio": float(w / h) if h else 1.0,
        "brightness_mean": float(gray.mean()),
        "brightness_std": float(gray.std()),
        "r_mean": float(r.mean()),
        "g_mean": float(g.mean()),
        "b_mean": float(b.mean()),
        "skin_mask_ratio": float(skin_mask.mean()),
        "center_brightness": float(center.mean()) if center.size else float(gray.mean()),
    }


def load_style_features(style_code):
    with STYLE_CSV.open("r", encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f):
            if row["style_id"] == style_code:
                return {
                    "style_length": float(row["style_length"]),
                    "style_color_warmth": float(row["style_color_warmth"]),
                    "style_complexity": float(row["style_complexity"]),
                    "style_gloss": float(row["style_gloss"]),
                    "style_edge_roundness": float(row["style_edge_roundness"]),
                    "style_name": row["style_name"],
                }
    raise ValueError(f"style_id not found in nail_styles.csv: {style_code}")


def p5p95_normalize(features, stats, cols):
    normalized = {}
    for col in cols:
        value = float(features[col])
        item = stats.get(col, {})
        p5 = float(item.get("p5", 0.0))
        p95 = float(item.get("p95", 1.0))
        if abs(p95 - p5) < 1e-9:
            normalized[col] = 0.5
        else:
            normalized[col] = min(1.0, max(0.0, (value - p5) / (p95 - p5)))
    return normalized


def main():
    payload = load_payload()
    image_path = payload["imagePath"]
    style_code = payload.get("styleCode") or "nail_01"
    model_path = Path(payload.get("modelPath") or MODEL_PATH).expanduser()
    if not model_path.is_absolute():
        model_path = (ROOT / model_path).resolve()
    bundle = joblib.load(model_path)
    model = bundle["model"]
    feature_cols = bundle["feature_cols"]
    stats = bundle.get("normalization_stats") or {}

    hand = image_features(image_path)
    style = load_style_features(style_code)
    raw_features = {**hand, **{k: v for k, v in style.items() if k.startswith("style_")}}
    normalized = p5p95_normalize(raw_features, stats, feature_cols)
    x = np.array([[normalized[col] for col in feature_cols]], dtype=np.float32)
    score = float(model.predict(x)[0])
    score = max(0.0, min(100.0, score))

    metrics = {
        "手型适配度": round(score, 1),
        "肤色显白度": round(65 + normalized["brightness_mean"] * 30 + normalized["style_color_warmth"] * 5, 1),
        "风格匹配度": round(60 + normalized["style_edge_roundness"] * 20 + normalized["style_gloss"] * 15, 1),
        "场景实用性": round(70 + (1 - normalized["style_complexity"]) * 18, 1),
        "整体美观度": round(score * 0.72 + normalized["style_gloss"] * 18 + normalized["brightness_std"] * 10, 1),
    }
    metrics = {k: max(0, min(100, v)) for k, v in metrics.items()}

    print(json.dumps({
        "score": round(score, 1),
        "metrics": metrics,
        "rawFeatures": raw_features,
        "normalizedFeatures": normalized,
        "styleCode": style_code,
        "styleName": style["style_name"],
        "model": model_path.name,
        "normalization": "p5/p95",
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
