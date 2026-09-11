"""WildAlert Recognition Service.

Consumes stored trail-camera images and classifies the animal (species + confidence)
using SpeciesNet. This module is the FastAPI entry point; for now it exposes only a
health check — model inference is added in a later slice.
"""

from fastapi import FastAPI

app = FastAPI(title="WildAlert Recognition Service")


@app.get("/health")
def health() -> dict[str, str]:
    """Liveness check used by the hosting platform and local dev."""
    return {"status": "ok", "service": "recognition"}
