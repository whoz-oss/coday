# Spec B5-T3: Artifact Store Global Wiring, Barrels, Infrastructure Docs & Runtime Bundle

## Goal
Complete task B5-T3 for Milestone B wave B5 in `factory/`:
1. Wire the artifact storage adapter in `factory/dashboard/composition-root.mjs` cleanly based on configuration and persistence mode.
2. Complete barrels in `factory/src/adapters/artifact/index.ts` and `factory/src/application/artifact/index.ts` (new barrel) and update entrypoints if needed.
3. Document artifact infrastructure usage (MinIO + retention/presign/admin commands) in `factory/infra/README.md`.
4. Regenerate `factory/runtime/factory-operational.mjs` ONCE using `node factory/toolchain/build.mjs`.
5. Extend/add a comprehensive global wiring test (`factory/tests/test-artifact-global-wiring.mjs` and/or extending existing test files) verifying selection by config and capability availability.

---

## Detailed Plan

### Step 1: Complete Barrels & Entrypoints

1. **New barrel: `factory/src/application/artifact/index.ts`**
   - Create `factory/src/application/artifact/index.ts` re-exporting everything from `./artifact-admin-use-cases.js`.
   - Re-exports:
     - `ARTIFACT_ADMIN_UPLOAD_PREFIX`, `ARTIFACT_ADMIN_OBJECT_PREFIX`
     - Types: `ArtifactAdminPurgeStatus`, `ArtifactAdminPurgeResult`, `ArtifactGcAnomalyType`, `ArtifactGcAnomaly`, `ArtifactGcMetadataRow`, `ArtifactGarbageCollectionOptions`, `ArtifactGarbageCollectionReport`
     - Class: `ArtifactAdminError`
     - Functions: `purgeArtifactAdmin`, `setLegalHoldAdmin`, `collectAndAuditGarbage`

2. **Update barrel: `factory/src/adapters/artifact/index.ts`**
   - Re-export `PostgresArtifactStore`, `createPostgresArtifactStore`, `DEFAULT_ARTIFACT_RETENTION_DAYS`, `ARTIFACT_RETENTION_DAYS_ENV`, and types (`ArtifactBlobClient`, `PostgresArtifactStoreConfig`) from `./postgres-artifact-store.js`.

3. **Check entrypoint: `factory/src/entrypoints/factory-operational.ts`**
   - Ensure clean imports/exports without breaking existing exports.
   - Update `factory/src/entrypoints/factory-operational.ts` to export:
     - `export * from '../ports/artifact/index.js'`
     - `export * from '../adapters/artifact/index.js'`
     - `export * from '../adapters/persistence/sql/sql-artifact-metadata-repository.js'`
     - `export * from '../application/artifact/index.js'`

---

### Step 2: Dashboard Composition Root Wiring (`factory/dashboard/composition-root.mjs`)

1. **Config options in `loadConfig(env)`**:
   - Add artifact configuration section to returned config object:
     ```js
     artifact: {
       s3Endpoint: env.S3_ENDPOINT,
       s3Region: env.S3_REGION ?? 'us-east-1',
       s3Bucket: env.S3_BUCKET ?? 'coday-artifacts',
       s3AccessKeyId: env.S3_ACCESS_KEY_ID ?? env.AWS_ACCESS_KEY_ID,
       s3SecretAccessKey: env.S3_SECRET_ACCESS_KEY ?? env.AWS_SECRET_ACCESS_KEY,
       s3SessionToken: env.S3_SESSION_TOKEN ?? env.AWS_SESSION_TOKEN,
       artifactSignedUrlTtl: env.ARTIFACT_SIGNED_URL_TTL ? parseInt(env.ARTIFACT_SIGNED_URL_TTL, 10) : undefined,
       artifactRetentionDays: env.ARTIFACT_RETENTION_DAYS ? parseInt(env.ARTIFACT_RETENTION_DAYS, 10) : undefined,
     }
     ```

2. **Imports from runtime bundle**:
   - Update imports in `composition-root.mjs` or use imports from `../runtime/factory-operational.mjs` (or relative mjs modules):
     - `MemoryArtifactStore`, `S3ObjectClient`, `PostgresArtifactStore`, `createPostgresArtifactStore`, `createS3ObjectClient`, `purgeArtifactAdmin`, `setLegalHoldAdmin`, `collectAndAuditGarbage`, `createSqlArtifactMetadataRepository`.

3. **Store Selection in `createStores(config, options)`**:
   - Resolve `artifactStore` and `artifactBlobClient` (if S3 client is configured).
   - Selection Logic:
     - When `FACTORY_PERSISTENCE=sql` (or S3 config + sqlClient supplied):
       - If S3 parameters (`s3Endpoint` or `s3AccessKeyId`/`s3SecretAccessKey`) are present, create `S3ObjectClient`.
       - If `sqlClient` / SQL repositories are available, create `SqlArtifactMetadataRepository` (or pass `sqlClient` with scope `organizationId`/`workstreamId`).
       - Compose `PostgresArtifactStore({ client: blobClient, sqlClient, organizationId, workstreamId, retentionDays: config.artifact.artifactRetentionDays })`.
     - When `FACTORY_PERSISTENCE=fs` and no S3 client / sqlClient configured:
       - Instantiates `MemoryArtifactStore({ retentionDays: config.artifact.artifactRetentionDays })`.
   - Return `artifactStore` and `artifactBlobClient` as part of the `stores` object returned by `createStores`.

4. **Application / Adapters Attachment in `createApplication(config, stores, adapters)`**:
   - Ensure `artifactStore`, `artifactBlobClient`, and `artifactAdmin` (or listMetadata repository read helper) are cleanly accessible on `application` / `stores` / `adapters`.
   - Wire `handleArtifactAdminRequest` in `createHttpServer(application, config)` so HTTP requests to `/api/factory/admin/artifacts/*` route to admin use cases with `store`, `blobClient`, and `listMetadata`.

---

### Step 3: Regenerate Runtime Bundle

- Execute `node factory/toolchain/build.mjs` **EXACTLY ONCE**.
- Verify generated files:
  - `factory/runtime/factory-operational.mjs`
  - `factory/dist/factory-operational/factory-operational.mjs.map`
  - `factory/dist/factory-operational/factory-operational.meta.json`
- Do NOT edit `factory-operational.mjs` manually.

---

### Step 4: Documentation (`factory/infra/README.md`)

Add a dedicated **Artifact Infrastructure & Object Storage (MinIO)** section to `factory/infra/README.md`:
1. **MinIO Setup**:
   - How to start MinIO: `docker compose -f factory/docker-compose.minio.yml up -d`
   - Default bucket creation (`coday-artifacts`), MinIO Console access (port 9001, admin / minioadmin).
2. **Environment Variables**:
   - `S3_ENDPOINT=http://localhost:9000`
   - `S3_REGION=us-east-1`
   - `S3_BUCKET=coday-artifacts`
   - `S3_ACCESS_KEY_ID=minioadmin`
   - `S3_SECRET_ACCESS_KEY=minioadmin`
   - `ARTIFACT_RETENTION_DAYS=90`
   - `ARTIFACT_SIGNED_URL_TTL=900`
3. **Admin Commands & Procedures**:
   - Explicit admin purge: `purgeArtifactAdmin(store, artifactId, reason)`
   - Pose/retrait legal hold: `setLegalHoldAdmin(store, artifactId, legalHold, reason)`
   - Triggered GC & audit: `collectAndAuditGarbage(store, blobClient, options)`
   - HTTP route equivalents under `/api/factory/admin/artifacts/*`.
4. **Explicit Reminder / Notice**:
   - **NOTHING IS AUTOMATIC**: No background timers, no auto-purge schedulers. Every retention purge, legal hold toggle, and GC audit run MUST be triggered explicitly by an operator or administrative action.

---

### Step 5: Global Wiring Test & Verification

1. **Create `factory/tests/test-artifact-global-wiring.mjs`**:
   - Test 1: Configuration selection in `loadConfig` and `createStores`.
     - Default/FS mode without S3 config -> selects `MemoryArtifactStore`.
     - SQL mode / S3 config -> selects `PostgresArtifactStore` with `S3ObjectClient` / fake blob client and SQL repository.
   - Test 2: Capabilities on composed `artifactStore`:
     - Test `putArtifact`, `openArtifact`, `getSignedUrl`.
   - Test 3: Admin use cases invocation (`purgeArtifactAdmin`, `setLegalHoldAdmin`, `collectAndAuditGarbage`) through application / HTTP route context.
   - Test 4: Static source guard verifying `composition-root.mjs` exported signatures and route wiring for `handleArtifactAdminRequest`.

2. **Run All Offline Artifact Tests**:
   - `node factory/tests/test-artifact-store.mjs`
   - `node factory/tests/test-artifact-signed-urls.mjs`
   - `node factory/tests/test-artifact-admin-commands.mjs`
   - `node factory/tests/test-artifact-metadata-postgres.mjs`
   - `node factory/tests/test-artifact-global-wiring.mjs`
   - `node factory/tests/test-composition-root-source.mjs`

---

## Verification Plan

1. Run `node factory/toolchain/build.mjs` to regenerate the runtime bundle.
2. Run individual test scripts with `node`:
   ```bash
   node factory/tests/test-artifact-store.mjs
   node factory/tests/test-artifact-signed-urls.mjs
   node factory/tests/test-artifact-admin-commands.mjs
   node factory/tests/test-artifact-metadata-postgres.mjs
   node factory/tests/test-artifact-global-wiring.mjs
   node factory/tests/test-composition-root-source.mjs
   ```
3. Run the factory test suite command:
   ```bash
   pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2
   ```
