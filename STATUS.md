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
    **Still to do in the cloud:** clear `USER_ACCOUNT_BASE_URL` / `USER_ACCOUNT_AUTH` from the
    email-ingestion revision and revoke its `roles/run.invoker` on user-account — it has no use for
    either now.
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
