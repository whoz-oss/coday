# Presigned URLs for readable artifacts

## What changed

B5-T2a adds offline SigV4 query-parameter presigning for artifact payloads stored in S3/MinIO, while keeping PostgreSQL metadata authoritative. A readable artifact can now be exposed through a temporary GET URL without a storage round trip. Unknown artifacts and artifacts whose metadata is no longer `available` (including purged artifacts) yield `null`.

## Implementation

- `factory/src/adapters/artifact/s3-object-client.ts`
  - Adds `S3ObjectClient.getSignedUrl(key, options?)`, which computes an AWS SigV4 GET URL locally.
  - The canonical request signs `host` and uses `UNSIGNED-PAYLOAD`; it includes the standard `X-Amz-*` query parameters and an `X-Amz-Security-Token` when configured.
  - TTL precedence is `options.expiresInSeconds`, then the `ARTIFACT_SIGNED_URL_TTL` environment variable, then the 900-second default. The optional `now` value makes generated signatures deterministic in tests.
  - Exports the TTL environment-name and default constants.

- `factory/src/adapters/artifact/postgres-artifact-store.ts`
  - Extends the optional `ArtifactBlobClient` capability with `getSignedUrl`.
  - Adds `PostgresArtifactStore.getSignedUrl(artifactId, options?)`. It calls `getMetadataAndStorageKey`, checks refreshed metadata for `availabilityStatus === 'available'`, and passes the repository’s storage key and options to the blob client. It returns `null` for missing/unavailable metadata or clients without presigning support.

- `factory/runtime/factory-operational.mjs`
  - Carries the corresponding runtime implementation used by the standalone Node tests, including TTL resolution, SigV4 signing, and store-level availability checks.

- `factory/tests/test-artifact-signed-urls.mjs`
  - Adds a standalone, network-free Node test script. It checks URL shape and parameters, independently verifies signatures (including object-key encoding and session tokens), exercises explicit/env/default TTL behavior, and tests available, missing, purged, option-forwarding, and non-presigning-client cases.

- `specs/5262a4f2_presigned_s3_artifact_urls.md`
  - Records the B5-T2a implementation and verification plan, including the signing inputs and acceptance checks.

## Use and verification

Call the store method with an artifact ID:

```ts
const url = await artifactStore.getSignedUrl(artifactId, { expiresInSeconds: 3600 })
```

Omit the option to use `ARTIFACT_SIGNED_URL_TTL`, or the 900-second default. The low-level client also accepts `now` for deterministic callers/tests; it is not needed for normal use.

Run the offline checks from the repository root:

```bash
node factory/tests/test-artifact-store.mjs
node factory/tests/test-artifact-metadata-postgres.mjs
node factory/tests/test-artifact-signed-urls.mjs
```

The new script uses an in-memory SQL client and fake blob client, so it does not require S3/MinIO or a database server.
