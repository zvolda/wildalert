# WildAlert — Project Status

_Last updated: 2026-10-30. Companion to [`ROADMAP.md`](./ROADMAP.md): ROADMAP is the plan,
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
                  → [Detection History (Kotlin/jOOQ)] → Postgres   [2nd subscription, fan-out]
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
  - **Still open (structural), and it fired for real on 2026-10-20.** Matching the hunter inside the
    synchronous webhook means a real user-account outage silently costs alerts — but the trigger
    turns out to be far more routine than an outage. The first email through Cloudflare hit a
    **scaled-to-zero user-account** and both lookup attempts timed out:
    `I/O error … "Request cancelled"; retrying once` at 12:37:53, gave up at 12:37:58. The read
    timeout is 5s and a cold Spring Boot + Flyway + Cloud SQL start takes longer, so the retry
    — fired 5s later, still during the same boot — was doomed too. The photo was stored and published
    with `hunterId: null`, the webhook answered 200, and nothing in the response said why.
    **So in production the first photo after any idle period loses its SMS.** Three ways out:
    raise the read timeout to ~15s with a backing-off retry (cheap, keeps scale-to-zero);
    `min-instances=1` on user-account (~$10/month, gives up scale-to-zero); or the clean fix —
    publish the recipient address on the event and resolve the hunter in **notification**, where
    Pub/Sub's own retries absorb a cold start. Phase 7 decision.

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
    not. **Decision (2026-10-26): leave it running.** ~$9/month buys not having to think about it,
    and since the pipeline now holds real state — the hunter row, its inbound token, notification's
    idempotency table — and **backups are deliberately off**, a delete is unrecoverable. If it ever
    is worth pausing, **stop it, never delete it**:
    `gcloud sql instances patch wildalert-db --activation-policy=NEVER` keeps the data and costs
    only the 10 GB of HDD (~$0.90/month); `ALWAYS` brings it back. Note the product is **offline**
    while it is stopped: notification's lookup fails and an alert dead-letters after five retries.
    Cloud Run at min-instances 0 is free idle regardless.

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

- **Slice 5 (code done, pending deploy): email-ingestion can call a private user-account.**
  Found while planning the domain slice, not by a test: `HunterLookupClient` sent no credential, and
  user-account runs with Cloud Run's **Require authentication**. Every lookup in the cloud would have
  been a 403 — and because a failed lookup deliberately degrades to "no hunter matched" rather than
  failing the webhook, the symptom would have been a cheerful `200 {"matchedHunterId": null}` on
  every photo and no SMS, with nothing in the response to say why.
  - `UserAccountAuth` (a `fun interface`) with `GcpIdTokenUserAccountAuth` behind
    `services.user-account.auth=gcp-id-token`, same swappable-interface shape as `ImageStore` and
    `EventPublisher`: no bean at all is the local default, so tests and `bootRun` never touch
    Application Default Credentials.
  - Cloud Run wants a **Google-signed ID token whose audience is the target service's URL** — not an
    access token. On Cloud Run the token comes from the metadata server through ADC, so there is no
    key to manage; `IdTokenCredentials.getRequestMetadata()` caches and refreshes it. The only
    cloud-side setup is `roles/run.invoker` for `wildalert-email-ingestion` on user-account.
  - A token that cannot be minted is wrapped in `RestClientException`, so it takes the same
    retry-then-degrade path as an unreachable user-account: never fail the webhook, never lose the
    photo. Three new tests cover all of it (no header by default, the header when configured, and
    the degrade path) — 9 in `HunterLookupClientTest`, `./gradlew build` green.
  - **Not verified in the cloud yet** — it cannot be until email-ingestion is deployed. The proof
    will be a non-null `matchedHunterId` from `CONSOLE.md` step 10e.
- **Slice 6 (provisioning done 2026-10-16, deploy blocked): everything email-ingestion needs except
  the deploy itself.** Each step verified read-only with `gcloud` rather than trusted to the form.
  - **Pub/Sub IAM finished.** `wildalert-email-ingestion` holds `roles/pubsub.publisher` on
    `image.received`, `wildalert-recognition` the same on `animal.recognized`, and the
    Google-managed Pub/Sub service agent holds `iam.serviceAccountTokenCreator` **on the
    `wildalert-pubsub-push` account as a resource**, not on the project. That last grant is what
    lets Pub/Sub mint the OIDC token it pushes with; without it every push 403s with nothing
    visibly wrong in the subscription config. All four accounts have **no user-managed keys**, and
    none of them holds a project-level role.
  - **R2.** Bucket `wildalert-images`, *Eastern Europe (EEUR)* **location hint** and no
    jurisdiction — so the endpoint is the plain `https://<account-id>.r2.cloudflarestorage.com`.
    (An EU *jurisdiction* bucket is a different setting with a `.eu.` host; the two look identical
    in the form and the mismatch surfaces as an opaque S3 error.) Token scoped **Object Read &
    Write on that one bucket**. `R2ImageStore` needs the S3 *Access Key ID / Secret Access Key*
    pair — not the "Token value" the same page shows, which is for Cloudflare's own REST API.
  - **Three secrets, one binding each.** `wildalert-webhook-secret` (44 bytes = base64 of 32
    random bytes), `wildalert-r2-access-key` (32), `wildalert-r2-secret-key` (64) — all three with
    **no trailing newline**, checked by byte count without ever printing a value. Each carries a
    single `secretmanager.secretAccessor` for `wildalert-email-ingestion`, so it provably cannot
    read `wildalert-db-password`. (user-account's own accessor role is still *project-wide*, which
    is now a Phase 7 item in `ROADMAP.md`.)
  - **Image built.** Trigger `email-ingestion-main` — the twin of `user-account-main` with only
    `_SERVICE`/`_IMAGE_BASE` swapped — produced `email-ingestion:71d9c34`
    (`sha256:126549591fb1c3ea…`) in 1m55s. `71d9c34` is the PR #4 merge commit, so
    `UserAccountAuth` is in the image. Worth remembering: `gcloud builds log --stream` prints
    nothing for these builds, because `cloudbuild.yaml` sets `logging: CLOUD_LOGGING_ONLY` and that
    flag reads only Cloud Storage logs — use `gcloud beta builds log --stream`, or `builds
    describe` for the verdict.
  - **Deploy itself was refused by this session's permission classifier** ("Production Deploy" /
    "Permission Grant"), *before* executing — twice, on two different days, so the deploy was run by
    the user from the Claude Code prompt with `!` instead, with the output landing in the session so
    it could still be verified here. See slice 7.

- **Slice 7 (done 2026-10-19, pending review): email-ingestion is live in Google Cloud.**
  `https://email-ingestion-lznkorfm4q-ey.a.run.app`, revision `email-ingestion-00002-nf9`,
  image `email-ingestion:71d9c34`, **public** (`allUsers` → `roles/run.invoker`, the only such
  service), ingress All, min/max 0/3, 1 vCPU / 1 GiB, `wildalert-email-ingestion` service account.
  All six env vars and all three secret references were read back off the service template rather
  than trusted to the command line. **It answers on two URLs:** the `-ey` form above, which is what
  `describe` reports, and a project-number form
  (`https://email-ingestion-789527844011.europe-west3.run.app`) — both verified 200 against the same
  revision. The deploy output printed the project-number one, which is how it first got written down
  here. Harmless for this service because nothing mints tokens *for* it, but pick one form and keep
  it everywhere: an ID token's audience must match the URL string exactly.
  - **The deploy failed first, and the cause is worth keeping.** Git Bash's MSYS path conversion
    rewrote `--startup-probe=httpGet.path=/actuator/health/readiness` into
    `httpGet.path=C:/Program Files/Git/actuator/health/readiness` *before gcloud saw it*, so the
    probe hit `/`, got 404 twelve times, and the revision was failed after two minutes — while the
    container log showed a completely healthy app (`Started … in 9.55 seconds`, R2 initialised, the
    right ID-token audience, project resolved from the metadata server). The symptom accuses the
    container; the fault was in the shell. `MSYS_NO_PATHCONV=1` is **not** the fix — it breaks
    gcloud's own launcher, which needs the conversion to find `gcloud.py`. Quoting does nothing, and
    `//actuator/…` survives unconverted, which is no better. Worst of it: the mangled probe persists
    on the **service template**, so re-deploying without the flag inherits it. Cleared with
    `--startup-probe=""` (there is no `--clear-startup-probe`), which is also what made the second
    attempt a one-liner — everything else on the template was already right. Written up under
    *Troubleshooting* in `deploy/README.md`; recognition and notification use the same flag.
  - **Currently running Cloud Run's default TCP startup check**, not the HTTP probe. For this
    service that costs little — with no database, readiness only ever meant "Spring started". Put
    the HTTP probe back via the console's Health checks form, where no shell can mangle it.
  - **Verified against the live service:** `POST /api/emails` with no header → **401**, with a wrong
    secret → **401**; `/actuator/health` → `{"status":"UP","groups":["liveness","readiness"]}` and
    both probes → UP; `env`, `metrics`, `heapdump`, `beans` → **404** each, so a public URL leaks no
    config.
  - **Step 10e passed in full, including the one that matters.** A posted email returned
    **`matchedHunterId: a87cf72a-…`** — the proof that slice 5's ID token is minted from the
    metadata server *and* accepted by Cloud Run. Both side effects confirmed: the two
    `ImageReceived` events were pulled off `image.received.inspect` (correct `storageKey`,
    `senderEmail`, `hunterId`), and both objects are in R2 at exactly those keys, 8 bytes each,
    listed over the S3 API with the scoped token — so bucket, endpoint host and key pair are all
    right.
  - **The first attempt 403'd, and that was worth seeing.** Posted ~60s after the `run.invoker`
    grant, the lookup got a **403 from Cloud Run's own front door** (Google's HTML error page, not
    Spring's JSON) and the response was still a cheerful
    `200 {"matchedRecipient":null,"matchedHunterId":null}` with the photo stored — exactly the
    silent symptom `deploy/README.md` warns about, observed for real. Slice 1's degradation behaved
    as designed: one retry, then "treating it as no match, so the photo is still stored and
    published without a hunter". Identical request a minute later matched. **So the grant is
    correct and IAM propagation is the lag** — worth remembering before debugging a 403 that is
    only a minute old.
  - **Test data left behind, deliberately:** hunter `a87cf72a-3612-4263-bc4f-a789152157ec`
    (`e2e-test@wildalert.app`, inbound `pwjpd75ivta9@in.wildalert.local`, fake phone) and two 8-byte
    objects under `inbound/2026/10/19/` in R2. The hunter is the easiest way to re-run 10e; delete
    both when Phase 6 closes.

- **Slice 8 (done 2026-10-20, pending review): real inbound email works.**
  Worker written 2026-10-14 in `deploy/email-worker/` —
  `src/worker.js` POSTs the raw RFC-822 bytes to `/api/emails?envelopeTo=<message.to>` with the
  `X-Webhook-Secret` header, plus `wrangler.toml` and a setup/verification README.
  **`wildalert.app` is registered (2026-10-14, Cloudflare Registrar)** and the zone is served by
  Cloudflare's nameservers (`agustin`/`leia.ns.cloudflare.com`).
  **Email Routing is enabled and receiving mail (2026-10-14).** Verified from two resolvers
  outside Cloudflare (8.8.8.8 and 1.1.1.1): `MX` → `route1/2/3.mx.cloudflare.net` (priorities
  45/70/91) and `TXT` → `v=spf1 include:_spf.mx.cloudflare.net ~all`. The apex had no MX/TXT
  before this, so nothing was overwritten. A **temporary catch-all forwards to
  `animalrecognition@gmail.com`** (the verified destination address) and a real test email
  arrived there — so DNS, MX, SPF and the catch-all rule are all proven before our own service
  is involved. No DMARC record, which is fine while we only receive.
  **Live on 2026-10-20.** Worker deployed (`wildalert-email-in`, version `3e7572c7`) with two
  secrets — `INGESTION_URL` = `https://email-ingestion-lznkorfm4q-ey.a.run.app` and
  `WEBHOOK_SECRET` piped straight from Secret Manager so the value was never pasted or printed.
  Catch-all switched from *forward → Gmail* to **worker → `wildalert-email-in`**, and
  `INBOUND_EMAIL_DOMAIN=wildalert.app` set on user-account (revision `user-account-00002-w8n`), which
  moved the test hunter to `pwjpd75ivta9@wildalert.app` with no new token, as designed.
  - **Proven with two real emails from Gmail**, read off `wrangler tail` and the Cloud Run logs:
    `nobody@wildalert.app` → worker `delivered 41682B … -> 200`, stored, `hunterId: null`; then
    `pwjpd75ivta9@wildalert.app` → `delivered 41684B … -> 200`, **`hunterId:
    a87cf72a-…`**. Both photos in R2 (25086 bytes each) and both events pulled off
    `image.received.inspect`. Worker CPU time **1 ms** against the free plan's 10 ms budget, so
    streaming the body rather than buffering it is doing its job.
  - **Two Wrangler potholes.** `wrangler deploy` **uploads the script and then fails anyway** when
    the worker has no routes — it falls back to publishing on workers.dev, which needs a subdomain
    you may not have registered, so the command exits 1 while the worker is live and serving.
    `workers_dev = false` in `wrangler.toml` fixes it (committed). And `wrangler email routing rules
    update … --action-type worker` is refused by the CLI's own validation ("Catch-all rule only
    supports 'forward' or 'drop'") even though the dashboard and the REST API both allow it; the
    newer Wrangler that might fix it needs Node ≥22 and this machine has v20. The catch-all was
    therefore switched by hand in the dashboard.
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

- **Slice 9 (done 2026-10-22, pending review): both consumer images build, and notification can
  authenticate.**
  - **Build config takes a context.** `deploy/cloudbuild.yaml` gained a `_CONTEXT` substitution
    defaulting to `.`, so the two existing triggers are untouched while recognition passes
    `services/recognition`. It had to exist: recognition's Dockerfile does `COPY requirements.txt .`
    and `COPY app ./app`, which only resolve with the service directory as context — and the root
    `.dockerignore` excludes `services/recognition/`, so in a root context those files are not even
    sent. `notification:0340fa0` built in 120s, `recognition:0340fa0` in 360s.
  - **A typo cost nothing only because it was caught before the first run.** The `recognition-main`
    trigger had been created with `filename: deploy/claudbuild.yaml`; that build would have failed
    on a missing file, which reads nothing like "your context substitution is wrong". Fixed by
    `gcloud beta builds triggers export`, editing the file and re-importing —
    `gcloud builds triggers update github --build-config=…` rejects it with a bare
    `INVALID_ARGUMENT`. Worth checking `filename` on every new trigger.
  - **notification would never have sent an SMS.** `HunterClient` sent no credential, and
    user-account requires authentication, so Cloud Run would answer 403 *in front of the app*;
    `Forbidden` is not the `NotFound` it catches, so the push returns 5xx, Pub/Sub retries five
    times and the alert is dead-lettered — a service that looks healthy and texts nobody. Found by
    reading the code while writing the deploy form, not by a test. Ported `UserAccountAuth` +
    `GcpIdTokenUserAccountAuth` into `com.wildalert.notification.hunter` as a deliberate copy
    (≈30 lines of infrastructure, no domain; same call this project already makes for the event
    contracts), with `google-auth-library-oauth2-http` declared explicitly — email-ingestion only
    gets it transitively from the Pub/Sub starter. **The failure contract is inverted on purpose:**
    email-ingestion degrades to "no hunter" so a photo is never lost; notification throws, because
    the photo is already stored and a redelivery still gets the SMS out.
  - **`postgres-socket-factory` was missing too**, so `IDEMPOTENCY_STORE=postgres` could not have
    connected through its `socketFactory` URL. Added at user-account's version (1.28.4), which
    keeps the shared idempotency store usable rather than falling back to the per-instance one.
  - **Tests:** 3 new in `HunterClientTest` (no header by default, the header when configured, and
    an unmintable token thrown rather than swallowed); `:services:notification:build` green, 34
    tests, 0 failures.
  - **Deploy forms written** for both services as `CONSOLE.md` steps 10f and 10g. One claim in the
    first draft was wrong and the first deploy disproved it: the model is **not** loaded lazily on
    first request. `app/main.py`'s `lifespan` hook calls `get_classifier()` before the app accepts
    traffic (the `@lru_cache` in `classifier.py` is what makes that stick), so uvicorn does not
    listen until the weights are in and the **startup probe** is the gate, not the ack deadline.

- **Slice 10 (done 2026-10-22, pending review): recognition is live in Google Cloud.**
  `https://recognition-789527844011.europe-west3.run.app`, revision `recognition-00002-scn`, image
  `recognition:0340fa0`, private (Require authentication), ingress All, min/max 0/3, 2 vCPU,
  **8 GiB**, concurrency 1, request timeout 600s, startup CPU boost on, `wildalert-recognition`
  service account, R2 keys from Secret Manager.
  - **4 GiB is not enough, and the failure named the number:** the first revision died mid-load with
    `Memory limit of 4096 MiB exceeded with 4236 MiB used`. That figure is where it was killed, not
    its peak, so 8 GiB is headroom rather than a measurement. Everything else about that attempt was
    fine — torch 2.14.1+cpu, `YOLOv9c summary (fused): 156 layers, 25,321,561 parameters` — which
    confirmed the baked-in weights load with no network fetch.
  - **The model loads during startup, not on first request.** `app/main.py`'s `lifespan` calls
    `get_classifier()` before the app serves traffic, so uvicorn opens the port only once the
    weights are in: **61.5 seconds** here (11:10:00 → 11:11:02), against the default TCP startup
    probe's 240s. The ack deadline therefore covers queueing and inference, not the weight load —
    the opposite of what this file said a few hours earlier.
  - **Verified:** `/health` → 200 with an ID token, **403 without** one, so the private boundary
    holds. Nothing has been pushed to it yet — that needs `roles/run.invoker` for
    `wildalert-pubsub-push` and the subscription from step 11.

- **Slice 11 (done 2026-10-22, pending review): the whole pipeline runs in Google Cloud, end to end, on real email.**
  notification deployed (`notification-00001-nxt`, image `notification:6b29df5` — the one carrying
  the auth fix), both push subscriptions created, and every grant in place.
  - **notification's first revision proved four things at once:** `GcpIdTokenUserAccountAuth`
    initialised with the right audience (the morning's code, running), Flyway reached **Cloud SQL
    through the socket factory** (`PostgreSQL 18.6`), it created the `notification` schema and
    applied `V1 create processed event table` — so the shared idempotency store is live — and it
    started in 12.7s. Verified: health + both probes UP with an ID token, **403 without**,
    `/actuator/env` → 404. Flyway warns that PG18 is newer than it supports; the migration applied
    cleanly, and it is the same PG18-prod/PG17-local gap already recorded above.
  - **Subscriptions:** `image.received.recognition` → recognition `/events/image-received`, ack
    deadline **600s**; `animal.recognized.notification` → notification
    `/api/events/animal-recognized`, ack deadline **60s**. Both authenticate with an OIDC token from
    `wildalert-pubsub-push` and dead-letter after 5 attempts. The DLT topics carry no explicit
    grants: the Pub/Sub service agent already holds `roles/pubsub.serviceAgent` project-wide, which
    covers publish — to be proven by actually dead-lettering something rather than assumed.
  - **End-to-end on a real emailed photo (11:23):** worker → ingestion → R2 → `image.received` →
    recognition, which classified it **`species=wild boar confidence=1.00 low=False`** (6.4s
    inference, no model load — the instance was still warm from the deploy) → `animal.recognized`
    → notification, `attempt=1`. **The first time DeepFaune has classified a real photo in the
    cloud.**
  - **The first run produced no SMS, for the known reason.** email-ingestion's lookup timed out
    against a scaled-to-zero user-account (`Request cancelled`, 11:23:08), so the event carried
    `hunterId: null` and notification correctly declined: *"No SMS for event …: photo came from an
    unknown sender"*.
  - **The second run, with user-account warmed first, completed the product (11:34):**
    stored + published 11:34:47 with **no lookup warning**, recognition `attempt=1` 11:34:48,
    `species=wild boar confidence=1.00` published 11:34:55, notification `attempt=1` 11:34:56 →
    **`[FAKE SMS] to=+420100000000 body="WildAlert: wild boar detected (99% confidence)"`**.
    **Nine seconds from email to alert**, every hop on first attempt, no retries, nothing
    dead-lettered.
  - **Which pins the cold-start bug precisely:** same photo, same path, cold → no alert, warm →
    alert in 9s. It cost the alert in **two of three** real-email tests and is the default outcome
    after any idle period, not an edge case — a hunter emailing an idle system gets silence. It is
    now the one thing between this pipeline and a working product; see slice 1 for the three
    candidate fixes. Recommended: publish the recipient address on `ImageReceived` and resolve the
    hunter in notification, where Pub/Sub's retries already absorb cold starts — it also removes a
    synchronous dependency from the ingestion path.

- **Slice 12 (done 2026-10-26, pending review): a real SMS reached a real phone. The product
  works.** Photo emailed to `pwjpd75ivta9@wildalert.app` → R2 + `image.received` (08:06:05, hunter
  matched) → recognition **wild boar, confidence 1.00** (08:07:39) → notification `attempt=1`
  (08:07:54) → **GoSMS accepted for +420774584941** (08:07:56), and the SMS arrived. ~110s
  end to end, most of it recognition's cold start; no retries, nothing dead-lettered.
  - **Twilio was abandoned on cost, not capability.** It wants **$12/month to lease a number**, and
    its free alphanumiceric sender needs ~3 weeks of pre-registration in Czechia plus a paid
    account. Czech gateways have no monthly fee at all. BulkGate (cheapest on paper) **rejected the
    registration** — Czech A2P providers generally want a business entity — so GoSMS, which states
    it serves individuals, got the work. `TwilioSmsSender` stays in the codebase, unused, switchable
    by one env var.
  - **`GoSmsSender`** (`sms.provider=gosms`): OAuth2 client-credentials, token cached for the hour
    GoSMS grants (one token request per hour, not per SMS), refresh-and-retry once on a 401.
    Credentials are two per-secret Secret Manager entries; `GOSMS_CHANNEL` is a plain env var.
  - **The channel id came from the API, not the panel:** `GET /api/v1/` returns credit plus the
    channel list. Ours is `511762` ("SMS channel", source `GoSMS-test`).
  - **Two things cost a revision each, both avoidable.** A literal `CHANNEL_ID` placeholder reached
    the deploy twice: the property binds to an `Int`, so the container failed to start and
    `notification-00002`/`00003` are dead revisions. And the first `/messages/test` call returned
    *"Not enough credit"* — the 20 CZK sign-up bonus only lands once the phone number is verified.
    **`/api/v1/messages/test` validates credentials, channel, recipient and message length without
    sending or spending credit** — use it before every deploy.
  - **Real price is 1.20 CZK per SMS, not the 0.26 Kč headline**, because `GoSMS-test` is the trial
    channel. 20 CZK ≈ 16 alerts. The message is 45 chars, one part, no diacritics — Czech wording
    would halve the limit to 70.
  - **The response shape was guessed wrong and the defensive branch saved it.** A real send returns
    `{"recipients":{"invalid":[],"optedOut":[]},"link":"/api/v1/messages/47102867094"}` — **no `id`
    field**; the id is the last segment of `link`. The sender logged a warning and recorded
    `id=unknown` rather than throwing, which is what one of its tests pins: a gateway that has
    already accepted a message must not be made to send it twice over a parsing quibble. Now parsed
    properly, with a test carrying the verbatim response. 42 tests, 0 failures.

- **Slice 13 (done 2026-10-26, deployed and proven): the cold-start bug is fixed by moving the
  hunter lookup out of the webhook.** The recipient address now travels on the events and
  **notification** resolves it, so a scaled-to-zero user-account costs a Pub/Sub redelivery instead
  of the alert. email-ingestion makes **no calls to user-account at all** any more.
  - **Why this and not a bigger timeout or `min-instances=1`:** a timeout only widens the window,
    and min-instances gives up scale-to-zero (~$64/month for recognition's 8 GiB; user-account would
    be cheaper but still permanent). Moving the lookup puts it where a retry already exists.
  - **The wrinkle:** email-ingestion used to learn *which* recipient was the hunter's **from the
    lookup itself** (`candidates.firstNotNullOfOrNull { findByInboundAddress(it) }`). It now matches
    candidates against the configured **`inbound.email-domain`** — plain string work, no network.
    So `INBOUND_EMAIL_DOMAIN=wildalert.app` is **required on email-ingestion**; unset, every photo
    publishes with no recipient and nobody is ever texted.
  - **A privacy improvement fell out of it:** only an address on our own domain goes on the event.
    The hunter's personal Gmail (in the email's `To`) used to be a lookup candidate and could end up
    in logs; now it never leaves the parser. There is a test for it.
  - **A behaviour change worth knowing:** email-ingestion can no longer tell a real token from a
    made-up one, so mail to any address on our domain is forwarded and notification answers "not a
    hunter". Spam therefore still costs a storage write and a recognition run — same as before,
    since unmatched photos were already stored and classified, but the option of filtering early is
    gone. If it bites, the filter belongs in the worker or as a cheap token-shape check.
  - **Rollout is order-independent**: `recipient` is optional on both contracts and `hunterId` is
    kept as a fallback, so an old event in flight (or replayed from a dead-letter topic) still
    reaches its hunter. Tests pin both directions, including a legacy payload. Removing `hunterId`
    is a follow-up.
  - **The dead code is gone (same day).** `HunterLookupClient`, `UserAccountAuth`,
    `GcpIdTokenUserAccountAuth` and `HunterLookupClientTest` deleted, along with the
    `services.user-account.*` config and the `spring.http.client` timeouts — **email-ingestion now
    makes no outbound HTTP calls at all**, so those timeouts guarded nothing. The ID-token work from
    slice 5 therefore survives only in notification, which is where it was always needed: that
    service really does call a private user-account. Tests: 26 in email-ingestion (was 35; the nine
    deleted ones covered the deleted client).
    **Cloud side done too (2026-10-26):** email-ingestion `00004-lb7` carries only
    `SPRING_PROFILES_ACTIVE`, `STORAGE_PROVIDER` and `INBOUND_EMAIL_DOMAIN` (the startup log no
    longer constructs an ID-token bean at all), and its `roles/run.invoker` on user-account is
    **revoked** — that policy now lists `wildalert-notification` alone. This mattered more than
    tidiness: email-ingestion is the one public, internet-facing service, so until the revoke, a
    compromise of that webhook inherited an identity that could query the private phone-number
    service directly. Proven afterwards with a photo: **no 403 anywhere**, SMS delivered
    (11:30:02 → 11:32:06).
  - **The cold-start retry reproduces exactly, which makes it tunable.** Both post-fix runs failed
    `attempt=1` on a `Read timed out` and succeeded on `attempt=2` about 24s later. It is not bad
    luck: notification's read timeout is **10s** and a cold user-account takes longer, so the first
    alert after idle always pays a wasted attempt. Raising that timeout to ~25s would likely let
    the first attempt through — safe here, because the ack deadline is 60s and the retry stays as
    the net. Note this is the *opposite* call to email-ingestion's, where a longer timeout was the
    wrong fix because failure there lost the photo rather than delaying it.
  - **Tests:** 104 Kotlin (24 user-account, 35 email-ingestion, 45 notification) + 34 Python, all
    green. The cross-language contract test now carries a payload **generated from `events.py`
    itself** rather than hand-written.
  - **Deployed and proven against a genuinely cold stack (2026-10-26).** email-ingestion
    `00003-dd6` (with `INBOUND_EMAIL_DOMAIN=wildalert.app`), recognition `00003-fl9`, notification
    `00005-qv8`. A photo emailed with nothing warmed:
    - 09:52:05 stored + published with a `recipient`
    - 09:53:25 recognition received it after a **73s cold start**; 09:53:31 `wild boar` 1.00
    - 09:53:45 notification `attempt=1` → **`Read timed out`** on `by-inbound-address`
      (user-account scaled to zero) → 5xx
    - 09:54:09 Pub/Sub redelivered, **`attempt=2`** → 09:54:10 **SMS sent** (GoSMS id 47098239900)

    **The failure we were trying to survive actually occurred, and the alert arrived anyway** —
    24 seconds late instead of lost. Under the old design that same timeout produced a 200, a
    stored photo and silence. Email to SMS in **2m05s across two cold starts**.
  - **Two details worth keeping:** the `ProcessedEvents` claim sits *after* the lookup, so the
    failed attempt never claimed the event and the retry was free to send — had the claim come
    first, the redelivery would have been deduped into silence. And the GoSMS `link` id parsing
    worked on a real send (`id=47098239900`, not `unknown`).

- **Slice 14 (done 2026-10-26, pending review): the inbound-domain mismatch slice 13 left behind,
  and notification's read timeout.** Two config fixes, no behaviour change beyond them.
  - **email-ingestion defaulted to a domain nothing mints.** Slice 13 made the recipient match a
    configured domain instead of a lookup, and email-ingestion's default stayed
    `in.wildalert.local` — a leftover from the subdomain design the Cloudflare apex catch-all
    replaced — while user-account mints on `wildalert.local`. On top of that, `compose.yml` handed
    `INBOUND_EMAIL_DOMAIN` to user-account only. So **the whole local pipeline was silently
    broken**: no recipient was ever matched, every photo published with none, the webhook still
    answered 200, and no SMS was ever sent. Prod was unaffected — both cloud revisions carry
    `wildalert.app` explicitly — which is exactly why nothing noticed. Both defaults are now the
    same string and compose passes the variable to both services.
  - **Every existing test passed throughout, and that is the lesson.** The email-ingestion tests
    take the domain from the same `application.yml` they are validating, so they agreed with a
    value that agreed with nothing else. `InboundDomainContractTest` now reads the two services'
    `application.yml` files and `compose.yml` off disk and asserts they use the same variable and
    the same default — a contract test, not a unit test, because the bug lived *between* the files.
    It caught one straggler immediately (an uppercase `IN.WILDALERT.LOCAL` fixture a case-sensitive
    rename had missed).
  - **notification's read timeout 10s → 25s**, the tunable slice 13 identified from two measured
    runs: a cold Spring Boot + Flyway + Cloud SQL start takes longer than 10s, so `attempt=1` died
    on a read timeout every time and the SMS arrived ~24s late on `attempt=2`. 25s stays well
    inside the 60s ack deadline and the redelivery remains the net. **Opposite call to
    email-ingestion's**, where a longer timeout was the wrong fix because failure there lost the
    photo instead of delaying it — the asymmetry is the point.
  - **Also removed:** `USER_ACCOUNT_BASE_URL` from email-ingestion's compose block, dead since
    slice 13 deleted its last outbound HTTP call. The `in.wildalert.local` spelling is gone from
    every service and test fixture; it survives only in `deploy/email-worker/README.md` as history.
  - **Tests:** 97 Kotlin (24 user-account, 28 email-ingestion, 45 notification) + 34 Python, all
    green, `contextLoads` included with Postgres up.
  - **Not deployed, and it does not need to be:** both cloud revisions set the domain explicitly,
    so the default never applied there. The read-timeout change does need a notification revision
    to take effect — until then the first alert after idle still pays the wasted attempt.

## Done — Detection History (Phase 8, taken out of order, 2026-10-26)
A fifth service, `services/detection-history`: the hunter-facing record of what has been
photographed, and the first use of **jOOQ**. Built in parallel with the frontend's slice 1 and
slice 14 above, because it needs nothing from the frontend, needs no cloud access, and shared no
files with either. **Not deployed** — code only.

What it does: consumes `AnimalRecognized`, stores one row per recognised animal, and serves
`GET /api/detections` (one hunter's sightings, newest first, species filter, paged) and
`GET /api/detections/summary` (per-species counts, last seen, average confidence).

The decisions worth remembering:
- **A second push subscription on `animal.recognized`, not a change to notification.** This is
  fan-out, and the first time the broker earns its keep beyond decoupling: each consumer gets its
  own copy and acks independently, so detection-history can be down, slow or redeploying without
  delaying an SMS, and neither consumer knows the other exists. `compose.yml` now provisions
  `animal.recognized.history` alongside `animal.recognized.notification` (5 subscriptions).
- **Attributed by `recipient`, not by hunter id — so no synchronous call to user-account.**
  Notification has to call it (it needs the phone number); this service needs only an identity,
  and the event already carries one. A cold-starting user-account may delay an SMS, but it must
  never be able to lose history. The recipient→hunter mapping stays in user-account, which the
  frontend already queries.
- **Idempotency is the primary key.** `source_event_id` is the PK and inserts use
  `on conflict do nothing`. Notification needs a separate claim/release table because sending an
  SMS is an effect *outside* the database; here the write *is* the effect, so one statement covers
  at-least-once delivery with no race.
- **jOOQ codegen from the Flyway SQL, no database.** `DDLDatabase` replays
  `db/migration/*.sql` into in-memory H2 and generates from that, wired as a plain `JavaExec`
  (`generateJooq`, automatic before `compileKotlin`) rather than a jOOQ Gradle plugin. So the
  build needs no Postgres — which matters on this machine — and renaming a column in SQL breaks
  the Kotlin at compile time. The cost: the migration must be spelled so H2 can parse it too, so
  `timestamp with time zone` not `timestamptz`, `varchar(n)` not `text`.
- **A legacy event carrying only `hunterId`** (a replay from before the recipient switch) is
  stored rather than dead-lettered, behind a check constraint requiring one owner column or the
  other. It will not appear in a recipient-keyed read; keeping the row costs nothing, and
  resolving the id back to an address would need exactly the user-account call avoided above.
- **An event naming no owner at all gets a 400, not a 500** — retrying cannot add a hunter to it,
  so it goes straight to the dead-letter topic. A database failure is deliberately *not* caught
  and surfaces as 500 so Pub/Sub redelivers.

Verification: **44 tests, all passing, no infrastructure** —
`./gradlew :services:detection-history:test`. Including `JooqDetectionStoreSqlTest`, which renders
the real Postgres SQL from a connection-less `DSLContext` and asserts the things that quietly break
a reporting endpoint (schema qualification, `order by occurred_at desc`, the `group by`,
`on conflict do nothing`). It caught one wrong assumption already: jOOQ renders paging as the SQL
standard `offset … rows fetch next … rows only`, not Postgres's `limit/offset`.

Two things it taught about the toolchain, both now written down in the code:
- jOOQ's XML config rejects `--` inside an XML comment, which is easy to type in prose.
- Mockito's `eq()`/`capture()` return null, and Kotlin inserts a **caller-side** null check when a
  non-null parameter of a *Kotlin* function gets a nullable expression — so
  `verify(service).summary(eq(x), captor.capture())` throws `eq(...) must not be null` before
  Mockito is consulted. Mocks of Java types never hit this. Helpers in `DetectionControllerTest`
  substitute a non-null placeholder for the discarded return value.

The migration was checked against a real Postgres 17 (a throwaway container, one WSL2 session):
the DDL applies, a duplicate `source_event_id` insert is absorbed by `on conflict do nothing`, the
check constraint rejects a row owned by nobody, the index is created, and the per-species aggregate
returns `avg_conf 0.75` — the same number `InMemoryDetectionStoreTest` expects, so the fake and the
database agree.

### Verified end to end on real Postgres (2026-10-27)
The gap recorded above — "the service *running* on Postgres, Flyway on startup and jOOQ
**executing** rather than rendering" — is now closed, as a prerequisite for deploying. The built
image was run against the local Postgres with `HISTORY_STORE=postgres` (one WSL2 session, because
containers do not survive between shell invocations here):
- **Flyway migrated at startup** — `detection_history.detection` and
  `detection_history.flyway_schema_history` both created; readiness came up `{"status":"UP"}`.
- **The push endpoint stored an event** (204), and **pushing the identical event again also
  returned 204 and added no second row** — the primary-key idempotency working against a real
  database, not a `ConcurrentHashMap`.
- **jOOQ executed both reads correctly**: `GET /api/detections` returned the row with
  `occurredAt` round-tripped exactly (`2026-10-27T22:14:00Z`), `GET /api/detections/summary`
  returned `sightings: 1, averageConfidence: 0.97`, and a species filter matching nothing returned
  `[]`. Rendering was already tested; this is execution.
- **An event naming no owner returned 400**, so Pub/Sub would dead-letter it rather than retry.

Also added for the deploy: **`services/detection-history/Dockerfile`**, which the service was
missing entirely — so it could not have been built at all. jOOQ codegen runs inside the build
stage (no database needed, but it does need the network for `jooq-codegen` and H2); the image was
built locally to confirm that before spending a Cloud Build run.

Still open: the deploy itself (service account, Cloud Run revision, push subscription — the
commands are Step 10i in `deploy/CONSOLE.md`); presigned photo URLs so the dashboard can show the
images (`storage_key` is stored, the bucket stays private); and **auth before the read API is
exposed** — the inbound address is currently the only thing identifying a hunter and it travels as
a query parameter, so this service must stay `Internal` + *Require authentication* and must not get
a public URL.

## Remaining Phase 6 work
- **Pub/Sub push subscriptions still need the *consumers* deployed** (email-ingestion only
  publishes; a push subscription needs a URL to push *to*): the two authenticated **push**
  subscriptions, `roles/run.invoker` for `wildalert-pubsub-push` per consumer service, and the
  service agent's dead-letter grants (the subscription form offers those with one click).
  Separately: `image.received.inspect` was a pull subscription on the **live** topic, so every event
  accumulated there unacked for 7 days — useful for 10e, but a second consumer nobody would
  remember. **Deleted 2026-10-22**, once `image.received.recognition` was proven end to end. Each
  live topic now has exactly one subscription; the two `*.dlt.inspect` pull subscriptions stay,
  because a DLT with no subscription discards what it receives.
- Then recognition and notification; notification also needs `roles/run.invoker` on user-account.
  **Notification has no service account yet** — the four that exist are user-account,
  email-ingestion, recognition and pubsub-push. Create `wildalert-notification` before deploying
  it: without one it inherits the default compute service account, which still holds
  `roles/editor`, so its user-account lookups would succeed *by accident* and slice 5's ID-token
  path would never actually be exercised.

## Started early — Phase 8 (frontend)
- **Slice 1 (2026-10-26, verified in Cloud Build; browser check still outstanding): hunter
  registration UI.** The product had no
  front door — every row in `hunter` was inserted by hand or by `curl` — so this is one screen:
  register, then show the hunter their personal inbound address with setup help. New top-level
  `web/`: React + TypeScript + Vite + Tailwind v4, **not** a Gradle module, so `./gradlew build`
  is untouched. Nothing in `services/` was changed.
  - **It needs no backend change and no CORS, by construction.** The browser calls a same-origin
    `/api/hunters` and Vite's dev proxy forwards it to `user-account` on :8081. In production the
    same job falls to **Spring Cloud Gateway**, which will serve the built files *and* proxy
    `/api/**` to the private `user-account` with an ID token — one origin either way, so the React
    code is identical and the decision could be deferred. That gateway is the next slice.
  - **Why an edge is needed at all:** `user-account` is deployed `Require authentication` and
    serves phone numbers, so a browser cannot reach it and should not be able to. Every
    `roles/run.invoker` grant on it belongs to a Google service account.
  - **Shape:** pure logic (E.164/email validation, RFC 9457 error parsing) sits in its own modules
    so Vitest runs in a plain **node** environment — no jsdom, no DOM-testing library. 400 field
    errors land on the matching input; a 409 duplicate shows against the email field rather than
    as a banner; a validation error for a field the form does not render is surfaced instead of
    dropped, because silently discarding one is how a form becomes inexplicably stuck.
  - **`types.ts`/`validation.ts` deliberately duplicate `HunterDtos.kt`** (there is no shared
    schema). Each copy names the other in a comment so the pair is findable.
  - **Blocked before any verification could run: `npm install` does not work on the Oracle
    corporate network.** Its web gateway (McAfee, branded *Oracle Secure Web Gateway*) re-signs
    TLS to `registry.npmjs.org` and sends **only the leaf certificate**; the issuing intermediate
    is not sent, is in no Windows certificate store, and is not fetchable — the leaf's AIA
    extension carries only OCSP, no `caIssuers` URL. Node ships its own CA bundle and has no
    `--use-system-ca` before v22, so it cannot build a chain:
    `UNABLE_TO_VERIFY_LEAF_SIGNATURE`. Adding the `oracle-ztna-root-ca` root via
    `NODE_EXTRA_CA_CERTS` does **not** fix it — the gap is the intermediate. `npm install` hangs
    ~15 minutes retrying and writes nothing.
    - **A misleading symptom, recorded so it doesn't cost an hour twice:** `npm view <pkg>`
      *succeeds* for anything already in `%LOCALAPPDATA%\npm-cache` and fails only for packages
      never installed here. It looks package-specific. It isn't — a direct registry request
      fails for every package, from Node and from PowerShell alike.
    - **Decided: no TLS bypass.** Verification was moved to **Cloud Build** instead, where the
      registry is reachable: `deploy/cloudbuild-web.yaml`, submitted with
      `gcloud builds submit web --config deploy/cloudbuild-web.yaml`. It installs, runs the
      tests and runs the real build; nothing is deployed and no image is pushed — the exit code
      is the point. Local `npm run dev` still needs one install from a non-blocked network.
- **Verified in Cloud Build (build `fcf102dd`, 43s, 2026-10-26):** install clean (58 packages),
  **`tsc --noEmit` with no type errors**, **20 tests passing** in 2 files, and a real production
  build — 22 modules → `dist/` with 229.32 kB JS (72.01 kB gzipped) and 17.54 kB CSS. That the
  CSS is 17.5 kB rather than near-empty is the evidence Tailwind v4 is actually wired up.
  - **Resolved versions** (the ranges in `package.json` were corrected to these afterwards):
    `vite@8.3.5`, `vitest@5.0.5`, `tailwindcss`/`@tailwindcss/vite@4.3.3`,
    `@vitejs/plugin-react@6.1.2`, `react`/`react-dom@19.3.1`, `@types/react`/`@types/react-dom@19.3.0`,
    `typescript@5.9.3`. TypeScript is held at 5.9 deliberately — the current `typescript@7.0.2` is
    the new native compiler, not a change to take on a learning project mid-slice. Two of the
    guesses made offline were wrong and are now fixed: **vitest was a whole major behind** (4 vs
    5.0.5) and vite had moved 8.2.0 → 8.3.5.
  - **A trap worth recording: the npm bundled with `node:22` (10.9.9) cannot install this
    project at all.** It throws `Cannot read properties of null (reading 'edgesOut')` from
    arborist's own `#loadPeerSet` while walking **vitest's optional peer dependencies**
    (`@vitest/*`, `jsdom`, `happy-dom`, then `canvas`). It is an npm bug, not a bad
    `package.json` — the error names nothing useful and looks like a broken manifest. Upgrading
    to npm 12.2.1 in the build step fixes it outright; `--legacy-peer-deps` would too.
  - **Still outstanding:** the dev proxy carrying a real registration against a running
    `user-account`, and any look at the UI in a browser — neither is possible in Cloud Build.
    Also the two `ProblemDetail` bodies in `problemDetail.test.ts` are written from
    `GlobalExceptionHandler.kt` and Spring's documented shape, **not captured from a running
    service**; they are marked as such in the file and must be reconciled against real 400/409
    responses. A guessed response shape is exactly what bit us in slice 12. The 20 green tests
    prove the parsing logic is self-consistent, not that the server sends that shape.
  - ~~No `package-lock.json` yet~~ — **done in slice 2 below.** It is generated in Cloud Build and
    published as a build artifact, because it cannot be produced on the dev machine at all.

- **Slice 2 (2026-10-26, built and tested; not deployed): the gateway — a public front door for
  the UI.** `services/gateway`, a new Gradle module. It serves the built React app out of its own
  jar and forwards exactly one call to the private user-account, carrying a Google ID token. This
  is what makes slice 1's form reachable by an actual hunter, and it cashes in the **Spring Cloud
  Gateway** learning target.
  - **The servlet flavour, not the reactive default:** `spring-cloud-starter-gateway-server-webmvc`
    (Spring Cloud **2025.0.3**, the train that pairs with Boot 3.5.x). Every other service here is
    servlet Spring MVC, this one serves static files — which is the plain-MVC path — and the
    ID-token code it reuses from notification is written against `RestClient`, which is what
    Gateway MVC proxies with. The reactive gateway would have dragged in WebFlux and a second
    mental model for nothing at this size.
  - **The route list *is* the security model, and it is one method and one path.** user-account
    exposes `GET /api/hunters/{id}`, `by-email` and `by-inbound-address`, and **every one of them
    returns a hunter's phone number**. A convenient wildcard route under `/api/hunters` would have
    published all three to anyone who guessed the URL. Only `POST /api/hunters` is proxied;
    everything else 404s from the gateway and never reaches user-account. Two tests pin that,
    including asserting the stub upstream received *nothing*. Opening more routes needs
    authentication first (Phase 9): a hunter should see their own record, which means knowing who
    is asking.
  - **Serving the UI from the same service is what deletes CORS from the problem.** One origin for
    the page and the API — the same arrangement Vite's dev proxy fakes locally. There is still no
    CORS configuration anywhere in the repo, which was the point.
  - **The Dockerfile is the only one here with two toolchains**: a `node:22` stage runs `npm ci`
    and `npm run build`, and the result is copied into the Gradle module's resources *before*
    `bootJar`, so the static files end up inside the jar. One artifact, one deploy, and no way for
    the page and the API it calls to be different versions of each other. `npm run build` runs
    `tsc --noEmit` first, so a type error fails the image build instead of shipping.
  - **`classpath:/static/` is deliberately empty in the repo.** No placeholder `index.html`: if the
    copy ever failed, a 404 is an unambiguous signal, where a stub page would look like a working
    deploy serving the wrong thing.
  - **Verified:** `:services:gateway:build` green — **7 tests**, 0 failures, no compiler warnings.
    Proxying is tested for real against a JDK `HttpServer` stub standing in for user-account (a
    request leaves the JVM over HTTP and comes back), so it needs no Postgres, no real
    user-account and no extra test dependency. Full-repo `./gradlew build` also green: **148
    Kotlin tests across 33 classes, 0 failures**, so nothing regressed.
  - **The image was built *and run* in Cloud Build (`66cab5e5`, 2m10s, nothing pushed).** Building
    it is not evidence that the UI got inside the jar, so the container was started and curled:
    - health **200 after 1 second** — it loads no model and opens no database pool
    - `GET /` → **200**, and the HTML references its hashed bundles
    - `/assets/index-Ba2nHyTl.css` and `/assets/index-_0uauuI-.js` → **200 each**. Those are the
      same filenames the web-only build produced, so that exact `dist/` is in the jar
    - all three lookup endpoints → **404**, so the allowlist holds at runtime and not just in a
      unit test
    - `POST /api/hunters` → **500** against a deliberately unreachable upstream, with a connect
      exception in the logs: the route exists and tried to forward. A 404 there would have meant
      it was never wired up
    - **An earlier version of this check proved nothing and still passed**, which is worth
      recording: it ran `unzip -l` inside the JRE image, where `unzip` does not exist, and the
      failure was swallowed because the exit status came from the `head` at the end of the
      pipeline. `set -o pipefail` in the outer shell does not reach inside `sh -c`.
  - **Known rough edge:** an unreachable user-account surfaces as a **500**, not a 503. The
    `IdTokenUnavailableException` path is mapped, but Gateway MVC's own connect failure is not.
    Worth mapping so the browser can honestly offer a retry.
  - **Two traps this cost, both now in `AGENTS.md`:**
    - **Kotlin block comments nest.** Writing an API path with a wildcard in a KDoc comment puts a
      `/` directly before a `*`, which opens a nested comment that never closes. The build fails
      with "Unclosed comment" pointing at the *end* of the file, which tells you nothing.
    - **A Kotlin default parameter value on a `@Configuration` class breaks CGLIB.**
      `No default constructor found` for `ApiRoutes$$SpringCGLIB$$0`, at context startup. Fixed
      with `proxyBeanMethods = false`, which is also simply correct here — the class declares one
      `@Bean` and calls no other.
  - **`package-lock.json` now exists and the web build uses `npm ci`.** It was generated in Cloud
    Build and published as an artifact (105 packages, lockfileVersion 3), because the dev machine
    cannot reach the registry at all. `npm install --package-lock-only` does *not* work for this:
    npm 12 refuses it with `EALLOWREMOTE`, because `@tailwindcss/oxide-wasm32-wasi` is specified as
    a remote tarball URL. A full install takes ~7s anyway.
  - **Deploy steps written** as `CONSOLE.md` **step 10h**, not executed. It needs a service account
    that does not exist yet (`wildalert-gateway`) — create it *before* deploying, or the service
    inherits the default compute account, which still holds `roles/editor`, so its user-account
    calls would succeed **by accident** and the ID-token path would never be exercised. That is
    exactly the trap notification hit in slice 11.
  - **Deploying this changes the open-registration risk from theoretical to real.** The decision to
    defer that fix rested on nothing being publicly reachable. The gateway is the second public
    service (email-ingestion is the other), so once it is live anyone can create hunters at will —
    each a Cloud SQL row and a future SMS cost, and `max-instances` is a spend cap, not a rate
    limit. Add Cloudflare Turnstile to the form, or front it with a Cloudflare rate-limiting rule,
    before it sits on the internet for longer than a test. Flagged at the top of step 10h too.
- **Slice 3 (2026-10-27): the local install finally happened, and the stack ran end to end.**
  `npm ci` from outside the corporate network: **58 packages in 2 seconds**, and the local build
  produced `index-Ba2nHyTl.css` — **the same hash Cloud Build produced**, so the lockfile really
  is reproducing that build. 20 tests and `tsc --noEmit` green on this machine too.
  - **A real registration went through the Vite proxy into Postgres.** Postgres in WSL2 →
    `user-account` on :8081 (up in 7s) → Vite on :5173. `POST /api/hunters` through the proxy
    returned **201** with `inboundAddress: ew3px7356krb@wildalert.local`, and the email came back
    **lowercased** (`Browser.Check@Example.COM` → `browser.check@example.com`), which is exactly
    why the success panel renders the response rather than what was typed.
  - **Both `ProblemDetail` fixtures were confirmed character-for-character.** The inferred 400 and
    409 bodies matched the real ones exactly; the test file now says *verified* instead of
    *not yet reconciled*. The guesses were right — but they were still guesses until now.
  - **A real bug the live run caught, now fixed.** `"Browser.Check@Example.COM "` with one
    trailing space is **rejected** with `{"email":"must be a valid email address"}`: Jakarta's
    `@Email` validates the **raw** body, and `HunterService.register` only trims *afterwards*.
    The client trims before its own check, so it would have said the address was fine and the
    server would have disagreed — the most confusing failure a form can produce, and browsers
    leave trailing spaces behind routinely when autofilling. Fixed with `forSubmission()` in
    `validation.ts`, which trims email and phone before both validating and sending; 3 tests.
    Web tests now **23**.
  - **A second assumption checked while a real server was up:** `hunter@localhost` (no dot in the
    domain) returns **201**, so the deliberately lenient client-side email check was correct not
    to reject it. That test's comment now cites the live result instead of reasoning about
    Jakarta.
  - **Node 20 is a problem to resolve, not a blocker today.** `npm ci` warns
    `EBADENGINE: vitest@5.0.5 requires node ^22.12.0 || ^24 || >=26, current v20.19.5`. It runs
    fine regardless — 23 tests pass — but it is an unsupported configuration that any vitest patch
    could break, and **Node 20 is past end-of-life**. Vite 8 also wants `^20.19 || >=22.12`, so
    this stack is asking for Node 22+. Recommended fix is upgrading Node (nvm4w is already
    installed) rather than pinning vitest back to 4 to prop up an EOL runtime. Cloud Build runs
    `node:22` and never saw this, which is how the mismatch got in.
  - **Still outstanding:** nobody has *looked* at the UI in a browser. The data path is now proven
    end to end, but the three visual states (empty form, field errors, success panel), dark mode
    and the copy button are unverified — that needs a human with the dev server open.

- **Slice 4 (2026-10-27): the subscription plan is gone.** `FREE`/`BASIC`/`PRO` was written in
  Phase 1 and **never read by anything** — no branch in `SmsPolicy`, nothing in notification, no
  pricing attached. It was stored, returned and settable, and the registration form asked new
  hunters to pick between three options that had no effect, which is worse than not asking.
  - **Replaced by prepaid credit**, designed as its own feature and not yet built. The reasoning: SMS is ~90% of cost and strictly per-message, so credit prices what we actually
    spend, where a tier prices capacity nobody can size in advance — trail-cam traffic differs by
    an order of magnitude between a game trail and a garden. It is also a **hard spend cap**, which
    is a stronger answer to the open-registration abuse gap than a CAPTCHA: an abuser who has not
    paid cannot cost us an SMS. Deliberately split: this slice only removes the dead concept.
  - **Removed:** `Plan.kt`; the field from `Hunter`, `RegisterHunterRequest`, `UpdateHunterRequest`,
    `HunterResponse` and `HunterService`; Flyway **`V3__drop_hunter_plan.sql`**; the plan radio
    group from `RegisterForm.tsx`, the row from `InboundAddressPanel.tsx`, and the `Plan` type,
    `PLANS` and the `'plan'` member of `FormField` from the web contract. 13 test fixtures updated.
  - **Verified against the running stack, not just compiled.** Full `./gradlew build` green (148
    Kotlin tests, 33 classes, 0 failures) and web green (23 tests, `tsc --noEmit` clean; the bundle
    shrank 229.40 → 228.54 kB and the CSS 17.54 → 17.32 kB, which is the radio-group markup and its
    styles going away). Then, with Postgres and a freshly started user-account: Flyway reported
    **`Current version of schema "public": 3`**, and `POST /api/hunters` with **no plan field**
    returned **201**, through the Vite proxy as well as directly.
  - **Rollout is safe for clients that still send `plan`.** A request carrying `"plan":"PRO"` still
    returns 201 — Spring Boot leaves Jackson's `FAIL_ON_UNKNOWN_PROPERTIES` off, so the field is
    ignored rather than rejected. A browser tab holding the old bundle, or an old gateway image,
    therefore keeps working. Verified, not assumed.
  - **A sharp edge worth knowing for local dev:** applying V3 while an *old* user-account was still
    running made every registration 500 — the running code still inserted a column that no longer
    existed. Harmless locally, but it is a reminder that this migration is **not** backward
    compatible with the previous revision. In the cloud, deploy the new image *before* (or with)
    the migration, not after; Cloud Run's gradual rollout would otherwise have old and new
    revisions live at once, and the old one would fail every write.

## In progress — prepaid credit (replaces the deleted plan enum)

The design lives in a plan document outside the repo, decided over 2026-10-26/27: credit is money
in CZK haléře, **recognition costs the hunter 1.00 and an alert a further 1.50** (we pay GoSMS
1.20 — the difference is the margin), top-ups are any amount from 50 to 2000 CZK through Stripe
Checkout, and a new hunter is granted 2500 haléře. Seven slices; each ships inert behind a flag
and is switched on by the next.

- **Slice 1 (2026-10-27, done): the ledger exists, nobody spends it.** `V4__create_credit_tables.sql`
  plus a `com.wildalert.useraccount.credit` package — `HunterCredit` (the balance, one row per
  hunter), `CreditLedgerEntry` (append-only history), repositories and `CreditService`. The signup
  grant is written inside `HunterService.register`'s **existing transaction**, so a hunter can
  never exist without a balance and a rejected duplicate leaves no orphan credit row.
  `creditBalanceMinor` was added to `HunterResponse` and to `web/src/types.ts`.
  - **Nothing debits anything yet** — no charge endpoint, no Stripe, no notification or
    recognition change, no UI. That is deliberate: this slice is only the money being *there*.
  - **The balance is a cache and the ledger is the truth.** `balance_minor` must always equal the
    sum of that hunter's entries; the column exists so a future charge is one indexed read and one
    guarded update rather than a re-sum of history. A test pins the equality, and a reconciliation
    job across all hunters is a later slice.
  - **Two constraints and a partial unique index do the real work**, and are tested against a live
    Postgres because a mock cannot test a constraint: `balance_minor >= 0` (so any future code
    that forgets the guarded update fails loudly instead of running up silent debt),
    `amount_minor between -10000 and 10000000` (a debit amount will arrive over HTTP from another
    service, so it is input, not truth), and a **partial** unique index on
    `(entry_type, external_ref)` — the whole idempotency mechanism. Partial because grants and
    adjustments have no external ref and many nulls must coexist; keyed on both columns because
    one event will later charge a `RECOGNITION_DEBIT` *and* an `SMS_DEBIT` under the same
    `sourceEventId`. There is a test for each of those three properties.
  - **Verified live, not only in tests.** `POST /api/hunters` returned **201 with
    `creditBalanceMinor: 2500`**, and the database shows the balance row and a matching
    `SIGNUP_GRANT` ledger entry (`amount 2500`, `balance_after 2500`, null `external_ref`)
    committed together. The **migration backfill was checked against the real dev database with
    rows already in it** — 5 existing hunters, 5 credit rows, 5 ledger rows, every balance 2500 —
    which is how `V2`'s backfill was verified too.
  - **Tests:** 158 Kotlin across the repo (user-account 34, was 24), 0 failures; web 23 and
    `tsc --noEmit` clean.
  - **Two things cost a build each, both worth remembering.**
    - **Postgres `char(3)` is `bpchar`, and Hibernate's `validate` rejects it** against a Kotlin
      `String`: *"found [bpchar (Types#CHAR)], but expecting [varchar(3)]"*. It broke
      `contextLoads` too, so it looked worse than it was. Fixed by making the column `varchar(3)`,
      which is the better choice anyway — Postgres gives `char(n)` no advantage and blank-pads it.
    - **`EntityManager.flush()` does not get Spring's exception translation.** A constraint
      violation surfaces there as a JPA `PersistenceException`, not
      `DataIntegrityViolationException`, so three constraint tests failed while the constraints
      were working perfectly. Repository methods (`saveAndFlush`, `flush`) are translated; the
      test now goes through those.
  - **A local-dev note:** editing an already-applied migration changes its checksum and Flyway
    then refuses to start. Fixing V4 meant dropping both tables and deleting its
    `flyway_schema_history` row by hand. Fine for a migration that has never left this machine —
    not an option once one has been deployed.

- **Slice 2 (2026-10-29, done): the charge endpoint, still nobody calls it.**
  `POST /api/hunters/by-inbound-address/charges` (and a `{id}` variant for the legacy `hunterId`
  fallback) resolves the hunter **and** debits them in one call, returning
  `{hunterId, phone, active, outcome, balanceAfterMinor, notice}`. Outcomes are `CHARGED`,
  `ALREADY_CHARGED`, `INSUFFICIENT`, `INACTIVE`. Nothing calls it yet.
  - **One call, not two,** because the caller needs the phone number *and* permission to send.
    Splitting it would double the cold-start exposure on a scaled-to-zero service and create a
    looked-up-but-not-charged state — and it lets "may this hunter be texted?" be decided inside
    the transaction holding the money, which is the only race-free place for it.
  - **The three properties, and what actually enforces each:**
    - *A redelivery must not be charged twice.* A fast-path ledger lookup answers the ordinary
      case without touching the balance, but **the partial unique index is the guarantee** — if a
      racing replica charged the same event after that check, the insert violates it and the whole
      transaction rolls back, debit included. The retry then sees `ALREADY_CHARGED`.
    - *Replicas must not overdraw.* `update … where balance_minor >= :cost` does the arithmetic
      and the check in one statement, under the row lock the update itself takes. No
      read-then-write in Kotlin, so no window to lose.
    - *A refusal leaves no trace.* `INSUFFICIENT` and `INACTIVE` write nothing — so there is
      nothing to roll back and nothing to explain in a statement. A refusal is logged, not
      recorded.
  - **`ALREADY_CHARGED` is a success, not an error.** The charge means "this event is paid for",
    which stays true across every redelivery — which is why there is no refund in the hot path and
    no compensating call to get wrong.
  - **Told once per depletion**, via the `warning_level` move: only the transaction that actually
    raises the level gets a notice back, so thirty photos on an empty balance produce one message.
  - **Verified live against the running service**, end to end on one hunter:
    `RECOGNITION_DEBIT` 100 → 2400, then `SMS_DEBIT` 150 **under the same event id** → 2250 (the
    two-part price working, because the key is `(entry_type, external_ref)` and not the event
    alone), the same alert charge again → `ALREADY_CHARGED` with the balance unmoved, down to 500
    → `LOW_BALANCE`, next charge → `NONE` (already warned), exactly to 0 → `OUT_OF_CREDIT`, and
    one more → `INSUFFICIENT` with `NONE` and **no ledger row**. The resulting ledger reads as a
    clean statement with a correct running balance.
  - **The racing-replicas test is the one that matters, and it passes.** Eight threads, distinct
    event ids, a balance holding exactly one alert → exactly **one** `CHARGED`, seven
    `INSUFFICIENT`, final balance 0, one ledger row. A second case runs eight threads on the
    *same* event id — the redelivery storm where the fast path cannot help because nothing has
    committed — and the money still moves exactly once. Both **commit** (a rolled-back test
    transaction would prove nothing) and clean up after themselves; verified afterwards that no
    test rows were left behind.
  - **`ApiRoutes.kt`'s forbidden list now names the charge endpoints** — comment only, no route
    added — and the gateway test asserts they 404 and never reach the upstream. They are worse
    than the other private endpoints: they return a phone number *and* spend credit, so reaching
    them from a browser would let anyone drain an account.
  - **Tests:** 172 Kotlin across the repo (user-account 34 → 48), 0 failures.
  - **Two traps, both the same shape as slice 1's:** a **native update does not refresh JPA's
    first-level cache**, so `findById(...).warningLevel` returned the pre-charge value and a test
    was asserting against the cache rather than the database — balance and warning level are both
    read natively now. And `@Modifying` needs **`flushAutomatically = true`**, or a native
    statement runs before a pending insert in the same transaction reaches the database and
    reports a false `INSUFFICIENT`.
  - **A local-dev reminder that cost a build:** WSL2 shuts the distro down between separate
    `wsl.exe` invocations and takes Postgres with it, so `docker start` followed by a Gradle run
    fails with a bare `ConnectException`. Holding the distro open with a long-running
    `wsl.exe -e sleep` for the duration of the test run is enough.

- **Slice 2b (2026-10-29): the signup grant now defaults to 0, which unblocks the gateway deploy.**
  One line of config and the reasoning behind it. `SIGNUP_GRANT_MINOR` still turns the grant on;
  what changed is that **unset now means nothing rather than 2500**.
  - **Why it unblocks the deploy.** The gateway's launch condition was abuse control, because a
    public, unauthenticated, unthrottled registration form handing out 2500 haléře makes each
    scripted signup worth 25 CZK — and roughly **sixteen** such accounts could drain a 200 CZK
    GoSMS balance. The money was never the main risk: once GoSMS is empty, *paying* hunters get no
    alerts, which for a product whose only output is an SMS is a silent outage. With a zero grant
    a fake account cannot cost an SMS **or a DeepFaune run**, so a signup buys a Cloud SQL row and
    nothing else, and the gateway can go up before Turnstile exists.
  - **Zero is the *default*, not an override to remember.** Forgetting an env var should cost
    nothing rather than give credit away, and this project has been bitten by must-set config
    before — `INBOUND_EMAIL_DOMAIN` in slice 13: unset, and nobody was ever texted. **Set no
    `SIGNUP_GRANT_MINOR` on the Cloud Run revision**; unset is the intended configuration.
  - **A zero grant still writes a credit row and a 0-amount `SIGNUP_GRANT` entry**, so "every
    hunter has a balance" stays true and nothing downstream needs a missing-row special case —
    and "granted nothing" is a fact a statement can show.
  - **The default is pinned by a test** (`SignupGrantDefaultTest`, no `properties` override, so it
    reads what `application.yml` ships). It is a security property, not a preference: a change
    that quietly made it generous again should fail a build rather than turn up on an invoice.
    The two tests that *assert* on a granted balance now declare `billing.signup-grant-minor=2500`
    themselves instead of silently inheriting a production default — which they should always have
    done.
  - **Verified live:** registration returned `creditBalanceMinor: 0`, and a charge against that
    hunter returned `INSUFFICIENT` — so an account created by a script cannot trigger even one
    inference.
  - **Tests:** 174 Kotlin, 0 failures. `CONSOLE.md` step 10h and `deploy/README.md` rewritten:
    the gateway is now deployable as-is, with the grant switched on later behind Turnstile or a
    Cloudflare rate-limit rule.

- **Slice 3 (2026-10-29): notification charges, so credit finally does something.** Until this,
  the ledger and the charge endpoint existed but **nothing called them** — a balance of 0 and a
  balance of 2500 behaved identically end to end, because `HunterClient` still used the plain
  `by-inbound-address` lookup. Slice 2b's claim that a zero grant stops an inference was true of
  the endpoint called directly by `curl`, not of the pipeline. It is true of the pipeline now.
  - **The two lookups are gone, replaced by two charge calls.** `chargeByInboundAddress` and
    `chargeById` POST to `…/charges`; `HunterContact` became `HunterCharge`, which carries the
    outcome, the balance and the notice alongside the phone number. There is no longer a point in
    the flow where we know who the hunter is but not whether this alert is paid for — one call
    answers both, inside the transaction holding the money.
  - **`payable` is a property on `HunterCharge`, not a check at the call site.** `CHARGED` and
    `ALREADY_CHARGED` both mean send. Reading `ALREADY_CHARGED` as a failure is the easiest
    mistake to make against this contract and it would silence **every redelivered alert**, so the
    rule lives in one place with the reason attached.
  - **The charge comes before the claim, and that ordering is the slice.** A claim can only exist
    because an earlier attempt got past the charge for the same `sourceEventId`, so that attempt
    would answer `ALREADY_CHARGED` having moved nothing — charging first is safe. The reverse is
    not: claim first and a charge that throws (cold user-account, 403, read timeout) has *eaten*
    the event, and the redelivery that should have rescued the alert finds it handled and stays
    silent. Same lesson as slice 13's, one call further down. Two tests pin it.
  - **A refusal writes nothing and claims nothing.** `INSUFFICIENT`/`INACTIVE` answer the push
    **200** — redelivering would only produce the same refusal — and deliberately leave the source
    id unclaimed. A failed *send* still releases the claim, but **not** the charge: the retry reads
    `ALREADY_CHARGED` and sends. One alert, charged once, however many attempts it took.
  - **Strict on the outcome, lenient on the notice — deliberately asymmetric.** An unknown
    `outcome` throws: guessing `CHARGED` sends an SMS nobody paid for, guessing `INSUFFICIENT`
    swallows one that was paid for, so failing loudly into the DLT is the only honest answer. An
    unknown `notice` maps to `NONE` with a warning, because a notice user-account learns later is
    an additive change that must never cost an alert. Not knowing what to *add* to a message is
    survivable; not knowing whether we may *send* it is not.
  - **The warning rides along on the alert instead of costing its own SMS.** user-account only
    returns a notice on a *successful* charge, so whenever there is something to say there is
    already a message going out to say it on — otherwise we would pay for an SMS telling someone
    they have run out of SMS credit. `SmsPolicy` appends " Credit low: 4.00 CZK left." or
    " Credit used up - no more alerts until you top up.", ASCII only, and a test pins the longest
    possible combination at **≤160 characters** so the warning never splits the message into two
    paid segments.
  - **The price lives in notification** (`billing.sms-cost-minor`, `SMS_COST_MINOR`, default
    **150**) because this is the service that knows what an SMS costs. Default 150 is safe here and
    is the **opposite** call to the signup grant's default of 0, for the opposite reason: forgetting
    the grant must give nothing away, while forgetting the price must still charge. `@Min(1)` on
    the endpoint means a 0 fails loudly rather than running free. `SmsCostDefaultTest` pins it.
  - **The free trial is switched on for local dev only, and that is not timidity.** `compose.yml`
    now sets `SIGNUP_GRANT_MINOR=2500` for user-account — 25 CZK, ten alerts with their recognition
    at 100 + 150. `application.yml` still defaults to **0** and `SignupGrantDefaultTest` still pins
    that. **Do not set it on the Cloud Run revision yet:** the gateway is live and
    `gateway-lznkorfm4q-ey.a.run.app` is publicly reachable, which the site slice records as
    tolerable *only* because the grant is 0. Any edge rule on `wildalert.app` is walked around by
    calling that URL directly, so Turnstile (or closing the origin with `x-origin-secret`) is the
    gate, and it comes first. The grant is one env var the day it does.
  - **Verified live, end to end, on a real hunter through the real push endpoint.** Registration
    returned `creditBalanceMinor: 2500`; one pushed `AnimalRecognized` → **204**, `[FAKE SMS]`
    sent, balance **2350**, and a `SMS_DEBIT` of -150 in the ledger keyed on the event's
    `sourceEventId`. Then 16 more pushes drained it: 2200, 2050 … 400, 250, 100, and the 17th →
    **`INSUFFICIENT`, no SMS, no ledger row** (16 debits for 17 pushes). The low-balance warning
    appeared **exactly once**, on the alert that crossed 500 ("Credit low: 4.00 CZK left."), and on
    neither of the two alerts after it — the `warning_level` claim working. `balance_minor` (100)
    equalled the ledger sum (100) after all of it, so the cache-is-a-cache invariant survived 17
    charges.
  - **The best result came from restarting notification and redelivering the first event.** With
    the in-memory claim cleared and the hunter now holding **100 — less than the 150 price** — the
    redelivered alert **still went out**, logged `charged=ALREADY_CHARGED`, the balance stayed at
    100 and no 17th debit appeared. That is the fast-path ledger lookup preceding the balance
    check, and it is the property that matters most: an alert already paid for is never lost
    because the hunter has since run dry.
  - **A finding, not a bug, and worth a later slice: `OUT_OF_CREDIT` is practically unreachable.**
    `noticeFor` raises it on `balanceAfter <= 0`, which needs the balance to land on *exactly* 0 —
    and 2500 is not a multiple of 150, so this hunter went 100 → `INSUFFICIENT` without ever being
    told "credit used up". `warning_level` stopped at 1. The hunter is not left in silence (the
    low-balance warning fires at ≤500, about three alerts out), but the out-of-credit message will
    almost never be seen. `balanceAfter < smallest chargeable amount` would be the honest test.
    Left alone here because it is user-account's logic, not this slice's.
  - **The trial buys 16 alerts today, not 10.** Only `SMS_DEBIT` (150) is charged so far; it
    becomes exactly ten once `recognition` charges its `RECOGNITION_DEBIT` of 100 per photo, which
    is the next slice. The 2500 figure is already right for the end state.
  - **Tests:** 196 Kotlin across the repo (notification 45 → 67), 0 failures; full `./gradlew build`
    green with Postgres up.
  - **A harness trap worth remembering:** a hand-built Pub/Sub envelope from PowerShell's
    `ConvertTo-Json` was rejected **400** by the push endpoint, which looks exactly like a service
    bug. It was not — nested `ConvertTo-Json` mangled the payload, and building the JSON by string
    concatenation gave 204 immediately. When a local probe fails, suspect the probe first.

- **The site on `wildalert.app` (2026-10-29): a Cloudflare Worker, because Cloud Run cannot map
  the domain here.** The gateway went up the same day (`gateway-00001-p5f`, SA
  `wildalert-gateway@`, image `:a2bd22f`) and serves the registration form on its `run.app` URL;
  this slice is the domain in front of it. **The worker is deployed** (2026-10-29, version
  `eaa4d36d-dcf1-4c6a-8cc2-23c5b592fbfb`, 1.27 KiB, 2 ms startup) with both routes attached; the
  site is **not live yet** because the apex still has no A/AAAA record.
  - **Worker before DNS, and the runbook was rewritten to say so.** It was first written DNS-first.
    Both routes attached while the apex had no address record at all, which proves routes are inert
    rather than invalid without one — so deploying first makes the DNS record a single clean
    switch, where the other order leaves a window of `wildalert.app` resolving to the discard
    prefix and hanging. Verified inert afterwards: no A/AAAA from 1.1.1.1 and `curl` returns `000`.
  - **The last two steps cannot be scripted with `wrangler`.** It has no DNS-record command, and
    the OAuth token `wrangler login` stores carries **`zone:read`** — no `dns_records:write`, no
    zone-settings scope. The two `AAAA → 100::` records and the SSL toggles are dashboard clicks,
    or they need a separate API token with *Zone → DNS → Edit* and *Zone → Zone Settings → Edit*.
    Worth knowing before planning any further Cloudflare automation in this project.
  - **Noted in passing:** that same token is missing the `challenge-widgets.write` scope, which is
    Turnstile's — so standing Turnstile up will mean a fresh `wrangler login` or dashboard work.
  - **Cloud Run routes by Host header**, so a proxied DNS record aimed at the gateway arrives as
    `Host: wildalert.app`, matches no service and 404s. Teaching Cloud Run the hostname is exactly
    what a *domain mapping* does — and it does **not exist in `europe-west3`**, and Google labels
    it preview and "not production-ready … due to latency issues" in the regions where it does.
    That one fact removed the obvious answer. **Checked against the live service, not assumed:**
    same URL, one header apart — `-H 'Host: wildalert.app'` → **404**, without it → **200**.
  - **The three remaining options, and the arithmetic.** Cloudflare's native host-header override
    is **Enterprise-only** (free Origin Rules can change the destination port and nothing else).
    A **global external Application Load Balancer** is Google's own recommendation and the only
    option that can take the `run.app` URL off the public internet — for **~€18–20/month**, which
    would roughly double this project's bill to solve a header. **A worker** does it for €0, in
    one file, on a zone where `wrangler` was already proven by the email worker. Firebase Hosting
    was the near-miss: free, Google-native, and `europe-west3` *is* supported — passed over
    because it adds a second CDN in front of a service that already serves its own static files,
    and its rewrites cannot add a header, so it has no answer to the bypass.
  - **The apex is canonical, `www` 301s to it** — in the worker, not in a dashboard redirect rule,
    so there is one place to look. The apex wins because hunter addresses are already
    `<token>@wildalert.app`: same string on the page and in the email.
  - **Email is untouched, and that is a fact about DNS, not a hope.** MX records are independent
    of A/AAAA/CNAME, so the apex catch-all and every hunter address keep working through the
    *other* worker. Two workers, one zone, no overlap: that one has an `email` handler and no
    routes, this one a `fetch` handler and two.
  - **The `AAAA → 100::` records look wrong and are not.** A Workers route only attaches to a
    hostname with a **proxied** record, and the worker answers before anything is dialled, so the
    content is never used — `100::` is the IPv6 discard prefix, chosen precisely because it is
    unroutable. Grey-clouded, the request goes to `100::` and hangs.
  - **Nothing is cached, deliberately.** The React app ships *inside* the gateway's jar so the
    page and the API can never be different versions; a cached `index.html` surviving a deploy is
    exactly how that guarantee would be lost. The hashed `assets/*` are safe to cache and worth a
    rule later — a rule that names them, never a blanket one.
  - **What it does not fix:** `gateway-lznkorfm4q-ey.a.run.app` stays publicly reachable, so any
    edge rule can be walked around by calling it directly. Tolerable **only because
    `SIGNUP_GRANT_MINOR` is 0**. The worker already sends `x-origin-secret` when `ORIGIN_SECRET`
    is set, so closing it later is a gateway-side change plus one `wrangler secret put` — in that
    order, or the site is down in between.
  - **Rate limiting, costed before choosing** (the question that drove the decision): Cloudflare's
    free tier is **one rule, IP only, a 10-second window and a 10-second block** — a patient
    script at 5 requests/10s still lands ~43,000 registrations a day, so it is a flood brake, not
    abuse control. **Turnstile (~half a day) is the real control and works regardless of who
    fronts the domain.** In-app is possible — Gateway MVC ships `Bucket4jFilterFunctions.rateLimit`
    that drops into `ApiRoutes.kt`'s existing `.before(...)` chain — but it resolves an
    `AsyncProxyManager` bean *per request* and the autoconfiguration registers only the
    `FilterSupplier`, so it wants a distributed store (Redis/Postgres) that this 512 MiB,
    no-datastore edge service deliberately lacks. A per-instance `bucket4j-core` filter (~60–80
    lines, ceiling 2× the configured rate at `max-instances=2`) is the honest cheap version if it
    is ever wanted. **Cloud Armor is the only zero-code real answer, and it rides on the €18 LB.**

- **Slice 4 (2026-10-29, done; needs a Cloud Run revision): recognition charges too, so the trial
  buys ten recognitions and not sixteen alerts.** Every pushed photo is debited
  `RECOGNITION_DEBIT` **100 haléře before the model runs**, the counterpart to slice 3's
  `SMS_DEBIT` 150. It is also the only thing that charges for a photo that is classified but alerts
  nobody — the case notification never sees.
  - **Charging before classifying is the whole point.** Charging afterwards would let a drained
    account buy a free DeepFaune run, which is the cost this debit exists to protect. A transient
    failure after the charge is harmless: the retry takes the fast-path `ALREADY_CHARGED` and
    re-runs inference without paying twice (proven live — balance unmoved, a new `eventId` with the
    same `sourceEventId` published).
  - **The order is fetch → charge → classify, not charge → fetch.** A missing image raises before
    any money moves, because there is no refund path in this design and a photo that cannot be
    classified must not be charged for. The storage read is free; the inference is not.
  - **A refusal is acked, not retried.** `INSUFFICIENT`, `INACTIVE`, or an address that is no
    hunter's → nothing classified, no `AnimalRecognized`, so notification never sees it and nobody
    is texted. Nothing to compensate. A 204 is honest here: the message is not poison, and filling
    the dead-letter topic with photos from empty accounts would bury the ones that really failed.
  - **A behaviour change that is an improvement:** mail to a made-up name on our domain used to be
    stored, classified and published, with notification then declining to text anyone — a DeepFaune
    run no balance paid for, and an event detection-history answered 400 to. It now costs a storage
    read and nothing else.
  - **The one non-obvious thing this slice had to fix in user-account: `canDeliverNotice`.** A
    notice fires once per depletion cycle (the `warning_level` claim), and recognition charges
    *first*, so it is usually recognition's debit that crosses the low-balance threshold — and
    recognition sends no SMS. Handing it the notice would not delay the warning, it would **lose**
    it, silently, for every hunter. The charge request therefore carries `canDeliverNotice`
    (default true, so notification's body is unchanged) and recognition sends false: it charges
    without claiming, and the `SMS_DEBIT` that follows claims the warning and rides it out on an
    alert that was going anyway. Verified live: recognition's debit landed the balance exactly on
    the 500 threshold with `warning_level` still 0, and the alert's charge then returned
    `LOW_BALANCE`.
  - **The Python client is a deliberate copy of notification's `HunterClient` + `ChargeDtos`** —
    the same call this project already makes for the event contracts. Strict on the outcome (an
    unknown one raises rather than guess about money), and the response's **phone number is parsed
    away**: recognition has no business holding it. `httpx` moved from `requirements-dev.txt` to
    `requirements.txt`, which is the only new production dependency; the ID token needs none
    (`google-auth` arrives with `google-cloud-pubsub`) and is built in the FastAPI `lifespan`
    alongside the model, so an unmintable credential fails the revision at startup rather than on
    the first real photo.
  - **There is no way to switch charging off**, unlike `Classifier`/`ImageSource`/`EventPublisher`
    with their fakes. Those stand in for things that cost money or need credentials; user-account
    is ours. A flag whose absence meant free inference is exactly the hole
    `billing.signup-grant-minor` was changed to avoid — the asymmetry is deliberate: forgetting the
    grant must give nothing away, forgetting the price must still charge.
  - **The bug the unit tests could not see, found on the first live call: HTTP 415.** The body is
    serialised by pydantic and passed to httpx as raw `content`, which httpx sends with **no
    content type**, so Spring rejected it *before the controller ran* — a mocked transport does not
    care. One header fixed it, and `test_credit.py` now pins it. The same shape as slice 12's
    guessed GoSMS response: the contract between two services is only proven by a real call.
  - **Verified live, Python → Kotlin → Postgres, on the real push endpoint** (user-account jar +
    uvicorn + the dev Postgres): 2500 → 2400 `CHARGED` then classified and published; the same
    event again → `ALREADY_CHARGED`, balance unmoved, re-published; `SMS_DEBIT` 150 under the
    **same event id** → 2250, which is the two-part price working off `(entry_type, external_ref)`;
    the threshold case above; an empty balance → `INSUFFICIENT`, 204, nothing classified, **no
    ledger row**; a stranger's address → 404 → acked and not classified. The ledger reads as a
    clean statement with a correct running balance throughout. Test rows removed afterwards.
  - **Known gap, and it is user-account's to close: the stranded balance.** With 100 ≤ balance <
    250 a photo is charged and classified but its alert is not affordable, so the hunter gets
    silence *and* no warning (`noticeFor` only fires on a successful charge). `OUT_OF_CREDIT` is
    practically unreachable for the same reason — it needs the balance to land on exactly 0. The
    honest test is `balanceAfter < the smallest chargeable amount`; a test pins today's behaviour
    so the change is deliberate.
  - **Tests:** 200 Kotlin (user-account 48 → 54) and **63 Python** (was 34), 0 failures.
    `./gradlew build` green with Postgres up.
  - **Deployed the same day: `recognition-00005-5vt`, image `recognition:8736653`** (built by the
    `recognition-main` trigger off the merge commit). Both variables were read back off the service
    template rather than trusted to the command line, and the startup log is the proof the charge
    code is the code running: `recognition.credit: Calls to user-account will carry a Google ID
    token for audience https://user-account-lznkorfm4q-ey.a.run.app`, with the model loaded before
    traffic as usual.
  - **`roles/run.invoker` on user-account for `wildalert-recognition` — granted** (by hand: this
    session's permission classifier refused the binding, `Permission Grant`, exactly as in Phase 6
    slice 6). That policy now lists gateway, notification and recognition. It was the one thing
    between the deployed revision and a working pipeline: recognition *does* call user-account now,
    and without the grant Cloud Run answers **403 in front of the app** — not the 404 the client
    maps to "no hunter" — so every photo would have been retried five times and dead-lettered
    while the service looked healthy.
  - **Two more revisions went out, because the slice was only half-live without them.**
    user-account was still on `a2bd22f`, which **predates `canDeliverNotice` and silently drops it**
    (Spring's Jackson ignores unknown properties), so it would have claimed the credit warning on
    recognition's charge and swallowed it — the very bug this slice fixes.
    `user-account-00004-pgg` closes that: Flyway validated 4 migrations with none to apply, because
    the credit tables have been there since slice 1. And notification was still on `e0ddd1d`, from
    **before credit slice 3**, so alerts were not charged at all; `notification-00006-qct` turns
    `SMS_DEBIT` on in production (decided 2026-10-29). Prices in the repo and prices in production
    now agree.
  - **A new shell trap, the mirror image of the Git Bash one, and it cost revision
    `recognition-00004-x6z`.** PowerShell parses an unquoted comma in
    `--update-env-vars=A=1,B=2` as an *array* and hands it to gcloud **space-joined**, so
    `USER_ACCOUNT_BASE_URL` arrived as
    `https://user-account-lznkorfm4q-ey.a.run.app USER_ACCOUNT_AUTH=gcp-id-token` and
    `USER_ACCOUNT_AUTH` did not exist at all. Nothing fails loudly: the deploy succeeds and the
    revision serves traffic misconfigured. Quoting the whole value fixes it. So: Git Bash mangles
    leading slashes, PowerShell mangles commas — **read the variables back off the template
    whichever shell you use.** Written up under *Troubleshooting* in `deploy/README.md`.
  - **Proven in production on a real emailed photo (2026-10-29, 15:20:33 → 15:22:06 UTC), and the
    two-part price is now real money:**
    - 15:20:36 stored in R2 (`inbound/2026/10/29/0118ab69-….jpg`, 39172 bytes), 15:20:37
      `ImageReceived` published
    - 15:20:38 recognition cold-started, ready 15:21:53 (**75s**), push `attempt=1` 15:21:54
    - 15:21:56 **the charge call answered `HTTP/1.1 200 OK`, not 403** — the grant and the
      metadata-server ID token both working against a private service
    - 15:22:02 **`Charged hunter a87cf72a-… 100 haléře … (CHARGED), balance 2400`**, then
      `species=wild boar confidence=1.00` published (4 animals detected, 3.7s inference)
    - 15:22:04 notification `attempt=1` → 15:22:06 **GoSMS accepted for +420774584941
      (id=47117094037), `charged=CHARGED, balance=2250`**

    So **2500 → 2400 → 2250** on one photo, both debits under the same `sourceEventId`
    `5f79923a-…`, **email to SMS in 93 seconds**, every hop on first attempt, nothing
    dead-lettered. The first time a hunter has paid for a recognition.
  - **A documented claim turns out to be half right, found in this log:** "the model is loaded at
    startup, not on the first request" holds for **MegaDetector** (`YOLOv9c summary` at 15:21:29,
    during `lifespan`) but **not for DeepFaune's own weights** — `Model loaded from
    …/deepfaune-vit_large_patch14_dinov2…v3.pt` is logged at 15:21:56.5, *after* "Application
    startup complete" (15:21:53) and after the charge call. `DeepFauneClassifier.__init__`
    constructs both eagerly, so it is PytorchWildlife that defers reading that checkpoint to the
    first classification. It cost ~3s here against a 600s ack deadline, so it is a note rather than
    a problem — but the startup probe does **not** cover it, and the first photo on every cold
    instance pays it.
  - **A pre-existing trap noticed in passing:** `services/recognition/Dockerfile.local`, which
    `compose.yml`'s local profile builds, is matched by `.gitignore`'s `*.local` and has therefore
    never been committed. `docker compose --profile local up` cannot work from a fresh clone. Not
    fixed here (it is nothing to do with credit) but worth its own one-line slice.

- **Slice 5 (2026-10-30, done; needs a Cloud Run revision): guarding *our* credit at the gateway,
  which is the other half of the credit story.** Slices 1–4 made sure a hunter cannot spend more
  than they bought. This one makes sure **WildAlert** cannot: GoSMS is prepaid, and when it empties
  every alert fails — including the ones hunters have already paid for. That is the silent outage
  slice 2b described (sixteen fake signups could drain a 200 CZK balance); the zero signup grant
  removed the cheap way to cause it, this removes the consequence.
  - **The two credits are deliberately different objects and protect different things.** The
    hunter's balance lives in Postgres and is spent per event; ours lives at GoSMS and is read, not
    written. Mixing them up would be easy and expensive, so the new types say which they mean:
    `SmsProviderCredit` / `ProviderCredit` / `SmsProviderCreditGuard`, all in notification.
  - **The balance read was verified against the live account before a line of parsing was written**,
    because this project has now been burned twice by a guessed GoSMS shape (the missing `id` in
    slice 12, the missing content type in credit slice 4). `GET /api/v1/` really answers:
    `{"currentCredit":315.21,"invoicingType":"Prepaid","currency":"CZK","channels":[…]}`. So the
    field is **`currentCredit`**, in **CZK major units** — STATUS.md's earlier note called it
    "credit", which would not have parsed — and `invoicingType` is what says whether there is a
    balance to run out of at all. A test carries that exact body.
  - **The guard runs before the hunter is charged, not around the send.** The charge comes first in
    `NotificationService` and there is no refund path, so discovering our own empty balance at send
    time would take 150 haléře and deliver silence. Checked first, no money moves at all.
  - **An empty gateway is retried, not acked — the opposite of a hunter's `INSUFFICIENT`.** That
    refusal is final and slice 3/4 ack it to keep the dead-letter topic clean. This one we can fix
    by topping up, so it throws: the push 5xxs, Pub/Sub retries with backoff, and what is still
    unsent parks in the dead-letter topic, replayable. **A filling dead-letter topic is the correct
    alarm for "the account needs money"** — the alert is kept, not dropped.
  - **Two rules keep the guard from becoming a new way to lose alerts**, which is the real risk of
    adding a precondition to the delivery path:
    1. **An unreadable balance never blocks.** A broken or changed credit endpoint is our problem,
       not the hunter's, and the send itself is the real test — GoSMS refuses what it cannot bill.
       `ProviderCredit.Unknown` passes, loudly. Same reasoning as `Notice` being lenient while
       `ChargeOutcome` is strict: not knowing what to *add* is survivable, not knowing whether we
       may *send* is not. A credit in another currency is `Unknown` too, rather than compared
       against CZK prices.
    2. **A blocking balance is never cached.** A healthy one is trusted for 5 minutes, so the guard
       costs one call per TTL instead of one per alert; bad news is re-read every time, so the first
       alert after a top-up goes out at once instead of waiting out a stale "empty".
  - **A low balance warns in the logs (≤ 20 CZK, ~16 messages) — and warns at most once per TTL**,
    because the reads are cached. That falls out of the caching rather than needing its own
    throttle. It is a log line, not an SMS to the operator: a *second* SMS provider to tell us the
    first one is empty is a dependency this does not need.
  - **`GET /api/sms/credit`** answers "will the next alert go out?" with `{state, remainingMinor,
    currency, messagesLeft}` and no send. Safe to expose because notification is private on Cloud
    Run (only the Pub/Sub push SA holds `roles/run.invoker`) — the same reason the actuator
    endpoints that would leak config stay off.
  - **The price of a message is config, not a constant, and it lives next to the guard**
    (`sms.provider-credit.message-price-minor`, 120) rather than under `billing`, which is what we
    charge the hunter (150). A new test asserts `billing.sms-cost-minor` is greater than it using
    the *shipped* values, so "we lose money on every alert" is now a failing build rather than a
    comment. A `0` price cannot divide by zero: it is coerced to 1 and logged, which guards nothing
    — the direction every uncertainty here resolves in.
  - **Verified live, twice, against the real GoSMS account** (the jar run locally with the real
    credentials from Secret Manager):
    - healthy: `GET /api/sms/credit` →
      `{"state":"KNOWN","remainingMinor":31521,"currency":"CZK","messagesLeft":262}`, and the
      startup log reads `Guarding SMS provider credit (a message costs us 120 haléře, warning at
      2000, re-read every PT5M)`. The account holds **315.21 CZK ≈ 262 alerts**, so there is plenty
      of runway — the guard is in place before it is needed, which is the only useful time.
    - blocking: the same jar with `SMS_PROVIDER_MESSAGE_PRICE_MINOR=99999999` (cheaper than
      emptying a real account) answered a real push **HTTP 500** with
      `SmsProviderOutOfCreditException`, and **user-account was not running at all** — nothing
      called it, which is the proof the guard precedes the charge. Each of curl's retries re-read
      the balance, which is rule 2 visible in a log.
  - **Tests:** notification 95, 0 failures (was 71) — 14 for the guard, 7 for the GoSMS read, 4
    pinning the shipped defaults, 2 for the endpoint, and one in `NotificationServiceTest` that an
    empty gateway charges nobody, claims nothing and leaves the event redeliverable.
  - **Deployed the same day: `notification-00007-2pl`, image `notification:26bf723`**, with **no new
    variables** — the defaults are the intended configuration, as with `SIGNUP_GRANT_MINOR`'s zero.
    All eleven variables were read back off the template afterwards (slice 4's comma trap) and are
    byte-identical to the previous revision's; only the image and the revision name moved. The
    startup log is the proof the guard is the code running: `Guarding SMS provider credit (a message
    costs us 120 haléře, warning at 2000, re-read every PT5M)`.
  - **The deploy was blocked for half an hour by something that had nothing to do with it, and the
    ROADMAP had predicted it in writing.** Phase 7's "strip `roles/editor` from the default compute
    service account" was done in the console at **07:03:41 UTC**, and the Cloud Build triggers still
    named that account. The failure mode is nasty precisely because it is so quiet:
    - `gcloud builds submit` runs **as the trigger's service account**, so the docker build step
      still succeeded (it needs no GCP permission at all) and the **PUSH** step then failed. The
      build is reported `FAILURE` with `statusDetail: null` and `failureInfo: null`.
    - **There were no build logs to read**, because the same strip removed `logging.logWriter` and
      `deploy/cloudbuild.yaml` sets `logging: CLOUD_LOGGING_ONLY`. Cloud Logging held two lines
      (`starting build`, `FETCHSOURCE`) for a build that ran for two minutes. A build that fails
      *and* cannot say why is the real cost of this ordering.
    - **Which two services failed was pure timing, not code.** recognition and email-ingestion
      pushed at 07:01 and 07:03:53 — the latter finishing **12 seconds** before the IAM change —
      so they look fine and are equally broken for their next commit. notification and
      user-account started at 07:07 and failed. Nothing about the code differed.
    - **The fix was not to re-grant anything**, which would have undone the hardening.
      `wildalert-cloudbuild@` already existed and already held exactly `artifactregistry.writer`
      and `logging.logWriter`: the migration was simply half-finished, with no trigger pointing at
      it. `gcloud builds triggers update github <name> --service-account=…/wildalert-cloudbuild@…`
      on `notification-main` made the next build succeed first time.
    - **Lesson, worth more than the half hour:** an identity change lands on whatever runs *next*,
      so stripping a role and repointing the things that use it belong in one sitting. Checking
      `gcloud builds triggers list --format="table(name,serviceAccount)"` before the strip would
      have shown all four triggers naming the account about to be emptied.
    - **Finished the same morning: all four triggers run as `wildalert-cloudbuild@`** and the
      compute default is unused, which is the end state Phase 7 wanted rather than a patch.
      The `user-account` image the failed build never pushed was then rebuilt through the repointed
      trigger, which tags **the commit it builds** — so the image is `4de967a` (main's head after
      the docs merge), not the `26bf723` that went missing. Nothing was deployed from it: that
      service's code is unchanged in this slice and prod correctly kept running its older revision.
      Worth remembering that `triggers run --branch=main` means "build main as it is now", so the
      tag follows the branch rather than the build you are replacing.
  - **Not verified in prod beyond the startup log.** `GET /api/sms/credit` cannot be called from
    here: the service is private, a user credential cannot mint an audience-scoped ID token
    (`--audiences` is service-account only, and Cloud Run answers 401 at the edge), and
    `gcloud run services proxy` is outside what this session may run. The next real emailed photo
    exercises the guard anyway, since every alert now passes through it.
  - **Known gap, deliberately left:** nothing *watches* the warning. It is a log line in Cloud
    Logging, so it needs a log-based alert policy to reach a human — Phase 7's
    "structured logging + basic metrics/alerts" item, where it belongs with everything else that
    needs alerting. Until then the honest statement is that the product now **fails loudly and
    keeps the alerts** instead of losing them quietly.


## Done — hunter login (Phase 8 slice 3, 2026-10-30)

**A hunter can now come back.** Until this, registration showed the personal inbound address
*once*: close the tab and it was gone, with no screen that would ever show it again and no way to
see a credit balance at all. Since credit slice 3 notification actually charges per alert, "how
much have I got left?" became a question the product could not answer. So: a password on
registration, a login, a logout, and one authenticated page showing the balance and the address.

- **Email + password, and no password reset — deliberately, not by omission.** Magic-link by email
  is arguably the better fit for hunters who log in monthly (nothing to forget, and it verifies
  the address the product is keyed on), but it needs an outbound email sender, which this project
  has never had: Cloudflare Email Routing is inbound-only. Sign-in-with-Google was ruled out on
  the audience — this is a Czech product and a large share of Czech users are on `seznam.cz`, who
  would be locked out entirely. Password login costs nothing new, so it is what shipped, with the
  reset flow waiting for outbound email. **Recorded as a gap, not hidden:** a hunter who forgets
  their password currently needs a hand.
- **user-account issues and validates the session; the gateway stays a dumb proxy.** The hunter
  table and Postgres are both here, so credentials and sessions sit next to the data they
  describe. The gateway gained three routes and nothing else — no session store at the edge,
  which matters because it is a 512 MiB service with no datastore. The identity is asserted by
  the service that owns the hunter row; the edge only forwards a cookie.
- **A hand-rolled `hunter_session` table, not Spring Session JDBC**, on this repo's stated
  principle that a framework should replace something you have already written by hand. An
  in-memory `HttpSession` would simply be wrong: user-account runs on Cloud Run where a second
  instance can appear at any moment, and a session in one instance's heap does not exist in the
  other — a hunter would be logged out at random.
- **The token is stored hashed (SHA-256), and it is unsalted on purpose.** A leak of
  `hunter_session` must hand over nothing usable, but the lookup is *by* the token, so the hash
  has to be deterministic. That looks like a mistake to anyone who knows passwords need salt,
  which is why it carries a comment: the input is 32 bytes of `SecureRandom` (256 bits), so there
  is nothing to brute-force. Deliberately unlike `InboundAddresses.newToken()`'s 12 characters of
  a 32-character alphabet (~60 bits) — that is an email local-part a human types into a camera,
  this is a bearer credential. A test pins the difference so nobody reaches for the existing
  helper.
- **The riskiest line in the slice was `spring-boot-starter-security` itself.** Adding it secures
  *every* endpoint by default, and `POST /api/hunters/{id}/charges` is called by notification on
  every recognized animal with no cookie and no Spring credential. Break it and the money never
  moves, no SMS is sent, and nothing logs a cause — a silent outage. So `SecurityConfig` defaults
  to `permitAll` and authenticates only `GET /api/me` and `DELETE /api/sessions`, with the reason
  written in the file; defaulting the other way would leave every future service-to-service
  endpoint one forgotten line from breaking. `SecurityConfigTest` runs the **real** filter chain
  (the only `@WebMvcTest` here that does) and asserts the charge call still answers. Checked while
  planning and worth knowing: notification's two `/charges` calls are the *only* cross-service
  calls into user-account — email-ingestion no longer calls it at all, and nothing anywhere
  deserializes `HunterResponse`.
- **Every failed login answers identically**, verified live across all four causes: unknown email,
  wrong password, a hunter whose `password_hash` is null, and a deactivated account. When there is
  no hunter the encoder still runs against a throwaway hash, so an unknown address does not answer
  in a millisecond while a known one takes BCrypt's ~100ms — that gap alone is enough to enumerate
  who has an account. The `active` check comes *after* the password check for the same reason.
  A test asserts the four messages are one distinct string, so a later "helpful" error fails the
  build.
- **Logout deletes the row, not just the cookie**, and the live check proves it the only way that
  counts: capture the token, log out, replay the captured cookie by hand → 401. Clearing the
  cookie alone would leave a token that still worked for anyone who had copied it.
- **Registration logs the hunter straight in.** They have just chosen the password; asking them to
  type it again is friction with no security gain, and it means the existing "here is your
  address" screen flows into the dashboard instead of a dead end.
- **Two bugs the integration tests caught that mocks could not have.**
  1. `char(64)` for the token hash — Postgres reports it as `bpchar`, which `ddl-auto: validate`
     rejects against a String mapping. `varchar(64)` is also simply better: `char` blank-pads, so
     a padded 64-character hash compared against an unpadded one is a bug waiting to happen. V5
     was unreleased, so it was edited and the dev database's V5 row dropped and re-applied.
  2. The sweep's bulk JPQL delete left the persistence context believing the rows were still
     there, so a `findById` in the same transaction cheerfully returned a deleted session. Fixed
     with `clearAutomatically` — and that fix forced the better design below.
- **`ExpiredSessionSweeper` is its own bean running `REQUIRES_NEW`**, because housekeeping must not
  be able to disturb or fail the login it rides along with: the bulk delete clears the persistence
  context, and a cleanup that throws must not cost somebody their session. The consequence is that
  its test cannot be `@Transactional` — `REQUIRES_NEW` opens a transaction that cannot see the
  test's uncommitted rows, so the test would fail while the code is right. It commits and cleans
  up, like `CreditChargeConcurrencyTest`. Called on login rather than `@Scheduled` because on
  Cloud Run with scale-to-zero a scheduled task runs only while an instance happens to be alive.
- **Spring Boot's default `user` with a generated password is now excluded.** The security starter
  was printing "Using generated security password: ..." at every startup. Nothing could use it
  (basic and form login are disabled) so it was misleading log noise — but it is a real credential
  the moment anyone re-enables either, so `UserDetailsServiceAutoConfiguration` is excluded. The
  only identities this service recognises are hunters with sessions.
- **No `react-router`, and that is now a decision rather than a deferral.** The session cookie
  does the job a URL would: the app asks `GET /api/me` on load, so refresh, a reopened tab and a
  visit tomorrow all land on the dashboard. Routes would cost a new npm dependency — which on
  this network means an off-network `npm install` (see AGENTS.md) — plus an SPA fallback in the
  gateway, which does not exist: any unknown path there is a 404 today. Worth revisiting when
  there are enough screens to get lost between.
- **`alertPriceMinor` is now on `HunterResponse`** so the dashboard can say "about 10 alerts left"
  rather than "25.00 CZK", which is the number a hunter can actually act on. **Known duplication,
  recorded rather than discovered later:** notification owns `billing.sms-cost-minor` (150) and
  recognition will own its 100; those are the internal debit amounts, this is the hunter-facing
  price, and it lives with the account that holds the money. Consolidate when recognition starts
  charging.
- **The dashboard is honest about a zero balance.** Production runs `SIGNUP_GRANT_MINOR=0` until
  there is abuse protection in front of the form, so a hunter registering today sees `0.00 CZK`.
  The panel says why and what to do instead of looking broken.
- **A surprise in the registration contract, recorded because it reads like a bug.** An *absent*
  `password` key fails in Jackson before bean validation runs (the Kotlin property is non-null),
  so it is a bare 400 with no `errors` map, while an *empty* string gets a per-field message.
  Pre-existing for every required field, harmless because the React form always sends all three
  keys, and it would matter to a hand-written API client. Both cases are pinned by tests.
- **`InboundAddressPanel` gained `moveFocusOnMount`.** It used to take focus on mount, which is
  right when the registration form has just vanished and wrong on a dashboard that loaded cold —
  a returning hunter would have focus yanked away on every visit.
- **Verified live, end to end, against the running service on real Postgres.** Register → 201 with
  an `HttpOnly; SameSite=Lax` cookie and a 2500 balance; `GET /api/me` with the cookie → 200,
  without → 401 as `application/problem+json`; a refused login sets **zero** cookies; logout →
  204 plus a `Max-Age=0` cookie and a replayed token → 401; a 73-character password → 400 against
  the password field. **Regressions both held:** the charge call answered **200** with no cookie
  and moved the balance 2500 → 2350, and `by-email` still answers unauthenticated.
- **Tests:** **269 Kotlin across the repo, 0 failures** (from 196) — user-account 92, gateway 10,
  notification 95, detection-history 44, email-ingestion 28 — and 49 in `web/`, plus `npm run build`
  (`tsc --noEmit` + a real Vite build) clean. The gateway tests now prove `Set-Cookie` survives
  the proxy in both directions, which was assumed rather than checked before; a proxy that
  dropped it would look exactly like a broken login. `ApiRoutesTest`'s stub response was also
  stale — still carrying `"plan":"FREE"`, a field dropped in V3, and no `creditBalanceMinor` —
  and is now a faithful copy of the real contract.
- **Still open, and all of it deliberate.** **No password reset** (needs outbound email) --
  *superseded 2026-10-30: built, see the password section below; what it waits on is a sending
  provider, not the architecture.* **No
  brute-force protection** — a public login form is a credential-stuffing target and this slice
  adds nothing against it; it belongs with Turnstile and arguably should not reach production
  ahead of it. **CSRF is disabled**, justified by the `SameSite=Lax` cookie and the fact that the
  only authenticated state-changing endpoint is the logout — revisit the moment the dashboard can
  spend money. **Pre-existing production hunters have a null `password_hash`** and cannot log in
  until they re-register or a hash is set by hand. **Nobody has looked at the new screens in a
  browser yet** — the data path is proven end to end, the pixels are not.
- **Deploy consequences:** a new user-account revision (with `SESSION_COOKIE_SECURE=true` — a
  `Secure` cookie is silently not set over plain http, which is why it is false by default and
  would make local dev look like a broken login otherwise) and a new gateway image, since the
  gateway bundles the React build.


### Deployed to prod (2026-10-30, same day as the merge)

Both services live from image tag **`161fe43`** (merged main, PR #32):
**`user-account-00005-sjb`** and **`gateway-00002-twt`**.

- **Flyway applied V5 to production Cloud SQL**: "Current version of schema: 4" → "Successfully
  applied 1 migration … now at version v5" in 0.26s, service up in 15.5s. The startup probe points
  at `/actuator/health/readiness`, which includes `db`, so a failed migration would have left the
  revision never serving and prod running `00004-pgg` — the safety net working as designed rather
  than as a hope.
- **`SESSION_COOKIE_SECURE=true` is set on the revision**, and the live `Set-Cookie` proves it:
  `wa_session=…; Path=/; Max-Age=2592000; Secure; HttpOnly; SameSite=Lax`. Set with
  `--update-env-vars`, never `--set-env-vars`, which would have dropped `DATABASE_URL`,
  `POSTGRES_USER`, `INBOUND_EMAIL_DOMAIN` and the `POSTGRES_PASSWORD` secret reference.
- **`SIGNUP_GRANT_MINOR` is still unset, deliberately**, and the deploy check confirms it end to
  end: a hunter registered through the real edge came back `creditBalanceMinor: 0`. The grant stays
  off until Turnstile is in front of the form.
- **Verified through the public edge, not the private service.** Health 200; the app served (not a
  404, so the `web/dist` copy landed); `GET /api/me` and `POST /api/sessions` answer **401 rather
  than 404**, which is what proves the new routes exist *and* the gateway's ID token is being
  accepted; `by-email` and `charges` still **404 at the gateway**, so the allowlist is intact.
  Then the full round trip: register → 201 with a `Secure` cookie → `/api/me` 200 → logout 204 →
  `/api/me` 401 → log back in with the password → 200.
- **The served bundle is the right build**, checked rather than assumed: `assets/index-nJWIFrnb.js`
  is the same hash the local `vite build` produced, and it contains the new copy ("Sign in",
  "Your credit", "no password reset yet", "Where to send photos").

**The sequencing mistake, written down because the reasoning was half right.** user-account went
first on the grounds that its additions are additive and the new UI needs them — true, but
incomplete: `password` became a **required** field on `POST /api/hunters`, which is a *breaking*
change for the old client. So for about six minutes the live gateway served the old React app
against the new contract and every registration answered **400** (confirmed deliberately with the
old payload, rather than assumed). Blast radius was small — `wildalert.app` still has no A/AAAA
record, so only the unadvertised `run.app` URL was affected — but the honest rule is the one
`STATUS.md` already states for the notification/user-account pair: when a field becomes required,
the client and the server have to ship together, or the client ships first. Making `password`
optional on the endpoint for one release would have removed the window entirely.

**Two Cloud Build facts worth knowing before the next deploy.**
- **Merging to main already builds everything with a trigger.** All four triggers are
  `--branch=main` push triggers, so PR #32's merge fired `user-account`, `notification`,
  `email-ingestion` and `recognition` builds at 17:25 on its own. A manual
  `gcloud builds triggers run user-account-main` afterwards was pure duplication — `161fe43`
  already existed in Artifact Registry — and it queued behind the automatic ones. Check
  `gcloud artifacts docker tags list …/<service>` for the merge commit's tag before building
  anything by hand.
- **There is no `gateway` trigger** (nor a `detection-history` one), so the gateway still needs a
  manual `gcloud builds submit`, and it now needs
  `--service-account=…wildalert-cloudbuild@…` since the compute default was emptied. **Creating a
  `gateway-main` trigger is the obvious follow-up** — it would have made this deploy two commands.
- **The `europe-west3` queue serialises, and the build region is independent of the Artifact
  Registry region.** The gateway build sat `QUEUED` behind the automatic `recognition` build (the
  longest in the repo — torch plus model weights), which could not be cancelled from here. Re-
  submitting the *same* config with no `--region` built it in the **global** region instead, and
  the image still pushed to the `europe-west3` repository. A useful escape hatch, not a workaround
  to be ashamed of.

**Left behind, on purpose and worth a tidy-up:** the deploy check registered
`deploy-check-1793381934@example.com` in production. It holds **0 credit**, so it cannot cost an
SMS or a DeepFaune run, and there is no delete endpoint — user-account is private and `PUT
/api/hunters/{id}` is not proxied — so removing it means a Cloud SQL session or a console step.

**Still true after the deploy:** nobody has opened the new screens in a **browser**. The data path
is now proven in production end to end; the pixels are not. And the login form is publicly
reachable at the `run.app` URL with **no brute-force protection**, which is the argument for doing
Turnstile next rather than after the DNS switch.

## Done — password change and reset (2026-10-30, branch `worktree-password-reset`)

Designed first in [`docs/password-reset.md`](./docs/password-reset.md) (written and committed
before any code, so the reasoning survives independently), then built in one branch.

**The gap was wider than "no forgot-password".** Nothing in the product could change a password at
all: `UpdateHunterRequest` carried only `phone` and `active`, and `setPassword` was called from
exactly one place, `register`. A hunter who *knew* their password could not rotate it, and there
was no admin path, so the only route was SQL against production — with two traps that are now
written down because operators will still do it occasionally: the hash needs its `{bcrypt}` prefix
(the encoder is the delegating one), and `password_hash = NULL` **locks** an account rather than
clearing it.

**Email, not SMS, and the premise in the old note was wrong twice.** STATUS said reset was
"blocked on outbound email, which this project has never had". But the apex MX governs *inbound*
mail only: SPF today carries just Cloudflare's forwarding include (`v=spf1
include:_spf.mx.cloudflare.net ~all`) and there is **no DMARC record at all**, so sending needs a
provider plus DNS records, not a re-architecture. And reusing GoSMS — the tempting option, since
the phone is already on file — was rejected: **a reset endpoint has to be unauthenticated**, so an
SMS flow lets anyone on the internet spend prepaid credit keyed on someone else's address. That is
the silent outage the credit guard exists to prevent, reached from a direction the guard cannot
cover, because it refuses an alert it cannot pay for *after* the credit is already gone. SMS's
right job here is **phone verification**, where the phone is the thing being proven and the spend
sits behind registration.

**What shipped:**
- `POST /api/me/password` (session-authenticated) — re-verifies the current password even though a
  valid session is present, because a session may be an unattended browser and this endpoint hands
  over permanent control. Closes every session, then issues a fresh cookie for the caller, so the
  hunter who made the change stays signed in and every other browser does not.
- `POST /api/password-resets` — **always 202**, for a registered address or not. Anything else
  turns a public form into a way to test which addresses have accounts. Silent on four paths:
  unknown address, deactivated hunter, a hunter with no password (pre-login rows), and throttled.
- `POST /api/password-resets/confirm` — one generic **400** for unknown, expired, spent, or
  deactivated. Issues no cookie on purpose: reading a mailbox proves mailbox access, not knowledge
  of the password, so the hunter is sent to sign in and uses the new password once immediately.
- Flyway **V6** `password_reset_token`, shaped like `hunter_session`: the SHA-256 hex is the
  primary key, 1h expiry, single use enforced on `used_at` (the spent row is kept so a replay is
  *recognisably* spent), a ~5 minute per-hunter throttle, and a sweep of spent/expired rows that
  rides on the rare request path rather than a `@Scheduled` task Cloud Run would not reliably run.
- `EmailSender` + `LoggingEmailSender` — the project's **first outbound email of any kind**,
  following the house interface/fake-default rule. It logs recipient and subject at INFO but
  **withholds the body**, because the body holds the link and the link *is* the credential: on
  Cloud Run every line is retained in Cloud Logging, which would make the log a standing list of
  working resets. The link goes out at DEBUG for local dev only.
- Gateway: three routes added to the deliberately tiny allowlist. The two reset paths are the only
  state-changing endpoints there an anonymous caller is *meant* to reach.
- `web/`: a "Forgot password?" link, a request screen whose confirmation reads "if … has an
  account" (so the two cases cannot be told apart by comparing copy), a reset screen, and a
  collapsed change-password panel on the dashboard. The stale "There is no password reset yet"
  hint on the login form is gone.

**The link is `/?reset=…`, a query parameter, not a `/reset` path** — caught while wiring the UI.
The gateway serves the app from `static-locations` with no SPA fallback, so a path-shaped link
would have arrived in inboxes already 404ing. Same constraint that keeps the app routerless.

**Verified live, not only in unit tests** (18 checks against the real jar on real Postgres, since
this project has been bitten by a 415 that every mocked test passed): register → sign in → request
a reset for a non-existent address (202) → for the real one (202) → an immediate second request
produced **one** link, not two (throttle) → too-short password 400 → bogus token 400 → confirm 204
→ replay 400 → the pre-reset session 401 → old password 401 → new password 200 → change-password
with a wrong current 401, with no session 401, properly 200 with a fresh `Set-Cookie` → reissued
cookie 200. In the database: `token_hash` 64 chars, `used_at` set, TTL 59:59, and the stored
password carries `{bcrypt}`. Test rows deleted afterwards.

**Still to do before a hunter can actually use it:** pick a sending provider (Resend/Postmark/SES,
or Cloudflare Email Sending — no new vendor, but it sends from a Worker binding), add its SPF
include, DKIM and a DMARC record, put the API key in Secret Manager with a **per-secret** binding
(not the project-level `secretAccessor` user-account still holds), and set `EMAIL_PROVIDER` and
`PUBLIC_BASE_URL=https://wildalert.app` with `--update-env-vars`. Until then a reset mail is
logged, not sent. **Turnstile belongs in front of this form** as much as registration and login —
the throttle is a courtesy to a hunter's inbox, not an abuse control.

### Deployed to prod (2026-10-31)

Both services live from image tag **`d870772`** (merged main, PRs #34 + #35):
**`user-account-00006-kn4`** and **`gateway-00003-kgj`**.

- **Flyway applied V6 to production Cloud SQL**: "Current version of schema: 5" → "Migrating
  schema to version 6 - create password reset token" → "Successfully applied 1 migration … now at
  version v6" in 0.184s. The startup probe points at `/actuator/health/readiness`, which includes
  `db`, so a failed migration would have left the revision never serving and prod still on
  `00005-sjb` — the same safety net that covered V5.
- **`PUBLIC_BASE_URL=https://wildalert.app` is set on the revision**, so reset links point at the
  real site rather than the `http://localhost:5173` default. Set with `--update-env-vars`, and the
  revision afterwards still carries all six: `DATABASE_URL`, `POSTGRES_USER`,
  `INBOUND_EMAIL_DOMAIN`, the `POSTGRES_PASSWORD` secret reference, `SESSION_COOKIE_SECURE` and
  the new one. `--set-env-vars` would have dropped the first five.
- **Ordering was user-account first, deliberately, and this time it was right.** Every API change
  in this slice is **additive** — three new endpoints, no existing contract altered — so the old
  UI was unaffected while the new backend was live. That is the opposite of the hunter-login
  deploy, where `password` became a *required* field on `POST /api/hunters` and the old client
  broke for six minutes. The rule that distinguishes them: when a field becomes **required**,
  client and server ship together or the client ships first; when endpoints are only **added**,
  the server goes first.
- **Verified through the public edge, not the private services.** `wildalert.app` 200; the served
  bundle is `assets/index-CrB20mn5.js`, byte-identical in name to what the local `vite build`
  produced, and it contains the new copy ("Forgot your password?", "Change password", "Choose a new
  password"). `POST /api/me/password` with no cookie answers **401 rather than 404**, and
  `POST /api/password-resets` answers **400** to a malformed address and **202** to an unknown one
  — which together prove the routes exist *and* that the gateway's ID token is being accepted.
  `confirm` with a bogus token gives the one generic 400 and a body that says only "This password
  reset link is no longer valid. Please request a new one." The allowlist is still tight:
  `by-email` and `by-inbound-address` both **404 at the gateway**.
- **No hunter was registered by this check**, unlike the hunter-login deploy, which left
  `deploy-check-1793381934@example.com` in production needing a Cloud SQL session to remove. The
  reset-request path is readable with an address that deliberately has no account, so the 202 path
  and the 400/401 paths can all be exercised without writing a row.

**The known trade, accepted deliberately: the "Forgot password?" link is live and cannot deliver.**
`EMAIL_PROVIDER` is unset, so it defaults to the logging fake: a hunter gets "check your inbox"
and no mail ever arrives, because there is no sending provider yet. **Resend was chosen**
(2026-10-31) — one HTTPS POST from Kotlin, a single API key, their generated DKIM records — and
wiring it is the next slice. Until then the honest state is that *changing* a password works end
to end in production and *resetting* a forgotten one does not. A hunter who needs one is still
helped by hand, with the SQL recipe in `docs/password-reset.md`.

**Build notes.** Merging PR #35 fired all four `--branch=main` triggers automatically, but the
`europe-west3` queue serialises and `user-account` sat behind `recognition` (torch plus model
weights, the longest build in the repo). Re-submitting the same config with **no `--region`** built
both images in the **global** region instead — `gateway` in 3m01s, `user-account` in 1m46s — and
they still pushed to the `europe-west3` repository. `gateway` has **no trigger at all**, so it
needs a manual `gcloud builds submit` with `--service-account=…wildalert-cloudbuild@…` every time;
creating `gateway-main` remains the obvious follow-up and would have made this deploy two commands.

## Done — Resend wired behind EmailSender (2026-10-31)

**Resend chosen** over Postmark and SES: one HTTPS POST, a single API key, DKIM records it
generates. Cloudflare Email Sending would have added no vendor relationship — appealing here — but
it is a **Worker binding rather than an API**, so Kotlin would have had to call a Worker we then
own and secure as a send relay. `ResendEmailSender` sits behind `email.provider=resend`, so
`LoggingEmailSender` is still the default and no test needs a key.

**It deliberately does not retry.** A failure propagates to `PasswordResetService`, which discards
the unsent token; a hunter clicking the button again is a better retry than one we invent, and a
retry loop would hold the request open waiting for a 202 that says nothing about delivery anyway.

**A missing key fails startup rather than the flow.** `ResendEmailSender` requires a non-blank key
in `init`, so `EMAIL_PROVIDER=resend` without the secret is a revision that never serves — visible
as a deploy that did not happen. The deliberate opposite of `INBOUND_EMAIL_DOMAIN`, which was once
unset and nobody was ever texted. Verified live: exit code 1, the guard's own message, and
"Started UserAccountApplication" never printed.

### Three things the real sender exposed that the logging fake had hidden

These are the reason this slice touched more than one new file, and all three were invisible while
the only implementation could not fail.

1. **A send inside the `@Transactional` request held a Cloud SQL connection for the whole HTTPS
   call.** On `db-f1-micro`, with a provider having a bad minute, that is the connection pool spent
   on waiting. The database work moved into **`PasswordResetTokenIssuer`**, which commits before
   anything is sent.
2. **A send failure would have escaped as a 500 — an account-existence oracle.** An address with
   no account never reaches a send, so it always answers 202; a *real* address during an outage
   would have answered 500. That difference is precisely what the whole flow is written to hide.
   Now caught, the unsent token discarded, and the answer stays 202. Discarding also *clears the
   throttle*, which is keyed on a fresh unused token — so a hunter can retry immediately instead
   of waiting five minutes for a mail that never came.
3. **`PasswordResetTokenIssuer` had to be a separate bean.** The first version put `issue()` and
   `discard()` on `PasswordResetService` itself, where `@Transactional` would have been **silently
   ignored**: Spring's transaction support is proxy-based, and a call between two methods of the
   same class never passes through the proxy. The session and token sweepers are separate beans
   for exactly this reason, which is what made the mistake recognisable.

**Dead config found and fixed.** `password-reset.from-address` was declared on
`PasswordResetProperties`, documented in `application.yml`, set in a test — and **read by
nothing**. The first real sender needed a From address, which is how it surfaced. It now lives on
`EmailProperties` as `email.from-address`, because the address belongs to the DNS-verified domain
rather than to this one flow. Worth remembering: dead config is indistinguishable from working
config to anyone reading `application.yml`.

### Verification

116 tests green in user-account (`PasswordResetServiceTest` 16, `ResendEmailSenderTest` 5). The
Resend tests use `MockRestServiceServer` bound to the real `RestClient.Builder`, as
`GoSmsSenderTest` does, and pin the request shape: bearer key, `from`, `to` as a list, `text` and
**no `html`**, plus 401, Resend's 422 "domain is not verified" (the likeliest first-deploy
failure), a 200 with no id, and the empty-key guard.

Then live, against a deliberately unreachable Resend (`base-url=http://localhost:9`), because the
oracle and the discard cannot be proven by unit tests: unknown address **202**, real address
**202** rather than 500, an immediate retry **202** (not throttled), the failure logged twice,
nothing logged as sent, and **zero rows left in `password_reset_token`** — checked in Postgres, not
just in the log. Test rows deleted afterwards.

### Not deployed, and what gates it

The code is merged but **`EMAIL_PROVIDER` stays unset in production**, so the live "Forgot
password?" link is still the dead end recorded in Known gaps. Turning it on needs, in order:
a **Resend account** with `wildalert.app` verified (a human step); **DNS** — Resend's DKIM CNAMEs,
its SPF include *alongside* Cloudflare's rather than replacing it, and a `_dmarc` record, with
**MX untouched** since that is Email Routing carrying every hunter's inbound address; the key in
Secret Manager with a **per-secret** binding (not the project-level `secretAccessor` user-account
already over-holds); then `EMAIL_PROVIDER=resend` and `RESEND_API_KEY` via `--update-env-vars`.
Full checklist in [`docs/password-reset.md`](./docs/password-reset.md).
## Remaining phases
- **7 — Hardening & cost control** (tune threshold, smart-SMS to cut cost, logging/metrics,
  admin auth). **Registration abuse control is now live work, not future work** — the gateway is
  deployed and the form is publicly reachable. **Turnstile is the control**; Cloudflare's free
  rate limiting (one rule, IP only, 10-second window) is a flood brake and nothing more. See
  slice 2 and the domain slice below.
- **8 — React frontend** — slice 1 (registration UI), slice 2 (the gateway that serves it) and
  slice 3 (hunter login + a credit and address page) built and tested, plus the detection-history
  service behind the eventual dashboard; all above.
  **The gateway is deployed** (2026-10-29, `gateway-00001-p5f`) and serves the form on its
  `run.app` URL; `deploy/site-worker/` puts it on `wildalert.app`, awaiting two DNS records and a
  `wrangler deploy`. Still to do after that: phone verification, the detection list on the
  dashboard, presigned photo URLs, and brute-force
  protection on the login form.
- **9 — Scale / auth / billing.** Hunter authentication is **partly done** as of slice 3 above: a
  hunter can sign in and read their own record. What remains under this heading is the password
  reset, brute-force protection, authorizing detection-history's read API (it still identifies a
  hunter by an inbound address in a query parameter, so it must stay private), and billing.

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
- **The "Forgot password?" link is live on `wildalert.app` and cannot deliver an email**
  (2026-10-31). `EMAIL_PROVIDER` is unset, so `LoggingEmailSender` is active: the request is
  accepted, 202 comes back, the link is logged at DEBUG and no mail is sent. A hunter sees "check
  your inbox" and waits forever. Accepted knowingly at deploy time to get the working
  change-password panel out. Changing a password works end to end — only *resetting a forgotten
  one* is the dead end. **The Resend sender is now written, tested and merged** (2026-10-31), so
  what remains is not code: a Resend account with `wildalert.app` verified, the DKIM/SPF/`_dmarc`
  records, the key in Secret Manager with a per-secret binding, then `EMAIL_PROVIDER=resend` on
  the revision. Checklist in `docs/password-reset.md`. Note that turning it on with the key
  missing is a revision that never serves, by design — so set the secret before the env var.
- **Nothing watches the gateway-credit warning yet.** The low-balance line is in Cloud Logging and
  the dead-letter topic fills if the account empties, but neither reaches a human on its own — it
  needs a log-based alert policy, with Phase 7's logging/metrics item.
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
