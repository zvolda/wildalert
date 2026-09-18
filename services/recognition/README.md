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

## Kafka worker

The same code also runs as a Kafka worker: it consumes `ImageReceived` from `image.received`,
fetches the image by its storage key, classifies it and publishes `AnimalRecognized` to
`animal.recognized`.

```bash
python -m app.worker
```

- The logic lives in `app/handler.py` (`handle(event)`); `app/worker.py` is only the Kafka
  adapter, so a different transport (e.g. a Pub/Sub push endpoint) can reuse `handle`.
- **At-least-once:** the offset is committed only after the result is delivered to Kafka, so a
  crash means the event is processed again. Downstream consumers deduplicate on `sourceEventId`.
- Messages that can never succeed (invalid JSON, image missing) are logged and skipped. Other
  failures stop the worker without committing, so the event is retried after a restart.
  Retries and a dead-letter topic come in the hardening slice.

`AnimalRecognized` JSON:
`{eventId, sourceEventId, hunterId, storageKey, species, confidence, lowConfidence, occurredAt}`

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

- Kafka worker: `KAFKA_BOOTSTRAP_SERVERS` (default `localhost:29092`; `kafka:9092` from a
  container on the compose network), `IMAGE_RECEIVED_TOPIC` (default `image.received`),
  `ANIMAL_RECOGNIZED_TOPIC` (default `animal.recognized`), `RECOGNITION_CONSUMER_GROUP`
  (default `recognition`).

This service reads real environment variables only (it does not load the repo's `.env`).

## Test

```bash
pytest
```

## Docker

Self-contained image (build context is this directory), CPU build:

```bash
docker build -t wildalert-recognition services/recognition
docker run -p 8084:8084 wildalert-recognition             # HTTP API
docker run wildalert-recognition python -m app.worker     # Kafka worker
```
