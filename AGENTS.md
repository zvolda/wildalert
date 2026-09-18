# AGENTS.md — WildAlert

Guidance for AI agents working in this repo. Start here, then read
[`STATUS.md`](./STATUS.md) (current progress) and [`ROADMAP.md`](./ROADMAP.md) (the plan).

## What this is
Trail-cam email → recognize the animal (mostly B/W infrared night photos) → SMS the hunter.
Event-driven microservices: Kotlin/Spring Boot + one Python recognition service.

```
Email → email-ingestion (Kotlin) --ImageReceived--> Kafka
           → recognition (Python/DeepFaune) --AnimalRecognized--> Kafka
                → notification (Kotlin) → SMS (Twilio)
     user-account (Kotlin) — email→phone mapping, shared Postgres
```

## How we work
- **One reviewable slice at a time.** Build a small, self-contained change; verify it; hand off.
  Don't bundle unrelated changes.
- **Verify before claiming done.** Run the tests / exercise the endpoint; report real output.
- **Commits are made by the human, not the agent.** Make and verify changes, then stop.
  Commit messages are short and descriptive (e.g. "match hunter / sender").

## Core conventions
- **Swappable-interface pattern with a fake default, real impl chosen by config.** Examples:
  `ImageStore` (logging / R2), `SmsSender` (logging / Twilio), `EventPublisher`
  (logging / kafka), `Classifier` (stub / deepfaune). New integrations should follow this:
  interface + fake default + real impl gated by a config property, so the app runs with zero
  infra and tests use the fake.
- **Config via env vars with defaults**, bound in `application.yml` (`${VAR:default}`) and
  read through `@Value` / `@ConfigurationProperties` (Kotlin) or `os.getenv` (Python).
- **Events are broker-agnostic JSON** so Kafka can be swapped for managed Kafka / Cloud
  Pub/Sub in prod. Events carry an `eventId` for future idempotency.

## Build & test
- **Kotlin services** (JDK 25, Gradle wrapper):
  - `./gradlew :services:<name>:test` — run a service's tests
  - `./gradlew :services:<name>:bootRun` — run it (add `--args='--events.provider=kafka'` etc.)
  - `@SpringBootTest contextLoads` runs without external infra (producers/clients are lazy).
- **Recognition service** (Python 3.12+; venv in `services/recognition/.venv`):
  - `cd services/recognition && pytest` — tests run against the **stub** classifier (no ML deps)
  - The real DeepFaune model runs only in the Docker image
    (`docker build -t wildalert-recognition services/recognition`); it needs Python 3.12
    (torch has no 3.14 wheels) and is enabled by `RECOGNITION_CLASSIFIER=deepfaune`.
- **Local infra:** `docker compose up -d` (Postgres + Kafka + Kafka UI). Docker Engine runs in
  WSL2 (no Docker Desktop).

## Local-dev environment gotchas (this machine)
- WSL2 shuts the distro down between separate shell invocations, killing running containers —
  do multi-step Docker work in ONE session.
- The Windows host **can** reach WSL2 Docker published ports (verified 2026-09-17:
  `Test-NetConnection localhost -Port 5432` → True, and `:services:user-account:test`
  `contextLoads` passes over real JDBC). An earlier note here claimed the opposite — it was wrong.
  If a connection fails, check the container is actually running before blaming networking.

## Key decisions (see STATUS.md for rationale)
- Recognition model is **DeepFaune** (not SpeciesNet) — targets European animals.
- Recognition & SMS costs: SMS dominates (~90%); models are free/open, self-hosted.
