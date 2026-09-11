"""WildAlert Recognition Service.

Consumes stored trail-camera images and classifies the animal (species + confidence)
using SpeciesNet. This module is the FastAPI entry point. Classification currently runs
through a stub; a real model is wired in behind the Classifier interface in a later slice.
"""

from fastapi import Depends, FastAPI, UploadFile

from app.classifier import Classifier, Recognition, StubClassifier

app = FastAPI(title="WildAlert Recognition Service")


def get_classifier() -> Classifier:
    """Provides the classifier implementation. Swap the stub for a real model here later."""
    return StubClassifier()


@app.get("/health")
def health() -> dict[str, str]:
    """Liveness check used by the hosting platform and local dev."""
    return {"status": "ok", "service": "recognition"}


@app.post("/recognize", response_model=Recognition)
async def recognize(
    file: UploadFile,
    classifier: Classifier = Depends(get_classifier),
) -> Recognition:
    """Classifies an uploaded image into a species + confidence."""
    image_bytes = await file.read()
    return classifier.classify(image_bytes)
