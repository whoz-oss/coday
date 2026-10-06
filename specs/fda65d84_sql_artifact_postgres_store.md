# Implementation Plan: SQL Artifact Metadata Repository and Postgres ArtifactStore Adapter (B5-T1)

This plan details the implementation of the SQL artifact metadata repository adapter (`SqlArtifactMetadataRepository`) and the composite PostgreSQL `ArtifactStore` adapter (`PostgresArtifactStore`).

## Architecture & Responsibilities

### 1. `SqlArtifactMetadataRepository`
Located at `factory/src/adapters/persistence/sql/sql-artifact-metadata-repository.ts`.
Responsible for direct interactions with PostgreSQL table `artifacts` (created in V6 migration).

**Database Schema (`artifacts` table)**:
- Primary Key: `(organization_id, workstream_id, namespace_id, workflow_id, artifact_id)`
- Columns:
  - `organization_id` VARCHAR(255) DEFAULT 'default'
  - `workstream_id` VARCHAR(255) DEFAULT 'default'
  - `namespace_id` VARCHAR(255)
  - `workflow_id` VARCHAR(255)
  - `artifact_id` VARCHAR(255)
  - `availability_status` VARCHAR(64) ('pending' | 'uploading' | 'available' | 'unavailable' | 'purged')
  - `retention_status` VARCHAR(64) ('active' | 'expired')
  - `legal_hold` BOOLEAN
  - `retention_until` TIMESTAMPTZ
  - `purged_at` TIMESTAMPTZ
  - `purge_reason` TEXT
  - `legal_hold_reason` TEXT
  - `legal_hold_set_at` TIMESTAMPTZ
  - `content_hash` VARCHAR(255)
  - `size` BIGINT
  - `content_type` VARCHAR(255)
  - `storage_key` VARCHAR(1024)
  - `payload` JSONB DEFAULT '{}'::jsonb
  - `created_at` TIMESTAMPTZ
  - `updated_at` TIMESTAMPTZ

**Key Design & Mapping Details**:
- **Tenant & Hierarchy Scoping**:
  - Accept `SqlArtifactMetadataRepositoryOptions`:
    - `organizationId?: string` (default: 'default')
    - `workstreamId?: string` (default: 'default')
    - `namespaceId?: string` (default: 'default')
    - `workflowId?: string` (default: 'default')
  - Extracting default scoping or mapping from `ArtifactMetadata.owner`:
    - If owner is formatted as `namespaceId/workflowId` or `namespaceId`, attempt to parse `namespaceId` and `workflowId`. Otherwise, use default `namespaceId` ('default') and `workflowId` ('default') or options fallback.
    - Alternatively/additionally, allow `saveMetadata` and query methods to accept optional `scopeOverride?: { namespaceId?: string; workflowId?: string; organizationId?: string; workstreamId?: string }`.
- **Domain <-> SQL Mapping**:
  - Convert DB row to `ArtifactMetadata`:
    - `id`: `artifact_id`
    - `owner`: parsed/restored owner string or extracted from `payload.owner`
    - `hash`: `content_hash`
    - `size`: `Number(row.size)`
    - `contentType`: `content_type`
    - `availabilityStatus`: `availability_status` as `ArtifactAvailabilityStatus` ('available' | 'purged' | 'archived')
    - `retentionStatus`: `retention_status` as `ArtifactRetentionStatus` ('active' | 'expired')
    - `legalHold`: `Boolean(row.legal_hold)`
    - `createdAt`: `row.created_at.toISOString()` or string
    - `retentionUntil`: `row.retention_until` if present
    - `purgedAt`: `row.purged_at` if present
    - `purgeReason`: `row.purge_reason` if present
    - `legalHoldReason`: `row.legal_hold_reason` if present
    - `legalHoldSetAt`: `row.legal_hold_set_at` if present
    - `retentionDays`: extracted from `payload.retentionDays` if set.
- **Methods**:
  - `saveMetadata(metadata: ArtifactMetadata, storageKey: string, scope?: Partial<SqlArtifactMetadataRepositoryScope>): Promise<ArtifactMetadata>`:
    - Inserts a row into `artifacts`.
    - Handles conflict / update if needed or standard insert.
  - `getMetadata(artifactId: string, scope?: Partial<SqlArtifactMetadataRepositoryScope>): Promise<ArtifactMetadata | null>`:
    - Queries `artifacts` by `artifact_id` and tenant scoping. Returns `ArtifactMetadata | null`.
  - `getMetadataAndStorageKey(artifactId: string, scope?: Partial<SqlArtifactMetadataRepositoryScope>): Promise<{ metadata: ArtifactMetadata; storageKey: string } | null>`:
    - Returns metadata along with `storage_key` so the caller can access object storage.
  - `updateLegalHold(artifactId: string, legalHold: boolean, reason?: string, now?: Date, scope?: Partial<SqlArtifactMetadataRepositoryScope>): Promise<ArtifactMetadata | null>`:
    - Sets or releases legal hold, updating `legal_hold`, `legal_hold_reason`, `legal_hold_set_at`.
  - `purgeArtifact(artifactId: string, reason: string, now?: Date, scope?: Partial<SqlArtifactMetadataRepositoryScope>): Promise<boolean>`:
    - Updates `availability_status` = 'purged', `purged_at` = now, `purge_reason` = reason.
    - Respects DB legal hold constraint / check in SQL query (`WHERE legal_hold = FALSE AND availability_status != 'purged'`). Returns `true` if updated, `false` otherwise.
- **Exports**:
  - Class `SqlArtifactMetadataRepository`
  - Factory function `createSqlArtifactMetadataRepository(client: SqlClient, options?: SqlArtifactMetadataRepositoryOptions): SqlArtifactMetadataRepository`

### 2. `PostgresArtifactStore`
Located at `factory/src/adapters/artifact/postgres-artifact-store.ts`.
Implements `ArtifactStore` port (`putArtifact`, `getArtifactMetadata`, `openArtifact`, `deleteArtifact`, `purgeArtifact`, `setLegalHold`).

**Composition**:
- Interacts with `S3ObjectClient` (or compatible object store client interface / methods: `putObject`, `copyObject`, `getObject`, `deleteObject`, `headObject`) for binary payload BLOBs.
- Interacts with `SqlArtifactMetadataRepository` (or `SqlClient`) for authoritative metadata in PostgreSQL.

**Protocol & Governance Rules**:
1. **Upload-Then-Commit Protocol in `putArtifact`** (Amendment 5):
   - Calculate `hash` (`computeArtifactHash(data)`) and `size`. Generate `id = createArtifactId()`.
   - Staging key: `uploads/<id>.part`
   - Content-addressed key: `objects/<sha256>` (extracting digest after `sha256:`).
   - Upload BLOB to staging key via S3 client.
   - Copy staging key to final content key via S3 client (`copyObject`).
   - Verify object existence or hash/size in object storage before PG commit (`headObject` or `getObject`).
   - Determine `retentionDays`: use `params.retentionDays` if defined, otherwise check `process.env.ARTIFACT_RETENTION_DAYS` (parsed as number, defaulting to `90`).
   - Construct initial `ArtifactMetadata` using `buildArtifactMetadata(...)`.
   - Commit authoritative metadata row in PostgreSQL via `SqlArtifactMetadataRepository` (inside PG transaction / SQL query).
   - Best-effort delete staging key (`uploads/<id>.part`).
   - If PG commit or verification fails, error is thrown, leaving BLOB as orphan in S3 (reclaimable by `collectOrphanedUploads`), and NO authoritative PG metadata row is committed.
2. **Authoritative Governance**:
   - `getArtifactMetadata`: Fetches metadata from PG repo. Applies `refreshArtifactMetadata(metadata, now)` using pure governance helpers from `memory-artifact-store.ts`.
   - `openArtifact`: Fetches metadata from PG repo. If `null` or `availabilityStatus === 'purged'`, returns `null`. Otherwise reads stream from S3 at `objects/<sha256>`.
   - `deleteArtifact(artifactId, reason)`: Delegates to `#destroy(artifactId, reason ?? 'deleted')`.
   - `purgeArtifact(artifactId, reason)`: Delegates to `#destroy(artifactId, reason ?? 'retention-expired')`.
   - `#destroy(artifactId, reason)`:
     - Fetches metadata from PG repo. If `null`, returns `false`.
     - Checks `isArtifactDestroyable(metadata, now)`. If `false` (due to legal hold or active retention), returns `false`.
     - Calls `repo.purgeArtifact(...)` to update PG row (`availability_status = 'purged'`, `purged_at`, `purge_reason`).
     - Best-effort deletes content object from S3 (`objects/<sha256>`).
     - Returns `true`.
   - `setLegalHold(artifactId, legalHold, reason)`:
     - Calls `repo.updateLegalHold(...)`.
     - Returns refreshed metadata.
3. **Exports**:
   - Class `PostgresArtifactStore` (or `ComposedPostgresArtifactStore`)
   - Factory function `createPostgresArtifactStore(config: PostgresArtifactStoreConfig): PostgresArtifactStore`

### 3. Updates to `in-memory-sql-client.mjs`
To support offline testing of `artifacts` queries in `test-artifact-metadata-postgres.mjs`:
- Register `artifacts` primary key in `PRIMARY_KEYS` in `factory/tests/support/in-memory-sql-client.mjs`:
  ```js
  artifacts: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id', 'artifact_id'],
  ```

### 4. Conformance Tests
File: `factory/tests/test-artifact-metadata-postgres.mjs`
Executed via: `node factory/tests/test-artifact-metadata-postgres.mjs`

**Test Cases**:
1. **Round-trip**: `putArtifact` -> `getArtifactMetadata` -> `openArtifact`.
2. **Upload-then-commit failure**: Simulate PG insert error. Verify staging upload was created, but no metadata exists in PG, and error is thrown.
3. **Retention enforcement**: Default retention (e.g. 90 days or env var `ARTIFACT_RETENTION_DAYS`) is applied when omitted. Verify `purgeArtifact` is a no-op while retention is active.
4. **Retention expiry & purge**: Fast-forward time past `retentionUntil`. Verify `purgeArtifact` updates PG `availability_status` to `'purged'` and sets `purged_at`/`purge_reason`.
5. **Legal hold enforcement**: `setLegalHold(true)` prevents `deleteArtifact` and `purgeArtifact`. Releasing legal hold permits purge when expired.
6. **Governance parity**: Verify parity with `MemoryArtifactStore` governance rules.

---

## Proposed Changes

### Files to create:
1. `factory/src/adapters/persistence/sql/sql-artifact-metadata-repository.ts`
2. `factory/src/adapters/artifact/postgres-artifact-store.ts`
3. `factory/tests/test-artifact-metadata-postgres.mjs`

### Files to modify:
1. `factory/tests/support/in-memory-sql-client.mjs`:
   - Add `artifacts` to `PRIMARY_KEYS`.

---

## Verification Plan

### Offline Test Command
Run the standalone test script:
```bash
node factory/tests/test-artifact-metadata-postgres.mjs
```

Run existing unit & conformance tests to ensure no regressions:
```bash
node factory/tests/test-sql-repository-ports-adapters.mjs
node factory/tests/test-artifact-store.mjs
```

Run Nx test suite:
```bash
pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2
```
