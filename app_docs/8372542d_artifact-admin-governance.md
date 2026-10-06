# Artifact admin governance (B5-T2b)

## What changed

Factory artifact governance now has three explicit, operator-triggered admin use cases:

- `purgeArtifactAdmin` only purges an existing artifact after retention has expired and refuses missing artifacts, active retention, or an active legal hold. It returns a structured result and re-reads metadata after the store operation to classify concurrent refusals.
- `setLegalHoldAdmin` delegates explicit placement/removal of a legal hold and raises `ArtifactAdminError` with `ARTIFACT_NOT_FOUND` for an unknown artifact.
- `collectAndAuditGarbage` reclaims staging uploads, scans `objects/`, and compares object keys with an injected authoritative metadata listing. Its report includes reclaimed keys, scanned keys/row count, an ISO timestamp, and `blob_without_pg_row` or `pg_purged_or_missing_blob` anomalies. It does not introduce timers or automatic purging.

The frozen artifact store and SQL metadata contracts remain unchanged. Metadata listing is supplied through the GC options because those contracts do not expose a listing operation. Staging reclamation uses the store’s `collectOrphanedUploads()` capability when present, with an object-listing/deletion fallback.

## Authorization and HTTP surface

`checkAdminAuthorization` and `requireAdminRole` in `factory/dashboard/http-utils.mjs` form the explicit, replaceable B6 authorization seam. They inspect the resolved `TrustContext`, accepting `admin`, `admin:*`, or `*` entitlements and returning/throwing a stable 403 `FORBIDDEN_ADMIN_REQUIRED` contract otherwise; they do not implement an IdP.

`factory/dashboard/artifact-admin-routes.mjs` handles POST requests for:

- `/api/factory/admin/artifacts/:artifactId/purge`
- `/api/factory/admin/artifacts/:artifactId/legal-hold`
- `/api/factory/admin/artifacts/gc`

Every matching command invokes `requireAdminRole` before reading the body or calling a use case. The handler injects the store/blob client, translates use-case outcomes into HTTP responses, validates the legal-hold body, and returns 405 for non-POST requests. The use cases are exported directly from `factory/src/entrypoints/factory-operational.ts`; the generated runtime and `factory/lib/artifact-admin-use-cases.mjs` expose the same functions for the offline/dashboard JS path.

## Verification

Run the focused offline suite:

```sh
node factory/tests/test-artifact-admin-commands.mjs
```

It covers expired and refused purges, legal-hold lifecycle, both staging-reclamation paths, both anomaly types, report shape, and authorization for purge/legal-hold/GC routes. The TypeScript implementation is in `factory/src/application/artifact/artifact-admin-use-cases.ts`, while the checked-in generated runtime is `factory/runtime/factory-operational.mjs`.
