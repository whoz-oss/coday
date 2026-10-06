# factory/

Disposable local infrastructure helpers for Factory.

## What this directory is (and is not)

`factory/` contains no orchestrator, Node runtime, dashboard server, instrument
entry script, or frontend. The legacy static dashboard has been removed.

The **control plane and execution runtime are `factory-service`** — the Kotlin /
Spring Boot application in `../factory-service/`. It owns the REST API, SSE,
DAG sequencer, persistence, artifacts, and retention/GC jobs.

The **only Factory UI is `apps/cockpit-v2`**. Its Angular build is served
same-origin by `factory-service` at `/cockpit-v2`; former `/cockpit` entry points
redirect there.

## Contents

- `docker-compose.minio.yml` — optional local MinIO/S3 endpoint for artifact development.
- `infra/` — disposable local PostgreSQL + Flyway scripts used while developing the persistence layer.
- `ADR_TYPESCRIPT_MIGRATION.md` — historical ADR of the retired Node toolchain.

## Running

Build Cockpit V2, start the Kotlin service, then open the cockpit:

```bash
pnpm nx build cockpit-v2
cd factory-service
./gradlew bootRun
# Cockpit: http://127.0.0.1:8141/cockpit-v2
```
