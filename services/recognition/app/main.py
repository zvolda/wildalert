"""WildAlert Recognition Service.

Consumes stored trail-camera images and classifies the animal (species + confidence)
using DeepFaune (see ../bakeoff for why DeepFaune over SpeciesNet). This module is the
FastAPI entry point. The classifier is selected by config: a fake stub by default (no ML
deps), or the real DeepFaune model when RECOGNITION_CLASSIFIER=deepfaune.
"""

from fastapi import Depends, FastAPI, UploadFile

from app.classifier import (
    Classifier,
    RecognitionResult,
    apply_threshold,
    get_classifier,
)
from app.config import Settings, get_settings

app = FastAPI(title="WildAlert Recognition Service")


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
