"""Model accuracy bake-off over the sample photos.

Reads images from a samples directory, derives the true species from each filename (letters
only, so 'boar2.jpg' -> 'boar'), runs the DeepFaune classifier, and prints prediction vs
truth plus a top-1 accuracy summary. A first sanity read on real photos, not a verdict.

Usage (inside the container):  python /work/run.py /samples
"""

import sys
from pathlib import Path

from PytorchWildlife.models.classification import DeepfauneClassifier

# Map our short filename labels onto DeepFaune's English class names.
ALIASES = {
    "boar": "wild boar",
    "wildboar": "wild boar",
    "sanglier": "wild boar",
    "fox": "fox",
    "renard": "fox",
    "deer": "red deer",
    "reddeer": "red deer",
    "roedeer": "roe deer",
}

IMAGE_EXTS = {".jpg", ".jpeg", ".png"}


def true_label_from_filename(path: Path) -> str:
    token = "".join(ch for ch in path.stem.lower() if ch.isalpha())
    return ALIASES.get(token, token)


def is_match(prediction: str, truth: str) -> bool:
    return prediction == truth or truth in prediction or prediction in truth


def main(samples_dir: str) -> None:
    root = Path(samples_dir)
    images = sorted(p for p in root.rglob("*") if p.suffix.lower() in IMAGE_EXTS)
    if not images:
        print(f"No images found under {root}")
        return

    print("Loading DeepFaune classifier (downloads weights on first run)...")
    clf = DeepfauneClassifier(device="cpu", class_name_lang="en")

    correct = 0
    print()
    print(f"{'file':<28} {'true':<12} {'prediction':<14} {'conf':>6}  ok")
    print("-" * 72)
    for img in images:
        truth = true_label_from_filename(img)
        res = clf.single_image_classification(str(img), img_id=img.name)
        pred, conf = res["prediction"], res["confidence"]
        ok = is_match(pred, truth)
        correct += int(ok)
        print(f"{img.name:<28} {truth:<12} {pred:<14} {conf:>6.2f}  {'Y' if ok else '.'}")

    print("-" * 72)
    print(f"DeepFaune top-1 accuracy: {correct}/{len(images)} = {correct / len(images):.0%}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "/samples")
