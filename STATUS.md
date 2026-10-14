# WildAlert — Project Status

_Last updated: 2026-10-14. Companion to [`ROADMAP.md`](./ROADMAP.md): ROADMAP is the plan,
this is where we actually are. Work proceeds one reviewable slice at a time._

## What it is
Hunter forwards a trail-cam email → we recognize the animal (mostly B/W infrared night
photos) → text the hunter the species via SMS. Event-driven microservices: Kotlin/Spring Boot
+ one Python recognition service. Dual goal: ship cheap & scalable, and learn
Kotlin/Spring/microservices.

## Architecture
```
Email → [Email Ingestion (Kotlin)] --ImageReceived--> [Pub/Sub]
             → [Recognition (Python/DeepFaune)] --AnimalRecognized--> [Pub/Sub]
                  → [Notification (Kotlin)] → SMS (Twilio)
        [User/Account (Kotlin)] — email→phone mapping, shared Postgres
```

## Done
- **Phase 0 — Foundations:** monorepo, Gradle Kotlin DSL multi-module, `compose.yml`
  (Postgres + the Cloud Pub/Sub emulator), README, `.env.example`.
- **Phase 1 — User/Account:** `Hunter` entity, Flyway migration, register/get/update
  endpoints, lookup-by-email (`GET /api/hunters/by-email`), email normalization
  (lowercase/trim), validation, tests.
- **Phase 2 — Notification (SMS):** `SmsSender` interface + `TwilioSmsSender` (real) /
  `LoggingSmsSender` (fake default), controller, tests.
- **Phase 3 — Email Ingestion:** MIME parser; image storage (`ImageStore` → R2 real /
  logging fake); sender→hunter matching via `HunterLookupClient` (Spring RestClient);
  `ImageReceived` event via `EventPublisher` (logging fake / Pub/Sub). Tested.
- **Phase 4 — Recognition (Python/FastAPI):** `/health`, `/recognize`
  (`{species, confidence, low_confidence}`), confidence-threshold safeguard, DeepFaune wired
  in behind a `Classifier` interface (MegaDetector→crop→DeepFaune), CPU Docker image with
  weights baked in, plus bake-off tooling. Verified end-to-end on real photos.

## Done — Phase 5 (event wiring)
_Historical record: this phase was built on Kafka, which was replaced by Cloud Pub/Sub in Phase 6
slice 4 (below). The notes are kept as written — the Kafka-specific class names and offset talk no
longer exist in the repo, but what each slice proved still holds._
- **Slice 1 (done):** email-ingestion `KafkaEventPublisher` publishes `ImageReceived` (JSON)
  to topic `image.received`. Unit-tested; verified with a real broker round-trip.
- **Slice 2 (done):** image fetch by storage key. email-ingestion
  `FileSystemImageStore` (`storage.provider=filesystem`, writes `<root>/<key>`); recognition
  `ImageSource` (`LocalFolderImageSource` / `R2ImageSource` via boto3, chosen by
  `RECOGNITION_IMAGE_SOURCE`). Tested both sides; verified a key written by the running
  email-ingestion app reads back byte-identical from Python.
- **Slice 3 (done, pending review):** recognition Kafka worker (`python -m app.worker`, same
  image as the API). `events.py` (ImageReceived / AnimalRecognized contracts, camelCase JSON),
  transport-free `handler.handle(event)`, `worker.py` Kafka adapter (confluent-kafka).
  At-least-once: commit only after the result is delivered; invalid/missing-image messages
  skipped; other failures stop without committing. Verified in WSL with the real DeepFaune
  image + broker: boar → `wild boar` 1.00; mouflon → `fallow deer` 0.65 flagged
  `lowConfidence` (threshold caught a misclassification); bad messages skipped; LAG 0; restart
  reprocessed nothing.
- **Slice 4 (done, pending review):** notification consumes `animal.recognized`
  (`AnimalRecognizedListener`, Spring Kafka, on with `events.provider=kafka`) →
  `HunterClient` (`GET /api/hunters/{id}`) → `SmsPolicy` → `SmsSender`. Policy: one SMS per
  photo; confident → "WildAlert: wild boar detected (99% confidence)"; low confidence → hedged
  "animal detected, species uncertain (maybe fallow deer)"; unknown sender / deleted / inactive
  hunter → no SMS; English. user-account outage is thrown (retried by Spring Kafka's default
  error handler, then skipped). Added `services/notification/Dockerfile` + root `.dockerignore`.
  Verified full chain in WSL (ImageReceived → DeepFaune worker → notification → fake SMS) with
  real user-account + Postgres: all 6 cases correct, LAG 0.
- **Slice 5 (done):** per-hunter inbound address. user-account: Flyway V2
  `inbound_token` (backfilled for existing hunters), `InboundAddresses` (token@
  `INBOUND_EMAIL_DOMAIN`, 12 unambiguous chars), `inboundAddress` in responses,
  `GET /api/hunters/by-inbound-address`. email-ingestion: matches the hunter by recipient —
  `?envelopeTo=` (webhook), then `Delivered-To`/`X-Original-To`/`To`/`Cc`; `From` matching
  dropped (spoofable); `matchedRecipient` in the response; added `Dockerfile`.
  **Verified true end-to-end from raw emails in WSL** (email-ingestion → filesystem + Kafka →
  DeepFaune worker → notification → fake SMS): auto-forwarded email (From camera, To hunter's
  Gmail, Delivered-To inbound) → SMS "wild boar"; camera-direct via envelopeTo → hedged SMS;
  spoofed From → no match, no SMS. Migration applied to a DB with existing hunters: all got
  distinct tokens.
- **Slice 6 (done, pending review):** no double SMS on redelivery. notification gained a
  `ProcessedEvents` interface — `InMemoryProcessedEvents` (fake default, per-instance) and
  `PostgresProcessedEvents` (`IDEMPOTENCY_STORE=postgres`, Flyway V1 `notification.processed_event`
  in its own schema so it doesn't clash with user-account's Flyway history). `NotificationService`
  **claims** `sourceEventId` before sending (`insert … on conflict do nothing`, so racing replicas
  can't both send) and **releases** it if the send throws, so a failed send is still retried.
  `DataSourceAutoConfiguration` is excluded, so the service still starts with no database.
- **Slice 7 (done, pending review):** retries + dead-letter topics, closing Phase 5. Both
  consumers now park what they can't process in `<topic>.dlt` (original bytes + key, reason in a
  header) and commit, so nothing is lost and one bad message can't block a partition.
  - **Python worker:** unprocessable messages (invalid JSON, missing image) are dead-lettered
    immediately; other failures get 3 attempts with a growing pause, then the DLT. Only a failure
    to reach Kafka is raised (no commit → retried after restart).
  - **Notification (Kotlin):** `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`, 3 attempts
    2s apart, `HttpClientErrorException` treated as not retryable. Replaces Spring's default of
    retrying fast and then silently dropping the alert.
  - **Verified in WSL:** with user-account stopped, a valid result ended up in
    `animal.recognized.dlt`; an `ImageReceived` pointing at a missing image ended up in
    `image.received.dlt` with `reason:No image stored under key …`. Both consumer groups ended at
    LAG 0. _Not directly observed: the retry count/timing — only that the handler gave up and
    dead-lettered._
  - **Known trade-off:** a long storage outage would push a backlog into the DLT rather than
    waiting it out. A replay tool for the DLT is a Phase 7 job.

## In progress — Phase 6 (deploy to managed cloud)
- **Slice 0 (done):** ports are injectable — `server.port=${PORT:<own>}` in all three Kotlin
  services and a shell-form `CMD` in the recognition Dockerfile, so Cloud Run's injected `PORT`
  is honoured and local defaults still work.
- **Slice 1 (done, pending review):** the three things that would have bitten us on first deploy.
  - **email-ingestion no longer loses an email when user-account is unreachable.**
    `HunterLookupClient` caught only 404, so a connection failure 500'd the webhook and the photo
    was never stored — unrecoverable. It now retries once (the common cause is a peer instance
    cold-starting, which is routine on Cloud Run) and then degrades to "no match": the image is
    still stored and `ImageReceived` still published with a null `hunterId`, which the pipeline
    already handles. Only that photo's SMS is missed. Explicit `spring.http.client.*` timeouts
    added too (email-ingestion 2s/5s; notification 2s/10s, more generous because a timeout there
    throws and the event is redelivered).
  - **Shared secret on the email webhook.** `WebhookAuthFilter` requires `X-Webhook-Secret` on
    `/api/emails` (constant-time compare, 401 otherwise). It guards only that path, so health
    probes stay open, and stands down with a loud startup warning when `WEBHOOK_SECRET` is unset
    — local dev and tests need no setup. Without this, a public Cloud Run URL would let anyone
    push images through storage and DeepFaune at our expense.
  - **Actuator health/readiness on all three Kotlin services.** Only the `health` endpoint is
    exposed (`env`/`metrics`/`heapdump` would leak config from a public URL); `probes.enabled`
    adds `/actuator/health/liveness` and `/readiness` for Cloud Run. **Gotcha found while
    verifying:** Boot's default readiness group contains only `readinessState`, so user-account's
    readiness stayed UP with Postgres stopped even though `/actuator/health` was 503. Fixed by
    `group.readiness.include: readinessState,db` — an instance that can't reach the DB is now
    taken out of rotation while liveness stays UP (no pointless restart).
  - **Verified:** full suite green (79 Kotlin tests incl. `contextLoads` with Postgres up, 32
    Python). Against a running email-ingestion: no header → 401, wrong secret → 401, correct
    secret → 200 with the image stored, `/actuator/health` + both probes → 200, `/actuator/env`
    → 404. The 200 case also proved the resilience fix — user-account was *not* running, and the
    log shows one retry then "treating it as no match" instead of a 500. Against user-account
    with Postgres stopped/started: readiness 503↔200 while liveness stayed 200.
  - **Still open (structural):** matching the hunter inside the synchronous webhook means a real
    user-account outage silently costs alerts. The clean fix is to publish the recipient address
    on the event and resolve the hunter in notification instead; `min-instances=1` on user-account
    sidesteps the cold-start case in the meantime.

- **Slice 2 (infrastructure provisioned, service not yet deployed):** **Cloud SQL** (an explicit
  learning target) in **europe-west3**, all of it created **by hand in the Cloud Console** rather
  than by script — the point was to see the forms. `deploy/CONSOLE.md` is the click-by-click
  walkthrough and the record of which option was chosen where; `deploy/README.md` holds the settings
  table, cost model, verification commands and troubleshooting.
  - **What exists (verified by `gcloud ... describe`):** Artifact Registry `wildalert`
    (europe-west3, Docker); service account `wildalert-user-account@…` with exactly
    `cloudsql.client` + `secretmanager.secretAccessor` and no user-managed keys; Cloud SQL
    `wildalert-db` (**PostgreSQL 18**, db-f1-micro, zonal, 10 GB HDD, `europe-west3-c`); database
    `wildalert` (UTF8/en_US.UTF8); built-in user `wildalert`; secret `wildalert-db-password` (v1,
    payload checked for stray whitespace).
  - **The console's presets lie about what they switch off.** With the *Sandbox* preset chosen, the
    instance still came out with automated backups, point-in-time recovery, deletion protection,
    *retain backups after deletion* and *final backup on deletion* all **on** — the last two
    survive the instance and keep billing storage after a teardown. All turned off afterwards and
    re-verified. `CONSOLE.md` step 4 now lists them explicitly.
  - **`cloudsqlsuperuser` kept on the `wildalert` user**, which answers the open question from the
    scripted version: PG15+ dropped the default `CREATE` grant on `public`, and Cloud SQL databases
    are owned by that role, so Flyway can create its tables. More than least privilege
    (`CREATEDB`/`CREATEROLE`); splitting it into a migration role + a DML-only app role is a
    Phase 7 task.
  - **Prod is PG18, local compose is PG17.** Accepted rather than recreating the instance; the
    migrations are plain DDL. Bumping local would need the `pgdata` volume wiped.
  - **DB connection is config-only:** the Cloud SQL Java connector is named as the JDBC
    `socketFactory` in `DATABASE_URL`, so the only code change was one `runtimeOnly` dependency
    (`com.google.cloud.sql:postgres-socket-factory:1.28.4`). No password over TCP, no IP allowlist.
  - **Private service:** deploy with **Require authentication**, because it serves hunters' phone
    numbers; verify with `gcloud auth print-identity-token`. `roles/run.invoker` for the other
    services comes when they deploy.
  - The Cloud Run **startup probe points at `/actuator/health/readiness`**, so a revision only goes
    live once Flyway has run and Cloud SQL is reachable — the payoff from slice 1.
  - **The shell scripts were deleted.** `00-bootstrap.sh`, `10-cloudsql.sh`,
    `20-deploy-user-account.sh`, `99-teardown.sh` and `config.sh` are gone now that the console is
    the workflow: an unrun script drifts (that `config.sh` still said `POSTGRES_17` after the
    instance was built as 18) and then misleads. Their settings live in `deploy/README.md`; teardown
    is a documented console checklist. `deploy/cloudbuild.yaml` stays — the console cannot build an
    image. If this outgrows clicking, the answer is OpenTofu, not shell.
  - **Default compute service account still holds `roles/editor`**, and it is what Cloud Build runs
    as (`wildalert-prod` has no parent org, so Google's automatic-grant restriction never applied).
    Left alone deliberately so the first build works; stripping it with explicit build roles is a
    Phase 7 task in `ROADMAP.md`.
  - **Cost:** $0.01/hour ≈ **$9/month** for the instance, billed while it exists whether queried or
    not. Delete it between work sessions — Cloud Run at min-instances 0 is free idle.

- **Slice 3 (done, 2026-10-08): user-account is live in Google Cloud.** Image built by a **Cloud
  Build trigger** on `main` (GitHub App connection, `deploy/cloudbuild.yaml`, tag from `$SHORT_SHA`)
  and deployed to **Cloud Run** `user-account` in europe-west3 through the console: private
  (**Require authentication** → IAM, not IAP), min/max instances 0/3, 1 vCPU / 1 GiB,
  `wildalert-user-account` service account, Cloud SQL connection attached, `POSTGRES_PASSWORD` from
  Secret Manager, startup probe on `/actuator/health/readiness`.
  - URL `https://user-account-lznkorfm4q-ey.a.run.app`, revision `user-account-00001-bl4`, image
    `…/user-account:345dc61` (`sha256:15894444e817…`).
  - **Verified against the running service:** no token → 403; `/actuator/health`,
    `/readiness`, `/liveness` → UP; `/actuator/env` → 404; `POST /api/hunters` created a hunter with
    an `inboundAddress`; `GET` by id, by-email (uppercase input normalised) and by-inbound-address
    all returned it; unknown email → 404. **Readiness UP is the proof the DB works** — Flyway ran
    both migrations as `wildalert` and `cloudsqlsuperuser` was enough for `public`, which closes the
    open question from slice 2.
  - **Two build failures worth remembering:** `gradlew` is committed `100644` (added from Windows),
    so `./gradlew` in the Linux build image was `Permission denied` (exit 126) — every Dockerfile now
    runs `chmod +x gradlew` first. And Cloud Build does **not** expand built-ins inside a user
    substitution's value, so `_IMAGE=…:$SHORT_SHA` failed with `could not parse reference`; the tag
    is now built inside `cloudbuild.yaml` from `_IMAGE_BASE` + `$SHORT_SHA`.
  - **Known gap:** the trigger runs as the **default compute service account** (still `roles/editor`).
    A dedicated `wildalert-cloudbuild` with only `artifactregistry.writer` + `logging.logWriter` is
    the fix, deliberately deferred to Phase 7 so the first deploy wasn't two problems at once.

- **Slice 4 (done, pending review): Kafka removed, Cloud Pub/Sub in — code + local dev.**
  Managed Kafka was the only triple-digit monthly cost, and it would not have avoided an always-warm
  recognition instance (a pull consumer must run); Pub/Sub push is free at this volume and lets both
  consumers scale to zero. Cloud provisioning is the next slice.
  - **email-ingestion publishes** with `PubSubTemplate` (`spring-cloud-gcp-starter-pubsub` 7.4.9 —
    the 7.x line is Boot 3.5's; 8.x is Boot 4). It **blocks** on the publish now, so a broker
    outage fails the webhook instead of answering 200 for a photo nobody will recognise.
  - **Both consumers are plain HTTP endpoints** — `POST /api/events/animal-recognized`
    (notification) and `POST /events/image-received` (recognition) — so neither needs a Pub/Sub
    client. The HTTP status is the acknowledgement: 204 ack, 400 unparseable, 404 image missing,
    500 transient, and Pub/Sub's own retry policy + dead-letter topics replaced the hand-written
    retry loop and DLT code from Phase 5 slice 7.
  - **The trap worth remembering:** Spring Cloud GCP's auto-configuration takes `CredentialsProvider`
    as a constructor parameter, and that bean eagerly calls `GoogleCredentials.getApplicationDefault()`
    — so merely having the starter on the classpath breaks startup on any machine without GCP
    credentials, even with an emulator host set (the emulator short-circuit happens *after* that bean
    is built). Two defences: a **`pubsub` Spring profile** that flips `events.provider` and both
    `spring.cloud.gcp.*.enabled` flags together (off by default), and `PubSubCredentialsConfig`,
    which supplies `NoCredentialsProvider` when an emulator host is set — a user bean beats the
    auto-configuration's `@ConditionalOnMissingBean`.
  - **Local dev:** `compose.yml` runs the Pub/Sub **emulator** with a `curl` init container that
    creates 4 topics and 4 subscriptions over the emulator's REST API (`gcloud pubsub` does not talk
    to it). The emulator pushes to the services via `host.docker.internal` (needs
    `extra_hosts: host-gateway` on Docker Engine), so the services must run in the same WSL2 session
    and uvicorn must bind `0.0.0.0`.
  - **No ordering keys** — the Kafka key only bought partition affinity, and Pub/Sub ordering would
    reintroduce head-of-line blocking on failures. See ROADMAP.
  - **Tests:** `./gradlew build` green (incl. two new wiring tests asserting the push endpoint
    exists with `events.provider=pubsub` and does *not* without it); 33 Python tests green. The
    ported `AnimalRecognizedPushControllerTest` still asserts the **same captured JSON** from the
    Python service, which is what keeps the hand-duplicated contracts honest.
  - **Containerised local run.** `compose.yml` gained a **`local` profile** with all four services,
    so the emulator reaches them by container name — no host networking at all. The Kotlin services
    run a plain `eclipse-temurin:25-jre` image over the host-built bootJar (no image build; the
    Gradle cache does the compiling), and recognition has a slim `Dockerfile.local` with the stub
    classifier, so it builds in seconds instead of pulling several GB of torch and weights.
    `docker compose up -d` is still infra-only; `docker compose --profile local up -d --build` runs
    the pipeline. This was chosen over running the services on the host because **WSL has no JDK**
    and the Python venv is Windows-side, so `host.docker.internal` had nothing to reach.
  - **Verified end-to-end in WSL (2026-10-09), containers only:**
    - **Happy path:** raw forwarded email → email-ingestion stored the image and published
      `ImageReceived` → emulator pushed to recognition (`POST /events/image-received`) → published
      `AnimalRecognized` → pushed to notification → `[FAKE SMS] … "WildAlert: wild boar detected
      (97% confidence)"`. The whole chain, on the real transport.
    - **Poison path:** garbage published straight to `image.received` → recognition answered **400**
      five times → the message arrived in `image.received.dlt` with its original bytes and
      `CloudPubSubDeadLetterSourceDeliveryCount: 5`, pulled from `image.received.dlt.inspect`. So
      Pub/Sub's retry + dead-letter config really does replace the hand-written loop, and the
      inspect subscriptions really are what stops the DLT discarding messages.
    - **Idempotency:** the same `AnimalRecognized` pushed twice → **2 pushes received, 1 SMS sent**,
      one `notification.processed_event` row, and the second logged "source event … was already
      handled (duplicate delivery)". (A first attempt at this test was invalid — the event had
      `hunterId: null`, so notification short-circuits before claiming and no SMS is attempted
      either way.)
  - **Fixed while verifying:** removing `worker.py` removed the only `logging.basicConfig` call, so
    the Python service's own INFO lines (push endpoint, publisher) were being dropped — uvicorn
    configures only its own loggers. `app/main.py` now sets it up (`LOG_LEVEL`, default INFO).
  - **Still not verified:** the no-credentials boot check (plan step 7) — the containers never have
    ADC, so email-ingestion starting there is weak evidence for it, but the host case with the
    `pubsub` profile *off* has not been exercised deliberately.

## Remaining Phase 6 work
- **Provision Pub/Sub in GCP** (the code side is done — slice 4 below): 4 topics
  (`image.received`, `animal.recognized`, + `.dlt` for each), 2 authenticated push subscriptions,
  2 pull subscriptions on the dead-letter topics (**a topic with no subscription discards
  messages**), runtime service accounts with `roles/pubsub.publisher` on the topic, a
  `wildalert-pubsub-push` account with `roles/run.invoker` per service, and the Pub/Sub service
  agent's `iam.serviceAccountTokenCreator` + dead-letter grants.
- Deploy the other three services; grant them `roles/run.invoker` on user-account.
- Domain + Cloudflare Email Routing. **Worker written (2026-10-14): `deploy/email-worker/`** —
  `src/worker.js` POSTs the raw RFC-822 bytes to `/api/emails?envelopeTo=<message.to>` with the
  `X-Webhook-Secret` header, plus `wrangler.toml` and a setup/verification README. Untested until
  email-ingestion has a URL.
  **`wildalert.app` is registered (2026-10-14, Cloudflare Registrar)** and the zone is served by
  Cloudflare's nameservers (`agustin`/`leia.ns.cloudflare.com`).
  **Email Routing is enabled and receiving mail (2026-10-14).** Verified from two resolvers
  outside Cloudflare (8.8.8.8 and 1.1.1.1): `MX` → `route1/2/3.mx.cloudflare.net` (priorities
  45/70/91) and `TXT` → `v=spf1 include:_spf.mx.cloudflare.net ~all`. The apex had no MX/TXT
  before this, so nothing was overwritten. A **temporary catch-all forwards to
  `animalrecognition@gmail.com`** (the verified destination address) and a real test email
  arrived there — so DNS, MX, SPF and the catch-all rule are all proven before our own service
  is involved. No DMARC record, which is fine while we only receive.
  Still to do: deploy the worker, switch the catch-all from *Send to an email* to
  **Send to a Worker** (`wildalert-email-in`), and set `INBOUND_EMAIL_DOMAIN=wildalert.app`.
  Hunters created before that carry placeholder addresses and need new tokens.
  - **Design change forced by Cloudflare:** the plan said a catch-all on `in.<domain>`.
    Catch-all rules are **apex-only**; a subdomain needs one literal rule per address, i.e. a
    Cloudflare API call during registration. Hunter addresses therefore live on the apex
    (`<token>@wildalert.app`). Costs nothing in code — `InboundAddresses` reads the domain from
    config — and human mail still works, because explicit addresses are matched before the
    catch-all. Price paid: the apex MX is Cloudflare's, so no other provider can host mailboxes
    on it later.
  - **email-ingestion must be the one public Cloud Run service.** Workers call in over the public
    internet, so it cannot use Require authentication / internal ingress like the Pub/Sub
    consumers; `WebhookAuthFilter`'s shared secret is its protection, which is why that filter was
    built in slice 1.

## Remaining phases
- **7 — Hardening & cost control** (tune threshold, smart-SMS to cut cost, logging/metrics,
  admin auth).
- **8 — React frontend.**
- **9 — Scale / auth / billing.**

## Key decisions
- **Recognition model = DeepFaune** (not SpeciesNet) — targets European animals; chosen via a
  bake-off on real photos (DeepFaune gave specific species; SpeciesNet gave vague
  families/global bias). Both are free/open, self-hosted.
- **Transport = Cloud Pub/Sub, push subscriptions** (decided 2026-10-08; Kafka removed from the
  repo). Free at this volume, nothing to operate, and both consumers are plain HTTP endpoints that
  need no client library — so Cloud Run keeps scale-to-zero. Keeping events as broker-agnostic
  JSON behind interfaces is what made the swap a small change.
- **Swappable-interface pattern everywhere:** `ImageStore` (R2/logging), `SmsSender`
  (Twilio/logging), `EventPublisher` (pubsub/logging), `Classifier` (deepfaune/stub) — fake
  default, real impl via config.
- Recognition & SMS are cheap/free; **SMS dominates cost** (~90%).

## Known gaps / follow-ups
- **Inbound email webhook written but never run:** matching by recipient is done (slice 5) and
  the Email Worker now exists (`deploy/email-worker/`), but it cannot be deployed until
  email-ingestion has a Cloud Run URL. `message.to` (the SMTP envelope recipient) is what it
  sends as `?envelopeTo=`; the email's own headers stay the fallback.
- **user-account `contextLoads` test needs Postgres running** (`docker compose up -d postgres`);
  unit/web tests don't. It passes from the Windows host once the container is up.
- **Recognition model load (~1 min)** now happens at **startup** (FastAPI lifespan), so a pushed
  event never waits for the weights and risks its acknowledgement deadline. `/recognize` benefits
  too.
- **Model licences — resolve before charging money:** `MegaDetectorV6("MDV6-yolov9-c")` is
  Ultralytics-based (**AGPL-3.0**, a concern for a paid network service); PyTorch-Wildlife also
  ships MIT (`megadetectorv6_mit`) and Apache (`rtdetr_apache`) MDv6 variants — swap and re-run the
  accuracy check. DeepFaune weights are **CC BY-SA 4.0** per the loader header (commercial use
  OK with attribution to CNRS/DeepFaune; confirm on deepfaune.cnrs.fr). Not legal advice — get a
  licensing check before launch.
- **Local-dev env limit (this machine):** WSL2 shuts the distro down between commands, killing
  running containers — do multi-step Docker work in one session. Port reachability is **not** a
  problem: the Windows host reaches WSL2 Docker ports fine (corrected 2026-09-17), so
  Testcontainers / host-run integration tests are worth trying.

## Tech stack
Kotlin + Spring Boot (Web, JPA, Flyway, Validation, RestClient) + Spring Cloud GCP Pub/Sub,
JDK 25 · Python 3.12 + FastAPI + PyTorch-Wildlife (DeepFaune/MegaDetector) · Postgres 17 local /
18 on Cloud SQL · Cloud Pub/Sub (emulator locally) · Cloudflare R2 (S3 SDK) · Twilio ·
Docker (Engine in WSL2 locally, managed cloud in prod).
