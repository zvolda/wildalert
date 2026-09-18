"""DeepFaune-backed classifier.

Runs the proper camera-trap pipeline: MegaDetector finds the animal, we crop the highest-
confidence box, and DeepFaune classifies that crop into a European species. Chosen over
SpeciesNet because the project targets European animals (see the bake-off in ../bakeoff).

The heavy dependencies (torch, PytorchWildlife) are imported lazily inside __init__ so this
module — and the light stub path used by tests — load fine without them installed. Only the
container image (Python 3.12 + torch) actually instantiates this class.
"""

import io

from app.classifier import Recognition


class DeepFauneClassifier:
    """Classifier implementation backed by MegaDetector + DeepFaune via PyTorch-Wildlife."""

    def __init__(self, device: str = "cpu", detector_conf: float = 0.2) -> None:
        # Imported here (not at module top) so importing this module needs no torch.
        import numpy as np
        from PytorchWildlife.models.classification import DeepfauneClassifier
        from PytorchWildlife.models.detection import MegaDetectorV6

        self._np = np
        self._detector_conf = detector_conf
        self._detector = MegaDetectorV6(device=device, pretrained=True, version="MDV6-yolov9-c")
        self._classifier = DeepfauneClassifier(device=device, class_name_lang="en")

    def classify(self, image_bytes: bytes) -> Recognition:
        from PIL import Image

        arr = self._np.array(Image.open(io.BytesIO(image_bytes)).convert("RGB"))
        crop = self._best_animal_crop(arr)
        res = self._classifier.single_image_classification(crop)
        return Recognition(species=res["prediction"], confidence=float(res["confidence"]))

    def _best_animal_crop(self, arr):
        """Crops the highest-confidence animal MegaDetector finds; falls back to the whole
        image when nothing is detected."""
        res = self._detector.single_image_detection(arr, det_conf_thres=self._detector_conf)
        dets = res.get("detections")
        if dets is None or len(dets.xyxy) == 0:
            return arr

        i = int(self._np.argmax(dets.confidence))
        x1, y1, x2, y2 = (int(round(v)) for v in dets.xyxy[i])
        h, w = arr.shape[:2]
        x1, y1, x2, y2 = max(0, x1), max(0, y1), min(w, x2), min(h, y2)
        if x2 <= x1 or y2 <= y1:
            return arr
        return arr[y1:y2, x1:x2]
