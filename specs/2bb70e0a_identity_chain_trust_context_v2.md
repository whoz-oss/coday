# Specification: Identity Chain & TrustContext Enrichment (Local Dev / Fake IdP)

## 1. Overview & Goals

Milestone B, Wave B1, Task T3 requires establishing a secure identity chain at the Factory HTTP boundary and enriching the `TrustContext` for local development using a fake Identity Provider (Fake IdP).

### Key Architectural Objectives
1. **Rich Trust Context Structure**: Extend the runtime `TrustContext` with structured identity fields (`principalId`, `principalType`, `organizationId`, `workstreamId`, `squadId`, `roles`, `scopes`, `correlationId`, `authenticationMethod`, `serviceIdentityId`).
2. **Impersonation Guard**: Disallow impersonation by default (`impersonatedBy` and `delegationChain` strictly `null`/`undefined`).
3. **HTTP Boundary Extraction**: Construct the `TrustContext` exactly once at the HTTP edge (`extractTrustContext` in `http-utils.mjs` and used in `composition-root.mjs`) and propagate it down to request handlers.
4. **Identity Verification & Local Fake IdP**: Validate inbound JWT OIDC tokens and signed proxy headers using HMAC/RSA signatures via a lightweight `FakeIdP` module (`factory/src/domain/identity/fake-idp.ts`).
5. **Loopback & Forgery Prevention**: Unsigned identity headers MUST be rejected and ignored. In unauthenticated local dev / fallback mode without valid signed headers or JWTs, assign the `loopback-dev` or `anonymous` authentication method.
6. **Service Identity Credentials**: Support short-lived service credentials with explicit `audience`, `scopes`, and `principalType: 'service'`.
7. **Server-Side Membership Resolution**: Prevent client-side forged membership headers. Derive `workstreamId`, `squadId`, `roles`, and `organizationId` strictly server-side using a `MembershipResolver` (`MockMembershipResolver` / `LocalDevMembershipResolver`).
8. **Backward Compatibility**: Preserve existing legacy `extractTrustContext` properties (`namespaceId`, `caseId`, `actorId`, `authorityId`, `runtimeId`, `agentId`, `threadId`, `trustMode`, `loopback`) so existing dashboard routes and tests remain unaffected.

---

## 2. File Scope & Modification Rules

### Allowed File Paths (Strictly Enforced)
- **New Domain Module & Types**:
  - `factory/src/domain/identity/trust-context.ts` (Types & constructors/validators)
  - `factory/src/domain/identity/fake-idp.ts` (Fake IdP, token issuer, proxy signer)
  - `factory/src/domain/identity/membership-resolver.ts` (MembershipResolver interface & Mock/LocalDev implementations)
  - `factory/src/domain/identity/index.ts` (Re-exports)
- **Dashboard & Transport Layer**:
  - `factory/dashboard/http-utils.mjs` (Enrich `extractTrustContext` & helper integration)
  - `factory/dashboard/composition-root.mjs` (Wire `MembershipResolver` / `FakeIdP` into request pipeline)
- **Test File**:
  - `factory/tests/test-identity-trust-context.mjs` (Comprehensive identity chain unit/integration tests)

### Forbidden Paths
- Database / SQL persistence adapters
- Object storage modules
- `agentos/**` framework files
- Any other repository files outside the authorized scope

---

## 3. Domain Design (`factory/src/domain/identity/`)

### 3.1. `trust-context.ts`

Define core domain types for identity and trust context.

```typescript
export type PrincipalType = 'human' | 'service'
export type AuthenticationMethod = 'jwt' | 'proxy-signature' | 'loopback-dev' | 'anonymous'

export interface TrustContext {
  // Legacy fields (backward compatibility for existing dashboard routes)
  namespaceId: string | null
  caseId: string | null
  actorId: string | null
  authorityId: string | null
  runtimeId: string | null
  agentId: string | null
  threadId: string | null
  trustMode: string
  loopback: boolean

  // Enriched Identity fields
  principalId: string | null
  principalType: PrincipalType
  organizationId: string | null
  workstreamId: string | null
  squadId: string | null
  roles: string[]
  scopes: string[]
  correlationId: string | null
  authenticationMethod: AuthenticationMethod
  serviceIdentityId: string | null

  // Impersonation / Delegation guard (strictly null by default)
  impersonatedBy: string | null
  delegationChain: string[] | null
}
```

Include pure functions for creating default anonymous or local loopback contexts, and validating context structure.

### 3.2. `fake-idp.ts`

Lightweight, zero-external-dependency Identity Provider for local dev & testing.
- Uses Node.js standard `node:crypto` (HMAC SHA-256 / RSA or symmetric secret signature).
- **JWT Token Generation & Verification**:
  - `issueJwt(payload, secret?, expiresInSeconds?)`: Emits a standard compact JWT (`header.payload.signature`).
  - `verifyJwt(token, secret)`: Parses and verifies JWT expiration, signature, `aud`, `sub`, `scopes`, `principalType`.
- **Signed Proxy Header Generation & Verification**:
  - `signProxyHeaders(headersObject, secret)`: Generates `X-Proxy-Signature` over canonical header values (e.g. `x-proxy-principal-id`, `x-proxy-principal-type`, `x-proxy-timestamp`).
  - `verifyProxyHeaders(headers, secret)`: Validates signature and timestamp freshness (preventing replays).
- **Service Identity Tokens**:
  - Mint tokens with `principalType: 'service'`, `serviceIdentityId`, custom `scopes`, and target `audience`.

### 3.3. `membership-resolver.ts`

Interface and server-side resolvers for memberships.

```typescript
export interface MembershipInfo {
  organizationId: string | null
  workstreamId: string | null
  squadId: string | null
  roles: string[]
}

export interface MembershipResolver {
  resolveMembership(principalId: string, principalType: PrincipalType): Promise<MembershipInfo> | MembershipInfo
}

export class LocalDevMembershipResolver implements MembershipResolver {
  private mockDb: Map<string, MembershipInfo>

  constructor(initialData?: Record<string, MembershipInfo>) { ... }

  resolveMembership(principalId: string, principalType: PrincipalType): MembershipInfo {
    if (!principalId) {
      return { organizationId: null, workstreamId: null, squadId: null, roles: [] }
    }
    return this.mockDb.get(principalId) ?? {
      organizationId: 'org-local-dev',
      workstreamId: 'ws-default',
      squadId: null,
      roles: principalType === 'human' ? ['developer'] : ['service-runner'],
    }
  }
}
```

---

## 4. HTTP Boundary & Integration (`http-utils.mjs` & `composition-root.mjs`)

### 4.1. `http-utils.mjs` Extensions

1. Re-export or import identity primitives from `../src/domain/identity/index.ts` (or pure JS helper equivalents compiled/linked to domain logic).
2. Update `extractTrustContext(req, bindPolicy = {}, options = {})`:
   - Step 1: Extract `correlationId` using `resolveCorrelationId(req)`.
   - Step 2: Check for `Authorization: Bearer <jwt>`. If present and valid via `FakeIdP`, authenticate as `authenticationMethod: 'jwt'`.
   - Step 3: Check for signed proxy headers (`X-Proxy-Signature`, `X-Proxy-Principal-Id`, etc.). If valid via `FakeIdP.verifyProxyHeaders()`, authenticate as `authenticationMethod: 'proxy-signature'`.
   - Step 4: **Rejection of Unsigned Identity Headers**: Any header attempting to supply `x-proxy-*`, `x-organization-id`, `x-roles`, etc., WITHOUT a valid signature MUST BE IGNORED.
   - Step 5: Fallback handling:
     - If socket is loopback (`isLoopbackAddress(req.socket.remoteAddress)`): set `authenticationMethod: 'loopback-dev'`, default `principalId: req.headers['x-factory-actor-id'] || 'local-dev-user'`, `principalType: 'human'`.
     - Otherwise: `authenticationMethod: 'anonymous'`, `principalId: null`, `principalType: 'human'`.
   - Step 6: **Server-Side Membership Resolution**: Pass `principalId` and `principalType` to `membershipResolver.resolveMembership(...)` to populate `organizationId`, `workstreamId`, `squadId`, `roles`. Client-sent organization/role headers are ignored.
   - Step 7: Populate `scopes` (from JWT/proxy header if signed, else default local scopes like `['*']` for loopback-dev or `[]` for anonymous).
   - Step 8: Ensure `impersonatedBy: null` and `delegationChain: null`.
   - Step 9: Preserve all legacy return fields (`namespaceId`, `caseId`, `actorId`, `authorityId`, `runtimeId`, `agentId`, `threadId`, `trustMode`, `loopback`).

### 4.2. `composition-root.mjs` Wiring

1. Instantiate `LocalDevMembershipResolver` and `FakeIdP` instance/secret during `loadConfig` / `createAdapters`.
2. Pass resolver and `FakeIdP` options to `extractTrustContext(req, config.bindPolicy, { membershipResolver, fakeIdpSecret })` inside the request handler.

---

## 5. Verification & Test Strategy

### 5.1. Unit & Integration Test (`factory/tests/test-identity-trust-context.mjs`)

Create a runnable Node.js test runner (using `node:test` or custom runner matching `factory/tests/test-*.mjs` conventions) verifying:
1. **Valid JWT OIDC Handling**:
   - Issue JWT with `FakeIdP`.
   - Pass in `Authorization: Bearer ...` header.
   - Assert `authenticationMethod === 'jwt'`, correct `principalId`, `principalType`, `scopes`.
2. **Invalid / Tampered JWT Rejection**:
   - Pass expired or tampered JWT.
   - Assert context falls back to `loopback-dev` or `anonymous` without crashing, ignoring fake identity claims in the token.
3. **Signed Proxy Headers Handling**:
   - Sign proxy headers with `FakeIdP`.
   - Pass headers in request.
   - Assert `authenticationMethod === 'proxy-signature'`, claims accepted.
4. **Forged Header Rejection**:
   - Send unsigned claims in `X-Proxy-Principal-Id` or client headers.
   - Assert claims are IGNORED, no elevation of privileges, falls back to `loopback-dev` / `anonymous`.
5. **Principal Types (Human vs Service)**:
   - Test service identity tokens with `principalType: 'service'` and `serviceIdentityId`.
6. **Server-Side Membership Resolution**:
   - Verify `organizationId`, `workstreamId`, `squadId`, and `roles` are loaded via `MembershipResolver` and NOT read from untrusted client headers.
7. **Legacy Compatibility**:
   - Assert `namespaceId`, `caseId`, `actorId`, `trustMode`, `loopback` are present and correctly populated.
8. **Impersonation Disallowed**:
   - Assert `impersonatedBy === null` and `delegationChain === null`.

### 5.2. Existing Tests Guard Check
Run:
- `node factory/tests/test-composition-root-source.mjs`
- `node factory/tests/test-coday-config.mjs`
- `node factory/tests/test-factory-api.mjs`
- `node factory/tests/test-identity-trust-context.mjs`

---

## 6. Implementation Steps Summary

1. **Create TypeScript Domain Primitives**:
   - Write `factory/src/domain/identity/trust-context.ts`
   - Write `factory/src/domain/identity/fake-idp.ts`
   - Write `factory/src/domain/identity/membership-resolver.ts`
   - Write `factory/src/domain/identity/index.ts`
2. **Update Transport Primitives**:
   - In `factory/dashboard/http-utils.mjs`, update `extractTrustContext` to parse JWT / proxy signatures and query membership resolver.
3. **Wire Composition Root**:
   - In `factory/dashboard/composition-root.mjs`, initialize identity components and thread them to request execution.
4. **Build & Test**:
   - Create `factory/tests/test-identity-trust-context.mjs`.
   - Run Node test suites to verify.
