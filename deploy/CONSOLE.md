# Deploying user-account through the Cloud Console (UI)

This is how the deploy is done: by clicking, form by form. It is also the record of which option
was chosen where, so keep it in step with [`README.md`](./README.md)'s settings table — that table
and these forms are the only places the values live.

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
step 11.*

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

## Step 8 — Build the image (the one step the UI cannot do)

There is no "build my Dockerfile" button in the console — the source is on your machine, not on
Google's. Two browser-only options:

### Option A — Cloud Shell (no local setup)

Click the **Cloud Shell** icon (`>_`) in the top right of the console. It is a browser terminal
with `gcloud`, `git` and Docker already installed, authenticated as you.

```bash
git clone https://github.com/zvolda/wildalert.git
cd wildalert
git checkout initialsetup

IMAGE="europe-west3-docker.pkg.dev/wildalert-prod/wildalert/user-account:$(git rev-parse --short HEAD)"
gcloud builds submit --config deploy/cloudbuild.yaml \
  --substitutions="_SERVICE=user-account,_IMAGE=${IMAGE}" .

echo "$IMAGE"   # you need this in step 9
```

Takes a few minutes — the Gradle build downloads its dependencies inside the container.

### Option B — a Cloud Build trigger (the CI/CD answer)

**Navigation:** Cloud Build → Triggers → **Create Trigger**

| Field | Value |
|---|---|
| Name | `user-account-main` |
| Event | Push to a branch |
| Source | connect `github.com/zvolda/wildalert` (one-time GitHub authorisation) |
| Branch | `^main$` |
| Configuration | Cloud Build configuration file → `deploy/cloudbuild.yaml` |
| Substitution variables | `_SERVICE` = `user-account`, `_IMAGE` = `europe-west3-docker.pkg.dev/wildalert-prod/wildalert/user-account:$SHORT_SHA` |

More setup, but then every push to `main` rebuilds automatically — which is how teams actually do
this, and it closes the "deploys happen from someone's laptop" gap.

Either way, confirm the image landed: Artifact Registry → `wildalert` → `user-account`.

*Cost: build minutes (there is a daily free allowance) plus a little storage for the image and the
uploaded source.*

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
| `INBOUND_EMAIL_DOMAIN` | `in.wildalert.local` |

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

## Step 11 — Stop paying when you stop working

**Navigation:** SQL → `wildalert-db` → **Delete**

You have to type the instance name to confirm. This is the only resource with a meaningful idle
cost. Nothing is lost that steps 5–6 plus Flyway cannot recreate — only test data.

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
