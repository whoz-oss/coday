# Plan: Implement AgentOsMembershipResolver (Milestone B, Wave B6, Task B6-T1)

## Goal
Implement `AgentOsMembershipResolver` in `factory/src/domain/identity/agentos-membership-resolver.ts`, re-export it via `factory/src/domain/identity/index.ts`, and create comprehensive offline tests in `factory/tests/test-agentos-membership-resolver.mjs`.

The resolver adapts AgentOS directory information (groups, roles, namespace) into Factory `MembershipInfo` (`organizationId`, `workstreamId`, `squadId`, `roles`) with in-memory TTL caching, instant/sync cache resolution, principal-type awareness (`service` isolation), and fail-closed security guarantees.

---

## Technical Context & Scope Boundaries

### Key File Locations
- Port interface: `factory/src/domain/identity/membership-resolver.ts` (`MembershipResolver`, `MembershipInfo`, `EMPTY_MEMBERSHIP`, `resolveMembershipSync`).
- Domain Identity barrel: `factory/src/domain/identity/index.ts`.
- Implementation file: `factory/src/domain/identity/agentos-membership-resolver.ts`.
- Offline test file: `factory/tests/test-agentos-membership-resolver.mjs`.

### Strict Boundaries
- DO NOT modify `extractTrustContext`/`http-utils.mjs` beyond trivial optional re-exports if needed (not needed for B6-T1).
- DO NOT modify `checkAdminAuthorization`.
- DO NOT touch DB migrations or bundle files manually.
- DO NOT touch Kotlin code in `agentos/`.
- Run tests using `node factory/tests/test-agentos-membership-resolver.mjs`.

---

## Detailed Requirements & Design

### 1. Injected Directory Client Interface (`AgentOsDirectoryClient`)
Define in `factory/src/domain/identity/agentos-membership-resolver.ts`:
```ts
export interface AgentOsDirectoryUser {
  principalId: string
  roles?: string[]
  groups?: string[]
  namespaceId?: string
  namespaceExternalId?: string
}

export interface AgentOsDirectoryClient {
  getDirectoryUser(principalId: string): Promise<AgentOsDirectoryUser | null | undefined> | AgentOsDirectoryUser | null | undefined
}
```
- The directory client is injected via constructor options.
- No network calls are hardcoded in `AgentOsMembershipResolver`.
- Tests will provide a fake in-memory `AgentOsDirectoryClient`.

### 2. Constructor Options (`AgentOsMembershipResolverOptions`)
```ts
export interface AgentOsMembershipResolverOptions {
  directoryClient: AgentOsDirectoryClient
  organizationId?: string // Defaults to process.env.FACTORY_ORGANIZATION_ID || 'org-local-dev'
  cacheTtlMs?: number     // Defaults to process.env.FACTORY_MEMBERSHIP_CACHE_TTL_MS || 60000
}
```

### 3. Mapping Rules & Behavioral Logic
- **Principal Identifier**: `principalId` is an email or external ID string.
- **Service Principal Handling (`principalType === 'service'`)**:
  - Services MUST NEVER be granted `admin` role.
  - Returns `roles: ['service-runner']`.
  - `organizationId`: configured org (e.g., `'org-local-dev'`).
  - `workstreamId`: `null`.
  - `squadId`: `null`.
- **Namespace Projection**:
  - `workstreamId` is projected from `namespaceId` or `namespaceExternalId` (e.g., `user.namespaceId ?? user.namespaceExternalId ?? null`).
- **Organization ID**:
  - Taken from constructor option `organizationId` or `FACTORY_ORGANIZATION_ID` env var, defaulting to `'org-local-dev'`.
- **Squad ID**:
  - Always strictly `null`.
- **Role Derivation & Normalization**:
  - Upper/lowercase normalization: AgentOS roles (e.g. `['ADMIN']`, `['MEMBER']`, `['ADMIN', 'MEMBER']`) or groups/roles strings are normalized to lower-case trimmed strings.
  - AgentOS `ADMIN` -> Factory `'admin'`.
  - AgentOS `MEMBER` -> Factory `'dev'` (normalized: `'member'` or `'dev'` mapped to `'dev'`).
  - Other roles (lowercase): e.g. `'developer'` -> `'dev'`.
  - Deduplicate roles.
  - If no explicit roles found, or unknown roles present, map appropriately:
    * `ADMIN` (case-insensitive) -> `'admin'`
    * `MEMBER` (case-insensitive) -> `'dev'`
    * Any role matching `admin` -> `'admin'`
    * Any role matching `member`, `dev`, `developer` -> `'dev'`
    * Drop unrecognized roles or map to lowercase string.

### 4. Caching & TTL Invalidation Mechanics
- Store cache entries in memory:
  ```ts
  interface CacheEntry {
    membership: MembershipInfo
    expiresAt: number
  }
  ```
- **Sync vs Async Resolution**:
  - `resolveMembership(principalId: string | null, principalType: PrincipalType): MembershipInfo | Promise<MembershipInfo>`
  - When `principalId` is null/empty: returns `EMPTY_MEMBERSHIP` synchronously.
  - If cached and `Date.now() < cacheEntry.expiresAt`: return cached `MembershipInfo` synchronously (non-thenable object). This complies seamlessly with `resolveMembershipSync`.
  - If not cached or expired:
    - If `directoryClient.getDirectoryUser` returns a Promise, fetch asynchronously, construct `MembershipInfo`, cache it, and return Promise.
    - If `directoryClient.getDirectoryUser` returns synchronously (non-promise), process and cache synchronously, returning `MembershipInfo`.
  - Expose explicit cache management helper methods:
    * `primeCache(principalId: string, membership: MembershipInfo, ttlMs?: number): void`
    * `clearCache(principalId?: string): void`
    * `getCachedMembership(principalId: string): MembershipInfo | null`

### 5. Fail-Closed Guarantee
- If `directoryClient` throws an exception, returns `null`/`undefined`, or returns invalid structure:
  - Return `EMPTY_MEMBERSHIP` (`{ organizationId: null, workstreamId: null, squadId: null, roles: [] }`).
  - Do NOT throw exceptions from `resolveMembership`.

---

## Proposed Implementation Plan

### Step 1: Create `factory/src/domain/identity/agentos-membership-resolver.ts`
Implement `AgentOsMembershipResolver` with:
- Interfaces: `AgentOsDirectoryUser`, `AgentOsDirectoryClient`, `AgentOsMembershipResolverOptions`.
- Class `AgentOsMembershipResolver` implementing `MembershipResolver`.
- Logic for cache hit/miss, fail-closed try/catch, service principal isolation, role mapping (`ADMIN` -> `admin`, `MEMBER` -> `dev`), namespace -> `workstreamId` projection.

### Step 2: Update `factory/src/domain/identity/index.ts`
Re-export everything from `./agentos-membership-resolver.ts`:
```ts
export * from './agentos-membership-resolver.ts'
```

### Step 3: Create Offline Test Suite `factory/tests/test-agentos-membership-resolver.mjs`
Structure test suite using Node.js standard assertions (`node:assert/strict`) matching other `factory/tests/test-*.mjs` files.
Scenarios to cover:
1. Role derivation: `ADMIN` -> `'admin'`, `MEMBER` -> `'dev'`, casing normalization (`AdMiN` -> `'admin'`).
2. Namespace projection: `namespaceId` / `namespaceExternalId` -> `workstreamId`.
3. Configured `organizationId` (constructor override and default).
4. Service principal handling (`principalType === 'service'`): preserves `service-runner`, `squadId` is null, never admin even if directory returns `ADMIN`.
5. Cache hit (instant synchronous return compatible with `resolveMembershipSync`) and TTL expiration (fetches fresh after TTL passes).
6. Fail-closed handling:
   - Directory client throws error -> returns `EMPTY_MEMBERSHIP`.
   - Unknown principal / client returns `null` -> returns `EMPTY_MEMBERSHIP`.
   - Invalid directory response -> returns `EMPTY_MEMBERSHIP`.
7. Cache management helpers (`primeCache`, `clearCache`).

### Step 4: Verification
Execute:
`node factory/tests/test-agentos-membership-resolver.mjs`
And run existing identity test suite to ensure no regression:
`node factory/tests/test-identity-trust-context.mjs`

---

## Verification Commands
- Primary check: `node factory/tests/test-agentos-membership-resolver.mjs`
- Regression check: `node factory/tests/test-identity-trust-context.mjs`
