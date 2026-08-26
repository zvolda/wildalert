# WildAlert

Recognize animals in trail-camera photos forwarded by email, and text the hunter the
result. Kotlin + Spring Boot microservices, event-driven, with a Python recognition
service. See [`ROADMAP.md`](./ROADMAP.md) for the full plan and phase-by-phase build.

## Repository layout

```
services/
  user-account/     # Kotlin/Spring Boot — hunters, email→phone mapping (Phase 1)
  ...               # more services added as we build them
compose.yml         # local dev infra: Postgres + Kafka + Kafka UI
```

The recognition service (Python + SpeciesNet) is added later and lives outside the
Gradle build.

## Prerequisites

- **JDK 25** (already installed). Gradle is provided via the wrapper (`./gradlew`) —
  no separate install needed.
- **Docker Engine in WSL2** for local Postgres/Kafka. We deliberately avoid Docker
  Desktop (company licensing) and use the free, Apache-2.0 Docker Engine instead.

### One-time WSL2 + Docker Engine setup

In a Windows Terminal / PowerShell:

```powershell
wsl --install -d Ubuntu     # create a UNIX user + password when prompted
```

Then inside the Ubuntu shell:

```bash
echo -e "[boot]\nsystemd=true" | sudo tee /etc/wsl.conf     # so Docker auto-starts
```

Back in PowerShell: `wsl --shutdown`, then reopen Ubuntu and install Docker Engine:

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER
```

Reopen Ubuntu once more, then verify:

```bash
docker run hello-world
docker compose version
```

## Running locally

Start the infrastructure (from the repo root, inside WSL2 Ubuntu):

```bash
docker compose up -d
```

- Postgres → `localhost:5432` (user/password/db: `wildalert`)
- Kafka → `localhost:29092`
- Kafka UI → http://localhost:8080

Build everything:

```bash
./gradlew build
```

Run the User/Account service (fleshed out in Phase 1):

```bash
./gradlew :services:user-account:bootRun
```

Copy `.env.example` to `.env` and adjust as needed. `.env` is git-ignored.
