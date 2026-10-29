"""WildAlert Recognition Service.

Consumes stored trail-camera images and classifies the animal (species + confidence)
using DeepFaune (see ../bakeoff for why DeepFaune over SpeciesNet). This module is the
FastAPI entry point, serving both /recognize (an uploaded file) and the Pub/Sub push
endpoint in app.push (a stored image, named by an ImageReceived event). The classifier is
selected by config: a fake stub by default (no ML deps), or the real DeepFaune model when
RECOGNITION_CLASSIFIER=deepfaune.
"""

import logging
import os
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, UploadFile

from app.classifier import (
    Classifier,
    RecognitionResult,
    apply_threshold,
    get_classifier,
)
from app.config import Settings, get_settings
from app.credit import get_credit_client
from app.push import router as push_router

# uvicorn configures only its own loggers, so without this the service's own INFO lines (the push
# endpoint, the publisher) are dropped by the root logger's default WARNING level — the Kafka worker
# used to call basicConfig in its main().
logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO"),
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)

log = logging.getLogger("recognition")


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Loads the model and builds the credit client before the service accepts traffic.

    DeepFaune takes about a minute to load. Doing it here means Cloud Run's startup probe holds
    traffic until it is ready, instead of the first pushed message waiting on the weights and
    risking its acknowledgement deadline.

    The credit client is built here for a different reason: it mints the ID token for user-account,
    and a credential that cannot be minted should fail the revision at startup rather than turn up
    as a 500 on the first real photo. It opens no connection.
    """
    log.info("Loading classifier at startup")
    get_classifier()
    get_credit_client()
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
