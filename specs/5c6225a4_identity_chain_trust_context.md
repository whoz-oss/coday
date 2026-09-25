# Architecture Plan — Milestone B, Wave B1, Task T3: Local Identity Chain & TrustContext Enrichment

## Objective
Establish a secure, extensible identity chain and enriched `TrustContext` at the Factory HTTP boundary (`factory/dashboard/` and `factory/src/domain/identity/`), backed by a dev/local Fake Identity Provider (Fake IdP).

## Scope
- Permitted paths:
  - `factory/dashboard/**` (`http-utils.mjs`, `composition-root.mjs`, `server.mjs`, route handlers)
  - `factory/src/domain/identity/**` (domain models, interfaces, validation, token verifiers)
  - `factory/src/application/identity/**` or `factory/src/adapters/identity/**` (Fake IdP, membership resolvers, proxy signature verifier)
  - `factory/src/entrypoints/factory-operational.ts` (re-export identity primitives)
  - `factory/lib/identity.mjs` (thin JS adapter if needed)
  - `factory/tests/**` (unit/integration test suite)
- Restricted paths (DO NOT TOUCH):
  - Persistence adapters / SQL
  - Object storage
  - `agentos/**`

---

## Technical Specifications & Invariants

### 1. Enriched `TrustContext` Model
The `TrustContext` object produced at the HTTP boundary must contain:
```ts
export interface TrustContext {
  principalId: string
  principalType: 'human' | 'service'
  organizationId: string
  workstreamId: string
  squadId?: string | null
  roles: string[]
  scopes: string[]
  correlationId: string
  authenticationMethod: 'jwt' | 'proxy-signature' | 'loopback-dev' | 'anonymous'
  serviceIdentityId?: string | null
  impersonatedBy?: string | null // Disabled by default, strict audit if present
  delegationChain?: string[] | null // Strictly typed, empty/null by default
  // Backward compatibility fields for legacy routes:
  namespaceId: string | null
  caseId: string | null
  actorId: string | null
  authorityId: string | null
  runtimeId: string | null
  agentId: string | null
  threadId: string | null
  trustMode: string
  loopback: boolean
}
```

### 2. Impersonation & Forged Header Protection
- Forging arbitrary unverified identity headers (e.g. sending raw `x-factory-actor-id` or unverified `x-user-id` without valid proxy signature or JWT) MUST BE REJECTED or downgraded to anonymous/loopback-dev.
- Unsigned identity headers are NEVER treated as authoritative for roles/memberships.

### 3. Server-Side Membership Resolution
- `workstreamId`, `squadId`, `roles`, `organizationId` MUST NOT be accepted directly from unverified client inputs as authority.
- The Factory resolves memberships on the server side based on `principalId` via a `MembershipResolver` interface:
```ts
export interface MembershipResolver {
  resolveMemberships(principalId: string, principalType: 'human' | 'service'): Promise<{
    organizationId: string
    workstreamId: string
    squadId?: string | null
    roles: string[]
    scopes: string[]
  }>
}
```
- Implement `LocalDevMembershipResolver` / `MockMembershipResolver` as default for local dev.

### 4. Authentication Verification Chain & Dev Fallback
1. **OIDC JWT Verification**: Validate Bearer JWT token (signature, issuer, audience, expiration). Extract `sub`, `aud`, `scope` / `roles`, `principalType`.
2. **Signed Proxy Headers Verification**: Validate HMAC/RS256 signature on proxy headers (`x-proxy-signature`, `x-proxy-timestamp`, `x-principal-id`). Reject if timestamp is stale, signature invalid, or missing proxy secret.
3. **Local/Dev Fallback (`loopback-dev`)**:
   - If request comes from loopback and no token/signed header is supplied: synthesize a safe dev `TrustContext` (`authenticationMethod: 'loopback-dev'`) to maintain backward compatibility for existing dashboard tests.
   - Forged proxy signatures or invalid JWTs must produce `401 UNAUTHENTICATED` or `403 FORBIDDEN` even on loopback.

### 5. Dev Fake Identity Provider (Fake IdP)
- Provide a light Fake IdP helper (`factory/src/adapters/identity/fake-idp.ts`) capable of:
  - Minting/signing mock OIDC JWTs with HMAC (HS256) or RSA (RS256) keys.
  - Generating valid signed proxy headers (`signProxyHeaders(principalId, secret, timestamp)`).
  - Serving test identities (`human` vs `service`).

---

## Detailed Implementation Steps

### Phase 1: Identity Domain & Infrastructure Modules (`factory/src/domain/identity/` & `factory/src/adapters/identity/`)

1. **`factory/src/domain/identity/trust-context.ts`**:
   - Define TypeScript interfaces: `TrustContext`, `PrincipalType`, `AuthenticationMethod`.
   - Implement `createTrustContext(params)` factory and validation helpers.

2. **`factory/src/domain/identity/membership-resolver.ts`**:
   - Define `MembershipResolver` interface.
   - Implement `LocalDevMembershipResolver` with deterministic defaults (`org-dev`, `ws-default`, `role-developer`).

3. **`factory/src/adapters/identity/jwt-verifier.ts`**:
   - Light JWT verification function (using `node:crypto`) checking standard claims (`sub`, `exp`, `iss`, `aud`, `signature`).

4. **`factory/src/adapters/identity/proxy-signature-verifier.ts`**:
   - Verify HMAC SHA-256 signatures for proxy headers.

5. **`factory/src/adapters/identity/fake-idp.ts`**:
   - Utility for minting test JWTs and proxy signed headers for dev & test suites.

6. **Re-export in `factory/src/entrypoints/factory-operational.ts`**:
   - Export identity types and functions so the bundled runtime and `factory/lib/` can consume them.

---

### Phase 2: HTTP Boundary Integration (`factory/dashboard/http-utils.mjs` & `composition-root.mjs`)

1. **Enrich `extractTrustContext` in `http-utils.mjs` / `identity-handler.mjs`**:
   - Parse Authorization header (`Bearer <jwt>`) or signed proxy headers (`X-Proxy-Signature`, `X-Proxy-Timestamp`, `X-Principal-Id`).
   - Run verification chain: JWT -> Signed Proxy -> Local Fallback (`loopback-dev` / `anonymous`).
   - Invoke `MembershipResolver` to resolve `organizationId`, `workstreamId`, `squadId`, `roles`, and `scopes`.
   - Populate enriched `TrustContext` while preserving legacy header mappings (`namespaceId`, `caseId`, `actorId`, etc.) for existing route compatibility.

2. **Cabling in `composition-root.mjs`**:
   - Wire `MembershipResolver`, `JwtVerifier`, `ProxySignatureVerifier` into `createCompositionRoot`.
   - Thread enriched `TrustContext` to downstream controllers and handlers.

---

### Phase 3: Testing & Verification (`factory/tests/`)

Create dedicated test files in `factory/tests/`:
1. `factory/tests/test-identity-jwt-verifier.mjs`:
   - Valid vs expired vs invalid signature vs wrong audience OIDC JWTs.
2. `factory/tests/test-identity-proxy-signature.mjs`:
   - Valid proxy headers signed with secret -> accepted (`authenticationMethod: 'proxy-signature'`).
   - Forged headers / invalid HMAC / expired timestamp -> rejected (`401/403`).
3. `factory/tests/test-identity-trust-context.mjs`:
   - Human (`principalType: 'human'`) vs Service (`principalType: 'service'`) distinction.
   - Membership resolution on server side.
   - Fallback `loopback-dev` when no auth provided on loopback.
   - Absolute rejection of unauthenticated impersonation headers.
4. Existing Test Suite Validation:
   - Ensure all existing Factory tests pass (`node factory/tests/test-*.mjs` or `ls factory/tests/test-*.mjs | xargs -n 1 node`).

---

## Verification Plan

Run the standalone Factory test suite:
```bash
# Rebuild operational runtime if TypeScript files modified
node factory/toolchain/build.mjs

# Run new and existing factory tests
node factory/tests/test-identity-jwt-verifier.mjs
node factory/tests/test-identity-proxy-signature.mjs
node factory/tests/test-identity-trust-context.mjs
node factory/tests/test-composition-root-source.mjs
```
