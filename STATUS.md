# WildAlert — Project Status

_Last updated: 2026-09-15. Companion to [`ROADMAP.md`](./ROADMAP.md): ROADMAP is the plan,
this is where we actually are. Work proceeds one reviewable slice at a time._

## What it is
Hunter forwards a trail-cam email → we recognize the animal (mostly B/W infrared night
photos) → text the hunter the species via SMS. Event-driven microservices: Kotlin/Spring Boot
+ one Python recognition service. Dual goal: ship cheap & scalable, and learn
Kotlin/Spring/microservices.

## Architecture
```
Email → [Email Ingestion (Kotlin)] --ImageReceived--> [Kafka]
             → [Recognition (Python/DeepFaune)] --AnimalRecognized--> [Kafka]
                  → [Notification (Kotlin)] → SMS (Twilio)
        [User/Account (Kotlin)] — email→phone mapping, shared Postgres
```

## Done
- **Phase 0 — Foundations:** monorepo, Gradle Kotlin DSL multi-module, `compose.yml`
  (Postgres + Kafka + Kafka UI), README, `.env.example`.
- **Phase 1 — User/Account:** `Hunter` entity, Flyway migration, register/get/update
  endpoints, lookup-by-email (`GET /api/hunters/by-email`), email normalization
  (lowercase/trim), validation, tests.
- **Phase 2 — Notification (SMS):** `SmsSender` interface + `TwilioSmsSender` (real) /
  `LoggingSmsSender` (fake default), controller, tests.
- **Phase 3 — Email Ingestion:** MIME parser; image storage (`ImageStore` → R2 real /
  logging fake); sender→hunter matching via `HunterLookupClient` (Spring RestClient);
  `ImageReceived` event via `EventPublisher` (logging fake / Kafka). Tested.
- **Phase 4 — Recognition (Python/FastAPI):** `/health`, `/recognize`
  (`{species, confidence, low_confidence}`), confidence-threshold safeguard, DeepFaune wired
  in behind a `Classifier` interface (MegaDetector→crop→DeepFaune), CPU Docker image with
  weights baked in, plus bake-off tooling. Verified end-to-end on real photos.

## In progress — Phase 5 (Kafka wiring)
- **Slice 1 (done):** email-ingestion `KafkaEventPublisher` publishes `ImageReceived` (JSON)
  to topic `image.received`. Unit-tested; verified with a real broker round-trip.
- **Remaining slices:**
  1. Recognition consumer/producer (Python): consume `image.received` → DeepFaune →
     publish `animal.recognized`.
  2. Notification consumer (Kotlin): consume `animal.recognized` → SMS policy → send SMS.
  3. Hardening: retries, dead-letter, idempotency (events already carry `eventId`).

## Remaining phases
- **6 — Deploy** to managed cloud (Cloud Run + managed Postgres + managed Kafka/Pub-Sub + R2
  + Cloudflare Email Routing).
- **7 — Hardening & cost control** (tune threshold, smart-SMS to cut cost, logging/metrics,
  admin auth).
- **8 — React frontend.**
- **9 — Scale / auth / billing.**

## Key decisions
- **Recognition model = DeepFaune** (not SpeciesNet) — targets European animals; chosen via a
  bake-off on real photos (DeepFaune gave specific species; SpeciesNet gave vague
  families/global bias). Both are free/open, self-hosted.
- **Broker-agnostic events** (JSON) so Kafka local ↔ managed Kafka **or** Cloud Pub/Sub in
  prod is a config/impl swap. Pub/Sub free tier is the likely cheapest prod option on GCP.
- **Swappable-interface pattern everywhere:** `ImageStore` (R2/logging), `SmsSender`
  (Twilio/logging), `EventPublisher` (kafka/logging), `Classifier` (deepfaune/stub) — fake
  default, real impl via config.
- Recognition & SMS are cheap/free; **SMS dominates cost** (~90%).

## Known gaps / follow-ups
- **`HunterLookupClient` resilience:** only catches 404; if user-account is down, the
  ingestion webhook 500s and no event is created. Fix = also catch connection errors → treat
  as no-match (degrade gracefully). _Not yet done._
- **Recognition first-request latency:** model loads on first `/recognize` (~1 min); could
  warm at startup.
- **Local-dev env limits (this machine):** WSL2 shuts the distro down between commands
  (kills containers), and the Windows host can't reach WSL2 Docker ports — so Windows-JVM
  apps/Testcontainers can't hit WSL Kafka/Postgres. Verify integration inside WSL;
  deployment is unaffected. Local fix: WSL `networkingMode=mirrored`.

## Tech stack
Kotlin + Spring Boot (Web, JPA, Flyway, Validation, Spring Kafka, RestClient), JDK 25 ·
Python 3.12 + FastAPI + PyTorch-Wildlife (DeepFaune/MegaDetector) · Postgres 17 ·
Apache Kafka 3.8 · Cloudflare R2 (S3 SDK) · Twilio · Docker (Engine in WSL2 locally, managed
cloud in prod).
