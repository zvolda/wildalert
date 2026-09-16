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
| Recognition model | **DeepFaune** (MegaDetector V6 finds the animal → crop → DeepFaune classifies), via PyTorch-Wildlife, **cloud-hosted** as a container | Trained on European camera-trap species; won a bake-off on real photos (specific species, where SpeciesNet gave vague families / global bias); free & open, no per-image fee; scales by replicas |
| Recognition language | **Python** microservice (polyglot is normal for microservices) | DeepFaune / PyTorch-Wildlife are Python; everything else stays Kotlin |
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
      *Trade-off to weigh (decide in Phase 6):* a Kafka consumer **pulls**, so on Cloud Run it
      needs min-instances=1 with always-on CPU — it **cannot scale to zero** (paid 24/7; the
      recognition service is the costly one since DeepFaune needs several GB RAM). **Cloud
      Pub/Sub push** calls an HTTP endpoint per event, which wakes Cloud Run → keeps
      scale-to-zero, with built-in retries + dead-letter; cost is a cold start (~1 min model
      load) on the first photo after idle. Kafka stays the better fit on always-on hosting
      (GKE, a VM, Railway). Keep consumer logic in a transport-free `handle(event)` so the
      Kafka loop vs. push endpoint is a thin adapter. (Also re-check managed-Kafka free tiers —
      Upstash is believed to have discontinued Kafka.)
- [ ] **Managed Postgres.** **Cloud SQL** (recognized GCP skill, but no free tier and no
      scale-to-zero — a few $/month minimum) vs. **Neon/Supabase** (free tier, cheaper idle).
      *Leaning:* Cloud SQL for the hands-on experience, if the small fixed cost is acceptable.
- [ ] **Hosting platform.** *Default:* **Google Cloud Run** (scale-to-zero = near-zero idle
      cost, scales to thousands, EU region). **Railway** is an easier alternative for the
      first deploy. Everything is Dockerized so switching is cheap.
- [ ] **SMS policy default.** *Recommendation:* start with **one SMS per recognized photo**,
      then add **smart alerts** (target species only, or a daily digest) as a cost-control +
      paid-tier feature.

---

## Learning Targets (technologies to get hands-on with — only where they genuinely fit)

| Technology | Status / fit | Where |
|---|---|---|
| **Spring Boot + PostgreSQL** | In use (3 services, Flyway). Deepen with a detection-history table and the **transactional outbox** pattern. | Phase 5, 8 |
| **jOOQ** | Good fit for SQL-heavy reads (dashboard/reporting). Keep user-account on JPA; use jOOQ in the detection-history service to compare both. Generate code from Flyway SQL via `DDLDatabase` (no live DB needed — avoids the Windows↔WSL2 Docker port issue). | Phase 8 |
| **S3-compatible storage** | In use (`R2ImageStore`, S3 SDK). Deepen: boto3 fetch in recognition, presigned URLs, lifecycle rules, optional local S3-compatible server (check MinIO's current distribution/licensing; Garage/SeaweedFS as alternatives). GCS also offers an S3-compatible XML API (HMAC keys). | Phase 5, 7, 8 |
| **Cloud Run** | Strong fit — default hosting for all containers. | Phase 6 |
| **Cloud SQL** | Good fit, small fixed cost (see Open Decisions). | Phase 6 |
| **Cloud Run functions** (formerly Cloud Functions) | Good fit for small event/scheduled jobs: daily digest SMS, old-image cleanup. | Phase 7 |
| **GKE** | Weak fit for prod (overkill + idle cost for 4 services). Learn deliberately: local `kind`/`k3s` in WSL2, then a short-lived GKE Autopilot deploy that is torn down. Natural home for always-on Kafka consumers. | Phase 9 |

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
                                         (2) Recognition Service      (Python + DeepFaune)
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
2. **Recognition** (Python/DeepFaune) — consume `ImageReceived`, classify, emit `AnimalRecognized{species, confidence}`.
3. **Notification** (Kotlin) — consume `AnimalRecognized`, look up phone, apply SMS policy, send SMS.
4. **User/Account** (Kotlin) — hunters, email→phone mapping, subscription/plan; API for the future React app.
5. **Detection History** (Kotlin, later — Phase 8) — stores every `AnimalRecognized`; jOOQ queries for the dashboard.

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
- **Goal / learn:** monorepo layout, Gradle Kotlin DSL, local containerized dev via the
  free Docker Engine running in WSL2.
- **Deliverable:** a repo skeleton that builds + local infra that starts with one command.
- **Tasks:**
  - [ ] Monorepo layout: one folder per service
  - [ ] Gradle (Kotlin DSL) multi-module setup; Java/Kotlin toolchain (JDK 25 already present)
  - [ ] Dockerfile template per service (OCI images)
  - [ ] `compose.yml` for local dev: Postgres + Kafka (+ Kafka UI)
  - [ ] Root README: one-time WSL2 + Docker Engine setup, then how to run locally
- **DoD (review against):** `docker compose up` starts Postgres + Kafka cleanly; repo
  builds with `./gradlew build`; README lets a newcomer run it from scratch.

> **Dev runtime:** Docker **Desktop** is avoided (company licensing). We use the free,
> Apache-2.0 **Docker Engine** inside **WSL2** — identical `docker`/`compose` commands, no
> license. WSL2 is already enabled on this machine; only an Ubuntu distro + Docker Engine
> need installing. Prod still uses managed services (see Phase 6). Testcontainers works
> because it talks to the WSL2 Docker socket.

### Phase 1 — User/Account Service (first Spring Boot service)
- **Goal / learn:** Spring Boot + Kotlin basics — controllers, JPA, migrations, tests.
- **Deliverable:** a working REST service managing hunters (email → phone).
- **Tasks:**
  - [ ] Spring Boot + Kotlin project (Web, Data JPA, Validation, Flyway)
  - [ ] Entity + Flyway migration: hunter (email, phone, plan, active)
  - [ ] REST endpoints: register / update / get hunter
  - [ ] Postgres via local `compose.yml` (Docker Engine in WSL2)
  - [ ] Unit + integration tests (Testcontainers, via the WSL2 Docker socket)
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

### Phase 4 — Recognition Service (Python + DeepFaune, cloud CPU)
- **Goal / learn:** a polyglot microservice; running an ML model; confidence handling.
- **Deliverable:** a service that classifies a stored image into species + confidence.
- **Tasks:**
  - [ ] Bake-off of candidate models on real photos (SpeciesNet vs. DeepFaune → DeepFaune chosen)
  - [ ] Python service wrapping DeepFaune behind a `Classifier` interface (MegaDetector → crop → DeepFaune)
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
  - [ ] `AnimalRecognized` schema: `eventId`, `sourceEventId`, `hunterId`, `storageKey`,
        `species`, `confidence`, `lowConfidence`, `occurredAt`
  - [ ] Recognition fetches image bytes by `storageKey`: Python `ImageSource` interface
        (local-folder fake / R2 via **boto3**) + a `FileSystemImageStore` in email-ingestion
        so the flow runs locally without cloud credentials
  - [ ] Consumer logic in a transport-free `handle(event)`; Kafka loop is a thin adapter
        (keeps the Pub/Sub-push option open)
  - [ ] End-to-end: forwarded email → recognized → SMS delivered
  - [ ] **Per-hunter inbound address** (e.g. `jan-7f3k9q@in.wildalert.app`): match the hunter by
        the email's **recipient**, not its `From` header — auto-forwarding rules keep the camera's
        address in `From`, and cameras can email us directly. user-account: `inbound_token`
        column (Flyway V2; address = token@`INBOUND_EMAIL_DOMAIN`), generated on register,
        `GET /api/hunters/by-inbound-address`; email-ingestion: match the envelope recipient
        (`?envelopeTo=` from the webhook), then `Delivered-To`/`X-Original-To`/`To`/`Cc`.
        `From` matching dropped (spoofable).
  - [ ] Retries + dead-letter handling for failures
  - [ ] Idempotency (don't double-SMS on redelivery)
  - [ ] **Transactional outbox** (Postgres) so a DB write and its event can't diverge
- **DoD (review against):** one forwarded email reliably produces exactly one correct SMS;
  a forced failure lands in the dead-letter path, not lost; redelivery does not double-send.

### Phase 6 — Deploy to Cloud (managed, no server management)
- **Goal / learn:** deploying containers to a managed platform + managed data services.
- **Deliverable:** the whole system running in the cloud on a real domain.
- **Tasks:**
  - [ ] Managed Postgres — **Cloud SQL** (preferred for experience) or Neon/Supabase
  - [ ] Broker — managed Kafka (Confluent) **or Cloud Pub/Sub push subscriptions** — *decide
        using the scale-to-zero trade-off in Open Decisions*
  - [ ] Deploy all containers to **Cloud Run** (or Railway), scale-to-zero where possible
  - [ ] Warm the recognition model at startup (reduce cold-start delay)
  - [ ] Domain + Cloudflare Email Routing pointed at deployed ingestion — **catch-all** on the
        inbound subdomain (`*@in.<domain>`) so every hunter's personal address reaches the webhook
  - [ ] Secrets/config management; environment separation
- **DoD (review against):** forwarding an email to the real address delivers an SMS in prod;
  no secrets in the repo; services scale to zero when idle.

### Phase 7 — Hardening & Cost Control
- **Goal / learn:** production concerns — thresholds, observability, cost levers.
- **Deliverable:** a robust, cost-controlled service.
- **Tasks:**
  - [ ] Tune confidence threshold on real photos
  - [ ] **Smart SMS** options: target-species-only + daily digest
  - [ ] Daily digest as a scheduled **Cloud Run function** (Cloud Scheduler trigger)
  - [ ] Automatic image expiry: storage **lifecycle rules** (R2/GCS) or a cleanup function
  - [ ] Structured logging + basic metrics/alerts
  - [ ] Basic auth on internal/admin endpoints
- **DoD (review against):** smart-SMS mode measurably cuts SMS count; logs/metrics let you
  trace a request end-to-end; admin endpoints not publicly open.

### Phase 8 — React Frontend (later)
- **Goal / learn:** React + talking to your Kotlin API.
- **Deliverable:** a hunter-facing web app.
- **Tasks:**
  - [ ] Hunter signup + phone verification
  - [ ] Show the hunter's personal inbound address with setup help (Gmail/Outlook forwarding rule,
        or entering it directly in the camera app)
  - [ ] **Detection History service** (Kotlin/Spring Boot + Postgres): consume
        `AnimalRecognized`, store detections; reporting queries with **jOOQ** (codegen via
        `DDLDatabase` from Flyway SQL)
  - [ ] Dashboard: detection history, photos, species
  - [ ] Show photos via **S3 presigned URLs** (bucket stays private)
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
  - [ ] Kubernetes hands-on: run the containers on local `kind`/`k3s` (WSL2) with manifests/Helm
  - [ ] Short-lived **GKE Autopilot** deploy of the same manifests, then tear the cluster down;
        write up Cloud Run + Pub/Sub vs. GKE + Kafka trade-offs from experience
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

- **Per service:** unit + integration tests (Testcontainers for Postgres/Kafka, via the
  WSL2 Docker Engine socket).
- **Recognition accuracy:** run DeepFaune against a folder of real B/W night trail-cam
  photos; confirm correct species above the confidence threshold.
- **End-to-end:** forward a real email with a photo attachment → confirm an SMS arrives
  with the correct animal name (or "low confidence" when appropriate).
- **Cost/scale:** load-test the flow; confirm scale-to-zero idle cost and that adding
  replicas handles bursts.

---

## Next Immediate Step

Start with **Phase 0 + Phase 1** (repo skeleton + first Spring Boot service) — the fastest
hands-on Kotlin/Spring learning and a working, testable piece on day one.
