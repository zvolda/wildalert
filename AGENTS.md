# AGENTS.md — WildAlert

Guidance for AI agents working in this repo. Start here, then read
[`STATUS.md`](./STATUS.md) (current progress) and [`ROADMAP.md`](./ROADMAP.md) (the plan).

## What this is
Trail-cam email → recognize the animal (mostly B/W infrared night photos) → SMS the hunter.
Event-driven microservices: Kotlin/Spring Boot + one Python recognition service.

```
Email → email-ingestion (Kotlin) --ImageReceived--> Pub/Sub
           → recognition (Python/DeepFaune) --AnimalRecognized--> Pub/Sub
                → notification (Kotlin) → SMS (Twilio)
                → detection-history (Kotlin/jOOQ) → Postgres   [second subscription, fan-out]
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
  (logging / pubsub), `Classifier` (stub / deepfaune). New integrations should follow this:
  interface + fake default + real impl gated by a config property, so the app runs with zero
  infra and tests use the fake.
- **Config via env vars with defaults**, bound in `application.yml` (`${VAR:default}`) and
  read through `@Value` / `@ConfigurationProperties` (Kotlin) or `os.getenv` (Python).
- **Events are broker-agnostic JSON** — the transport is **Cloud Pub/Sub**, and the consumers are
  plain HTTP endpoints receiving push subscriptions, so no consumer needs a Pub/Sub client at all.
  Events carry an `eventId`; delivery is at-least-once, and notification dedupes on
  `sourceEventId`.

## Build & test
- **Kotlin services** (JDK 25, Gradle wrapper):
  - `./gradlew :services:<name>:test` — run a service's tests
  - `./gradlew :services:<name>:bootRun` — run it (`SPRING_PROFILES_ACTIVE=pubsub` for the real
    transport; the `pubsub` profile sets `events.provider` and the GCP auto-configuration together,
    which must stay off by default or startup requires GCP credentials)
  - `@SpringBootTest contextLoads` runs without external infra (producers/clients are lazy).
- **jOOQ (detection-history only):** the generated tables come from the Flyway migrations via
  `DDLDatabase` (replayed into in-memory H2), so **no database is needed to build**. `generateJooq`
  runs automatically before `compileKotlin`; change a migration and the Kotlin referencing that
  column stops compiling. `JooqDetectionStoreSqlTest` asserts the rendered SQL with no connection.
- **Recognition service** (Python 3.12+; venv in `services/recognition/.venv`):
  - `cd services/recognition && pytest` — tests run against the **stub** classifier (no ML deps)
  - The real DeepFaune model runs only in the Docker image
    (`docker build -t wildalert-recognition services/recognition`); it needs Python 3.12
    (torch has no 3.14 wheels) and is enabled by `RECOGNITION_CLASSIFIER=deepfaune`.
- **Web app** (`web/` — React + TypeScript + Vite + Tailwind v4; **not** a Gradle module, so
  `./gradlew build` ignores it):
  - `cd web && npm install`, then `npm run dev` (http://localhost:5173)
  - `npm test` — Vitest, plain **node** environment (no jsdom, no DOM-testing library: the
    testable logic is kept in pure modules on purpose)
  - `npm run build` — `tsc --noEmit` then a real Vite build
  - It needs `user-account` on :8081; the browser calls same-origin `/api/*` and Vite's dev
    proxy forwards it, which is why there is no CORS config anywhere. See `web/README.md`.
  - **`npm install` does not work on the Oracle network** (see the gotcha below). Verify in the
    cloud instead: `gcloud builds submit web --config deploy/cloudbuild-web.yaml`, which installs,
    tests and builds on a `node:22` worker and deploys nothing. Note that config has to upgrade
    npm first — the npm bundled with `node:22` crashes on vitest's optional peer graph.
- **Local infra:** `docker compose up -d` (Postgres + the Pub/Sub emulator, topics and
  subscriptions included). Docker Engine runs in WSL2 (no Docker Desktop). Because the emulator
  pushes over HTTP, run the services in that same WSL2 session and bind uvicorn to `0.0.0.0`.

## Local-dev environment gotchas (this machine)
- WSL2 shuts the distro down between separate shell invocations, killing running containers —
  do multi-step Docker work in ONE session.
- **Corporate TLS interception breaks the npm registry.** This machine sits behind an
  **Oracle Secure Web Gateway** that re-signs TLS, and it presents *only* the leaf certificate —
  the issuing intermediate is neither sent nor present in the Windows certificate stores, so no
  chain can be built. Windows-native clients cope; Node does not (it ships its own CA bundle and
  has no `--use-system-ca` before Node 22), so npm fails with
  `UNABLE_TO_VERIFY_LEAF_SIGNATURE`. Adding the `oracle-ztna-root-ca` root to
  `NODE_EXTRA_CA_CERTS` is **not** enough, because the missing certificate is the intermediate.
  Beware a misleading symptom: `npm view <pkg>` succeeds for packages already in
  `%LOCALAPPDATA%\npm-cache` and fails only for new ones, which looks package-specific but is not.
  **Decided 2026-10-26: no TLS bypass.** Do not set `strict-ssl false` or
  `NODE_TLS_REJECT_UNAUTHORIZED=0` on this machine, and do not propose it. `npm install` is run
  **from outside the corporate network** instead; `node_modules/` and `package-lock.json` then
  persist, so everything afterwards (dev server, tests, build) is local and works on-network. The
  properly clean alternative, if it is ever wanted, is the gateway CA certificate from Oracle IT
  pointed at by `npm config set cafile`.
- The Windows host **can** reach WSL2 Docker published ports (verified 2026-09-17:
  `Test-NetConnection localhost -Port 5432` → True, and `:services:user-account:test`
  `contextLoads` passes over real JDBC). An earlier note here claimed the opposite — it was wrong.
  If a connection fails, check the container is actually running before blaming networking.

## Key decisions (see STATUS.md for rationale)
- Recognition model is **DeepFaune** (not SpeciesNet) — targets European animals.
- Recognition & SMS costs: SMS dominates (~90%); models are free/open, self-hosted.
