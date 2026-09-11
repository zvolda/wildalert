from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_recognize_returns_species_and_confidence():
    # The stub ignores the bytes, so any content works for now.
    files = {"file": ("boar.png", b"\x89PNG\r\n\x1a\n fake image bytes", "image/png")}

    response = client.post("/recognize", files=files)

    assert response.status_code == 200
    body = response.json()
    assert body["species"] == "wild boar"
    assert 0.0 <= body["confidence"] <= 1.0
