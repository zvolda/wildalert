# Deploying WildAlert to Google Cloud

Phase 6. This folder deploys the **user-account** service first: it is the simplest one (HTTP in,
Postgres out, no broker), the other services depend on it, and deploying it exercises the whole
toolchain once — Artifact Registry, Cloud Build, Cloud Run, Cloud SQL and Secret Manager.

**The deploy is done by hand in the Cloud Console**, click by click, in
[`CONSOLE.md`](./CONSOLE.md) — that is the walkthrough to follow, and the record of which option was
chosen in every form. The only step the console cannot do is building the container image, because
there is no "build my Dockerfile" button; that uses [`cloudbuild.yaml`](./cloudbuild.yaml), see
*Building the image* below.

There are deliberately **no shell scripts here**. An earlier version of this folder had
`00-bootstrap.sh` / `10-cloudsql.sh` / `20-deploy-user-account.sh` / `99-teardown.sh`; they were
removed once the infrastructure had been provisioned through the console, because a script nobody
runs drifts from reality and then lies — that version still said `POSTGRES_17` after the instance
was built as 18. If this grows past a handful of services, the answer is OpenTofu/Terraform, real
infrastructure-as-code, not shell scripts.

## Settings — the values the deploy uses

These were `config.sh`; they now live here, and `CONSOLE.md` repeats each one in the form where it
gets entered.

| Setting | Value |
|---|---|
| Project | `wildalert-prod` (project number `789527844011`, no parent organization) |
| Region | `europe-west3` (Frankfurt — nearest EU region with both Cloud Run and Cloud SQL) |
| Artifact Registry repo | `wildalert` → `europe-west3-docker.pkg.dev/wildalert-prod/wildalert` |
| Cloud SQL instance | `wildalert-db`, `db-f1-micro`, **PostgreSQL 18**, zonal, 10 GB HDD, no backups |
| Cloud SQL connection name | `wildalert-prod:europe-west3:wildalert-db` |
| Database | `wildalert` (UTF8 / en_US.UTF8) |
| Database user | `wildalert` (built-in auth, keeps `cloudsqlsuperuser`) |
| Password secret | `wildalert-db-password` (global secret, automatic replication) |
| Service account | `wildalert-user-account@wildalert-prod.iam.gserviceaccount.com` |
| Service account roles | `roles/cloudsql.client`, `roles/secretmanager.secretAccessor` — nothing else |
| `INBOUND_EMAIL_DOMAIN` | `wildalert.app` — the **apex** of the Cloudflare zone, not a subdomain (registered 2026-10-14; the deployed revision still says `in.wildalert.local` until it is updated) |

### email-ingestion — the public one

| Setting | Value |
|---|---|
| Cloud Run service | `email-ingestion`, europe-west3, **Allow unauthenticated**, ingress All, min/max 0/3, 1 vCPU / 1 GiB |
| Service URL | `https://email-ingestion-lznkorfm4q-ey.a.run.app` — what `gcloud run services describe` reports, and the form to use for `INGESTION_URL` and any token audience. The project-number form `https://email-ingestion-789527844011.europe-west3.run.app` also answers on the same service (both verified 200); keep one form everywhere. Deployed 2026-10-19, revision `email-ingestion-00002-nf9`. |
| Service account | `wildalert-email-ingestion@wildalert-prod.iam.gserviceaccount.com` |
| Service account roles | `roles/pubsub.publisher` **on topic** `image.received`, `roles/run.invoker` **on service** `user-account`, `roles/secretmanager.secretAccessor` **per secret** (the three below) |
| Secrets | `wildalert-webhook-secret` → `WEBHOOK_SECRET`, `wildalert-r2-access-key` → `R2_ACCESS_KEY`, `wildalert-r2-secret-key` → `R2_SECRET_KEY` |
| Env | `SPRING_PROFILES_ACTIVE=pubsub`, `USER_ACCOUNT_BASE_URL=https://user-account-lznkorfm4q-ey.a.run.app`, `USER_ACCOUNT_AUTH=gcp-id-token`, `STORAGE_PROVIDER=r2`, `R2_ENDPOINT`, `R2_BUCKET=wildalert-images` |
| Image storage | Cloudflare R2 bucket `wildalert-images` (EU), scoped Object Read & Write API token |
| Build trigger | `email-ingestion-main` — same `cloudbuild.yaml`, `_SERVICE=email-ingestion` |
| Startup probe | **Cloud Run's default TCP check on 8080**, not the HTTP probe on `/actuator/health/readiness` — see the Git Bash path-mangling entry under *Troubleshooting*. Add the HTTP probe from the console when convenient; here it would only mean "Spring started", as this service has no database. |

**It is public on purpose, and it is the only one.** Cloudflare Workers call in from outside Google
Cloud, so there is no Google identity to grant `run.invoker` to; `WebhookAuthFilter`'s shared secret
is the protection. **It authenticates *outwards* with an ID token** (`UserAccountAuth`,
`USER_ACCOUNT_AUTH=gcp-id-token`), because user-account requires authentication — without the
matching `run.invoker` grant every lookup 403s and, by design, degrades to "no hunter matched"
rather than failing the webhook. So the symptom was silent: a 200 with no hunter and no alert.
**Fixed by moving the lookup:** the recipient address now travels on `ImageReceived` and
notification resolves it, where a failure throws and Pub/Sub redelivers. email-ingestion makes no
calls to user-account at all, and needs `INBOUND_EMAIL_DOMAIN` set to pick our own address out of
an email's recipients.

Keep everything in one region: cross-region traffic costs money and adds latency.

### Pub/Sub (topics and the `.dlt.inspect` pull subscriptions exist; push subscriptions wait for the consumers)

Events move on **Cloud Pub/Sub push subscriptions**: Pub/Sub POSTs each event to an HTTP endpoint,
so the consumers need no client library and still scale to zero. Dead-letter topics use a `.dlt`
suffix, matching the names the local emulator creates in `compose.yml`.

| Resource | Value |
|---|---|
| Topics | `image.received`, `animal.recognized`, `image.received.dlt`, `animal.recognized.dlt` |
| Push subscription → recognition | `image.received.recognition` → `POST <recognition-url>/events/image-received`, ack deadline **600s**, retry backoff 10s–600s, dead-letter → `image.received.dlt` after **5** attempts |
| Push subscription → notification | `animal.recognized.notification` → `POST <notification-url>/api/events/animal-recognized`, ack deadline **60s**, same retry, dead-letter → `animal.recognized.dlt` after **5** attempts |
| Dead-letter subscriptions | `image.received.dlt.inspect`, `animal.recognized.dlt.inspect` — plain pull |
| Push identity | `wildalert-pubsub-push@wildalert-prod.iam.gserviceaccount.com`, no keys, `roles/run.invoker` **per service** |
| Publisher identities | `wildalert-email-ingestion@…` on `image.received`, `wildalert-recognition@…` on `animal.recognized` — `roles/pubsub.publisher` granted **on the topic**, not the project |
| Pub/Sub service agent | `service-789527844011@gcp-sa-pubsub.iam.gserviceaccount.com` needs `roles/iam.serviceAccountTokenCreator` **on the push service account**, plus publisher on the `.dlt` topics and subscriber on the two source subscriptions |

Two things that are easy to get wrong:

- **A topic with no subscription discards everything published to it.** Without the two
  `.dlt.inspect` pull subscriptions, dead-lettering writes into the void and "nothing is lost" is
  a fiction.
- **Both consumers keep ingress `Internal`.** Pub/Sub push is one of the few sources that can
  reach an internal-ingress Cloud Run service, so they never need a public URL. Authentication is
  Cloud Run's job (the push subscription sends a Google-signed OIDC token); the apps deliberately
  do not verify the JWT themselves, because only the push account holds `run.invoker`.

Cloud Run settings that matter for push: recognition needs **request timeout 600s** (to match the
ack deadline) and startup CPU boost; the model is loaded at startup, so the startup probe holds
traffic until the weights are in memory.

## Building the image

The console cannot build it, so this runs in **Cloud Shell** (the `>_` icon, top right — it has
`gcloud`, `git` and Docker, authenticated as you, nothing to install locally):

```bash
git clone https://github.com/zvolda/wildalert.git
cd wildalert   # main is the default branch

BASE="europe-west3-docker.pkg.dev/wildalert-prod/wildalert/user-account"
SHA="$(git rev-parse --short HEAD)"
gcloud builds submit --config deploy/cloudbuild.yaml \
  --substitutions="_SERVICE=user-account,_IMAGE_BASE=${BASE},SHORT_SHA=${SHA}" .

echo "${BASE}:${SHA}"   # the exact string to paste into the Cloud Run form
```

**Commit and push before building.** Cloud Shell builds what is on GitHub, so uncommitted work is
invisible to it — notably the `postgres-socket-factory` dependency, whose absence produces an image
that builds fine and then fails at startup on a missing `socketFactory` class.

Each service's Dockerfile expects the **repo root** as its build context (it runs `./gradlew`),
which is why `cloudbuild.yaml` passes it with `--file=` rather than letting Docker find it. The root
`.dockerignore` excludes `.gradle/`, so every build downloads Gradle and the dependencies fresh —
hence `E2_HIGHCPU_8` and the 30-minute timeout.

The build runs as the **default compute service account**, which still holds `roles/editor`. That is
why it needs no extra IAM grants, and why stripping that binding is a Phase 7 task in `ROADMAP.md`:
do it *after* the first green deploy, with explicit build roles in its place, or builds break. A
dedicated `wildalert-cloudbuild` account needs only `roles/artifactregistry.writer` (push the image)
and `roles/logging.logWriter` (write build logs) — build-time rights stay separate from the
service's runtime identity, which can reach the database but must never be able to publish images.

**`gradlew` is committed without its execute bit** (it was added from Windows), so every Dockerfile
runs `chmod +x gradlew` before `./gradlew`; without it the build dies at `./gradlew: Permission
denied` (exit 126). `git update-index --chmod=+x gradlew` fixes it at the source if you prefer.

**The image tag comes from `$SHORT_SHA` inside `cloudbuild.yaml`**, not from a substitution field —
Cloud Build does not expand built-ins inside the value of a user substitution, so `_IMAGE` set to
`...:$SHORT_SHA` fails with `could not parse reference`. The trigger passes `_IMAGE_BASE` (no tag).

## What it costs

- **Cloud SQL is the only meaningful cost.** `db-f1-micro`, zonal, 10 GB HDD, no backups — the
  cheapest shape that runs Postgres properly, quoted at **$0.01/hour ≈ $9/month** including storage
  in `europe-west3`. It bills **per second for as long as the instance exists**, whether or not
  anything queries it. There is no scale-to-zero for Cloud SQL.
- **Cloud Run is effectively free here.** Min instances 0 means an idle service costs nothing; you
  pay only for CPU and memory while a request is being served.
- **Artifact Registry and Secret Manager** are fractions of a cent at this size.
- **Pub/Sub is free** at this volume — the free tier covers 10 GiB/month and a few trail-cam
  photos a day is nowhere near it.

So tear the database down when you stop for a while — see *Teardown*.

## Verifying the deploy

The service is deployed with **Require authentication**, because it serves hunters' phone numbers
and must never be open to the internet. A plain `curl` gets **403** — that is the correct result.
Call it with your own identity token, from Cloud Shell or any authenticated `gcloud`:

```bash
URL=$(gcloud run services describe user-account --region europe-west3 --format='value(status.url)')
TOKEN=$(gcloud auth print-identity-token)

curl -H "Authorization: Bearer $TOKEN" "$URL/actuator/health"
# {"status":"UP","groups":["liveness","readiness"]}

curl -X POST "$URL/api/hunters" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","phone":"+420123456789"}'
```

A healthy `/actuator/health` proves a lot at once: the container started, Flyway ran both
migrations, and the Cloud SQL connection works — `readiness` includes the `db` health indicator, so
it could not be `UP` otherwise.

## How the pieces connect

**The database connection.** No password travels over TCP and there is no IP allowlist. The JDBC URL
names a socket factory:

```
jdbc:postgresql:///wildalert?cloudSqlInstance=wildalert-prod:europe-west3:wildalert-db&socketFactory=com.google.cloud.sql.postgres.SocketFactory
```

The Postgres driver loads that class by name (hence the `runtimeOnly` dependency in
`services/user-account/build.gradle.kts`) and it opens an authenticated connection via the Cloud SQL
Admin API, authorised by the service account's `roles/cloudsql.client`. Locally `DATABASE_URL` is a
plain `jdbc:postgresql://localhost:5432/wildalert` — same jar, different environment variable.

**The password.** Produced by Cloud SQL's **Generate** button and stored as the only version of the
`wildalert-db-password` secret. Cloud Run injects it as `POSTGRES_PASSWORD` through a secret
reference, so it never appears in the service's environment variable list. The payload is raw bytes,
so a pasted trailing newline becomes part of the password and surfaces later as a Postgres
authentication error that says nothing about whitespace.

**Identity.** `wildalert-user-account@…` holds exactly two roles: open a Cloud SQL connection, and
read its own DB password. Each service gets its own, so one compromised service cannot act as
another. `roles/run.invoker` for email-ingestion and notification comes when they deploy.

**Startup.** The Cloud Run startup probe points at `/actuator/health/readiness`, so a revision only
goes live once Flyway has run and Cloud SQL is reachable.

## Troubleshooting

**The deploy fails and the revision never becomes ready.** Read the container's own logs — the
startup probe failing is a symptom, not the cause:

```bash
gcloud run services logs read user-account --region europe-west3 --limit 100
```

**Git Bash rewrites any flag value that starts with `/` (Windows only).** MSYS path conversion turns
`--startup-probe=httpGet.path=/actuator/health/readiness` into
`httpGet.path=C:/Program Files/Git/actuator/health/readiness` before gcloud ever sees it. The deploy
then fails after about two minutes with *"The user-provided container failed the configured startup
probe checks"*, while the container's own log shows a perfectly healthy app and a 404 whose body is
`{"path":"/"}`. Three things that do **not** fix it: quoting the value (conversion ignores quotes),
`MSYS_NO_PATHCONV=1` (it breaks gcloud's own launcher, which needs the conversion to locate
`gcloud.py`), and a leading `//` (it survives as `//actuator/...`, which 404s too). Worse, the
mangled probe is persisted on the **service template**, so a re-deploy that merely omits the flag
inherits it — clear it explicitly with `--startup-probe=""`, as there is no `--clear-startup-probe`.
Set HTTP probes from the **console** Health checks form instead, or run the deploy from PowerShell,
which does no conversion. This applies to every `gcloud` flag whose value begins with `/`, so the
recognition and notification deploys hit it identically.

**Flyway fails with a permission error on the `public` schema.** PostgreSQL 15 removed the default
`CREATE` grant on `public`, so whether the `wildalert` user can create tables depends on it
inheriting the database owner's rights through Cloud SQL's `cloudsqlsuperuser` role — which is why
that role was **kept** when the user was created. If it is ever missing, open **Cloud Shell** (it
has `psql` and connects without an IP allowlist) and run, as `postgres` against `wildalert`:

```sql
GRANT ALL ON SCHEMA public TO wildalert;
ALTER DATABASE wildalert OWNER TO wildalert;
```

**`403 Forbidden` on every request.** Expected — see *Verifying the deploy*. If the identity token
also gets 403, you need `roles/run.invoker` on the service.

**The password works locally but not in the cloud.** Check the stored payload for stray whitespace:

```bash
gcloud secrets versions access latest --secret wildalert-db-password | od -c | tail
```

**The instance name is already taken.** Cloud SQL reserves a deleted instance's name for a while.
Either wait, or create it as `wildalert-db2` — then update the connection name here, in `CONSOLE.md`
and in the Cloud Run `DATABASE_URL`.

## Teardown — stop paying when you stop working

**SQL → `wildalert-db` → Delete** (you have to type the instance name). That is the only resource
with a meaningful idle cost. Nothing is lost that the console steps plus Flyway cannot recreate —
only test data.

Three toggles can quietly keep billing you after the instance is gone, so confirm they are off
before deleting (**Edit → Data Protection**):

- **Retain backups after instance deletion** — leaves billed backup storage behind
- **Final backup on instance deletion** — creates a backup kept 30 days, billed
- **Prevent instance deletion** — blocks the delete outright

Cloud Run at min-instances 0 costs nothing idle, so the service can stay; deleting it is Cloud Run →
`user-account` → Delete. Keep the Artifact Registry image and the secret — they cost fractions of a
cent and save you a rebuild.

Coming back means redoing **steps 4–7** of `CONSOLE.md` (instance, database, user, secret) and then
**step 9** (deploy the existing image to Cloud Run).

## Keeping a record

The console leaves no trace of *what you chose*. After any change, capture the result:

```bash
gcloud sql instances describe wildalert-db --format=yaml > sql-actual.yaml
gcloud run services describe user-account --region europe-west3 --format=yaml > run-actual.yaml
```

If those disagree with the tables above, one of the two is wrong — worth knowing which. Replacing
this folder with OpenTofu is the proper fix, and a good slice once more than one service is
deployed.

## Not done yet in Phase 6

- **recognition and notification**: images, Cloud Run services, the two **push subscriptions** that
  need their URLs, `wildalert-pubsub-push` with `run.invoker` per service, `wildalert-recognition`
  with `pubsub.publisher` on `animal.recognized`, and the Pub/Sub service agent's
  `iam.serviceAccountTokenCreator`. **All of this was done on 2026-10-22** — both services are
  deployed, both push subscriptions exist, and a real emailed photo produced an SMS nine seconds
  later. `image.received.inspect`, the pull subscription used to read events while there was no
  consumer, was deleted the same day; recognition's own log now shows what it received.
- **`roles/run.invoker` on user-account for notification**, once it exists (email-ingestion's is
  part of its own deploy).
- **The last three Cloudflare clicks**: `wrangler deploy` the Email Worker, point the catch-all at
  it, and set `INBOUND_EMAIL_DOMAIN=wildalert.app` on user-account. No tokens need regenerating —
  only the token is stored, the domain comes from config.

The broker decision is **settled**: Cloud Pub/Sub push, Kafka removed from the repo (2026-10-08).
