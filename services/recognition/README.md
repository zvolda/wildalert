# Recognition Service (Python + SpeciesNet)

Consumes stored trail-camera images and classifies the animal (species + confidence).
Written in Python (SpeciesNet is Python); everything else in WildAlert is Kotlin. Lives
outside the Gradle build. Runs on port **8084** locally.

Currently this is the service skeleton with a `/health` check only — model inference is
added in a later slice.

## Setup

From this directory (`services/recognition`), create a virtualenv and install deps:

```bash
python -m venv .venv
# Windows (Git Bash):   source .venv/Scripts/activate
# Windows (PowerShell): .venv\Scripts\Activate.ps1
# Linux/macOS:          source .venv/bin/activate
pip install -r requirements-dev.txt
```

## Run

```bash
uvicorn app.main:app --reload --port 8084
```

- Health: http://localhost:8084/health
- Recognize: `POST /recognize` (multipart image) → `{species, confidence, low_confidence}`
- Interactive API docs (FastAPI): http://localhost:8084/docs

## Configuration

- `RECOGNITION_CONFIDENCE_THRESHOLD` (default `0.7`) — results below this are flagged
  `low_confidence: true`, so we never assert a species we're unsure of.

## Test

```bash
pytest
```
