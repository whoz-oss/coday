# B5-T2b Implementation Plan: Admin Governance Use Cases for Artifacts

## Overview
Implement explicit admin governance use cases for factory artifacts (`admin purge`, `admin legal hold management`, and `admin garbage collection` with anomaly detection). These use cases will reside in a dedicated domain/application module (`factory/src/application/artifact/artifact-admin-use-cases.ts`) and will be transpiled/exposed for JS runtimes (`factory/lib/artifact-admin-use-cases.mjs`). An explicit admin authorization guard hook (`requireAdminRole(trustContext)` / `checkAdminAuthorization(trustContext)`) will be defined in `factory/dashboard/http-utils.mjs` and wired/exposed where appropriate. An offline test suite (`factory/tests/test-artifact-admin-commands.mjs`) will thoroughly validate all admin operations, error cases, GC reporting, and authorization guard invocations.

---

## Technical Constraints & Scope Directives
1. **DO NOT** implement signed URLs (B5-T2a, parallel task).
2. **DO NOT** implement real IdP / full role check (reserved for B6).
3. **DO NOT** modify frozen contracts/ports/adapters:
   - `factory/src/ports/artifact/artifact-store.ts` (FROZEN)
   - `factory/src/adapters/persistence/sql/sql-artifact-metadata-repository.ts` (FROZEN)
4. **DO NOT** modify barrels (reserved for W3), DB migrations, or `agentos/**`.
5. **DO NOT** modify automatic timers or auto-purging logic.
6. Support both TypeScript (`factory/src/...`) and build artifact generation (`node factory/toolchain/build.mjs`) so JS imports under `factory/lib/...` remain consistent.

---

## Detailed Task Breakdown

### Task 1: Create Admin Use Cases Application Module
**File to create:**
`factory/src/application/artifact/artifact-admin-use-cases.ts`

**Key Functions / Operations:**
1. `purgeArtifactAdmin(store: ArtifactStore, artifactId: string, reason: string): Promise<AdminPurgeResult>`
   - Retrieves artifact metadata using `store.getArtifactMetadata(artifactId)`.
   - Returns/throws failure or structured error if:
     - Artifact not found (`status: 'NOT_FOUND'`).
     - `legalHold === true` (`status: 'LEGAL_HOLD_ACTIVE'`).
     - `retentionStatus === 'active'` (`status: 'RETENTION_ACTIVE'`).
   - If eligible (`retentionStatus === 'expired'` and `legalHold === false`), calls `store.purgeArtifact(artifactId, reason)`.
   - Returns structured result e.g. `{ success: true, artifactId, availabilityStatus: 'purged' }` or throws a clear error / returns failure object.

2. `setLegalHoldAdmin(store: ArtifactStore, artifactId: string, legalHold: boolean, reason?: string): Promise<ArtifactMetadata>`
   - Calls `store.setLegalHold(artifactId, legalHold, reason)`.
   - Throws error if artifact is not found or return null.

3. `collectAndAuditGarbage(store: ArtifactStore, blobClient: ArtifactBlobClient, options?: GarbageCollectionOptions): Promise<GarbageCollectionReport>`
   - Explicit triggered GC operation.
   - Reclaims staging uploads: invokes `store.collectOrphanedUploads()` if available on `store` (or via `PostgresArtifactStore`/`S3ArtifactStore` blob client listing `uploads/`).
   - Detects & audits blob store / metadata anomalies:
     - **Blob store key listing**: Lists keys in `objects/` (or staging/object prefixes).
     - **PG metadata listing / lookup**: Compares blobs in store with PG metadata records.
     - Detects `blob_without_pg_row`: Binary blob present in blob store without corresponding PG metadata row.
     - Detects `pg_purged_or_missing_blob`: PG metadata row marked `purged` or metadata exists in PG but binary blob is missing in blob store.
   - Returns structured report:
     ```ts
     export interface GarbageCollectionReport {
       reclaimedStagingKeys: string[]
       anomalies: Array<{
         type: 'blob_without_pg_row' | 'pg_purged_or_missing_blob'
         artifactId?: string
         storageKey?: string
         details?: string
       }>
       timestamp: string
     }
     ```

### Task 2: Express & HTTP Authorization Point
**Files to update:**
`factory/dashboard/http-utils.mjs` (and optionally `factory/dashboard/composition-root.mjs` / route handler if exposing HTTP endpoints)

**Changes:**
1. In `factory/dashboard/http-utils.mjs`:
   - Add explicit admin authorization guard helper:
     ```js
     export function checkAdminAuthorization(trustContext) {
       if (!trustContext) return { authorized: false, reason: 'MISSING_TRUST_CONTEXT' }
       // Check for admin entitlement (roles includes 'admin' or scope includes 'admin:*' or loopback with admin role)
       // Designed to be pluggable and easily replaced in B6
       const isAdmin = trustContext.roles?.includes('admin') ||
                       trustContext.scopes?.includes('admin:*') ||
                       trustContext.scopes?.includes('*')
       return { authorized: isAdmin, reason: isAdmin ? null : 'INSUFFICIENT_ADMIN_PERMISSIONS' }
     }

     export function requireAdminRole(trustContext) {
       const check = checkAdminAuthorization(trustContext)
       if (!check.authorized) {
         const error = new Error(check.reason)
         error.statusCode = 403
         error.code = 'FORBIDDEN_ADMIN_REQUIRED'
         throw error
       }
       return true
     }
     ```

2. Wire / document authorization check for admin operations in composition root or route handlers so it is explicitly invoked before calling admin artifact use cases.

### Task 3: Build Toolchain Execution
Run `node factory/toolchain/build.mjs` to transpile TypeScript modules (`factory/src/application/artifact/artifact-admin-use-cases.ts`) to JS (`factory/lib/artifact-admin-use-cases.mjs`).

### Task 4: Offline Test Suite
**File to create:**
`factory/tests/test-artifact-admin-commands.mjs`

**Test Cases to Implement:**
1. **Admin Purge Tests:**
   - Expired retention (`retentionStatus === 'expired'`, `legalHold === false`) -> Admin purge succeeds.
   - Active retention (`retentionStatus === 'active'`) -> Admin purge refused.
   - Active legal hold (`legalHold === true`) -> Admin purge refused.
   - Non-existent artifact -> Admin purge fails with not found error/status.
2. **Admin Legal Hold Tests:**
   - Placing legal hold (`legalHold: true`) sets hold status & reason.
   - Removing legal hold (`legalHold: false`) clears hold status.
3. **Triggered Garbage Collection & Audit Tests:**
   - Reclaims orphaned uploads in staging (`uploads/`).
   - Detects `blob_without_pg_row` anomaly when a blob exists in storage but metadata is missing.
   - Detects `pg_purged_or_missing_blob` anomaly when metadata is purged or binary blob is missing from storage.
   - Verifies structured report format.
4. **Authorization Guard Verification:**
   - Explicitly verify `checkAdminAuthorization` / `requireAdminRole` is invoked and rejects non-admin trust contexts while permitting admin contexts.

---

## Verification Plan

### Test Commands
1. Run offline test:
   `node factory/tests/test-artifact-admin-commands.mjs`
2. Build toolchain:
   `node factory/toolchain/build.mjs`
3. Run TS contract check:
   `node factory/tests/typescript-factory-operational.mjs`
4. Run existing artifact store tests to ensure no regressions:
   `node factory/tests/test-artifact-store.mjs`
   `node factory/tests/test-artifact-metadata-postgres.mjs`

---

## Execution Steps Summary for Builder
1. Create `factory/src/application/artifact/artifact-admin-use-cases.ts` implementing `purgeArtifactAdmin`, `setLegalHoldAdmin`, and `collectAndAuditGarbage`.
2. Update `factory/dashboard/http-utils.mjs` with explicit `checkAdminAuthorization` and `requireAdminRole` authorization guards inspecting `TrustContext`.
3. Run `node factory/toolchain/build.mjs` to update compiled JS files in `factory/lib/`.
4. Create `factory/tests/test-artifact-admin-commands.mjs` covering all specified governance use cases, anomaly detection, and authorization checks.
5. Execute all validation test scripts and verify all tests pass cleanly.
