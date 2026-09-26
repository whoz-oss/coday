# ARTEFACTS Aggregate Port to Kotlin factory-service Specification

## Abstract
Port the ARTEFACTS aggregate (metadata, dual-storage upload/purge protocol, orthogonal status management, S3 blob client, and admin governance use-cases/HTTP routes) from TypeScript (`factory/src/...`) to Kotlin in `factory-service/src/...` under package `io.whozoss.factory.artifact`.

---

## 1. Scope & Boundaries

### In Scope
- Package: `io.whozoss.factory.artifact` (domain, port, infrastructure, web, service)
- Configuration: Additive `factory.artifact.*` properties (S3 settings, retention defaults, signed URL TTL).
- Persistence: PostgreSQL against V6 `artifacts` table using Spring Data JDBC / `JdbcTemplate` with composite PK `(organization_id, workstream_id, namespace_id, workflow_id, artifact_id)`.
- Integration & Unit Tests: Testcontainers-backed integration tests extending `PostgresContainerSpec` + unit tests for domain/blob clients.

### Strictly Out of Scope
- DO NOT rewrite or refactor shared socle (`io.whozoss.factory.config`, `error`, `web`, `persistence` ScopedRepository) except additive configuration.
- DO NOT touch `factory/` (Node/TS) or `agentos/` or any other domain aggregate (e.g. `oracle`).

---

## 2. Architecture & Package Structure

```
factory-service/src/main/kotlin/io/whozoss/factory/artifact/
├── config/
│   └── ArtifactProperties.kt               # Configuration properties factory.artifact.*
├── domain/
│   ├── ArtifactAvailabilityStatus.kt       # AVAILABLE, PURGED, ARCHIVED, PENDING, UPLOADING, UNAVAILABLE
│   ├── ArtifactRetentionStatus.kt          # ACTIVE, EXPIRED
│   └── ArtifactMetadata.kt                 # Domain aggregate record & retention/purge domain logic
├── port/
│   └── ArtifactStore.kt                    # Domain port for storing, opening, purging, legal hold
├── infrastructure/
│   ├── blob/
│   │   ├── ArtifactBlobClient.kt           # Blob client interface
│   │   ├── InMemoryArtifactBlobClient.kt   # Thread-safe in-memory map client for testing/offline
│   │   └── S3ArtifactBlobClient.kt         # AWS S3 / MinIO client (S3Client/SigV4 or Java HTTP) with presigning
│   └── persistence/
│       ├── ArtifactEntity.kt               # Spring Data JDBC entity or row mapper mapping for V6 `artifacts`
│       ├── ArtifactGcMetadataRow.kt        # DTO for GC audit
│       └── PostgresArtifactStore.kt        # Implements ArtifactStore + OrphanUploadCollector
├── service/
│   ├── ArtifactAdminService.kt             # Admin use cases: purge, legal hold, GC collect & audit
│   └── ArtifactService.kt                  # General artifact application service
└── web/
    └── ArtifactAdminController.kt          # REST endpoints under /api/factory/admin/artifacts/*
```

---

## 3. Detailed Specifications

### 3.1 Domain Model & Rules (`io.whozoss.factory.artifact.domain` & `port`)

#### `ArtifactAvailabilityStatus` (Enum)
Values: `AVAILABLE` ("available"), `PURGED` ("purged"), `ARCHIVED` ("archived"), `PENDING` ("pending"), `UPLOADING` ("uploading"), `UNAVAILABLE` ("unavailable")
- Custom JSON serialization/deserialization to lower-case string values matching Node TS.

#### `ArtifactRetentionStatus` (Enum)
Values: `ACTIVE` ("active"), `EXPIRED` ("expired")
- Custom JSON serialization/deserialization to lower-case string values.

#### `ArtifactMetadata` (Data Class / Record)
Fields:
- `id`: String
- `owner`: String (e.g., "namespace/workflow" or principal)
- `hash`: String (content address e.g. "sha256:<hex>")
- `size`: Long
- `contentType`: String
- `retentionDays`: Int?
- `availabilityStatus`: ArtifactAvailabilityStatus
- `retentionStatus`: ArtifactRetentionStatus
- `legalHold`: Boolean
- `createdAt`: Instant
- `retentionUntil`: Instant?
- `purgedAt`: Instant? = null
- `purgeReason`: String? = null
- `legalHoldReason`: String? = null
- `legalHoldSetAt`: Instant? = null

#### Domain Rules:
- Default retention: `ARTIFACT_RETENTION_DAYS` (default 90 days if not set).
- Retention calculation: `retentionUntil = createdAt + retentionDays`.
- Retention status: `ACTIVE` if `retentionUntil > now`, else `EXPIRED`.
- Refuse destruction (`isDestroyable()`): returns `false` if `legalHold == true` OR `retentionStatus == ACTIVE`.

---

### 3.2 Configuration (`io.whozoss.factory.artifact.config`)

#### `ArtifactProperties`
Properties prefix `factory.artifact`:
- `retention-days`: Int = 90
- `signed-url-ttl-seconds`: Long = 900
- `s3`:
  - `endpoint`: String = "http://localhost:9000"
  - `region`: String = "us-east-1"
  - `bucket`: String = "coday-artifacts"
  - `access-key-id`: String = "minioadmin"
  - `secret-access-key`: String = "minioadmin"

---

### 3.3 Blob Storage Clients (`io.whozoss.factory.artifact.infrastructure.blob`)

#### Interface `ArtifactBlobClient`
```kotlin
interface ArtifactBlobClient {
    fun putObject(key: String, body: ByteArray, contentType: String? = null)
    fun copyObject(sourceKey: String, destinationKey: String)
    fun getObject(key: String): InputStream?
    fun deleteObject(key: String): Boolean
    fun headObject(key: String): Boolean
    fun listObjectKeys(prefix: String): List<String>
    fun getSignedUrl(key: String, expiresInSeconds: Long? = null, now: Instant = Instant.now()): String?
}
```

#### Implementations:
1. `InMemoryArtifactBlobClient`:
   - Thread-safe `ConcurrentHashMap<String, ByteArray>` for bytes and `ConcurrentHashMap<String, String>` for contentType.
   - Presigned URL generation returns mock URL string e.g. `http://localhost:9000/mock-bucket/$key?X-Amz-Expires=...`.
2. `S3ArtifactBlobClient`:
   - Configured via `factory.artifact.s3.*` or fallback env vars `S3_ENDPOINT`, `S3_REGION`, `S3_BUCKET`, `S3_ACCESS_KEY_ID`, `S3_SECRET_ACCESS_KEY`.
   - Uses Java's `java.net.http.HttpClient` with AWS SigV4 calculation or standard AWS SDK / MinIO S3 Presigner for presigned URLs using `signed-url-ttl-seconds` (default 900s).

---

### 3.4 Persistence & Protocol (`io.whozoss.factory.artifact.infrastructure.persistence`)

#### Schema Mapping:
V6 `artifacts` table composite PK: `(organization_id, workstream_id, namespace_id, workflow_id, artifact_id)`.
- Scoped by `TenantScope` (`organizationId`, `workstreamId`).
- `owner`: If formatted as `namespace/workflow`, parsed into `namespace_id` and `workflow_id`. Otherwise defaults to `namespace_id = "default"`, `workflow_id = "default"`.

#### Upload-Then-Commit Protocol (in `PostgresArtifactStore`):
1. Compute SHA-256 hash (`sha256:<hex>`) and create unique artifact ID (`art_<uuid>`).
2. Put payload to staging key: `uploads/<id>.part`.
3. Server-side copy staging object to content-addressed key: `objects/<hash_hex>`.
4. Verify object existence in blob client (`headObject`).
5. Save row in PostgreSQL `artifacts` table.
6. Best-effort delete staging key (`uploads/<id>.part`).

#### Store Methods Implemented:
- `putArtifact(owner, contentType, data, retentionDays)`
- `getArtifactMetadata(artifactId)`
- `openArtifact(artifactId)`: returns stream + metadata
- `deleteArtifact(artifactId, reason)`
- `purgeArtifact(artifactId, reason)`: checks destroyable rule, updates SQL row to `availability_status = 'purged'`, `purged_at`, `purge_reason`, and deletes object from blob store.
- `setLegalHold(artifactId, legalHold, reason)`: checks DB row exists, updates `legal_hold`, `legal_hold_reason`, `legal_hold_set_at`. Fails if setting `legalHold = true` on an already purged artifact (due to database constraint `artifacts_legal_hold_purge_check`).
- `collectOrphanedUploads()`: lists `uploads/` keys in blob storage and deletes them.
- `listAllMetadataRows()`: helper method for GC audit.

---

### 3.5 Admin Service & Web Controller (`io.whozoss.factory.artifact.web` & `service`)

#### Endpoints (`/api/factory/admin/artifacts/*`):

1. **POST `/api/factory/admin/artifacts/gc`**
   - Service: `collectAndAuditGarbage()`
   - Operation:
     - Reclaims staging keys under `uploads/`.
     - Scans blob keys under `objects/`.
     - Scans metadata rows from DB.
     - Detects anomalies:
       - `blob_without_pg_row`: Blob exists in S3 `objects/` with no active/purged DB row referencing it.
       - `pg_purged_or_missing_blob`: DB row is marked `purged` or DB row is `available` but missing blob in S3.
   - Response: `200 OK` `{ "data": { "reclaimedStagingKeys": [...], "anomalies": [...], "scannedBlobKeys": [...], "scannedMetadataRows": N, "timestamp": "ISO-8601" } }`

2. **POST `/api/factory/admin/artifacts/{artifactId}/purge`**
   - Body: `{ "reason": "string" }` (optional, default `"admin-purge"`).
   - Operation: Performs admin purge check.
     - If missing: `{ "success": false, "artifactId": "...", "reason": "...", "status": "NOT_FOUND" }` -> HTTP `404 Not Found` with code `NOT_FOUND`.
     - If legal hold active: `{ "success": false, "status": "LEGAL_HOLD_ACTIVE" }` -> HTTP `409 Conflict` with code `LEGAL_HOLD_ACTIVE`.
     - If retention active: `{ "success": false, "status": "RETENTION_ACTIVE" }` -> HTTP `409 Conflict` with code `RETENTION_ACTIVE`.
     - If successful: `{ "data": { "success": true, "artifactId": "...", "reason": "...", "status": "purged", "metadata": { ... } } }` -> HTTP `200 OK`.

3. **POST `/api/factory/admin/artifacts/{artifactId}/legal-hold`**
   - Body: `{ "legalHold": boolean, "reason": "string" }`
   - Validation: If `legalHold` is missing or not a boolean -> HTTP `400 Bad Request` with code `INVALID_LEGAL_HOLD`.
   - Operation: Sets/releases legal hold.
     - If missing: HTTP `404 Not Found` with code `ARTIFACT_NOT_FOUND`.
     - If success: HTTP `200 OK` `{ "data": <ArtifactMetadata> }`.

#### Security & HTTP Guarding:
- **ALL** 3 routes must invoke `AdminGuard.requireAdminRole(trustContext)` fail-closed at start.
- Non-admin callers receive HTTP `403` with body `{ "error": { "code": "FORBIDDEN_ADMIN_REQUIRED", "message": "...", "details": null } }`.
- Non-POST HTTP methods return HTTP `405` with `METHOD_NOT_ALLOWED`.
- Standard error envelope matching Node error codes and HTTP statuses.

---

## 4. Verification Plan

### Test Suites to Add
1. **`ArtifactDomainTest.kt`**:
   - Unit tests for retention date calculations and status evaluations (`ACTIVE` vs `EXPIRED`).
   - Destruction refusal when `legalHold == true` or `retentionStatus == ACTIVE`.

2. **`InMemoryArtifactBlobClientTest.kt`**:
   - Verify `putObject`, `copyObject`, `getObject`, `deleteObject`, `headObject`, `listObjectKeys`, and presigned URL format.

3. **`PostgresArtifactStoreIntegrationTest.kt`** (Extends `PostgresContainerSpec`):
   - CRUD metadata operations and verification of the 3 orthogonal status dimensions in DB.
   - Upload-then-commit protocol verification (staging copy to content-addressed key).
   - Purge lifecycle: success on expired retention, refused on active retention or legal hold.
   - Legal hold placement & removal.
   - Staging reclamation (`collectOrphanedUploads`).

4. **`ArtifactAdminControllerIntegrationTest.kt`** (Extends `PostgresContainerSpec`):
   - Admin authorization check: 403 `FORBIDDEN_ADMIN_REQUIRED` for non-admin principals on all 3 admin routes (`/gc`, `/purge`, `/legal-hold`).
   - POST `/api/factory/admin/artifacts/gc`: verifies reclaimed uploads + `blob_without_pg_row` & `pg_purged_or_missing_blob` anomaly detection.
   - POST `/api/factory/admin/artifacts/{artifactId}/purge`: test 200 success on expired, 409 on retention/hold, 404 on missing.
   - POST `/api/factory/admin/artifacts/{artifactId}/legal-hold`: test 200 success, 400 on bad body, 404 on missing.
   - Machine error code and response envelope parity with Node/TS implementation.

### Execution Command
Run in `factory-service/`:
```bash
./gradlew test
```
Ensure build compiles cleanly and all unit/integration tests pass.
