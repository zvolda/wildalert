# WildAlert — Trail-Camera Animal Recognition Platform

## Context

A hunter's trail camera photographs an animal when it approaches and emails the
photo to the hunter. The hunter forwards that email to us. We recognize the
animal in the attached photo (mostly **black-and-white / infrared night shots**)
and send the hunter an **SMS** with the animal's name, so they know what is at
that location right now (e.g. "wild boar").

This project has two goals at once:
1. Ship a **cheap, production-ready** service that can grow to **thousands of users**.
2. **Learn Kotlin + Spring Boot + microservices** (and React later) while building it.

Key realities established during planning:
- The photos are grayscale IR night images → generic cloud vision APIs are poor at
  this. A specialist trail-camera model is far more accurate.
- **Cost is dominated almost entirely by SMS**, not by AI or servers. The AI and
  idle servers are effectively free at low volume.
- No physical hardware / no heavy DevOps → everything runs on **managed cloud**
  (push a container, platform runs & scales it).

---

## Locked Decisions

| Area | Decision | Why |
|---|---|---|
| Language / framework | **Kotlin + Spring Boot** | Learning goal; industry standard, job-marketable |
| Recognition model | **Google SpeciesNet** (MegaDetector + EfficientNetV2), **cloud-hosted** as a container | Purpose-built for trail-cam IR/night photos; 2000+ species; no per-image fee; scales by replicas |
| Recognition language | **Python** microservice (polyglot is normal for microservices) | SpeciesNet is Python; everything else stays Kotlin |
| Precision safeguard | **Confidence threshold** — if unsure, send "low confidence" instead of guessing | Need correct results, not wild guesses |
| Email in | **Cloudflare Email Routing → webhook** | Free, unlimited forwarding |
| SMS out | **Twilio** (abstracted so it can be swapped for Plivo/Vonage), **free EU alphanumeric sender ID** | Best docs for learning; no monthly phone-number fee in EU |
| Image storage | **Cloudflare R2** (10 GB free, no egress) | Cheap; delete after processing |
| Architecture | **Event-driven microservices** | The domain (email → recognize → SMS) is naturally async; core learning goal |
| Hosting | **Managed platform, no server management** (exact one: see open item) | Scalability without DevOps |

## Open Decisions (to finalize as we build — sensible defaults chosen)

- [ ] **Message broker: Kafka now vs. later.** Kafka wanted, but a chat is pending.
      *Default:* build the code **broker-agnostic** and event-driven from day one; run a
      local Kafka in dev via Docker Compose; use **managed Kafka** (Upstash/Confluent free
      tier) in cloud. Finalize "Kafka vs. Cloud Pub/Sub" after the Kafka chat.
- [ ] **Hosting platform.** *Default:* **Google Cloud Run** (scale-to-zero = near-zero idle
      cost, scales to thousands, EU region). **Railway** is an easier alternative for the
      first deploy. Everything is Dockerized so switching is cheap.
- [ ] **SMS policy default.** *Recommendation:* start with **one SMS per recognized photo**,
      then add **smart alerts** (target species only, or a daily digest) as a cost-control +
      paid-tier feature.

---

## Architecture Overview

```
  Hunter forwards email
          |
          v
 [Cloudflare Email Routing] --webhook--> (1) Email Ingestion Service  (Kotlin/Spring Boot)
                                              | stores photo -> Cloudflare R2
                                              | publishes event: ImageReceived
                                              v
                                         [ Broker: Kafka ]
                                              |
                                              v
                                         (2) Recognition Service      (Python + SpeciesNet)
                                              | classifies species + confidence
                                              | publishes event: AnimalRecognized
                                              v
                                         [ Broker: Kafka ]
                                              |
                                              v
                                         (3) Notification Service     (Kotlin/Spring Boot)
                                              | looks up hunter's phone
                                              | sends SMS via Twilio

  (4) User/Account Service (Kotlin/Spring Boot) — maps email -> phone, subscriptions,
      later billing. React frontend talks to this. Shared PostgreSQL.
```

**Services:**
1. **Email Ingestion** (Kotlin) — receive webhook, extract attachment + sender, store image, emit `ImageReceived`.
2. **Recognition** (Python/SpeciesNet) — consume `ImageReceived`, classify, emit `AnimalRecognized{species, confidence}`.
3. **Notification** (Kotlin) — consume `AnimalRecognized`, look up phone, apply SMS policy, send SMS.
4. **User/Account** (Kotlin) — hunters, email→phone mapping, subscription/plan; API for the future React app.

**Shared infra:** PostgreSQL (managed), Kafka (managed), Cloudflare R2 (images).

---

## Build Plan (piece by piece — each phase is a reviewable unit)

**How we work:** each phase below is a **self-contained deliverable** to be
**code-reviewed before the next one starts**. Every phase has:
- a **Goal / what you learn**,
- a **Deliverable** (the reviewable artifact),
- **Tasks** (checkboxes), and
- a **Definition of Done (DoD)** — the acceptance criteria to review against.
We do not move on until the DoD is met and the review is approved.

Order is chosen for **learning momentum**: simplest Spring Boot services first,
then the async flow, then deploy, then scale.

---

### Phase 0 — Foundations
- **Goal / learn:** monorepo layout, Gradle Kotlin DSL, Docker, local dev environment.
- **Deliverable:** an empty-but-runnable repo skeleton + local infra that starts with one command.
- **Tasks:**
  - [ ] Monorepo layout: one folder per service
  - [ ] Gradle (Kotlin DSL) multi-module setup; Java/Kotlin toolchain
  - [ ] Dockerfile template per service
  - [ ] `docker-compose.yml` for local dev: Postgres + Kafka (+ Kafka UI)
  - [ ] Root README: how to run locally
- **DoD (review against):** `docker compose up` starts Postgres + Kafka cleanly; repo
  builds with `./gradlew build`; README lets a newcomer run it from scratch.

### Phase 1 — User/Account Service (first Spring Boot service)
- **Goal / learn:** Spring Boot + Kotlin basics — controllers, JPA, migrations, tests.
- **Deliverable:** a working REST service managing hunters (email → phone).
- **Tasks:**
  - [ ] Spring Boot + Kotlin project (Web, Data JPA, Validation, Flyway)
  - [ ] Entity + Flyway migration: hunter (email, phone, plan, active)
  - [ ] REST endpoints: register / update / get hunter
  - [ ] Postgres via Docker Compose
  - [ ] Unit + integration tests (Testcontainers)
- **DoD (review against):** endpoints work end-to-end against Postgres; input validation
  present; migrations run clean; tests green; no secrets in code.

### Phase 2 — Notification (SMS) Service
- **Goal / learn:** second service, integrating an external API cleanly (interface + config).
- **Deliverable:** a service that sends an SMS via Twilio behind a swappable interface.
- **Tasks:**
  - [ ] Spring Boot + Kotlin service
  - [ ] Twilio integration behind a `SmsSender` interface (swappable)
  - [ ] Configure **EU alphanumeric sender ID** (no phone-number rental)
  - [ ] Endpoint to send a test SMS
  - [ ] SMS policy logic (confidence threshold, "one per photo" to start)
- **DoD (review against):** a real test SMS is delivered; Twilio hidden behind `SmsSender`
  (a fake impl works in tests); credentials via config/secrets; policy logic unit-tested.

### Phase 3 — Email Ingestion Service
- **Goal / learn:** receiving webhooks, MIME parsing, object storage, emitting events.
- **Deliverable:** a service that turns a forwarded email into a stored image + an event.
- **Tasks:**
  - [ ] Cloudflare Email Routing → domain → webhook to this service
  - [ ] Parse MIME, extract image attachment + sender address
  - [ ] Match sender to hunter (via User/Account service)
  - [ ] Upload image to Cloudflare R2
  - [ ] Emit `ImageReceived` event
- **DoD (review against):** forwarding a real email stores the photo in R2 and publishes a
  valid `ImageReceived` event; unknown senders handled gracefully; malformed emails don't crash it.

### Phase 4 — Recognition Service (Python + SpeciesNet, cloud CPU)
- **Goal / learn:** a polyglot microservice; running an ML model; confidence handling.
- **Deliverable:** a service that classifies a stored image into species + confidence.
- **Tasks:**
  - [ ] Python service wrapping SpeciesNet (MegaDetector + classifier)
  - [ ] Load model; inference on a stored image → species + confidence
  - [ ] Containerize (CPU build; document GPU path for later)
  - [ ] Consume `ImageReceived`, emit `AnimalRecognized{species, confidence}`
  - [ ] Validate accuracy on sample **black-and-white night** photos
- **DoD (review against):** given a real B/W night photo, returns the correct species above
  threshold (and "low confidence" when appropriate); runs in a container; event schema matches.

### Phase 5 — Wire the Event-Driven Flow
- **Goal / learn:** event-driven microservices — topics, schemas, retries, idempotency.
- **Deliverable:** the full pipeline working end-to-end.
- **Tasks:**
  - [ ] Define Kafka topics + event schemas (broker-agnostic wrapper)
  - [ ] End-to-end: forwarded email → recognized → SMS delivered
  - [ ] Retries + dead-letter handling for failures
  - [ ] Idempotency (don't double-SMS on redelivery)
- **DoD (review against):** one forwarded email reliably produces exactly one correct SMS;
  a forced failure lands in the dead-letter path, not lost; redelivery does not double-send.

### Phase 6 — Deploy to Cloud (managed, no server management)
- **Goal / learn:** deploying containers to a managed platform + managed data services.
- **Deliverable:** the whole system running in the cloud on a real domain.
- **Tasks:**
  - [ ] Managed Postgres (Neon/Supabase/Railway)
  - [ ] Managed Kafka (Upstash/Confluent) — *pending Kafka chat*
  - [ ] Deploy all containers (Cloud Run or Railway), scale-to-zero where possible
  - [ ] Domain + Cloudflare Email Routing pointed at deployed ingestion
  - [ ] Secrets/config management; environment separation
- **DoD (review against):** forwarding an email to the real address delivers an SMS in prod;
  no secrets in the repo; services scale to zero when idle.

### Phase 7 — Hardening & Cost Control
- **Goal / learn:** production concerns — thresholds, observability, cost levers.
- **Deliverable:** a robust, cost-controlled service.
- **Tasks:**
  - [ ] Tune confidence threshold on real photos
  - [ ] **Smart SMS** options: target-species-only + daily digest
  - [ ] Structured logging + basic metrics/alerts
  - [ ] Basic auth on internal/admin endpoints
- **DoD (review against):** smart-SMS mode measurably cuts SMS count; logs/metrics let you
  trace a request end-to-end; admin endpoints not publicly open.

### Phase 8 — React Frontend (later)
- **Goal / learn:** React + talking to your Kotlin API.
- **Deliverable:** a hunter-facing web app.
- **Tasks:**
  - [ ] Hunter signup + phone verification
  - [ ] Dashboard: detection history, photos, species
  - [ ] Plan/subscription management
- **DoD (review against):** a hunter can self-register, verify their phone, and see history.

### Phase 9 — Scale & Production Readiness (later)
- **Goal / learn:** auth, billing, scaling to thousands.
- **Deliverable:** a monetizable, scalable product.
- **Tasks:**
  - [ ] Authentication/authorization for users
  - [ ] Billing (Stripe) tied to SMS usage
  - [ ] GPU inference option for burst volume
  - [ ] Monitoring/observability, load testing to "thousands of users"
  - [ ] Migration path to Kubernetes if/when justified
- **DoD (review against):** load test sustains target volume; billing charges correctly;
  auth protects user data.

---

## Cost Model (approximate, 2026)

**Fixed baseline (any number of users): ~$0–7/month**
- Domain ~$1/mo · managed Postgres $0–5 · managed Kafka $0 (free tier) · R2 ~$0 · idle Cloud Run ~$0

**Per user (dominated by SMS)** — assuming ~10 photos/day (~300/mo):
- Email in: $0 · Recognition (Cloud Run CPU): ~$0.15 · Storage+DB+broker share: ~$0.03
- **SMS: 300 × ~$0.008 ≈ $2.40 (~90% of cost)**
- **Typical total ≈ $2.60/user/mo** (Light ≈ $0.85, Heavy ≈ $7.40)

**Lever:** smart SMS rules (target species / digest) can cut a typical user to **< $1/mo**.

---

## Verification (how we prove each piece works)

- **Per service:** unit + integration tests (Testcontainers for Postgres/Kafka).
- **Recognition accuracy:** run SpeciesNet against a folder of real B/W night trail-cam
  photos; confirm correct species above the confidence threshold.
- **End-to-end:** forward a real email with a photo attachment → confirm an SMS arrives
  with the correct animal name (or "low confidence" when appropriate).
- **Cost/scale:** load-test the flow; confirm scale-to-zero idle cost and that adding
  replicas handles bursts.

---

## Next Immediate Step

Start with **Phase 0 + Phase 1** (repo skeleton + first Spring Boot service) — the fastest
hands-on Kotlin/Spring learning and a working, testable piece on day one.
