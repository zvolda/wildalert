"""WildAlert Recognition Service.

Consumes stored trail-camera images and classifies the animal (species + confidence)
using DeepFaune (see ../bakeoff for why DeepFaune over SpeciesNet). This module is the
FastAPI entry point. The classifier is selected by config: a fake stub by default (no ML
deps), or the real DeepFaune model when RECOGNITION_CLASSIFIER=deepfaune.
"""

from functools import lru_cache

from fastapi import Depends, FastAPI, UploadFile

from app.classifier import (
    Classifier,
    RecognitionResult,
    StubClassifier,
    apply_threshold,
)
from app.config import Settings, get_settings

app = FastAPI(title="WildAlert Recognition Service")


@lru_cache
def _load_deepfaune() -> Classifier:
    """Loads the DeepFaune model once (weights load is expensive) and reuses it."""
    from app.deepfaune import DeepFauneClassifier

    return DeepFauneClassifier()


def get_classifier() -> Classifier:
    """Provides the configured classifier: the real DeepFaune model or the fake stub."""
    if get_settings().classifier == "deepfaune":
        return _load_deepfaune()
    return StubClassifier()


@app.get("/health")
def health() -> dict[str, str]:
    """Liveness check used by the hosting platform and local dev."""
    return {"status": "ok", "service": "recognition"}


@app.post("/recognize", response_model=RecognitionResult)
async def recognize(
    file: UploadFile,
    classifier: Classifier = Depends(get_classifier),
    settings: Settings = Depends(get_settings),
) -> RecognitionResult:
    """Classifies an uploaded image, flagging low-confidence results below the threshold."""
    image_bytes = await file.read()
    result = classifier.classify(image_bytes)
    return apply_threshold(result, settings.confidence_threshold)
