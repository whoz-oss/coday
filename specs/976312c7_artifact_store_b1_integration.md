# ArtifactStore Port & Adapters Plan

## Goal
Restore and integrate the ArtifactStore domain port, S3/MinIO adapters, hashing helper, in-memory adapter, MinIO docker-compose configuration, Node test script, and entrypoint re-exports strictly under `factory/` without editing generated bundle files directly or modifying anything under `agentos/`. Regenerate `factory/runtime/factory-operational.mjs` via `node factory/toolchain/build.mjs`.

---

## File Changes & Structural Blueprint

All file changes are restricted strictly under `factory/`. No files under `agentos/` shall be touched.

### 1. `factory/src/ports/artifact/artifact-store.ts` (New File)
Define `ArtifactStore` port interface, metadata types, and state enums/types:
- **`ArtifactMetadata` interface**:
  - `id: string`
  - `owner: string`
  - `hash: string` (SHA-256 digest string formatted as `sha256:...` or hex hash)
  - `size: number`
  - `contentType: string`
  - `retentionDays?: number`
  - States:
    - `availabilityStatus: 'available' | 'purged' | 'archived'`
    - `retentionStatus: 'active' | 'expired'`
    - `legalHold: boolean`
  - Timestamps & reasons:
    - `createdAt: Date | string`
    - `retentionUntil?: Date | string`
    - `purgedAt?: Date | string`
    - `purgeReason?: string`
    - `legalHoldReason?: string`
    - `legalHoldSetAt?: Date | string`
- **`ArtifactStore` interface**:
  - `putArtifact(params: { owner: string; contentType: string; data: Buffer | Uint8Array; retentionDays?: number }): Promise<ArtifactMetadata>`
  - `getArtifactMetadata(artifactId: string): Promise<ArtifactMetadata | null>`
  - `openArtifact(artifactId: string): Promise<{ stream: AsyncIterable<Uint8Array>; metadata: ArtifactMetadata } | null>`
  - `deleteArtifact(artifactId: string, reason?: string): Promise<boolean>`
  - Additional governance/lifecycle operations on the interface:
    - `setLegalHold(artifactId: string, legalHold: boolean, reason?: string): Promise<ArtifactMetadata | null>`
    - `purgeArtifact(artifactId: string, reason?: string): Promise<boolean>`

### 2. `factory/src/ports/artifact/index.ts` (New File)
Re-export all types and interfaces from `./artifact-store.js`.

### 3. `factory/src/adapters/artifact/artifact-hash.ts` (New File)
- Standard Node `node:crypto` hashing helper.
- `computeArtifactHash(data: Buffer | Uint8Array): string` -> returns SHA-256 hash string (e.g. `sha256:<hex>` or hex).

### 4. `factory/src/adapters/artifact/memory-artifact-store.ts` (New File)
In-memory implementation of `ArtifactStore`:
- Maintains internal `Map<string, { metadata: ArtifactMetadata; data: Uint8Array }>`
- Implements `putArtifact`, `getArtifactMetadata`, `openArtifact`, `deleteArtifact`, `setLegalHold`, `purgeArtifact`.
- **Retention & Legal Hold Logic**:
  - Computing `retentionUntil` based on `createdAt` + `retentionDays`.
  - Enforcing `legalHold`: `deleteArtifact` / `purgeArtifact` fails or returns false if `legalHold: true`.
  - `availabilityStatus` management (`available`, `purged`, `archived`).
  - `retentionStatus` evaluation (`active` vs `expired` based on current time vs `retentionUntil`).

### 5. `factory/src/adapters/artifact/s3-object-client.ts` (New File)
Low-level S3/MinIO HTTP object client abstraction using Node native `fetch` / HTTP primitives without external npm dependencies:
- Supports S3 standard REST actions or MinIO compatibility (PUT, GET, DELETE, HEAD).
- Accepts endpoint config (`endpoint`, `region`, `accessKeyId`, `secretAccessKey`, `bucket`).
- Simple AWS SigV4 / SigV2 authorization header calculation or basic S3 client request signing for native Node `fetch`.

### 6. `factory/src/adapters/artifact/s3-artifact-store.ts` (New File)
S3 / MinIO adapter implementing `ArtifactStore`:
- Backed by `S3ObjectClient`.
- Implements upload-then-commit protocol (writing temporary key then moving/committing to permanent content-addressed object key or metadata store).
- Includes skeleton GC helper/method for cleaning orphaned uncommitted uploads.
- Handles metadata sidecars or headers for retention, legal hold, and availability status.

### 7. `factory/src/adapters/artifact/index.ts` (New File)
Re-export all artifact adapters (`artifact-hash.js`, `memory-artifact-store.js`, `s3-object-client.js`, `s3-artifact-store.js`).

### 8. `factory/docker-compose.minio.yml` (New File)
Local MinIO Docker Compose specification:
- Service `minio` using image `minio/minio`.
- Service `mc` (MinIO Client) for bucket initialization.
- Configured ports (e.g. 9000 for S3 API, 9001 for MinIO Console).
- Environment variables (`MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD`).

### 9. `factory/src/entrypoints/factory-operational.ts` (Update File)
Add export section for ArtifactStore:
```typescript
// --------------------------------------------------------------------------
// ArtifactStore: port and adapters
// --------------------------------------------------------------------------
export * from '../ports/artifact/index.js'
export * from '../adapters/artifact/index.js'
```

### 10. `factory/tests/test-artifact-store.mjs` (New File)
Node offline test script exercising `MemoryArtifactStore` and `computeArtifactHash`:
- Tests `putArtifact` with data, contentType, owner, retentionDays.
- Tests `getArtifactMetadata` returning metadata with expected state (`availabilityStatus: 'available'`, `retentionStatus: 'active'`, etc.).
- Tests `openArtifact` returning readable stream / chunk iterable matching original data.
- Tests `setLegalHold` enabling/disabling hold and preventing deletion when active.
- Tests `deleteArtifact` / `purgeArtifact` handling retention expiration and updating `availabilityStatus` to `purged`.
- Exits with status `0` on success.

### 11. Documentation Updates
- Update `factory/README.md` to list ArtifactStore capabilities.
- Update `factory/tests/README.md` to include `test-artifact-store.mjs` description under offline tests.

---

## Build & Verification Steps

1. **Write Source Files**:
   Create ports and adapters under `factory/src/ports/artifact/` and `factory/src/adapters/artifact/`.
   Create `factory/docker-compose.minio.yml`.
   Update `factory/src/entrypoints/factory-operational.ts`.

2. **Bundle Generation**:
   Run `node factory/toolchain/build.mjs`.
   Verify `factory/runtime/factory-operational.mjs` was regenerated and includes exported ArtifactStore symbols.

3. **Run Test Verification**:
   - Run `node factory/tests/test-artifact-store.mjs`.
   - Run operational contract test: `node factory/tests/typescript-factory-operational.mjs`.
   - Run all existing offline factory test scripts: `for f in factory/tests/test-*.mjs; do node "$f"; done`.
   - Verify zero regressions on persistence (`test-repository-ports-adapters.mjs`, `test-sql-repository-ports-adapters.mjs`) and identity (`test-identity-trust-context.mjs`).

4. **Commit**:
   Commit changes with conventional commit header: `feat(factory): integrate ArtifactStore port and S3/MinIO adapters into B1`.

---

## Acceptance Checklist
- [ ] Source files for ArtifactStore present in TypeScript strictly under `factory/`.
- [ ] No files touched outside `factory/` (specifically no edits under `agentos/`).
- [ ] `factory/runtime/factory-operational.mjs` generated by `node factory/toolchain/build.mjs`.
- [ ] `node factory/tests/test-artifact-store.mjs` exits 0.
- [ ] All offline tests `factory/tests/test-*.mjs` exit 0.
- [ ] Clean conventional git commit created.
