# B6-T3 — Tenant scoping and entitlement authorization

## What changed

The Factory now has shared, fail-closed identity primitives for tenant scope and admin entitlement decisions:

- `factory/src/domain/identity/tenant-scope.ts` resolves the composite `{ organizationId, workstreamId }` only from a verified `TrustContext`. Missing, blank, malformed, or anonymous contexts produce no scope; `requireTenantScope` raises `TENANT_SCOPE_REQUIRED`. It also provides canonical scope comparison and cache-key helpers.
- `factory/src/domain/identity/entitlements.ts` normalizes AgentOS roles (`ADMIN` → `admin`, `MEMBER` → `dev`) and scopes, then resolves admin access without consulting client headers. Anonymous contexts have no privilege, and an optional target namespace must match the principal’s verified organization/workstream. Missing or insufficient context fails closed.
- `factory/src/domain/identity/index.ts` exports both new modules.
- `factory/dashboard/http-utils.mjs` keeps the existing `checkAdminAuthorization` and `requireAdminRole` signatures, but `checkAdminAuthorization` now delegates to the real entitlement resolver. Existing artifact governance routes therefore use the resolved trust context rather than the former role-check seam.

The SQL capability-token lookup in `factory/src/adapters/persistence/sql/sql-agent-step-result-repository.ts` now filters by both `organization_id` and `workstream_id`; the same change is present in the generated `factory/runtime/factory-operational.mjs` bundle. A token issued in another workstream cannot be redeemed through that repository.

## Isolation and authorization coverage

`factory/tests/test-tenant-isolation.mjs` is an offline suite using an in-memory SQL client, fake membership resolution, and fake artifact/blob stores. It covers:

- fail-closed tenant-scope resolution;
- cross-workstream and cross-organization reads and writes for workflow instances, evidence, work units, result capabilities, and artifact metadata;
- AgentOS role mapping and anonymous/member rejection;
- namespace-scoped admin checks, including rejection of an admin targeting another workstream;
- rejection of forged `x-organization-id`, `x-workstream-id`, and `x-roles` headers;
- artifact legal-hold, purge, and garbage-collection commands: anonymous/non-admin callers receive `403 FORBIDDEN_ADMIN_REQUIRED`, while verified admin and loopback-dev contexts are accepted.

## Verification

Run the new isolation suite and the related existing offline suites with Node:

```sh
node factory/tests/test-tenant-isolation.mjs
node factory/tests/test-boundary-hardening.mjs
node factory/tests/test-coday-identity-bridge.mjs
node factory/tests/test-agentos-membership-resolver.mjs
node factory/tests/test-artifact-admin-commands.mjs
node factory/tests/test-identity-trust-context.mjs
node factory/tests/test-sql-repository-ports-adapters.mjs
```

If TypeScript identity or repository sources are changed again, regenerate the runtime bundle through the toolchain rather than editing it directly:

```sh
node factory/toolchain/build.mjs
```

The implementation plan and verification checklist are recorded in `specs/46d682bd_tenant_scoping_and_entitlements.md`.
