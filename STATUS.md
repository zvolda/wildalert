# WildAlert — Project Status

_Last updated: 2026-10-26. Companion to [`ROADMAP.md`](./ROADMAP.md): ROADMAP is the plan,
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

## Remaining phases
- **7 — Hardening & cost control** (tune threshold, smart-SMS to cut cost, logging/metrics,
  admin auth). **Registration abuse control now belongs here and is no longer optional** once the
  gateway deploys — see slice 2.
- **8 — React frontend** — slice 1 (registration UI) and slice 2 (the gateway that serves it)
  built and tested, plus the detection-history service behind the eventual dashboard; all above.
  Still to do: deploy the gateway, see the UI in a browser, phone verification, the dashboard
  itself, presigned photo URLs and auth.
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
