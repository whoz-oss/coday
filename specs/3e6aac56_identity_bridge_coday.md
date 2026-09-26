# Implementation Plan - B6-T2a: Identity Bridge (`coday-identity-bridge`)

## Overview
This task implements the identity bridge module `factory/src/domain/identity/coday-identity-bridge.ts` for the Factory Node/TS codebase. 
The bridge takes a Coday identity (user email resolved by proxy/auth layer) and issues a signed JWT using the shared Fake IdP secret (HMAC-SHA256). The resulting JWT can be verified by the unmodified `extractTrustContext` function in `factory/dashboard/http-utils.mjs`, setting `principalId = email`, `principalType` (defaulting to `'human'`), `scopes`, and `authenticationMethod = 'jwt'`.

## Strict Scope Boundaries (Do Not Touch)
- `factory/dashboard/http-utils.mjs` (specifically `extractTrustContext`, `checkAdminAuthorization`, `requireAdminRole`) MUST REMAIN UNTOUCHED.
- `factory/src/domain/identity/agentos-membership-resolver.ts` MUST REMAIN UNTOUCHED.
- `factory/runtime/factory-operational.mjs` MUST NOT BE REGENERATED.

## Key Decisions & Requirements
1. `principalId = email` (email is stable at Whoz).
2. Fail-closed principle: Any invalid, empty, or malformed email / parameters must result in failure (returns `null` or throws appropriate fail-closed error).
3. NEVER read unsigned client headers for authorization.
4. Future OIDC / JWKS migration path must be clearly documented in source file comments.
5. High quality TypeScript typing with explicit interfaces and JS export compatibility.

---

## User Review Required
> [!NOTE]
> No interactive user input required. All requirements and constraints are fully specified.

---

## Proposed Changes

### 1. Identity Bridge Module: `factory/src/domain/identity/coday-identity-bridge.ts`

Create `factory/src/domain/identity/coday-identity-bridge.ts` with explicit documentation comments explaining:
- How this HMAC-SHA256 Fake IdP bridge bridges Coday identity (user email resolved by proxy) into Factory JWTs.
- Future replacement path: Replacing `issueJwt` with a real OIDC / JWKS provider (e.g. Auth0, Keycloak, Entra ID) where token verification moves from HMAC secret to OIDC public key / JWKS validation.

#### Functions and Interfaces to Export:
- `interface CodayIdentityOptions`:
  - `email`: `string` (required user email address)
  - `scopes?`: `string[]` (optional scopes, defaults to `[]`)
  - `principalType?`: `'human' | 'service'` (defaults to `'human'`)
  - `audience?`: `string` (optional audience override)
  - `expiresInSeconds?`: `number` (optional TTL override)
- `mintCodayIdentityToken(options: CodayIdentityOptions, secret?: string): string`:
  - Input validation (fail-closed): Validate `email` is a non-empty string and looks like a valid email string (trimmed non-empty, contains `@` and valid format). If invalid or blank, fail closed by throwing an Error or returning `null` (decide on fail-closed pattern: `mintCodayIdentityToken` returns `null` or throws `Error('Invalid email for Coday identity')`). Throws explicit `Error` on invalid email / missing email so callers are aware of invalid token minting attempts.
  - Normalizes `principalType` (defaults to `'human'`).
  - Calls `issueJwt` from `./fake-idp.ts` passing `principalId: trimmedEmail`, `principalType`, `scopes`, `audience`, using `secret` (or `DEFAULT_FAKE_IDP_SECRET`).
  - Returns compact signed JWT string.
- `issueCodayIdentityToken`: Alias of `mintCodayIdentityToken` for convenience.
- Class `CodayIdentityBridge`: A class wrapping the secret and providing instance methods:
  - `constructor(secret?: string)`
  - `mintToken(options: CodayIdentityOptions): string`

### 2. Re-export in Barrel: `factory/src/domain/identity/index.ts`

Add re-export line to `factory/src/domain/identity/index.ts`:
```ts
export * from './coday-identity-bridge.ts'
```

### 3. Test Suite: `factory/tests/test-coday-identity-bridge.mjs`

Create a new standalone test script `factory/tests/test-coday-identity-bridge.mjs` following the test harness style of existing factory tests (`test-identity-trust-context.mjs`, `test-agentos-membership-resolver.mjs`).

#### Test Cases:
1. **Valid Coday Identity Token Generation & Boundary Integration**:
   - `mintCodayIdentityToken({ email: 'user@whoz.com', scopes: ['read', 'write'] }, SECRET)`
   - Pass `Authorization: Bearer <jwt>` to `extractTrustContext(req, bindPolicy)`.
   - Assert `ctx.authenticationMethod === 'jwt'`.
   - Assert `ctx.principalId === 'user@whoz.com'`.
   - Assert `ctx.principalType === 'human'`.
   - Assert `ctx.scopes` contains `['read', 'write']`.
2. **Coday Identity with custom principalType & audience**:
   - Mint token with `principalType: 'service'`, custom audience.
   - Verify JWT claims via `verifyJwt` and verify `extractTrustContext` behavior.
3. **Rejection & Fallback Paths**:
   - Unsigned / tampered token -> `extractTrustContext` falls back to loopback/anonymous context (`authenticationMethod !== 'jwt'`).
   - Token signed with wrong secret -> rejected by `extractTrustContext`.
   - Expired token -> rejected by `extractTrustContext`.
4. **Fail-Closed Behavior**:
   - Missing email (`null`, `undefined`, empty string `""`, whitespace `"  "`).
   - Invalid email format (e.g., non-string, missing `@`).
   - Assert `mintCodayIdentityToken` throws an explicit error / returns null and refuses to mint invalid identity tokens.
5. **Class `CodayIdentityBridge` API test**:
   - Verify `new CodayIdentityBridge(SECRET).mintToken({ email: 'user@whoz.com' })` works identically.
6. **Offline Execution**:
   - Ensure test runs completely offline using `node factory/tests/test-coday-identity-bridge.mjs` with exit code 0 on success.

---

## Verification Plan

### Automated Tests
1. Run newly created offline test suite:
   ```bash
   node factory/tests/test-coday-identity-bridge.mjs
   ```
   Expect exit code `0`.

2. Run existing test suites to ensure zero regression:
   ```bash
   node factory/tests/test-identity-trust-context.mjs
   node factory/tests/test-agentos-membership-resolver.mjs
   ```
   Expect exit code `0` for both.

3. Run Nx affected tests (if applicable):
   ```bash
   pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2
   ```

### Code Boundary & Quality Verification
- Check `git status` / `git diff` to ensure `factory/dashboard/http-utils.mjs`, `agentos-membership-resolver.ts`, and `factory-operational.mjs` are NOT modified.
