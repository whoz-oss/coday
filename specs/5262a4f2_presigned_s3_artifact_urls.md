# Plan B5-T2a: Presigned S3/MinIO URLs for Readable Artifacts in PostgresArtifactStore

## Task Overview

Implement task B5-T2a to support presigned S3/MinIO URLs for readable artifacts in `PostgresArtifactStore`.

### Context & Boundaries
- Working strictly under `factory/` (Node/TS).
- Primary files to touch:
  - `factory/src/adapters/artifact/s3-object-client.ts`
  - `factory/src/adapters/artifact/postgres-artifact-store.ts`
  - `factory/tests/test-artifact-signed-urls.mjs`
- DO NOT modify `ports/artifact/artifact-store.ts` (port stays untouched).
- DO NOT modify `sql-artifact-metadata-repository.ts` (SQL repo frozen).
- DO NOT modify barrels (`index.ts` files, e.g. `factory/src/adapters/artifact/index.ts`).
- DO NOT add admin commands (B5-T2b handles that).
- DO NOT touch migrations, manual bundles, or `agentos/**`.

---

## Detailed Step-by-Step Implementation Plan

### Step 1: Low-level Presigning in `S3ObjectClient`

**File**: `factory/src/adapters/artifact/s3-object-client.ts`

1. **Config & Environment Constants**:
   - Environment variable constant: `ARTIFACT_SIGNED_URL_TTL_ENV = 'ARTIFACT_SIGNED_URL_TTL'`
   - Default TTL constant: `DEFAULT_SIGNED_URL_TTL_SECONDS = 900`

2. **Method Signature**:
   ```ts
   getSignedUrl(
     key: string,
     options?: { expiresInSeconds?: number; now?: Date }
   ): string
   ```
   *Note*: Optional `now` parameter allows deterministic test verification without clock drift issues.

3. **SigV4 Presigned URL Construction Logic**:
   - Determine TTL: `options?.expiresInSeconds` -> `process.env[ARTIFACT_SIGNED_URL_TTL_ENV]` (if valid positive integer) -> `DEFAULT_SIGNED_URL_TTL_SECONDS` (900).
   - Timestamp computation: Use `options?.now ?? new Date()`. Format using existing `formatAmzDate(now)` helper -> `{ amzDate, dateStamp }`.
   - Uri path: Construct canonical URI: `/${encodeS3Component(this.#config.bucket)}/${encodeS3KeyPath(key)}` (or if `key` is empty, just bucket path).
   - Scope: `${dateStamp}/${this.#config.region}/s3/aws4_request`.
   - Credential string: `${this.#config.accessKeyId}/${scope}`.
   - Query Parameters (must be sorted alphabetically by key):
     - `X-Amz-Algorithm`: `AWS4-HMAC-SHA256`
     - `X-Amz-Credential`: `${this.#config.accessKeyId}/${scope}`
     - `X-Amz-Date`: `amzDate`
     - `X-Amz-Expires`: `${ttlInSeconds}`
     - `X-Amz-SignedHeaders`: `host`
     - If `sessionToken` present in `#config`: `X-Amz-Security-Token`: `${this.#config.sessionToken}`
   - Canonical Query String: Key-value pairs URL-encoded with `encodeS3Component` and sorted lexicographically by parameter name.
   - Canonical Headers: `host:${this.#host}\n`.
   - Signed Headers: `host`.
   - Payload Hash for Presigned GET requests: `UNSIGNED-PAYLOAD`.
   - Canonical Request:
     ```
     GET
     <canonicalUri>
     <canonicalQueryStringWithoutSignature>
     host:<host>

     host
     UNSIGNED-PAYLOAD
     ```
   - String to Sign:
     ```
     AWS4-HMAC-SHA256
     <amzDate>
     <scope>
     <sha256Hex(canonicalRequest)>
     ```
   - SigV4 HMAC Calculation:
     - `kDate = hmac("AWS4" + secretAccessKey, dateStamp)`
     - `kRegion = hmac(kDate, region)`
     - `kService = hmac(kRegion, "s3")`
     - `kSigning = hmac(kService, "aws4_request")`
     - `signature = hmac(kSigning, stringToSign).toString('hex')`
   - Final URL: Return `${this.#base}${canonicalUri}?${canonicalQueryStringWithoutSignature}&X-Amz-Signature=${signature}`.

---

### Step 2: Presigning Capability in `PostgresArtifactStore`

**File**: `factory/src/adapters/artifact/postgres-artifact-store.ts`

1. **Extend `ArtifactBlobClient` Interface**:
   Add optional `getSignedUrl` method to `ArtifactBlobClient`:
   ```ts
   export interface ArtifactBlobClient {
     // ... existing methods ...
     getSignedUrl?(key: string, options?: { expiresInSeconds?: number; now?: Date }): string
   }
   ```

2. **Add `getSignedUrl` Method to `PostgresArtifactStore`**:
   ```ts
   async getSignedUrl(
     artifactId: string,
     options?: { expiresInSeconds?: number; now?: Date }
   ): Promise<string | null>
   ```
   Implementation logic:
   - Check if underlying blob client supports `getSignedUrl`. If not, return `null` (or throw if client cannot presign, but returning `null` or checking capability cleanly is ideal; task requires clean return `null` if artifact not available / non-existent).
   - Authoritative Metadata Lookup: Call `this.#repository.getMetadataAndStorageKey(artifactId)`. If returns `null`, return `null`.
   - Status Check & Refresh: Refresh metadata using `refreshArtifactMetadata(record.metadata, now)`.
   - Availability Guard: If `metadata.availabilityStatus === 'purged'` (or not available), return `null`.
   - Presign Execution: If blob client has `getSignedUrl`, call `this.#client.getSignedUrl(record.storageKey, options)`. Return the generated presigned URL string.

---

### Step 3: Comprehensive Standalone Offline Tests

**File**: `factory/tests/test-artifact-signed-urls.mjs`

Create new standalone Node test script matching the project harness style (offline, no external network, `process.exit(1)` on failure).

1. **Fake Blob Client Mock (`FakePresigningS3ObjectClient`)**:
   - Implement `FakePresigningS3ObjectClient` (or extend `FakeS3ObjectClient` with `getSignedUrl` / `S3ObjectClient.prototype.getSignedUrl`).
   - Test both `S3ObjectClient` directly AND `PostgresArtifactStore` using both `S3ObjectClient` / mock.

2. **Test Cases to Implement**:
   - **Case A: `S3ObjectClient.getSignedUrl` Unit & Format Verification**:
     - Presigns GET URL with correct host, path, and query params (`X-Amz-Algorithm`, `X-Amz-Credential`, `X-Amz-Date`, `X-Amz-Expires=900`, `X-Amz-SignedHeaders=host`, `X-Amz-Signature`).
     - Includes `X-Amz-Security-Token` when session token is provided.
     - Payload hash matches `UNSIGNED-PAYLOAD`.
   - **Case B: Configurable TTL and Environment Variable `ARTIFACT_SIGNED_URL_TTL`**:
     - Custom `expiresInSeconds: 3600` option produces `X-Amz-Expires=3600`.
     - Environment variable `ARTIFACT_SIGNED_URL_TTL=1800` produces `X-Amz-Expires=1800` when no option is passed.
     - Default TTL (900s) used when neither option nor env var is provided.
   - **Case C: `PostgresArtifactStore.getSignedUrl` Governance & Availability**:
     - Returns valid signed URL string when artifact exists and is `available`.
     - Returns `null` when artifact ID does not exist in PostgreSQL metadata repository.
     - Returns `null` when artifact `availabilityStatus === 'purged'` (e.g., after retention expiry & purge).
     - Respects `options.expiresInSeconds` passed through to blob client.

---

## Verification Plan

### Execution Command
Run offline node tests:
```bash
node factory/tests/test-artifact-store.mjs
node factory/tests/test-artifact-metadata-postgres.mjs
node factory/tests/test-artifact-signed-urls.mjs
```

### Checks
1. Ensure all 3 test scripts exit with status 0.
2. Confirm no forbidden files were touched (`git status`). Specifically verify untouched:
   - `factory/src/ports/artifact/artifact-store.ts`
   - `factory/src/adapters/persistence/sql/sql-artifact-metadata-repository.ts`
   - `factory/src/adapters/artifact/index.ts`
   - `agentos/**`
   - DB migrations
