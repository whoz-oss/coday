# ARTEFACTS aggregate in `factory-service`

## What changed

The ARTEFACTS aggregate was ported into Kotlin under `io.whozoss.factory.artifact`, without changing the shared factory socle or the Node/TS implementation. The port covers artifact metadata, content-addressed storage, retention/legal-hold governance, PostgreSQL persistence, blob backends, and the three admin HTTP commands.

The domain model in `artifact/domain/` represents the orthogonal availability and retention statuses, legal hold fields, timestamps, hash and payload metadata. `ArtifactGovernance` applies the 90-day default, computes `retentionUntil`, refreshes active/expired retention, and blocks destruction while retention is active, a legal hold is present, or the artifact is already purged. `ArtifactHash` produces `sha256:<hex>` addresses and UUID artifact IDs.

`PostgresArtifactStore` in `artifact/infrastructure/persistence/` uses the V6 `artifacts` table with tenant scoping and owner parsing (`namespace/workflow`, with defaults for unstructured owners). Uploads follow the staging protocol: `uploads/<id>.part` → `objects/<hash>`, blob existence verification, PostgreSQL commit, then best-effort staging cleanup. Reads, purge/delete, legal-hold updates, orphan staging cleanup, and GC metadata listing are provided through the `ArtifactStore` port.

## Blob storage and configuration

`ArtifactBlobClient` defines put/copy/get/delete/head/list/presign operations. `InMemoryArtifactBlobClient` is thread-safe and intended for local/testing use. `S3ArtifactBlobClient` implements S3/MinIO REST operations and AWS SigV4 signing/presigned GET URLs using Java `HttpClient`, with a default signed URL TTL of 900 seconds.

`ArtifactProperties`, `ArtifactConfiguration`, and the additive `application.yml` settings select `in-memory` or `s3`, configure `ARTIFACT_RETENTION_DAYS`/`ARTIFACT_SIGNED_URL_TTL`, and bind the S3 endpoint, region, bucket, and credentials from the documented environment variables or `factory.artifact.*` properties.

## Admin API

`ArtifactAdminService` and `ArtifactAdminController` expose exactly these POST routes:

- `/api/factory/admin/artifacts/gc`: removes staging orphans and audits `objects/` against PostgreSQL, reporting `blob_without_pg_row` and `pg_purged_or_missing_blob` anomalies.
- `/api/factory/admin/artifacts/{artifactId}/purge`: purges only expired, non-held artifacts; defaults the reason to `admin-purge` and returns 404/409 machine-coded failures for missing, active-retention, or held artifacts.
- `/api/factory/admin/artifacts/{artifactId}/legal-hold`: requires a Boolean `legalHold`, sets/releases the hold and optional reason, and returns `INVALID_LEGAL_HOLD` for invalid input or `ARTIFACT_NOT_FOUND` for a missing row.

Every route calls `AdminGuard.requireAdminRole` before the use case. The shared error envelope carries `FORBIDDEN_ADMIN_REQUIRED` for non-admins; `ArtifactAdminMethodNotAllowedAdvice` maps non-POST requests to the canonical 405 `METHOD_NOT_ALLOWED` response. Successful responses use `{ "data": ... }`. The OpenAPI file now describes the three routes.

## Verification coverage

Tests added under `factory-service/src/test/kotlin/io/whozoss/factory/artifact/` cover governance and hashing, in-memory blob behavior, S3 signing/HTTP behavior, PostgreSQL metadata/status round trips and upload-then-commit, purge refusal/success, legal-hold placement/removal, GC anomalies, admin authorization, method handling, envelopes, and Node-compatible machine error codes. The integration suites extend `PostgresContainerSpec` and are disabled when Docker is unavailable.

To verify locally, run from `factory-service/`:

```bash
./gradlew test
./gradlew build
```

The change is carried by the new `artifact/config`, `domain`, `error`, `infrastructure/blob`, `infrastructure/persistence`, `port`, `service`, and `web` files; the additive `factory-service/src/main/resources/application.yml`; the route additions in `factory-service/openapi/factory-openapi.yaml`; and the artifact test files listed above.
