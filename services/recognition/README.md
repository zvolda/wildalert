# Recognition Service (Python + DeepFaune)

Consumes stored trail-camera images and classifies the animal (species + confidence).
Written in Python (DeepFaune / PyTorch-Wildlife are Python); everything else in WildAlert is
Kotlin. Lives outside the Gradle build. Runs on port **8084** locally.

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
- `RECOGNITION_CLASSIFIER` (default `stub`) — `stub` (fake) or `deepfaune` (real model, Docker only).
- `RECOGNITION_IMAGE_SOURCE` (default `local`) — where stored images are read from by storage key:
  - `local` — reads `<RECOGNITION_IMAGE_ROOT>/<key>` (default root `data/images`). Point it at
    the **same folder** email-ingestion writes to with `STORAGE_PROVIDER=filesystem`; its default
    is `data/images` under the repo root, so from this directory use
    `RECOGNITION_IMAGE_ROOT=../../data/images`.
  - `r2` — Cloudflare R2 via boto3; needs `R2_ENDPOINT`, `R2_BUCKET`, `R2_ACCESS_KEY`,
    `R2_SECRET_KEY` (optional `R2_REGION`, default `auto`) — the same names email-ingestion uses.

This service reads real environment variables only (it does not load the repo's `.env`).

## Test

```bash
pytest
```

## Docker

Self-contained image (build context is this directory), CPU build:

```bash
docker build -t wildalert-recognition services/recognition
docker run -p 8084:8084 wildalert-recognition
```
