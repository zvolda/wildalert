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

## Pub/Sub push endpoint

`POST /events/image-received` is where events arrive: Cloud Pub/Sub pushes an `ImageReceived`, the
service fetches the image by its storage key, **charges the hunter for it**, classifies it and
publishes `AnimalRecognized` to `animal.recognized`. There is no separate worker process — one
FastAPI app serves both this and `/recognize`.

The charge comes **before** the model runs, because charging afterwards would let a drained account
buy a free DeepFaune run — the cost this debit exists to protect. A photo nobody pays for (an
address that is no hunter's, an empty balance, a switched-off hunter) is acked and dropped: it is
never classified, no `AnimalRecognized` exists, and nobody is texted.

**The HTTP status is the acknowledgement**, so the retry and dead-letter policy lives in the
subscription rather than in code:

| Status | Meaning | What Pub/Sub does |
|---|---|---|
| 204 | handled, result published — **or nobody paid, so the photo was not classified** | acks |
| 400 | not a valid `ImageReceived`, never will be | retries, then dead-letters with the original bytes |
| 404 | no image stored under that key | retries (so a slow storage write gets another chance), then dead-letters |
| 500 | storage error, model failure, publish failure | retries with backoff, then dead-letters |

- The logic lives in `app/handler.py` (`handle(event)`); `app/push.py` is only the transport
  adapter and `app/publisher.py` only the output side, so neither the use case nor the model
  knows Pub/Sub exists.
- **At-least-once, and a redelivery re-runs classification:** the same `ImageReceived` yields a new
  `eventId` with the same `sourceEventId`. Notification deduplicates on `sourceEventId`, which is
  what stops a hunter getting two texts.
- The model is loaded at **startup** (FastAPI lifespan), not on the first request, so a pushed
  message never waits a minute for the weights and risks its acknowledgement deadline.
- Locally the Pub/Sub emulator pushes to this endpoint — start uvicorn with `--host 0.0.0.0`, or
  the emulator container cannot reach it.

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

- `RECOGNITION_EVENTS_PROVIDER` (default `logging`) — `logging` (fake, just logs the result) or
  `pubsub` (publishes for real). With the default, the push endpoint still works end to end;
  only the result goes to the log instead of a topic.
- `ANIMAL_RECOGNIZED_TOPIC` (default `animal.recognized`) — the topic results are published to.
  Which topic *feeds* this service is the subscription's business, so there is no input setting.
- `PUBSUB_PROJECT_ID` (or `GOOGLE_CLOUD_PROJECT`, which Cloud Run sets) — required when the
  provider is `pubsub`.
- `PUBSUB_EMULATOR_HOST` (e.g. `localhost:8085`) — the Python client honours this automatically,
  so local dev needs no credentials.

Credit (every pushed photo is charged before the model runs — see `app/credit.py`):

- `RECOGNITION_COST_MINOR` (default `100`) — what one classified photo costs the hunter, in CZK
  haléře. There is deliberately **no way to switch charging off**: forgetting a variable must not
  hand out free inference, and `0` is rejected at startup.
- `USER_ACCOUNT_BASE_URL` (default `http://localhost:8081`) — user-account, which holds the
  balance. The push endpoint needs it; `/recognize` (an uploaded file) does not and charges
  nothing.
- `USER_ACCOUNT_AUTH` (default `none`) — set to `gcp-id-token` in the cloud, where user-account
  runs with Cloud Run's *Require authentication*. The audience is `USER_ACCOUNT_BASE_URL`, so that
  value must match the target service's URL string exactly.

This service reads real environment variables only (it does not load the repo's `.env`).

## Test

```bash
pytest
```

## Docker

Self-contained image (build context is this directory), CPU build:

```bash
docker build -t wildalert-recognition services/recognition
docker run -p 8084:8084 wildalert-recognition   # /recognize + the Pub/Sub push endpoint
```
