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

- [x] **Message broker — DECIDED (2026-10-08): Cloud Pub/Sub, and Kafka is out of the
      repository.** Managed Kafka is the only component that would have cost triple digits a
      month, and it would not even have removed the recognition service's always-warm instance: a
      Kafka consumer **pulls**, so it needs min-instances=1 regardless — broker cost *on top of*
      that. **Pub/Sub push** POSTs to an HTTP endpoint per event, so both consumers are plain
      controllers that need no client library, Cloud Run keeps scale-to-zero, and the free tier
      (10 GiB/month) covers this project entirely. Pub/Sub's native dead-letter topics and retry
      policy replaced the hand-written retry/DLQ code. Local dev runs the **Pub/Sub emulator** in
      `compose.yml`. Consumer logic stayed in a transport-free `handle(event)`, which is exactly
      what made the swap small.
      *Follow-ups this created:*
      - **Ordering keys deliberately not used.** The Kafka message key (`hunterId`) only bought
        partition affinity, never correctness. Pub/Sub's equivalent would reintroduce
        head-of-line blocking — a nacked message blocks every later message with the same key —
        which is the very thing whose absence let the retry loop be deleted. If per-hunter
        ordering is ever needed ("latest sighting wins"), it is an ordering key on `hunterId`
        plus that cost.
      - **Webhook retry can duplicate an alert.** email-ingestion now *blocks* on publish and
        fails the webhook if Pub/Sub is unreachable, instead of answering 200 for a lost photo.
        The email provider then retries and we get a second `ImageReceived` with a **new**
        `eventId`, which notification's idempotency (keyed on `sourceEventId`) will not dedupe.
        Pre-existing for any webhook retry; the fix is deriving the event id from the message,
        not generating it.
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
| **jOOQ** | **In use** (detection-history, 2026-10-26) for the reporting reads, with user-account staying on JPA so the two are directly comparable. Codegen from the Flyway SQL via `DDLDatabase` through in-memory H2, so the build needs no database at all — and a column renamed in SQL breaks the Kotlin at compile time. | Phase 8 |
| **S3-compatible storage** | In use (`R2ImageStore`, S3 SDK). Deepen: boto3 fetch in recognition, presigned URLs, lifecycle rules, optional local S3-compatible server (check MinIO's current distribution/licensing; Garage/SeaweedFS as alternatives). GCS also offers an S3-compatible XML API (HMAC keys). | Phase 5, 7, 8 |
| **Cloud Run** | Strong fit — default hosting for all containers. | Phase 6 |
| **Cloud SQL** | Good fit, small fixed cost (see Open Decisions). | Phase 6 |
| **Cloud Run functions** (formerly Cloud Functions) | Good fit for small event/scheduled jobs: daily digest SMS, old-image cleanup. | Phase 7 |
| **GKE** | Weak fit for prod (overkill + idle cost for 4 services). Learn deliberately: local `kind`/`k3s` in WSL2, then a short-lived GKE Autopilot deploy that is torn down. | Phase 9 |
| **Spring Cloud GCP** | In use for publishing (`PubSubTemplate` in email-ingestion). Note its auto-configuration eagerly demands Application Default Credentials, so it is gated behind the `pubsub` profile — see `PubSubCredentialsConfig`. | Phase 6 |
| **Spring Cloud** (the umbrella — *not* Spring Cloud GCP) | Deliberate learning track, each item replacing something already hand-written, which is the point: you know what it abstracts. **OpenFeign** instead of the `RestClient` in `HunterLookupClient`/`HunterClient`; **Resilience4j** for the retry/timeout written by hand there (and it adds a circuit breaker we don't have); **Spring Cloud Gateway** in front of the services for the frontend; optionally **Spring Cloud Stream** — but its Pub/Sub binder is streaming-**pull**, so a service using it cannot scale to zero (min-instances=1), which is why the push design was chosen instead. | Phase 7, 8 |

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
                                         [ Pub/Sub topic  ]
                                              |
                                              v
                                         (2) Recognition Service      (Python + DeepFaune)
                                              | classifies species + confidence
                                              | publishes event: AnimalRecognized
                                              v
                                         [ Pub/Sub topic  ]
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

**Shared infra:** PostgreSQL (managed), Cloud Pub/Sub, Cloudflare R2 (images).

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
  - [x] `compose.yml` for local dev: Postgres + the Cloud Pub/Sub emulator
  - [ ] Root README: one-time WSL2 + Docker Engine setup, then how to run locally
- **DoD (review against):** `docker compose up` starts Postgres + the emulator cleanly; repo
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
  - [x] Define topics + event schemas (broker-agnostic JSON)
  - [ ] `AnimalRecognized` schema: `eventId`, `sourceEventId`, `hunterId`, `storageKey`,
        `species`, `confidence`, `lowConfidence`, `occurredAt`
  - [ ] Recognition fetches image bytes by `storageKey`: Python `ImageSource` interface
        (local-folder fake / R2 via **boto3**) + a `FileSystemImageStore` in email-ingestion
        so the flow runs locally without cloud credentials
  - [x] Consumer logic in a transport-free `handle(event)`; the transport is a thin adapter
        (which is what made replacing Kafka with Pub/Sub push a small change)
  - [ ] End-to-end: forwarded email → recognized → SMS delivered
  - [ ] **Per-hunter inbound address** (e.g. `7f3k9q2mx4ab@wildalert.app`): match the hunter by
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
  - [x] Managed Postgres — **Cloud SQL** `wildalert-db` (PostgreSQL 18, db-f1-micro, europe-west3),
        provisioned by hand in the console 2026-10-07
  - [x] Broker — **Cloud Pub/Sub push subscriptions** (decided 2026-10-08; see Open Decisions).
        Code + local emulator done; the four topics and the `.dlt.inspect` pull subscriptions are
        provisioned. The two **push** subscriptions need the consumers' URLs, so they come with them.
  - [ ] Deploy all containers to **Cloud Run** (or Railway), scale-to-zero where possible —
        user-account live 2026-10-08; email-ingestion next (public, the webhook); recognition and
        notification after it
  - [x] **Service-to-service authentication** — user-account requires authentication, so callers
        mint a Google **ID token whose audience is the target URL** (`UserAccountAuth` in
        email-ingestion, `USER_ACCOUNT_AUTH=gcp-id-token`) and need `roles/run.invoker` on it.
        Notification needs the same treatment when it deploys.
  - [x] Warm the recognition model at startup (FastAPI lifespan, ~1 min at boot instead of inside
        the first pushed event)
  - [~] Domain + Cloudflare Email Routing pointed at deployed ingestion. **`wildalert.app`
        registered, Email Routing live and receiving mail (2026-10-14)**; the Email Worker is
        written (`deploy/email-worker/`). What remains is `wrangler deploy`, switching the
        catch-all from *Send to an email* to *Send to a Worker*, and
        `INBOUND_EMAIL_DOMAIN=wildalert.app` on user-account — all of which need email-ingestion's
        Cloud Run URL to exist first.
        Design: **catch-all on the apex** (`*@<domain>`), so every hunter's personal address
        reaches the webhook with no per-hunter setup. **Not a subdomain:** Cloudflare offers a
        catch-all on the apex only; a subdomain needs one explicit routing rule per address,
        which would put a Cloudflare API call inside hunter registration. Explicit custom
        addresses still beat the catch-all, so `hello@<domain>` can forward to a real mailbox.
        Consequence: the apex MX belongs to Cloudflare, so no other mail provider can host
        mailboxes there later.
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
  - [ ] **Strip `roles/editor` from the default compute service account**
    (`789527844011-compute@developer.gserviceaccount.com`). `wildalert-prod` has no parent
    organization, so Google's `iam.automaticIamGrantsForDefaultServiceAccounts` policy (default
    only for orgs created on/after 2024-05-03) never applied and the legacy automatic Editor grant
    fired. Since this project's first build post-dates Google's mid-2024 change, `gcloud builds
    submit` runs **as that account** — so Editor must be replaced with explicit build roles first,
    or builds break: `roles/logging.logWriter` (cloudbuild.yaml sets
    `logging: CLOUD_LOGGING_ONLY`), `roles/artifactregistry.writer`, and read on the
    `*_cloudbuild` source bucket (`roles/storage.objectViewer`). Better still, give Cloud Build its
    own service account via `--service-account` and leave the compute default unused. Deliberately
    deferred out of Phase 6: doing it before the first green deploy means debugging IAM and the
    deploy at the same time. Don't delete/disable the account — bindings don't restore cleanly.
  - [ ] **Scope `user-account`'s secret access down to the one secret it needs.** It holds
    `roles/secretmanager.secretAccessor` **at project level**, granted from the console's "grant
    this service account access to project" step (`CONSOLE.md` step 3) because that page is the
    path of least resistance. Harmless while it was the only secret; since 2026-10-16 the project
    also holds `wildalert-webhook-secret` and both R2 keys, which belong to email-ingestion, and
    user-account can read all three. Replace the project-level role with a single binding on
    `wildalert-db-password` (Secret Manager → that secret → Permissions), then deploy a revision
    and confirm readiness still comes up: the password is read at startup, so a wrong grant shows
    as a revision that never goes live rather than a runtime error. email-ingestion is already
    done the right way — per-secret bindings, no project-level secret role — and is the pattern to
    copy. Same instinct as the per-topic `pubsub.publisher` grants: the console's project-wide
    offer is almost never what you want.
- **DoD (review against):** smart-SMS mode measurably cuts SMS count; logs/metrics let you
  trace a request end-to-end; admin endpoints not publicly open.

### Phase 8 — React Frontend (later)
- **Goal / learn:** React + talking to your Kotlin API.
- **Deliverable:** a hunter-facing web app.
- **Tasks:**
  - [ ] Hunter signup + phone verification
  - [ ] Show the hunter's personal inbound address with setup help (Gmail/Outlook forwarding rule,
        or entering it directly in the camera app)
  - [x] **Detection History service** (Kotlin/Spring Boot + Postgres): consume
        `AnimalRecognized`, store detections; reporting queries with **jOOQ** (codegen via
        `DDLDatabase` from Flyway SQL). Built 2026-10-26, ahead of the rest of Phase 8 because it
        needs nothing from the frontend and shares no files with the work in flight. Taken as a
        **second push subscription** on `animal.recognized` rather than a change to notification —
        fan-out, so history can be down without delaying an SMS and neither consumer knows the
        other exists. Keyed on the event's `recipient`, not a hunter id, so recording a detection
        makes **no synchronous call to user-account**: a cold start may delay an SMS, but it must
        never be able to lose history. Idempotent on `source_event_id` as the primary key — the
        write *is* the effect, so no separate claim/release table is needed. jOOQ codegen runs off
        the Flyway SQL through in-memory H2, so the build needs no database; `DDLDatabase` is also
        why the migration avoids `timestamptz`/`text` shorthands. 44 tests, no infrastructure.
        *Still open:* deploy (needs a service account + the real push subscription), presigned
        photo URLs, and auth before the read API is exposed — the inbound address is currently the
        only thing identifying a hunter.
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
        write up Cloud Run + Pub/Sub vs. GKE + always-on consumers, from experience
- **DoD (review against):** load test sustains target volume; billing charges correctly;
  auth protects user data.

---

## Cost Model (approximate, 2026)

**Fixed baseline (any number of users): ~$0–7/month**
- Domain ~$1/mo · Cloud SQL ~$9 · Pub/Sub $0 (10 GiB/month free) · R2 ~$0 · idle Cloud Run ~$0

**Per user (dominated by SMS)** — assuming ~10 photos/day (~300/mo):
- Email in: $0 · Recognition (Cloud Run CPU): ~$0.15 · Storage+DB+broker share: ~$0.03
- **SMS: 300 × ~$0.008 ≈ $2.40 (~90% of cost)**
- **Typical total ≈ $2.60/user/mo** (Light ≈ $0.85, Heavy ≈ $7.40)

**Lever:** smart SMS rules (target species / digest) can cut a typical user to **< $1/mo**.

---

## Verification (how we prove each piece works)

- **Per service:** unit + integration tests (Testcontainers for Postgres, via the WSL2 Docker
  Engine socket); the event transport is proven end-to-end against the Pub/Sub emulator.
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
