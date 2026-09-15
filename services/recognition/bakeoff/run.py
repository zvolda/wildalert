"""Model accuracy bake-off over the sample photos.

Reads images from a samples directory, derives the true species from each filename (letters
only, so 'boar2.jpg' -> 'boar'), runs the DeepFaune classifier, and prints prediction vs
truth plus a top-1 accuracy summary. A first sanity read on real photos, not a verdict.

Usage (inside the container):  python /work/run.py /samples
"""

import re
import sys
from pathlib import Path

from PytorchWildlife.models.classification import DeepfauneClassifier

# Map our filename labels onto DeepFaune's English class names.
ALIASES = {
    "boar": "wild boar",
    "sanglier": "wild boar",
    "renard": "fox",
}

# Species DeepFaune (European taxonomy) has no class for — can't be right by design.
OUT_OF_TAXONOMY = {"white tail deer", "white tailed deer", "whitetail deer"}

IMAGE_EXTS = {".jpg", ".jpeg", ".png"}


def true_label_from_filename(path: Path) -> str:
    stem = path.stem.lower()
    stem = re.sub(r"[_-]+", " ", stem)   # separators -> space
    stem = re.sub(r"\d+", "", stem)      # drop digits: "boar2" -> "boar"
    stem = re.sub(r"\s+", " ", stem).strip()
    return ALIASES.get(stem, stem)


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
    scored = 0
    print()
    print(f"{'file':<24} {'true':<18} {'prediction':<14} {'conf':>6}  ok")
    print("-" * 74)
    for img in images:
        truth = true_label_from_filename(img)
        res = clf.single_image_classification(str(img), img_id=img.name)
        pred, conf = res["prediction"], res["confidence"]
        if truth in OUT_OF_TAXONOMY:
            mark = "n/a"  # DeepFaune has no European class for this species
        else:
            scored += 1
            ok = is_match(pred, truth)
            correct += int(ok)
            mark = "Y" if ok else "."
        print(f"{img.name:<24} {truth:<18} {pred:<14} {conf:>6.2f}  {mark}")

    print("-" * 74)
    pct = f"{correct / scored:.0%}" if scored else "n/a"
    print(f"DeepFaune top-1 accuracy (in-taxonomy only): {correct}/{scored} = {pct}")
    if scored != len(images):
        print(f"({len(images) - scored} image(s) excluded as out-of-taxonomy for DeepFaune)")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "/samples")
