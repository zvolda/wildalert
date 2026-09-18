from fastapi.testclient import TestClient

from app.classifier import Recognition
from app.main import app, get_classifier

client = TestClient(app)

FILES = {"file": ("boar.png", b"\x89PNG\r\n\x1a\n fake image bytes", "image/png")}


def test_recognize_returns_species_confidence_and_confident_flag():
    # The default stub returns 0.97, comfortably above the 0.7 threshold.
    response = client.post("/recognize", files=FILES)

    assert response.status_code == 200
    body = response.json()
    assert body["species"] == "wild boar"
    assert 0.0 <= body["confidence"] <= 1.0
    assert body["low_confidence"] is False


def test_recognize_flags_low_confidence_result():
    class LowConfidenceClassifier:
        def classify(self, image_bytes: bytes) -> Recognition:
            return Recognition(species="unknown", confidence=0.2)

    app.dependency_overrides[get_classifier] = lambda: LowConfidenceClassifier()
    try:
        response = client.post("/recognize", files=FILES)
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    assert response.json()["low_confidence"] is True
