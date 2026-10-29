# Deploying WildAlert through the Cloud Console (UI)

This is how the deploy is done: by clicking, form by form. It is also the record of which option
was chosen where, so keep it in step with [`README.md`](./README.md)'s settings table — that table
and these forms are the only places the values live.

Steps 1-10 cover the shared infrastructure and **user-account** (private, Cloud SQL). Steps 10a-11b
cover **email-ingestion** (public, the webhook) and the domain that feeds it. Steps 1-3 and 8 are
reused rather than repeated for the second service.

> **Verifying as you go.** The console does not tell you what it actually created, and its presets
> switch on fewer things than the labels imply — provisioning this instance left backups,
> point-in-time recovery and deletion protection **on** despite the Sandbox preset. So after every
> step, run the `gcloud ... describe` command given with it and read the result. *Some* forms also
> offer an **"Equivalent command line"** button (Cloud SQL does, Artifact Registry does not); it is
> worth reading before you click Create, but the describe afterwards is what proves anything.

> **Console labels move.** Google rewords and reorganises these forms regularly. The **values**
> below are what matter; if a label reads differently, look for the nearest equivalent rather than
> assuming something is missing.

Make sure the project selector at the top of the console says **wildalert-prod** throughout.

---

## Step 1 — APIs (probably already done)

**Navigation:** APIs & Services → Enabled APIs & services

Confirm these five are listed. All five were already enabled on your project, so this is a check,
not a task:

`run.googleapis.com` · `cloudbuild.googleapis.com` · `artifactregistry.googleapis.com` ·
`sqladmin.googleapis.com` · `secretmanager.googleapis.com`

**`pubsub.googleapis.com` is a sixth, needed for step 11** (not for `user-account`). It is already
enabled on this project — it came with the default bundle a console-created project gets, like
`bigquery` and `storage`. Note that the Pub/Sub **service agent** which step 11 grants roles to may
still not appear in IAM until Pub/Sub is actually used.

Anything missing: **Library** → search the name → **Enable**.

*Cost: none.*

---

## Step 2 — Artifact Registry repository

**Navigation:** Artifact Registry → Repositories → **Create Repository**

| Field | Value |
|---|---|
| Name | `wildalert` |
| Format | **Docker** |
| Mode | Standard |
| Location type | Region |
| Region | `europe-west3` (Frankfurt) |
| Description | `WildAlert service images` |

Leave encryption, cleanup policies and immutable tags at their defaults.

The region **must** match where Cloud Run runs, or every image pull crosses regions and costs
egress.

This form has no "Equivalent command line" button. Check it afterwards in Cloud Shell:

```bash
gcloud artifacts repositories describe wildalert --location=europe-west3   --format="value(name,format,mode)"
# .../repositories/wildalert   DOCKER   STANDARD_REPOSITORY
```

*Cost: none while empty.*

---

## Step 3 — Service account

**Navigation:** IAM & Admin → Service Accounts → **Create Service Account**

| Field | Value |
|---|---|
| Service account name | `wildalert-user-account` |
| Service account ID | `wildalert-user-account` (auto-filled) |
| Description | `WildAlert user-account service` |

On the **"Grant this service account access to project"** step, add exactly two roles:

- **Cloud SQL Client** (`roles/cloudsql.client`)
- **Secret Manager Secret Accessor** (`roles/secretmanager.secretAccessor`)

Skip the third step (user access). Click **Done**.

You should end up with `wildalert-user-account@wildalert-prod.iam.gserviceaccount.com`.

**Why bother:** if you skip this, Cloud Run falls back to the default compute service account,
which holds **Editor** on the whole project. A bug in this one service could then delete your
database or read every secret. These two roles let it do exactly two things: open a Cloud SQL
connection, and read its own password.

*Cost: none.*

---

## Step 4 — Cloud SQL instance ← **this is where billing starts**

**Navigation:** SQL → **Create Instance** → **PostgreSQL**

The console's defaults here are **far** more expensive than this project needs — it steers you
toward Enterprise Plus, multi-core, high availability and backups. Change all of it:

| Field | Value | Why |
|---|---|---|
| Instance ID | `wildalert-db` | |
| Password (built-in `postgres` user) | click **Generate**, save it in your password manager | Admin user, separate from the app user in step 6 |
| Database version | **PostgreSQL 18** | what the deployed instance runs; local compose is still 17 |
| Cloud SQL edition | **Enterprise** (*not* Enterprise Plus) | shared-core tiers only exist on Enterprise |
| Preset | **Sandbox** (or Development) | Production turns on HA and doubles the cost |
| Region | `europe-west3` | same region as Cloud Run |
| Zonal availability | **Single zone** | HA doubles the price for a learning project |
| Machine shape | **Shared core → 1 vCPU, 0.614 GB** (`db-f1-micro`) | cheapest tier that runs Postgres |
| Storage type | **HDD** | cheaper than SSD, fine at this size |
| Storage capacity | **10 GB** | |
| Automatic storage increases | **off** | stops storage silently growing into a bigger bill |
| Automated backups | **off** | nothing here that Flyway cannot recreate |
| Point-in-time recovery | **off** | continuously archives write-ahead logs to billed storage |
| Public IP | leave enabled | how Cloud Shell reaches it if you ever need `psql` |
| Prevent instance deletion | **off** | otherwise teardown needs an extra unlock step |
| Retain backups after instance deletion | **off** | would leave billed backup storage behind after teardown |
| Final backup on instance deletion | **off** | would create a backup kept 30 days, billed, on every teardown |

The last four live together under **Data Protection**, and the Sandbox preset does **not** turn them
off — on this project all four came out on and had to be unchecked afterwards (Edit → Data
Protection → Save; disabling PITR restarts the instance, which takes a minute). The bottom two are
the ones people miss, because the mental model is "delete the instance, the billing stops" — with
those on, it does not.

Confirm afterwards:

```bash
gcloud sql instances describe wildalert-db   --format="value(settings.tier,settings.availabilityType,settings.backupConfiguration.enabled,settings.backupConfiguration.pointInTimeRecoveryEnabled,settings.deletionProtectionEnabled)"
# db-f1-micro   ZONAL   False   False   False
```

**Before clicking Create, read the cost estimate panel.** That number is the real answer to "what
does this cost" — it bills per second for as long as the instance exists, whether or not anything
queries it. There is no scale-to-zero for Cloud SQL.

Creation takes **5–10 minutes**. It is not stuck.

*Cost: the one line item that matters. Delete the instance when you stop working for a while —
step 12.*

---

## Step 5 — Database

**Navigation:** SQL → `wildalert-db` → **Databases** → **Create Database**

| Field | Value |
|---|---|
| Database name | `wildalert` |
| Character set / collation | defaults |

*Cost: none (lives inside the instance).*

---

## Step 6 — Database user

**Navigation:** SQL → `wildalert-db` → **Users** → **Add User Account**

| Field | Value |
|---|---|
| Authentication | Built-in authentication (password) — not IAM |
| User name | `wildalert` — exactly this, no suffix |
| Postgres roles | leave **`cloudsqlsuperuser`** on |
| Password | click **Generate** — then **copy it**, you need it in step 7 |

Keep that password handy for the next step. This is the account the service uses; the `postgres`
user from step 4 stays for admin work only.

**Get the name exactly right.** Cloud Run passes `POSTGRES_USER=wildalert`, so a user called
anything else (it is easy to type the *instance* name here) fails authentication at startup. Cloud
SQL cannot rename a user — you create the right one and remove the wrong one.

**Why keep `cloudsqlsuperuser`.** PostgreSQL 15 removed the default `CREATE` grant on the `public`
schema, and Flyway creates `hunter` and `flyway_schema_history` there. Cloud SQL databases are owned
by the `cloudsqlsuperuser` role, so a user holding it can create tables; without it the first deploy
fails with `permission denied for schema public` and needs manual `GRANT`s from Cloud Shell. It is
not a real superuser (no filesystem access, no `COPY FROM PROGRAM`), but it does allow
`CREATEDB`/`CREATEROLE` — more than least privilege. Splitting it into a migration role that owns the
schema and an app role with only DML is the proper version, and a Phase 7 task.

Confirm afterwards:

```bash
gcloud sql users list --instance wildalert-db --format="value(name,type)"
# postgres    BUILT_IN
# wildalert   BUILT_IN
```

*Cost: none.*

---

## Step 7 — Store the password in Secret Manager

**Navigation:** Security → Secret Manager → **Create Secret**

| Field | Value |
|---|---|
| Name | `wildalert-db-password` |
| Secret value | paste the password from step 6 |
| Replication policy | Automatic |

Leave rotation, expiration, labels and CMEK empty. Use the **Secrets** tab, not **Regional secrets**
— Cloud Run's secret reference resolves a global secret by short name.

**Paste cleanly.** The payload is raw bytes, so a trailing newline or space becomes part of the
password, and it surfaces two steps later as a Postgres authentication error that says nothing about
whitespace. Check it:

```bash
gcloud secrets versions access latest --secret wildalert-db-password | od -c | tail -2
```

You do **not** need to grant anything on this secret: `wildalert-user-account` already holds
`roles/secretmanager.secretAccessor` project-wide, which covers it.

**Why not just type the password into Cloud Run as an environment variable:** env vars are visible
to anyone who can read the service config, and they persist in revision history. A secret is
fetched at container start and never appears in the service definition. It also makes rotation one
new secret version instead of a redeploy.

*Cost: fractions of a cent.*

---

## Step 8 — Build the image (a Cloud Build trigger)

There is no "build my Dockerfile" button on a Cloud Run or Artifact Registry page, but the build is
still clickable: a **Cloud Build trigger** builds from GitHub using
[`cloudbuild.yaml`](./cloudbuild.yaml), and it is the CI/CD answer anyway — it closes the "deploys
happen from someone's laptop" gap. Push to `main`, get an image.

**Navigation:** ☰ → CI/CD → Cloud Build → **Triggers**, region **`europe-west3`** (triggers are
regional; keep it with the registry) → **+ Create trigger**

| Field | Value |
|---|---|
| Name | `user-account-main` |
| Event | **Push to a branch** |
| Source → Repository | **Connect new repository** → GitHub → authorise → install the Cloud Build app on `zvolda/wildalert` → Connect |
| Branch | `^main$` |
| Configuration → Type | **Cloud Build configuration file (yaml or json)** |
| Location | Repository |
| Configuration file location | `deploy/cloudbuild.yaml` |
| Service account | `789527844011-compute@developer.gserviceaccount.com` |

Substitution variables (**Advanced** → **+ Add variable**) — both are required, `cloudbuild.yaml`
reads them:

| Variable | Value |
|---|---|
| `_SERVICE` | `user-account` |
| `_IMAGE_BASE` | `europe-west3-docker.pkg.dev/wildalert-prod/wildalert/user-account` — **no tag** |

The tag is added inside `cloudbuild.yaml` as `$SHORT_SHA`, the commit being built, so every image is
traceable to its code without you typing a tag. It has to happen there: Cloud Build does **not**
re-scan the value of a user substitution for built-ins, so a substitution field containing
`...:$SHORT_SHA` fails with `could not parse reference`.

**A third substitution, `_CONTEXT`, and only recognition needs it.** The Kotlin services build from
the repo root because their Dockerfiles run `./gradlew` across the whole multi-module project.
`services/recognition/Dockerfile` instead does `COPY requirements.txt .` and `COPY app ./app`,
which only resolve when the context *is* the service directory — and the root `.dockerignore`
excludes `services/recognition/`, so in a root context those files are not even sent. The build
config defaults `_CONTEXT` to `.`, so the existing triggers need no change; recognition overrides
it. The four triggers are otherwise identical — same file, same service account, same `^main$`:

| Trigger | `_SERVICE` | `_IMAGE_BASE` (no tag) | `_CONTEXT` |
|---|---|---|---|
| `user-account-main` | `user-account` | `…/wildalert/user-account` | *omit* |
| `email-ingestion-main` | `email-ingestion` | `…/wildalert/email-ingestion` | *omit* |
| `notification-main` | `notification` | `…/wildalert/notification` | *omit* |
| `recognition-main` | `recognition` | `…/wildalert/recognition` | `services/recognition` |

Expect the recognition build to take **far longer than the others and to produce a much larger
image**: it installs CPU PyTorch and PytorchWildlife, then runs the model constructors so the
MegaDetector and DeepFaune weights are baked in rather than downloaded at cold start. The 30-minute
timeout in `cloudbuild.yaml` exists for this build, not the Gradle ones.

**Build without waiting for a push:** Triggers list → **⋮** → **Run trigger** → branch `main` →
**Run**. Watch it under **Cloud Build → History**; the log streams there (`cloudbuild.yaml` sets
`logging: CLOUD_LOGGING_ONLY`). A few minutes — the root `.dockerignore` excludes `.gradle/`, so
Gradle downloads its dependencies fresh every build, which is why the config asks for
`E2_HIGHCPU_8` and a 30-minute timeout.

**Connecting the repository is a real authorisation:** it installs Google's Cloud Build GitHub App
with read access to that repo plus a webhook, which is what makes push-triggered builds work. If you
would rather not build on every push, set the event to **Manual invocation** instead — `$SHORT_SHA`
still resolves.

Confirm the image landed: Artifact Registry → `wildalert` → `user-account`, and note the tag — step
9 needs that exact string.

```bash
gcloud artifacts docker images list   europe-west3-docker.pkg.dev/wildalert-prod/wildalert/user-account   --format="table(package,version,tags,createTime)"
```

**Avoid Cloud Run's "Continuously deploy from a repository" option** for this service, tempting as it
looks in the deploy form: it generates its own build config, ignoring this `cloudbuild.yaml` — so the
cold Gradle build loses the bigger machine and the longer timeout — and it pushes the image into a
separate `cloud-run-source-deploy` repository instead of `wildalert`.

*Cost: build minutes (there is a daily free allowance) plus a little storage for the image.*

---

## Step 9 — Deploy to Cloud Run

**Navigation:** Cloud Run → **Deploy container** → **Service**

### Top of the form

| Field | Value |
|---|---|
| Container image URL | **Select** → Artifact Registry → `user-account` → the tag you built |
| Service name | `user-account` |
| Region | `europe-west3` |
| Authentication | **Require authentication** ← *not* "Allow unauthenticated" |
| Billing | Request-based |
| Auto-scaling | Min instances **0**, Max instances **3** |
| Ingress | All (authentication is what protects it, not ingress) |

**Require authentication matters.** This service returns hunters' phone numbers. Choosing "Allow
unauthenticated" publishes that to the internet.

### Container → Settings

| Field | Value | Note |
|---|---|---|
| Container port | leave **8080** | Cloud Run injects `PORT`, and `application.yml` reads `${PORT:8081}` |
| Memory | **1 GiB** | |
| CPU | **1** | |

### Container → Variables & Secrets

Environment variables — add three:

| Name | Value |
|---|---|
| `DATABASE_URL` | `jdbc:postgresql:///wildalert?cloudSqlInstance=wildalert-prod:europe-west3:wildalert-db&socketFactory=com.google.cloud.sql.postgres.SocketFactory` |
| `POSTGRES_USER` | `wildalert` |
| `INBOUND_EMAIL_DOMAIN` | `wildalert.local` until the domain exists, then `wildalert.app` (the **apex** — see `deploy/email-worker/`) |

Secrets — **Reference a secret**:

| Field | Value |
|---|---|
| Secret | `wildalert-db-password` |
| Reference method | **Exposed as environment variable** |
| Name | `POSTGRES_PASSWORD` |
| Version | `latest` |

Copy that `DATABASE_URL` exactly, including the `&`. It tells the Postgres driver to connect
through the Cloud SQL socket factory — authenticated by the service account, so no password
crosses the network and there is no IP allowlist to maintain.

### Container → Health checks

Add a **Startup probe**:

| Field | Value |
|---|---|
| Type | HTTP |
| Path | `/actuator/health/readiness` |
| Port | same as the container port |
| Initial delay | `10` seconds |
| Timeout | `5` seconds |
| Period | `10` seconds |
| Failure threshold | `12` |

This is what makes the deploy honest: readiness includes the database check, so a revision only
receives traffic once Flyway has migrated **and** Cloud SQL is reachable. 12 × 10s gives startup
about two minutes.

### Container → Connections (or Cloud SQL connections)

Add Cloud SQL connection: **`wildalert-prod:europe-west3:wildalert-db`**

### Security

| Field | Value |
|---|---|
| Service account | `wildalert-user-account@wildalert-prod.iam.gserviceaccount.com` |

Then **Create**. The first deploy takes a couple of minutes — the JVM starts and Flyway runs both
migrations.

---

## Step 10 — Verify

The service is private, so opening its URL in a browser gives **403**. That is correct.

Easiest check is Cloud Shell:

```bash
URL=$(gcloud run services describe user-account --region europe-west3 --format='value(status.url)')
TOKEN=$(gcloud auth print-identity-token)

curl -H "Authorization: Bearer $TOKEN" "$URL/actuator/health"
# {"status":"UP","groups":["liveness","readiness"]}

curl -X POST "$URL/api/hunters" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","phone":"+420123456789"}'
```

A healthy response proves six things at once: the image built, Cloud Run started it, Flyway ran
both migrations, the socket factory authenticated, the secret was injected, and the IAM roles are
right.

If the revision never goes green: Cloud Run → `user-account` → **Logs**. The startup probe failing
is a symptom — the cause is in the container's own log. `deploy/README.md` has the known
Flyway-on-`public`-schema gotcha and its fix.

---

## Step 10a — Image storage: an R2 bucket (Cloudflare, not Google)

email-ingestion writes the photo somewhere recognition can read it later by storage key. On Cloud
Run the container filesystem is per-instance and vanishes, so `filesystem` is not an option in the
cloud: it has to be object storage, and the choice made in Phase 3 is **Cloudflare R2** (10 GB free,
no egress fees, S3 API — so `R2ImageStore` uses Amazon's S3 SDK pointed at a different endpoint).

**Navigation:** Cloudflare dashboard → **R2 Object Storage** → **Create bucket**

| Field | Value |
|---|---|
| Bucket name | `wildalert-images` |
| Location | **EU** (keeps photos near europe-west3; R2 has no per-region pricing) |
| Default storage class | Standard |

R2 asks for a payment method even on the free tier. Then **R2 → API → Manage API tokens → Create
API token**:

| Field | Value |
|---|---|
| Token name | `wildalert-email-ingestion` |
| Permissions | **Object Read & Write** |
| Specify bucket | `wildalert-images` only |
| TTL | Forever |

The result page shows, once only:

- **Access Key ID** and **Secret Access Key** → these become the two Secret Manager secrets below
- the **S3 endpoint** `https://<account-id>.r2.cloudflarestorage.com` → this is `R2_ENDPOINT`

Ignore the "Token value" line — that one is for Cloudflare's own API, not the S3 one. What
`R2ImageStore` needs is the *Access Key ID / Secret Access Key* pair.

> Skipping R2 for now is legitimate: deploy with `STORAGE_PROVIDER=logging` and the mail path still
> proves itself end to end (the event is published with a storage key that nothing can fetch yet).
> Recognition is what actually needs the bytes, and it is not deployed yet.

*Cost: free below 10 GB stored and 1M writes/month. Phase 7 adds a lifecycle rule that deletes
photos after processing.*

---

## Step 10b — Secrets for email-ingestion

**Navigation:** Security → **Secret Manager** → **Create secret** (three times, or once if you
skipped R2)

| Secret name | Value |
|---|---|
| `wildalert-webhook-secret` | 32+ random characters — the only thing between the public webhook and the internet. `openssl rand -base64 32` in Cloud Shell, then paste |
| `wildalert-r2-access-key` | R2 **Access Key ID** from step 10a |
| `wildalert-r2-secret-key` | R2 **Secret Access Key** from step 10a |

All three: automatic replication, Google-managed encryption, no expiry. **Paste with no trailing
newline** — the console stores exactly what you paste, and a stray newline in the webhook secret
means every email gets a 401, because the filter compares the raw strings. Check before trusting it:

```bash
gcloud secrets versions access latest --secret=wildalert-webhook-secret | xxd | tail -2
# the final byte must not be 0a
```

Then grant the runtime account read access — **per secret**, not project-wide, so this service
cannot also read the database password:

**Navigation:** Secret Manager → tick the three secrets → **Permissions** panel → **Add principal**

| Field | Value |
|---|---|
| Principal | `wildalert-email-ingestion@wildalert-prod.iam.gserviceaccount.com` |
| Role | **Secret Manager Secret Accessor** |

---

## Step 10c — Build the email-ingestion image

A second trigger, identical to step 8 apart from the two substitutions. `cloudbuild.yaml` builds any
service, which is the whole reason it takes `_SERVICE`.

**Navigation:** Cloud Build → **Triggers** (region `europe-west3`) → **+ Create trigger**

| Field | Value |
|---|---|
| Name | `email-ingestion-main` |
| Event | Push to a branch |
| Source → Repository | the `zvolda/wildalert` connection from step 8 — already authorised, just pick it |
| Branch | `^main$` |
| Configuration | Cloud Build configuration file → `deploy/cloudbuild.yaml` |
| Service account | `789527844011-compute@developer.gserviceaccount.com` |
| `_SERVICE` | `email-ingestion` |
| `_IMAGE_BASE` | `europe-west3-docker.pkg.dev/wildalert-prod/wildalert/email-ingestion` — **no tag** |

**The code has to be on `main` first.** The trigger builds `^main$`, and `UserAccountAuth` (the
ID-token change) is what lets this service talk to the private user-account at all. Merge, then
**⋮ → Run trigger** rather than waiting for a push.

```bash
gcloud artifacts docker images list \
  europe-west3-docker.pkg.dev/wildalert-prod/wildalert/email-ingestion \
  --format="table(version,tags,createTime)"
```

Note the tag — step 10d needs it.

---

## Step 10d — Deploy email-ingestion to Cloud Run

**Navigation:** Cloud Run → **Deploy container** → **Service**

### Top of the form

| Field | Value |
|---|---|
| Container image URL | Artifact Registry → `email-ingestion` → the tag from step 10c |
| Service name | `email-ingestion` |
| Region | `europe-west3` |
| Authentication | **Allow unauthenticated invocations** ← the opposite of user-account, deliberately |
| Billing | Request-based |
| Auto-scaling | Min **0**, Max **3** |
| Ingress | All |

**Why this one is public.** Cloudflare Workers call in from outside Google Cloud, so IAM
authentication is not available to them — there is no Google identity to grant `run.invoker` to. The
protection is `WebhookAuthFilter`: 401 on `/api/emails` unless `X-Webhook-Secret` matches, which is
exactly why that filter was built in Phase 6 slice 1. Health probes stay open by design.

### What "public" exposes, and what it does not

"Allow unauthenticated" removes a check that happens **in front of the container**: with *Require
authentication* Google validates the caller's token and answers 403 itself, so `user-account`'s JVM
never sees an anonymous request at all. Here that door is open, and the only thing between a
stranger and the code is code we wrote. Giving the worker a Google identity instead would mean a
downloaded service-account key pasted into Cloudflare — a permanent credential on someone else's
platform in place of a shared secret we can rotate. Strictly worse.

What an anonymous request actually reaches:

| Request | Result |
|---|---|
| `POST /api/emails`, no or wrong header | **401** from `WebhookAuthFilter`, before the MIME parser, R2, user-account and the model |
| `GET /actuator/health` (+ `/liveness`, `/readiness`) | **200 `{"status":"UP"}`** — open on purpose so Cloud Run can probe it; says nothing else |
| anything else | **404** — `exposure.include: health` means `env`, `metrics` and `heapdump` do not exist |

The comparison is `MessageDigest.isEqual` on raw bytes, so the secret cannot be discovered one
character at a time by timing the replies. **Treat the URL itself as not secret** — it lives in the
worker's config and in logs, and `run.app` gets scanned. The secret is the control; obscurity is not.

**If the secret leaked**, the damage is bounded: photos pushed through R2 and the model at our
expense, and a fake alert to a hunter whose inbound address the caller already knew. Not phone
numbers — the webhook's response carries the hunter's UUID and matched address only, and this
service holds no database access at all.

**Max instances is therefore a spend cap, not a performance setting.** A flood of rejected 25 MB
bodies still costs requests and bandwidth; 3 is the ceiling on how much. Real protection — rate
limiting plus an allowlist of Cloudflare's published egress ranges — needs an external HTTPS load
balancer and Cloud Armor, roughly $18–20/month, more than the rest of this project combined. A
Phase 7 decision, deliberately not now.

### Container → Settings

| Field | Value | Note |
|---|---|---|
| Container port | **8080** | Cloud Run injects `PORT`; `application.yml` reads `${PORT:8083}` |
| Memory | **1 GiB** | a 25 MB email is parsed in memory, and the JVM wants headroom |
| CPU | **1** | |
| Request timeout | **300s** (the default) | |

### Container → Variables & Secrets

| Name | Value |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `pubsub` |
| `INBOUND_EMAIL_DOMAIN` | `wildalert.app` — **required**: it is how this service picks our own address out of an email's recipients. Wrong or unset and every photo is published with no recipient, so nobody is ever texted. |
| `STORAGE_PROVIDER` | `r2` (or `logging` if you skipped step 10a) |
| `R2_ENDPOINT` | `https://<account-id>.r2.cloudflarestorage.com` |
| `R2_BUCKET` | `wildalert-images` |

Secrets — **Reference a secret**, each exposed as an environment variable:

| Secret | Env var name | Version |
|---|---|---|
| `wildalert-webhook-secret` | `WEBHOOK_SECRET` | `latest` |
| `wildalert-r2-access-key` | `R2_ACCESS_KEY` | `latest` |
| `wildalert-r2-secret-key` | `R2_SECRET_KEY` | `latest` |

Three variables **not** to set here, each of which breaks something quietly:

- **`SPRING_CLOUD_GCP_PUBSUB_EMULATOR_HOST`** — local only. Setting it activates
  `PubSubCredentialsConfig`'s `NoCredentialsProvider`, and the service publishes into nothing.
- **`SPRING_CLOUD_GCP_PROJECT_ID`** — resolves from the metadata server in the cloud. An
  empty-but-present value is worse than an absent one.
- **`EVENTS_PROVIDER`** — the `pubsub` profile sets it *together with* the GCP auto-configuration it
  needs. Setting the provider on its own gives you a publisher with no client.

**This service sets no `USER_ACCOUNT_*` variables at all.** It made its last call to user-account
before the recipient moved onto the event; the lookup now happens in notification, which is where
those variables (and the `run.invoker` grant) belong. If a revision here still carries them, they
are leftovers and can be cleared.

### Container → Health checks

Startup probe, same shape as step 9: HTTP `/actuator/health/readiness`, initial delay `10`, timeout
`5`, period `10`, failure threshold `12`. This service has no database, so readiness only means
"Spring started" — still worth having, as it keeps traffic off a JVM that is still warming up.

### Security

| Field | Value |
|---|---|
| Service account | `wildalert-email-ingestion@wildalert-prod.iam.gserviceaccount.com` |

### The two grants this service needs

Without them the deploy succeeds and then fails on every email — worth doing before the first test
rather than after.

| Grant | Where in the console |
|---|---|
| `roles/pubsub.publisher` on topic `image.received` | Pub/Sub → Topics → `image.received` → **Permissions** → Add principal → the `wildalert-email-ingestion` account |
| `roles/run.invoker` on service `user-account` | Cloud Run → services list → tick `user-account` → **Permissions** → Add principal → the same account → **Cloud Run Invoker** |

A missing `run.invoker` reads like this in the logs: `Could not reach user-account to match … after
2 attempts; treating it as no match`. The photo is still stored and the event still published — only
that SMS is lost. The degradation is deliberate (see `HunterLookupClient`), but it does mean **a 403
is invisible in the HTTP response** (and since the recipient change this grant is no longer needed
here at all — email-ingestion makes no calls to user-account). Read the
response body, not the status code.

---

## Step 10e — Verify email-ingestion before Cloudflare is involved

From Cloud Shell. The point is to prove the webhook, the secret, the hunter match, the R2 write and
the Pub/Sub publish with `curl` as the only moving part — so that anything that fails afterwards
belongs to Cloudflare's half, not ours.

```bash
URL=$(gcloud run services describe email-ingestion --region europe-west3 --format='value(status.url)')
SECRET=$(gcloud secrets versions access latest --secret=wildalert-webhook-secret)

# 1. The door is locked.
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$URL/api/emails" --data-binary 'x'
# 401. A 200 means WEBHOOK_SECRET never reached the container and the endpoint is open.

# 2. Health, and nothing else.
curl -s "$URL/actuator/health"                                 # {"status":"UP",...}
curl -s -o /dev/null -w '%{http_code}\n' "$URL/actuator/env"   # 404

# 3. Which address belongs to a hunter?
TOKEN=$(gcloud auth print-identity-token)
UA=$(gcloud run services describe user-account --region europe-west3 --format='value(status.url)')
curl -s -H "Authorization: Bearer $TOKEN" "$UA/api/hunters/by-email?email=you@example.com"
# note "inboundAddress" -- <token>@<whatever INBOUND_EMAIL_DOMAIN currently says>
```

Then post a minimal email with a JPEG attachment, exactly as the worker will:

```bash
ADDR='<token>@wildalert.app'
IMG=$(printf '\377\330\377\333\000\001\377\331' | base64 -w0)
{
  printf 'From: camera@example.com\r\n'
  printf 'To: %s\r\n' "$ADDR"
  printf 'Subject: test\r\nMIME-Version: 1.0\r\n'
  printf 'Content-Type: multipart/mixed; boundary=b\r\n\r\n'
  printf -- '--b\r\nContent-Type: image/jpeg\r\n'
  printf 'Content-Transfer-Encoding: base64\r\n\r\n%s\r\n' "$IMG"
  printf -- '--b--\r\n'
} > mail.eml

curl -s -X POST "$URL/api/emails?envelopeTo=$ADDR" \
  -H "X-Webhook-Secret: $SECRET" -H 'Content-Type: message/rfc822' \
  --data-binary @mail.eml
# {"matchedRecipient":"<token>@...","matchedHunterId":"<uuid>","imageCount":1,...}
```

**As of the recipient change this response no longer carries `matchedHunterId`** — this service
does not resolve hunters any more, so `matchedRecipient` is the thing to check: it proves the
inbound domain is configured and the address was recognised as ours. Whether that address belongs
to a hunter is now proven further down the pipeline, by notification actually sending the SMS. Then confirm the two side effects:

```bash
# The event reached Pub/Sub. NOTE: image.received.inspect was deleted on 2026-10-22, once
# recognition consumed the topic for real -- a pull subscription on a live topic is a second
# consumer that quietly retains every event. Recreate it if you need this check again:
#   gcloud pubsub subscriptions create image.received.inspect --topic=image.received
# Otherwise read recognition's own log, which prints the event it received:
gcloud run services logs read recognition --region europe-west3 --limit 20
# {"storageKey":"...","contentType":"image/jpeg","senderEmail":"camera@example.com","hunterId":"..."}

# The bytes reached R2: Cloudflare dashboard → R2 → wildalert-images → the object with that key.
```

**This paragraph describes the pre-slice-13 ingestion path** and is kept for the deploy history:
email-ingestion no longer looks a hunter up at all, so it has no `matchedHunterId`, no ID token and
no `run.invoker` on user-account. The equivalent failure now shows as `matchedRecipient: null` in
the webhook's response, and there is only one cause left: `INBOUND_EMAIL_DOMAIN` on email-ingestion
is not the domain user-account mints on. Set the same value on both services — unset, email-ingestion
falls back to `wildalert.local`, which is right for local dev and wrong in the cloud.

_Historical:_ with the old path, `403 Forbidden` on the lookup meant `run.invoker` was missing,
`Could not mint an ID token` meant the service account or `USER_ACCOUNT_AUTH` was wrong, and a plain
404 meant the address was not a hunter's.

---

## Step 10f — Deploy notification to Cloud Run

**Two code changes made this deployable (2026-10-22), and both were invisible from the form.**

1. **`HunterClient` sent no credential.** user-account runs with *Require authentication*, so Cloud
   Run answers **403** in front of the app. `HttpClientErrorException.Forbidden` is not the
   `NotFound` that `HunterClient` catches, so it propagated, the push returned 5xx, Pub/Sub retried
   five times and the alert landed in `animal.recognized.dlt` — **never an SMS, with nothing
   obviously wrong with the service.** The same bug email-ingestion had before Phase 6 slice 5.
   `UserAccountAuth` + `GcpIdTokenUserAccountAuth` are now in
   `com.wildalert.notification.hunter`, applied via `.headers { auth?.authorize(it) }`, switched on
   by `services.user-account.auth=gcp-id-token`. Note the contract is the *opposite* of
   email-ingestion's: there an unmintable token degrades to "no hunter" so the photo is never lost;
   here the photo is already stored, so it throws and Pub/Sub redelivers.
2. **No Cloud SQL socket factory.** `IDEMPOTENCY_STORE=postgres` needs
   `com.google.cloud.sql:postgres-socket-factory`, which user-account had and notification did not,
   so the `socketFactory` JDBC URL could not connect. Added at the same version (1.28.4).

`google-auth-library-oauth2-http` is declared explicitly here: email-ingestion gets it transitively
from the Pub/Sub starter, but this service has no other GCP dependency — a push subscription is
just an HTTP POST.

**Navigation:** Cloud Run → **Deploy container** → **Service**

| Field | Value |
|---|---|
| Container image | `…/wildalert/notification:0340fa0` |
| Service name | `notification`, region `europe-west3` |
| Authentication | **Require authentication** — Pub/Sub pushes with an OIDC token |
| Ingress | **All** at first; tighten once it works |
| Auto-scaling | min **0**, max **3** |
| CPU / Memory | **1** / **1 GiB** |
| Container port | **8080** |
| Request timeout | **60s**, matching the subscription's ack deadline |
| Service account | `wildalert-notification@…` |
| Startup probe | `/actuator/health/readiness` |

| Env | Value |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `pubsub` — turns on the push endpoint; the profile is deliberately thin because a push subscription is just an authenticated POST |
| `USER_ACCOUNT_BASE_URL` | `https://user-account-lznkorfm4q-ey.a.run.app` (no trailing slash; it is also the token audience) |
| `USER_ACCOUNT_AUTH` | `gcp-id-token` |
| `SMS_PROVIDER` | `logging` (fake, logs what it would send), `gosms` or `twilio` |
| `IDEMPOTENCY_STORE` | **`postgres`** — the shared store, so a redelivery landing on a different instance cannot send a second SMS. `memory` works but is per-instance |

`postgres` also needs `DATABASE_URL` (the same `socketFactory` URL user-account uses),
`POSTGRES_USER=wildalert`, the `wildalert-db-password` secret as `POSTGRES_PASSWORD`, a **Cloud SQL
connection attached**, and for `wildalert-notification`: `roles/cloudsql.client` plus a **per-secret**
accessor binding on `wildalert-db-password`. Flyway creates its own `notification` schema with its
own history, so it will not collide with user-account's migrations.

**Grants:** `roles/run.invoker` on **user-account** for `wildalert-notification` (the lookup), and
`roles/run.invoker` on **notification** for `wildalert-pubsub-push` (the push).

### Sending real SMS: GoSMS, not Twilio

Twilio wants **$12/month to lease a number**, and its free alphanumeric sender needs ~3 weeks of
pre-registration in Czechia and a paid account. [GoSMS](https://gosms.eu) is Czech, has **no monthly
fee at all**, costs from ~0.26 Kč per message, registers individuals (several Czech gateways
require an IČO), and credits 20 Kč for verifying your phone — enough to test the whole pipeline for
nothing. `GoSmsSender` is the implementation; `TwilioSmsSender` stays in the codebase as a fallback.

From the GoSMS admin panel take the **client id**, **client secret** and the numeric **channel id**
(the channel decides the sender shown on the phone and the per-message price). Then:

| Env | Value |
|---|---|
| `SMS_PROVIDER` | `gosms` |
| `GOSMS_CHANNEL` | the numeric channel id |

Secrets — **Reference a secret**, as env vars, with a **per-secret** accessor binding for
`wildalert-notification`:

| Secret | Env var |
|---|---|
| `wildalert-gosms-client-id` | `GOSMS_CLIENT_ID` |
| `wildalert-gosms-client-secret` | `GOSMS_CLIENT_SECRET` |

**Check the credentials before deploying.** GoSMS has a `/api/v1/messages/test` endpoint that
validates a message — credentials, channel and recipient format — **without sending it or spending
credit**:

```bash
TOKEN=$(curl -s -X POST https://app.gosms.eu/oauth/v2/token   -d client_id="$(gcloud secrets versions access latest --secret=wildalert-gosms-client-id)"   -d client_secret="$(gcloud secrets versions access latest --secret=wildalert-gosms-client-secret)"   -d grant_type=client_credentials | python -c "import json,sys; print(json.load(sys.stdin)['access_token'])")

curl -s -o /dev/null -w '%{http_code}
' -X POST https://app.gosms.eu/api/v1/messages/test   -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json'   -d '{"message":"WildAlert test","recipients":["+420XXXXXXXXX"],"channel":N}'
# 200 = everything is right. 401 = credentials; 4xx with a body = channel or number format.
```

A hunter's `phone` must be the real number in E.164 — the 10e test hunter carries a deliberately
fake `+420100000000`, which the gateway will reject.

---

## Step 10g — Deploy recognition to Cloud Run

The expensive one, and the only service where "scales to zero" and "responds promptly" genuinely
conflict. **The model is loaded at startup, not on first request:** `app/main.py`'s `lifespan` hook
calls `get_classifier()` before the app accepts traffic, deliberately, so that a pushed message
never waits on the weights and risks its acknowledgement deadline. Its docstring puts the load at
about a minute. Uvicorn therefore does not listen until the model is in memory — which makes the
**startup probe** the real gate, and means a scale-from-zero costs a multi-GB image pull plus that
load before anything is served.

**Navigation:** Cloud Run → **Deploy container** → **Service**

| Field | Value | Why |
|---|---|---|
| Container image | `…/wildalert/recognition:0340fa0` | |
| Service name | `recognition`, region `europe-west3` | |
| Authentication | **Require authentication** | Pub/Sub pushes with an OIDC token |
| Ingress | **All** at first | same-project push *should* satisfy internal ingress; don't debug ingress and IAM at once |
| Memory | **8 GiB** | measured, not guessed: at 4 GiB the first revision died mid-load with `Memory limit of 4096 MiB exceeded with 4236 MiB used`. Requires ≥2 vCPU |
| CPU | **2** | |
| Concurrency | **1** | inference is CPU-bound — parallel requests on one instance only make each slower |
| Request timeout | **600s** | must exceed cold start + model load + inference, and match the ack deadline |
| Auto-scaling | min **0**, max **3** | min 1 on a 4 GiB instance is real monthly money; max 3 caps spend |
| Startup CPU boost | **on** | shortens the load |
| Startup probe | leave the **default TCP check** | the port opens only after `lifespan` has loaded the model, so TCP already means "ready"; the default allows 240s, comfortably over the ~1 minute load. An HTTP probe adds nothing and its path gets mangled from Git Bash (see `README.md` troubleshooting) |
| Service account | `wildalert-recognition@…` | already holds `pubsub.publisher` on `animal.recognized` |

| Env | Value |
|---|---|
| `RECOGNITION_IMAGE_SOURCE` | `r2` |
| `R2_ENDPOINT` | `https://5cc5a66ab391cec2270baa611d2a0a6d.r2.cloudflarestorage.com` |
| `R2_BUCKET` | `wildalert-images` |
| `RECOGNITION_EVENTS_PROVIDER` | `pubsub` |
| `PUBSUB_PROJECT_ID` | `wildalert-prod` |

Secrets — **Reference a secret**, as env vars: `wildalert-r2-access-key` → `R2_ACCESS_KEY`,
`wildalert-r2-secret-key` → `R2_SECRET_KEY` (both already carry a per-secret accessor binding for
`wildalert-recognition`).

**Do not set `RECOGNITION_CLASSIFIER`** — the image's own `ENV RECOGNITION_CLASSIFIER=deepfaune`
already selects the real model, and overriding it with the default `stub` would produce a service
that answers happily with invented species.

**`PUBSUB_PROJECT_ID` is not optional.** `config.py` falls back to `GOOGLE_CLOUD_PROJECT`, with a
comment saying Cloud Run sets it. Cloud Run's documented reserved variables are `PORT`, `K_SERVICE`,
`K_REVISION` and `K_CONFIGURATION` — the Kotlin services get the project from the *metadata server*
instead, not from an env var. Setting it explicitly makes the question moot; the first revision's
logs will settle whether the fallback would ever have worked.

**Grant:** `roles/run.invoker` on **recognition** for `wildalert-pubsub-push`.

---

## Step 10h — Deploy gateway to Cloud Run (the UI's front door)

The second public service, and the only one a browser talks to. It serves the built React app out
of its own jar and forwards exactly one call — `POST /api/hunters` — to the private user-account
with a Google ID token.

> **Open registration, and why this is now safe to deploy.** The register endpoint is
> unauthenticated and unthrottled, and this deploy is what makes it publicly reachable. Anyone can
> create hunters at will, and `max-instances` is a spend cap, not a rate limit.
>
> **What makes that affordable is that the signup grant now defaults to 0** (decided 2026-10-29).
> A hunter with no credit cannot cost us an SMS or a DeepFaune run, so a scripted signup buys a
> Cloud SQL row and nothing else. **Set no `SIGNUP_GRANT_MINOR` on this deploy** — the default is
> the safe value, and leaving it unset is the intended configuration.
>
> The arithmetic behind the decision: at the old 2500-haléř grant a fake signup was worth 25 CZK,
> and roughly **sixteen** abusive accounts could drain a 200 CZK GoSMS balance. The money was
> never the main risk — once GoSMS is empty, *paying* hunters get no alerts, which for a product
> whose only output is an SMS is a silent outage.
>
> Turn the grant on later, with `SIGNUP_GRANT_MINOR`, once Cloudflare Turnstile is on the form or
> a Cloudflare rate-limit rule is in front of it. Until then you can still register yourself and
> top up normally.

**First: the service account.** It does not exist yet — the ones that do are user-account,
email-ingestion, recognition, notification and pubsub-push. Create `wildalert-gateway` *before*
deploying. Without it the service inherits the default compute service account, which still holds
`roles/editor` — so its user-account calls would succeed **by accident** and the ID-token path
would never actually be exercised. Exactly the trap notification hit.

**Build the image** (context is the repo root — the Dockerfile runs both npm and Gradle):

```
gcloud builds submit --config deploy/cloudbuild.yaml --substitutions=\
_SERVICE=gateway,_IMAGE_BASE=europe-west3-docker.pkg.dev/wildalert-prod/wildalert/gateway,\
SHORT_SHA=$(git rev-parse --short HEAD) .
```

This is the slowest image here: it installs npm packages and builds the React app, *then* runs
Gradle. A type error or a failing Vitest test fails the image build rather than shipping — `npm run
build` runs `tsc --noEmit` first, deliberately.

**Navigation:** Cloud Run → **Deploy container** → **Service**

| Field | Value | Why |
|---|---|---|
| Container image | `…/wildalert/gateway:<sha>` | |
| Service name | `gateway`, region `europe-west3` | |
| Authentication | **Allow unauthenticated** | a browser has no Google token; this is the public edge. The *second* service set this way, and the last |
| Ingress | **All** | |
| Memory | **512 MiB** | serves static files and proxies one call; no model, no DB pool |
| CPU | **1** | |
| Concurrency | default **80** | it is I/O-bound |
| Request timeout | **30s** | a registration that takes longer has failed |
| Auto-scaling | min **0**, max **2** | caps spend. Startup is fast: **1 second** to health-200 in a container on a Cloud Build worker, measured — it loads no model and opens no database pool. Cloud Run adds image-pull time on top |
| Startup probe | HTTP `/actuator/health/liveness` | |
| Readiness probe | HTTP `/actuator/health/readiness` | no database, so readiness is just "started" |
| Service account | `wildalert-gateway@…` | created above |

| Env | Value | Why |
|---|---|---|
| `USER_ACCOUNT_BASE_URL` | `https://user-account-lznkorfm4q-ey.a.run.app` | also the ID token's **audience**, so no trailing path |
| `USER_ACCOUNT_AUTH` | `gcp-id-token` | without it the gateway sends no credential and **every registration 403s** |

No secrets. The gateway holds none — it has no database, no SMS account and no R2 keys.

**The one grant it needs:** `roles/run.invoker` on **user-account** for `wildalert-gateway`
(Cloud Run → user-account → Permissions). Nothing else; notably *not* project-level anything.

**Verify, in this order:**
1. `curl -s -o /dev/null -w '%{http_code}' <gateway-url>/actuator/health` → **200**, with no token
   (it is public now).
2. Open the gateway URL in a browser → the registration form, not a 404. A 404 means the
   `web/dist` copy in the Dockerfile did not land; there is deliberately no placeholder
   `index.html` in the repo to mask that.
3. `curl <gateway-url>/api/hunters/by-email?email=x` → **404 from the gateway**, and *nothing* in
   user-account's logs. That is the allowlist working: the lookup endpoints return phone numbers
   and are not proxied.
4. Register a hunter through the form. Then check user-account's logs — the request should arrive
   there, which proves the ID token was accepted. A **403** in the gateway's logs means
   `USER_ACCOUNT_AUTH` is unset or the invoker grant is missing.

---

## Step 10i — Deploy detection-history to Cloud Run (the second consumer)

The simplest consumer deploy so far, and the reason is a design decision rather than luck: this
service **calls nothing**. Notification needs the hunter's phone number, so it needs
`USER_ACCOUNT_BASE_URL`, `USER_ACCOUNT_AUTH`, an ID-token minter and a `run.invoker` grant on
user-account — four things that can each be wrong. Detection-history is attributed by the
`recipient` already on the event, so none of that exists here. All it needs is Postgres.

It is the **second push subscription on `animal.recognized`**, next to notification's. Neither
knows the other: each gets its own copy of every event and acks independently, so this service can
be down, cold or redeploying without delaying an SMS.

**One code change was needed before any of this:** the service had no `Dockerfile`. The build stage
runs jOOQ codegen on the way to `bootJar` (`generateJooq` is a dependency of `compileKotlin`), which
needs no database — `DDLDatabase` replays the Flyway migrations into in-memory H2 — but does need
the network to fetch `jooq-codegen` and H2. Verified by building the image locally and running it
against a real Postgres before deploying.

**Navigation:** Cloud Run → **Deploy container** → **Service**

| Field | Value |
|---|---|
| Container image | `…/wildalert/detection-history:<short-sha>` |
| Service name | `detection-history`, region `europe-west3` |
| Authentication | **Require authentication** — Pub/Sub pushes with an OIDC token |
| Ingress | **Internal** — nothing but Pub/Sub push needs to reach it (see the warning below) |
| Auto-scaling | min **0**, max **3** |
| CPU / Memory | **1** / **1 GiB** |
| Container port | **8080** (Cloud Run injects `PORT`; `application.yml` falls back to 8086 locally) |
| Request timeout | **60s**, matching the subscription's ack deadline |
| Service account | `wildalert-detection-history@…` |
| Cloud SQL connection | `wildalert-prod:europe-west3:wildalert-db` |
| Startup probe | `/actuator/health/readiness` |

| Env | Value |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `pubsub` — turns on the push endpoint |
| `HISTORY_STORE` | **`postgres`**. Unlike notification's `IDEMPOTENCY_STORE`, this is not an optimisation: the `memory` default forgets every detection on restart, which for a history service is no service at all |
| `POSTGRES_USER` | `wildalert` |
| `DATABASE_URL` | `jdbc:postgresql:///wildalert?cloudSqlInstance=wildalert-prod:europe-west3:wildalert-db&socketFactory=com.google.cloud.sql.postgres.SocketFactory` |
| `POSTGRES_PASSWORD` | **Reference a secret** → `wildalert-db-password` |

**Grants** — two, and deliberately no more:

| Grant | Where |
|---|---|
| `roles/cloudsql.client` | the project, for `wildalert-detection-history` |
| `roles/secretmanager.secretAccessor` | **on `wildalert-db-password` only** — Secret Manager → that secret → Permissions. Not project-wide: that mistake is still open against `user-account` (Phase 7) |
| `roles/run.invoker` | Cloud Run → `detection-history` → Permissions, for `wildalert-pubsub-push` |

Flyway creates its own `detection_history` schema with its own history table, so it cannot collide
with user-account's `public` or notification's `notification`.

### The push subscription

**Navigation:** Pub/Sub → Subscriptions → **Create subscription**

| Field | Value |
|---|---|
| Subscription ID | `animal.recognized.history` |
| Topic | `animal.recognized` (the *same* topic notification already consumes) |
| Delivery type | **Push** → `<detection-history-url>/api/events/animal-recognized` |
| Enable authentication | **on**, service account `wildalert-pubsub-push@…`, audience blank |
| Acknowledgement deadline | **60 seconds** |
| Retry policy | exponential backoff, min **10s**, max **600s** |
| Dead lettering | on → `animal.recognized.dlt`, max **5** attempts |
| Message ordering / exactly once | **off** / off |

The dead-letter form offers the service agent's `pubsub.publisher` and `pubsub.subscriber` grants
with one click — take it, then confirm both landed. The agent already has publisher on
`animal.recognized.dlt` from notification's subscription, but **subscriber is per-subscription** and
this is a new one.

> **Adding this subscription changes notification's behaviour not at all** — that is the point of
> fan-out, and it is worth confirming rather than assuming: after creating it, send one real photo
> and check that the SMS still arrives *and* a row appears.

### ⚠️ Do not make this service public

The read API is keyed on the hunter's inbound address and there is **no authentication yet**, so
anyone holding an address could read that hunter's history — and it travels as a query parameter,
so it lands in access logs. Ingress **Internal** plus *Require authentication* is what keeps that
safe for now. When the dashboard needs it, it goes behind the **gateway** with an explicit route
and real user auth (Phase 9) — never a wildcard proxy, for the same reason the gateway does not
proxy user-account's lookup endpoints.

**Verify, in this order:**

```bash
URL=$(gcloud run services describe detection-history --region europe-west3 --format='value(status.url)')

# 1. The door is locked. 403 from Cloud Run; a 4xx from the app means auth is NOT required.
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$URL/api/events/animal-recognized" -d '{}'

# 2. Flyway ran: the schema and its history table exist.
gcloud sql connect wildalert-db --user=wildalert --database=wildalert \
  --quiet <<< "\dt detection_history.*"

# 3. End to end: forward a real photo, then read the history back as an authenticated caller.
curl -s -H "Authorization: Bearer $(gcloud auth print-identity-token)" \
  "$URL/api/detections?recipient=<token>@wildalert.app"
```

A `401` on step 3 means your account lacks `run.invoker` on this service — grant it to yourself
temporarily, or read the row straight from Cloud SQL instead. An empty `[]` with the SMS having
arrived means the subscription is wrong, not the service: check `animal.recognized.history` exists
and points at `/api/events/animal-recognized`.

---

## Step 11 — Pub/Sub (for the event-driven services, not user-account)

Not needed to run `user-account`, which is plain HTTP. Do this when email-ingestion, recognition and
notification are deployed — the push subscriptions need their URLs to exist first. Locally the same
four topics and four subscriptions are created for you by `compose.yml`, so this is the cloud
mirror of what the emulator already does.

**Navigation:** Pub/Sub → Topics → **Create topic**

Create four, all with default settings (no schema, Google-managed encryption):

`image.received` · `image.received.dlt` · `animal.recognized` · `animal.recognized.dlt`

Uncheck **Add a default subscription** — the subscriptions below are configured deliberately.

> Periods are legal in Pub/Sub names, so these match the topic names the services already use.

### Push subscriptions

**Navigation:** Pub/Sub → Subscriptions → **Create subscription** (twice)

| Field | `image.received.recognition` | `animal.recognized.notification` |
|---|---|---|
| Topic | `image.received` | `animal.recognized` |
| Delivery type | **Push** | **Push** |
| Endpoint URL | `<recognition-url>/events/image-received` | `<notification-url>/api/events/animal-recognized` |
| Enable authentication | **on** | **on** |
| Service account | `wildalert-pubsub-push@…` | `wildalert-pubsub-push@…` |
| Audience | leave blank | leave blank |
| Acknowledgement deadline | **600 seconds** | **60 seconds** |
| Retry policy | Retry after exponential backoff, min **10s**, max **600s** | same |
| Dead lettering | on → `image.received.dlt`, max **5** attempts | on → `animal.recognized.dlt`, max **5** attempts |
| Message ordering | **off** | **off** |
| Exactly once delivery | off | off |

Why 600s for recognition: that is the maximum, and for a push subscription the ack deadline doubles
as the HTTP request timeout. Classification is quick, but a cold instance has to exist first. Set
the recognition Cloud Run service's **request timeout to 600s** to match, and leave startup CPU
boost on — the model loads during startup, not during the request.

### The two subscriptions people forget

**Navigation:** Pub/Sub → Subscriptions → **Create subscription**, twice more

| Field | Value |
|---|---|
| Subscription ID | `image.received.dlt.inspect` / `animal.recognized.dlt.inspect` |
| Topic | `image.received.dlt` / `animal.recognized.dlt` |
| Delivery type | **Pull** |

Everything else default. **A Pub/Sub topic with no subscription discards every message published to
it** — without these, dead-lettering quietly throws failures away and the whole "nothing is lost"
design is a fiction. They are also how you read what failed:

```bash
gcloud pubsub subscriptions pull image.received.dlt.inspect --limit 10 --auto-ack
```

### Service accounts and grants

**IAM & Admin → Service Accounts → Create service account** — three, no keys for any of them:

| Account | Grants | Where to grant it |
|---|---|---|
| `wildalert-pubsub-push` | `roles/run.invoker` | Cloud Run → `recognition` → **Permissions**, and again on `notification`. Per service, not project-wide |
| `wildalert-email-ingestion` | `roles/pubsub.publisher` | Pub/Sub → Topics → `image.received` → **Permissions** (already done in step 10d) |
| `wildalert-recognition` | `roles/pubsub.publisher` | Pub/Sub → Topics → `animal.recognized` → **Permissions** |

Then the Google-managed **Pub/Sub service agent**,
`service-789527844011@gcp-sa-pubsub.iam.gserviceaccount.com`:

| Grant | Where |
|---|---|
| `roles/iam.serviceAccountTokenCreator` | IAM & Admin → Service Accounts → **`wildalert-pubsub-push`** → Permissions → Grant access. A grant *on the service account*, not on the project — this is what lets Pub/Sub mint the OIDC token it sends |
| `roles/pubsub.publisher` | each `.dlt` topic |
| `roles/pubsub.subscriber` | each of the two push subscriptions |

The dead-letter section of the subscription form usually offers a one-click grant for the last two
— use it, then confirm both appear. The service agent is **hidden in IAM unless you tick "Include
Google-provided role grants"**, and it may not exist at all until Pub/Sub is first used.

**Both consumers keep ingress `Internal` and Require authentication.** Pub/Sub push is one of the
few sources that can reach an internal-ingress Cloud Run service, so they never need public URLs,
and the apps deliberately do not verify the OIDC token themselves — Cloud Run does it, and only
this one account holds `run.invoker`.

Verify authentication is really on, rather than trusting the form:

```bash
curl -X POST "$(gcloud run services describe notification --region europe-west3 --format='value(status.url)')/api/events/animal-recognized" -d '{}'
# 403 from Cloud Run. A 400 from the app means authentication is NOT required.
```

*Cost: free at this volume (10 GiB/month).*

---

## Step 11b — Domain and inbound email (Cloudflare, not Google)

The last link: a hunter's address on a real domain reaching the webhook you just verified. The
click-by-click steps, the worker code and its verification live in
**[`email-worker/README.md`](./email-worker/README.md)** — this is the sequence and the decisions
behind it.

**Done already (2026-10-14):** `wildalert.app` registered at Cloudflare Registrar, Cloudflare
nameservers authoritative, Email Routing enabled with its MX + SPF records (confirmed from 8.8.8.8
and 1.1.1.1), a verified destination address, and a temporary catch-all → *Send to an email* that a
real test message arrived through. DNS, MX, SPF and the catch-all rule are therefore proven
independently of our code.

**What is left, in this order — step 10d has to come first, because the worker needs the ingestion
URL as a secret:**

1. `cd deploy/email-worker && npx wrangler deploy`
2. `npx wrangler secret put INGESTION_URL` → the Cloud Run URL from step 10d, no trailing slash
3. `npx wrangler secret put WEBHOOK_SECRET` → **the same value** as `wildalert-webhook-secret`
   (`gcloud secrets versions access latest --secret=wildalert-webhook-secret`)
4. Email Routing → **Catch-all address → Send to a Worker → `wildalert-email-in`** → Save. This
   replaces the temporary forward-to-Gmail rule.
5. Cloud Run → `user-account` → **Edit & deploy new revision** → set
   `INBOUND_EMAIL_DOMAIN=wildalert.app` → Deploy. Existing hunters need nothing: only the token is
   stored and the domain comes from config, so `GET /api/hunters/{id}` starts returning
   `<token>@wildalert.app` on the new revision.
6. Send a real email to a hunter's `inboundAddress` with a photo attached, with `npx wrangler tail`
   running. Expect a log line from the worker, a 200 from ingestion, and the same
   `matchedHunterId` / R2 object / `image.received` message as step 10e.

**The catch-all is on the apex, not `in.wildalert.app`.** Cloudflare allows catch-all rules on the
apex only; a subdomain would need one literal routing rule per address, which would put a Cloudflare
API call inside hunter registration. Explicit custom addresses are still matched before the
catch-all, so `hello@wildalert.app` can forward to your own mailbox. The price: the apex MX belongs
to Cloudflare, so no other provider can host mailboxes there later.

**Nothing is lost if ingestion is down.** The worker throws rather than calling `setReject()`, and
Email Routing retries a worker that throws — including on a 401 from a mismatched secret, which is
why step 3 above matters more than it looks. A bounce, by contrast, makes some senders (Outlook)
retry hourly for hours.

**Following these steps in order, the SMS does not arrive until recognition and notification are
deployed** (steps 10f, 10g and 11) — `image.received` has a publisher and no consumer before that.
Read the event from recognition's log once it exists, or create a temporary pull subscription on
the topic. **As of 2026-10-22 the full chain is live:** a photo emailed to a hunter's address
produced `[FAKE SMS] … "WildAlert: wild boar detected (99% confidence)"` nine seconds later.

---

## Step 11c — The site on `wildalert.app` (Cloudflare Worker in front of the gateway)

The last ugly URL. The gateway works and serves the React app; this puts it on the domain you
already own. The worker, the DNS records and the reasoning live in
**[`site-worker/README.md`](./site-worker/README.md)** — this is the sequence.

**Why this is not a Cloud Run domain mapping.** Cloud Run routes by **Host header**, so a proxied
record pointed at the gateway arrives with `Host: wildalert.app`, matches no service and gets a
404. Teaching Cloud Run that hostname is exactly what a domain mapping does — and `europe-west3`
is **not in the supported region list**, with the feature flagged preview and "not
production-ready … due to latency issues" regardless. Cloudflare's own host-header override is
**Enterprise-only** on Origin Rules. A global external load balancer would solve it properly, and
costs **~€18–20/month** for the forwarding rule — twice the Cloud SQL idle cost, on the one
project whose premise is that infrastructure is nearly free. So: a worker whose whole job is one
header, for €0.

**Email is not affected.** MX records are independent of A/AAAA/CNAME, so the apex catch-all and
every `<token>@wildalert.app` keep working through step 11b's worker. Two workers, one zone, no
overlap: that one has an `email` handler and no routes, this one has a `fetch` handler and two.

**In this order — worker first, DNS second.** Routes attach fine to a hostname with no DNS record
and simply never fire, so deploying first makes the DNS record the single switch that turns the
site on. The other order leaves a window where `wildalert.app` resolves to the discard prefix and
hangs.

1. **Deploy the worker. Done 2026-10-29** — `wildalert-site`, version
   `eaa4d36d-dcf1-4c6a-8cc2-23c5b592fbfb`. Both routes attached while the apex still had no
   A/AAAA, which is what proved the ordering above.
   ```bash
   cd deploy/site-worker && npx wrangler deploy
   ```
   It has routes, so unlike the email worker this exits 0 and attaches them.

2. **DNS** → the zone → DNS → Records. Two records, both **Proxied** (orange cloud). **This is the
   step that makes the site live:**

   | Type | Name | Content | Proxy |
   |---|---|---|---|
   | AAAA | `@` | `100::` | Proxied |
   | AAAA | `www` | `100::` | Proxied |

   `100::` is the IPv6 discard prefix and is *meant* to be unroutable: a Workers route only
   attaches to a hostname with a proxied record, and the worker answers before anything is
   dialled, so the content is never used. **Grey-clouded, the request goes to `100::` and hangs.**
   Do not touch the MX or SPF rows.

   **Not doable from the CLI:** `wrangler` has no DNS-record command, and the OAuth token
   `wrangler login` stores carries `zone:read` only — no `dns_records:write` and no zone-settings
   scope. So this step and step 3 are dashboard clicks, or they need a separate API token with
   *Zone → DNS → Edit* and *Zone → Zone Settings → Edit*.

3. **SSL/TLS → Overview → Full (strict)** and **Edge Certificates → Always Use HTTPS → On**.
   Universal SSL already covers the apex and `*.wildalert.app`; nothing to request. Strict is free
   here because the only origin is `*.run.app`, which Google serves with a valid certificate.

4. **Verify** — the first three are one-liners, the fourth is the one that matters:
   ```bash
   curl -sI https://wildalert.app/ | head -1                       # 200
   curl -s  https://wildalert.app/ | grep -o '<title>.*</title>'   # WildAlert — Register
   curl -sI https://www.wildalert.app/ | grep -iE 'HTTP|location'  # 301 -> https://wildalert.app/
   curl -sI https://wildalert.app/api/hunters/by-email             # 404, nothing in user-account's logs
   ```
   Then **register a hunter through the form on the real domain** — the only check that exercises
   worker → gateway → ID token → private user-account end to end. A 404 on `/` while the
   `run.app` URL still answers 200 means the Host rewrite is not happening; a 502 means the
   gateway is down. `npx wrangler tail` shows which.

5. **Send one more photo** to a hunter's address afterwards. The DNS change should be invisible to
   Email Routing, and this is the cheapest way to know it was.

**What this does not fix.** `https://gateway-lznkorfm4q-ey.a.run.app` keeps answering the public
internet, so anything enforced at the Cloudflare edge can be bypassed by calling it directly. That
is tolerable **only because `SIGNUP_GRANT_MINOR` defaults to 0** (step 10h) — a scripted signup
buys a Cloud SQL row and nothing else. Before turning the grant back on, add Turnstile to the
form; it is the real control and works regardless of who fronts the domain. Cloudflare's free
rate limiting is one rule, IP only, with a **10-second** window and a 10-second block — a flood
brake, not abuse control. The bypass itself closes with `ORIGIN_SECRET`
(see `site-worker/README.md`) or with the load balancer.

---

## Step 12 — Stop paying when you stop working

`wildalert-db` is the only resource with a meaningful idle cost (~$9/month). **It is deliberately
left running** (decision 2026-10-26), and the right way to pause it is to **stop** it, not delete
it — `Edit → Activation policy`, or:

```bash
gcloud sql instances patch wildalert-db --activation-policy=NEVER   # data kept, ~$0.90/month
```

**Deleting is unrecoverable**: backups and PITR are off by step 4, so the hunter rows and their
inbound tokens are gone for good, and re-registering mints different addresses. Flyway recreates
the *schema*, never the data. And the product is offline while the database is — an alert arriving
then dead-letters after five retries.

**First check the three Data Protection toggles from step 4 are still off** (Edit → Data
Protection). *Prevent instance deletion* blocks the delete outright; *Retain backups after instance
deletion* and *Final backup on instance deletion* survive the instance and keep billing storage
after you think everything is gone.

Cloud Run at min-instances 0 costs nothing idle, so it can stay. Or delete it: Cloud Run →
`user-account` → Delete. Keep the Artifact Registry image and the secret — fractions of a cent, and
they save you a rebuild.

Coming back: steps 4–7 (instance, database, user, secret), then step 9 with the image you already
built. Cloud SQL reserves a deleted instance's name for a while, so it may have to be
`wildalert-db2` — then update the connection name in `README.md`, here, and in the Cloud Run
`DATABASE_URL`.

---

## Afterwards: keep a record

The console leaves no trace of *what you chose*. In three months you will not remember whether
backups were on or which tier you picked, and that is exactly the problem infrastructure-as-code
solves.

So after clicking through, capture the result and compare it with `README.md`'s settings table:

```bash
gcloud sql instances describe wildalert-db --format=yaml > sql-actual.yaml
gcloud run services describe user-account --region europe-west3 --format=yaml > run-actual.yaml
```

If those disagree with the table, one of the two is wrong — worth knowing which. Replacing this
folder with OpenTofu (or Terraform) is the proper fix, and a good slice once more than one service
is deployed.
