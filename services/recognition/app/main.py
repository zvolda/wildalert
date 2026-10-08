"""WildAlert Recognition Service.

Consumes stored trail-camera images and classifies the animal (species + confidence)
using DeepFaune (see ../bakeoff for why DeepFaune over SpeciesNet). This module is the
FastAPI entry point, serving both /recognize (an uploaded file) and the Pub/Sub push
endpoint in app.push (a stored image, named by an ImageReceived event). The classifier is
selected by config: a fake stub by default (no ML deps), or the real DeepFaune model when
RECOGNITION_CLASSIFIER=deepfaune.
"""

import logging
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, UploadFile

from app.classifier import (
    Classifier,
    RecognitionResult,
    apply_threshold,
    get_classifier,
)
from app.config import Settings, get_settings
from app.push import router as push_router

log = logging.getLogger("recognition")


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Loads the model before the service accepts traffic.

    DeepFaune takes about a minute to load. Doing it here means Cloud Run's startup probe holds
    traffic until it is ready, instead of the first pushed message waiting on the weights and
    risking its acknowledgement deadline.
    """
    log.info("Loading classifier at startup")
    get_classifier()
    yield


app = FastAPI(title="WildAlert Recognition Service", lifespan=lifespan)
app.include_router(push_router)


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
