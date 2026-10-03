# factory/

Static cockpit UI for the Factory, plus disposable local infrastructure helper
compose files.

## What this directory is (and is not)

`factory/` no longer contains an orchestrator, a Node runtime, a legacy
dashboard server or an instrument entry script. All of that has been removed.

The **control plane and the execution runtime are `factory-service`** — the
Kotlin / Spring Boot application living in `../factory-service/`. It owns the REST
API, the DAG sequencer that runs workflows, persistence, artifacts and the
retention/GC jobs.

## Contents

- `dashboard/` — the vanilla cockpit (`cockpit.html`, `css/`, `js/`). It is a
  static asset tree only: **no build step, no Node**. `factory-service` serves it
  same-origin at `/cockpit` on port `8141` (see `factory.cockpit.assets-dir` in
  `factory-service/src/main/resources/application.yml`).
- `docker-compose.minio.yml` — optional local MinIO/S3 endpoint for artifact
  development.
- `infra/` — disposable local PostgreSQL + Flyway scripts used while developing
  the persistence layer against a real database. Nothing here is part of the
  runtime; `factory-service` connects to PostgreSQL via `SPRING_DATASOURCE_*`.
- `ADR_TYPESCRIPT_MIGRATION.md` — historical ADR of the retired Node toolchain.

## Running

Start the Kotlin service, then open the cockpit:

```bash
cd factory-service
./gradlew bootRun          # serves the API and the cockpit on :8141
# Cockpit: http://127.0.0.1:8141/cockpit
```

See `../factory-service/README.md` for configuration, database setup and tests.
