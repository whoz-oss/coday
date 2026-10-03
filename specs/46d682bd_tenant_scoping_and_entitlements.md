# Plan B6-T3: Tenant Scoping and Entitlements

## Overview

The objective of B6-T3 is to make multi-tenant scoping (`organizationId` and `workstreamId`) and entitlement authorization **EFFECTIVE end-to-end** across the Factory codebase without breaking existing public signatures or call sites.

Every command/query in repositories and use cases must be scoped by `organizationId` and `workstreamId` obtained strictly from the verified `TrustContext`. Client headers, implicit defaults, or unauthenticated fallbacks must never be trusted for tenant scope or authorization decisions. Cross-workstream isolation must be strictly enforced and tested (read and write rejection).

Additionally, the seam `checkAdminAuthorization` in `factory/dashboard/http-utils.mjs` (established in B5-T2b) must be replaced with real entitlement resolution backed by AgentOS membership roles and scopes, mapping AgentOS `ADMIN` -> `admin` and `MEMBER` -> `dev`.

---

## 1. Domain & Entitlement Resolution Hardening

### 1.1 `checkAdminAuthorization` Body Replacement in `factory/dashboard/http-utils.mjs`
- **Keep signatures intact**: `checkAdminAuthorization(trustContext)` and `requireAdminRole(trustContext)`.
- **Implement Real Entitlement Resolution**:
  - `principalId` is the principal's identity (e.g. email).
  - Inspect `trustContext` fields (`authenticationMethod`, `principalId`, `organizationId`, `workstreamId`, `roles`, `scopes`).
  - Loopback-dev / wildcard principals (`scopes.includes('*')`) or principals with explicit `admin:*` scope or `admin` role in the verified `trustContext.roles` continue to pass.
  - Fail-closed: missing `trustContext`, unauthenticated/anonymous (`authenticationMethod === 'anonymous'`), or missing admin role/scope returns `{ authorized: false, reason: 'FORBIDDEN_ADMIN_REQUIRED' }` (or specific fail reason).
  - Verify that no client headers (`x-organization-id`, `x-workstream-id`, `x-roles`) are read inside authorization checks or `extractTrustContext`.
  - Ensure namespace-scoped admin logic: an admin in one workstream/namespace is NOT admin of another workstream/namespace if checking workstream-specific operations.
- **Support Route Enforcement**:
  - Unauthenticated or non-admin principals calling artifact admin endpoints (`/api/factory/admin/artifacts/*`) are rejected with `403 FORBIDDEN_ADMIN_REQUIRED`.

### 1.2 `TrustContext` & Boundary Guard Validation
- Ensure `extractTrustContext` in `factory/dashboard/http-utils.mjs` populates `organizationId` and `workstreamId` strictly from server-side `MembershipResolver` (or Fake IdP claims) and NEVER reads `x-organization-id`, `x-workstream-id`, or `x-roles` client headers.
- Audit all dashboard routes (`workflow-projection-routes`, `workflow-evidence-routes`, `delivery-operation-routes`, `workstream-routes`, `agent-step-result-routes`, `artifact-admin-routes`, `run-routes`, etc.) to guarantee they extract `organizationId` and `workstreamId` from `TrustContext` (or HTTP identity context derived from `TrustContext`) and thread them into repositories and use cases.

---

## 2. Systematic Repository & Use Case Tenant Scoping

### 2.1 Audit SQL Repositories
Review all SQL repository adapters in `factory/src/adapters/persistence/sql/` to verify every `SELECT`, `INSERT`, `UPDATE`, and `DELETE` query filters or injects `organization_id` and `workstream_id`:
1. `sql-workflow-instance-repository.ts`: Validate that `save`, `findByWorkflowId`, `findByStatus`, `listWorkflows`, `updateStatus` all enforce `organization_id = $1 AND workstream_id = $2`.
2. `sql-agent-step-attempt-repository.ts`: Validate queries filter by `organization_id` and `workstream_id`.
3. `sql-agent-step-result-repository.ts`: Verify `save`, `findLatest`, `listByStep` filter by `organization_id` and `workstream_id`. Note: `#findByToken` scans capability tokens; check if token resolution verifies or requires tenant scoping or capability validation against tenant scope.
4. `sql-artifact-metadata-repository.ts`: Verify queries filter by `organization_id` and `workstream_id`.
5. `sql-delivery-repository.ts`: Verify queries filter by `organization_id` and `workstream_id`.
6. `sql-lease-repository.ts`: Verify lease acquisition, release, heartbeat filter by `organization_id` and `workstream_id`.
7. `sql-oracle-execution-repository.ts`: Verify queries filter by `organization_id` and `workstream_id`.
8. `sql-work-environment-repository.ts`: Verify queries filter by `organization_id` and `workstream_id`.
9. `sql-work-unit-repository.ts`: Verify queries filter by `organization_id` and `workstream_id`.
10. `sql-worker-repository.ts`: Verify queries filter by `organization_id` (and `workstream_id` if applicable).
11. `sql-workflow-evidence-repository.ts`: Verify queries filter by `organization_id` and `workstream_id`.
12. `sql-workflow-human-interaction-repository.ts`: Verify queries filter by `organization_id` and `workstream_id`.

### 2.2 Filesystem / In-Memory / Staging Adapters Scoping
- Inspect `filesystem-*` and memory adapters to ensure tenant isolation is respected when instantiating repositories with tenant scoping parameters (`{ organizationId, workstreamId }`).
- Ensure use cases and application services (`delivery-controller`, `work-unit-environment-service`, `artifact-admin-use-cases`, etc.) accept or inherit the tenant scope from `TrustContext`.

---

## 3. Inter-Workstream Isolation & Cross-Tenant Rejection Tests

### 3.1 New Test Suite: `factory/tests/test-tenant-isolation.mjs`
Create an explicit, comprehensive offline test suite covering:
1. **Cross-Workstream Read Rejection**:
   - Save entity (workflow instance, evidence, delivery, artifact, interaction, lease) under `(orgA, wsA)`.
   - Query using repository or HTTP route scoped to `(orgA, wsB)`.
   - Assert returned value is `null`, empty list, or 404/403. Access MUST be rejected/invisible.
2. **Cross-Workstream Write Rejection**:
   - Attempt to update or overwrite entity belonging to `(orgA, wsA)` using a handle/repository/route scoped to `(orgA, wsB)`.
   - Assert write is rejected or creates no side-effect on `(orgA, wsA)`'s entity.
3. **Cross-Organization Isolation**:
   - Assert identical isolation across different `organizationId` values `(orgA, wsA)` vs `(orgB, wsA)`.
4. **Namespace vs Workstream Admin Entitlements**:
   - Principal with `ADMIN` role in `workstream-1` attempting admin or write operations on `workstream-2`.
   - Verify entitlement resolution denies cross-workstream admin access.
5. **Client Header Tampering Prevention**:
   - Send HTTP request with `x-organization-id: org-evil` or `x-workstream-id: ws-evil` or `x-roles: admin` without a valid signed JWT / proxy signature.
   - Verify `extractTrustContext` discards client headers and defaults to safe anonymous / authenticated scope.
6. **Artifact Admin Endpoints**:
   - Test purge, legal-hold, and GC routes under valid admin, non-admin (dev/member), and cross-namespace admin contexts. Confirm non-admin yields `403 FORBIDDEN_ADMIN_REQUIRED`.

### 3.2 Regression Verification for Existing Identity & Admin Tests
Update and run existing test files to ensure no regressions:
- `factory/tests/test-boundary-hardening.mjs`
- `factory/tests/test-coday-identity-bridge.mjs`
- `factory/tests/test-agentos-membership-resolver.mjs`
- `factory/tests/test-artifact-admin-commands.mjs`

---

## 4. Exports, Toolchain Build & Documentation Updates

### 4.1 Export Barrels
- Audit export barrels (`factory/src/entrypoints/factory-operational.ts`, `factory/src/domain/identity/index.ts`, `factory/dashboard/http-utils.mjs`) to ensure all updated identity / entitlement types and helper functions are cleanly re-exported.

### 4.2 Toolchain Build
- Run `node factory/toolchain/build.mjs` **ONCE** to regenerate `factory/runtime/factory-operational.mjs`.
- **CRITICAL**: Never edit `factory/runtime/factory-operational.mjs` directly.

### 4.3 Documentation Updates
- Update `app_docs/` or `factory/` architecture/security docs as appropriate for B6-T3 tenant scoping & entitlement enforcement guidelines.

---

## 5. Verification Plan

1. **Run New Offline Isolation Tests**:
   - `node factory/tests/test-tenant-isolation.mjs`
2. **Run All Related Identity & Hardening Tests**:
   - `node factory/tests/test-boundary-hardening.mjs`
   - `node factory/tests/test-coday-identity-bridge.mjs`
   - `node factory/tests/test-agentos-membership-resolver.mjs`
   - `node factory/tests/test-artifact-admin-commands.mjs`
   - `node factory/tests/test-sql-repository-ports-adapters.mjs`
3. **Run Full Test Suite Offline**:
   - Run affected tests via Nx or test scripts.
