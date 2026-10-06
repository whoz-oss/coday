# Hardening the Factory HTTP Boundary (Boundary-Hardening, B6-T2b)

## Context & Goal
Durcir le bord HTTP de la Factory (`factory/dashboard/http-utils.mjs`, `factory/dashboard/composition-root.mjs`) pour un serveur PARTAGÉ multi-utilisateurs SANS changer les signatures publiques. Appliquer le principe **fail-closed** partout.

## Invariants & Principles
1. **NE JAMAIS** lire d'en-têtes client non signés pour l'autorisation (règle déjà en place).
2. Ne **PAS** modifier l'émission de JWT ni le pont d'identité (disjoint de T2a).
3. Ne **PAS** toucher le resolver (T1) ni le seam admin (T3).
4. Ne **PAS** régénérer le bundle `factory/runtime/factory-operational.mjs`.
5. **Compatibilité descendante** : conserver le mode `loopback-dev` pour le développement quotidien local quand le flag `FACTORY_ALLOW_LOOPBACK_DEV` est activé ou transmis via la configuration.

---

## Detailed Components to Modify

### 1. Flag `FACTORY_ALLOW_LOOPBACK_DEV` & Mode `loopback-dev`
- **Location**: `factory/dashboard/http-utils.mjs` (in `extractTrustContext`) & `factory/dashboard/composition-root.mjs` (in `loadConfig` / `resolveFactoryBindPolicy` / `createIdentityBoundaryOptions`).
- **Logic**:
  - The `loopback-dev` authentication method MUST ONLY be granted if BOTH conditions are met:
    1. Socket remote address is a loopback address (`isLoopbackAddress(req.socket.remoteAddress)`).
    2. `FACTORY_ALLOW_LOOPBACK_DEV` is explicitly enabled (`=== 'true'`) via environment or configuration passed via `bindPolicy.allowLoopbackDev` / `bindPolicy.identity.allowLoopbackDev`.
  - If `FACTORY_ALLOW_LOOPBACK_DEV` is absent, undefined, or !== `'true'`, the `loopback-dev` mode is **REFUSED**, even for connections from `127.0.0.1`!
  - If `loopback-dev` is refused or if the remote address is not loopback, an unauthenticated request MUST fall back to `anonymous` mode.
  - **Dev local compatibility**: In `loadConfig(env)` / `resolveFactoryBindPolicy(env)` / `createIdentityBoundaryOptions(env)`, read `env.FACTORY_ALLOW_LOOPBACK_DEV` (defaulting to `'true'` in local dev options if needed by `resolveFactoryBindPolicy` or `loadConfig` defaults, but enforcing the strict check in `extractTrustContext` based on `bindPolicy.allowLoopbackDev` / `env`).

### 2. Mode `anonymous` = ZERO Privilege
- **Location**: `factory/dashboard/http-utils.mjs` (fallback branch of `extractTrustContext`).
- **Logic**:
  - When a request is `anonymous` (unauthenticated, non-jwt, non-proxy-signed, or loopback refused):
    - `scopes`: `[]` (empty array, NEVER `['*']`).
    - `roles`: `[]` (empty array).
    - `principalId`: `null`.
    - `organizationId`: `null`.
    - `workstreamId`: `null`.
    - `squadId`: `null`.
    - `principalType`: `'human'`.
    - `serviceIdentityId`: `null`.

### 3. Wildcard `*` Scope Scope-Removal Outside `loopback-dev`
- **Location**: `factory/dashboard/http-utils.mjs`.
- **Logic**:
  - `scopes = ['*']` must ONLY be granted in `loopback-dev` mode (when IP is loopback AND `FACTORY_ALLOW_LOOPBACK_DEV === 'true'`).
  - For `jwt`, `proxy-signature`, or `anonymous` requests, `*` is NEVER granted implicitly. (Explicit JWT or proxy-signature claims pass through their verified scopes).

### 4. Remote Unauthenticated Mode Hardening (Fail-Closed)
- **Location**: `factory/dashboard/composition-root.mjs` (`resolveFactoryBindPolicy`) and `factory/dashboard/http-utils.mjs`.
- **Logic**:
  - Remote unauthenticated requests (without valid JWT or valid proxy signature) resolve strictly to `anonymous` mode with zero privileges (`scopes: []`, `roles: []`, `principalId: null`).
  - Protected actions (such as admin endpoints checked via `checkAdminAuthorization` / `requireAdminRole` or routes checking authorization) fail-closed when called anonymously.
  - S'assurer que `resolveFactoryBindPolicy` et la logique de bind préservent le comportement fail-closed si la configuration distante est non-authentifiée.

### 5. Configurable & Restricted CORS
- **Location**: `factory/dashboard/http-utils.mjs` (`send()`) and `factory/dashboard/composition-root.mjs` (SSE stream header, `index.html` GET response, OPTIONS preflight handler).
- **Logic**:
  - Add `corsAllowedOrigins` (or `allowedOrigins`) configuration to `loadConfig(env)` using `env.FACTORY_ALLOWED_ORIGINS` or `env.FACTORY_CORS_ORIGIN`.
  - Pass the CORS settings into `send()` or `resolveCorsOrigin(reqHeaders, config)`.
  - Handle CORS headers dynamically:
    - If `FACTORY_ALLOWED_ORIGINS` is set (e.g. comma-separated list of origins `https://dashboard.example.com,http://localhost:3000` or `*`), check the incoming `Origin` header (`req.headers.origin`).
    - If incoming `Origin` matches an allowed origin in `FACTORY_ALLOWED_ORIGINS`, reflect `Access-Control-Allow-Origin: <origin>` (or set it to the allowed origin).
    - If `FACTORY_ALLOWED_ORIGINS` is not set or empty, set a safe default (e.g., reflect request origin if same-origin / local or omit / restrict to local host `http://localhost:${config.port}` / `null` or handle according to bind policy).
    - Update hardcoded `'Access-Control-Allow-Origin': '*'` in `http-utils.mjs` (`send()`), `composition-root.mjs` (SSE response headers, OPTIONS route, index.html GET route) to use the helper / configured CORS logic.

---

## Files to Modify

1. **`factory/dashboard/http-utils.mjs`**
   - In `extractTrustContext(req, bindPolicy)`:
     - Check `allowLoopbackDev`: `const allowLoopbackDev = bindPolicy?.allowLoopbackDev ?? (bindPolicy?.identity?.allowLoopbackDev ?? false)` (or read `process.env.FACTORY_ALLOW_LOOPBACK_DEV === 'true'`).
     - Loopback branch: `if (loopback && allowLoopbackDev)` -> `authenticationMethod = 'loopback-dev'`, `scopes = ['*']`, `principalId = ...`.
     - Otherwise (loopback without flag, or remote without token) -> `authenticationMethod = 'anonymous'`, `principalId = null`, `scopes = []`, `roles = []`, `organizationId = null`, `workstreamId = null`, `squadId = null`.
   - Update `send(res, status, body, ct, extraHeaders, corsOptions)` or helper to handle CORS origin matching instead of fixed `*`.

2. **`factory/dashboard/composition-root.mjs`**
   - Update `loadConfig(env)`:
     - Include `allowLoopbackDev: env.FACTORY_ALLOW_LOOPBACK_DEV === 'true'`.
     - Include `allowedOrigins: (env.FACTORY_ALLOWED_ORIGINS || env.FACTORY_CORS_ORIGIN || '').split(',').map(s => s.trim()).filter(Boolean)`.
   - Update `createIdentityBoundaryOptions(env)`:
     - Pass `allowLoopbackDev: env.FACTORY_ALLOW_LOOPBACK_DEV === 'true'`.
   - Update OPTIONS handler, SSE response header, and GET `/index.html` response header to use configured CORS headers.

3. **`factory/tests/test-boundary-hardening.mjs`** (NEW TEST FILE)
   - Test cases:
     1. `anonymous` => zero privileges (`scopes: []`, `roles: []`, `principalId: null`, `organizationId: null`).
     2. Loopback request with `FACTORY_ALLOW_LOOPBACK_DEV !== 'true'` => `anonymous` (refused loopback-dev).
     3. Loopback request with `FACTORY_ALLOW_LOOPBACK_DEV === 'true'` => `loopback-dev` (`scopes: ['*']`).
     4. Remote request even with `FACTORY_ALLOW_LOOPBACK_DEV === 'true'` => `anonymous` (`scopes: []`).
     5. Wildcard `*` not granted outside `loopback-dev` mode.
     6. CORS origin restricted according to `FACTORY_ALLOWED_ORIGINS` / `FACTORY_CORS_ORIGIN`.
     7. Remote unauthenticated request fails closed for protected operations (`checkAdminAuthorization` / `requireAdminRole`).

4. **Existing Test Files to Update if Needed**
   - `factory/tests/test-identity-trust-context.mjs`: Pass `identity: { allowLoopbackDev: true }` or set env `FACTORY_ALLOW_LOOPBACK_DEV = 'true'` in tests expecting `loopback-dev` behavior.
   - `factory/tests/test-composition-root-source.mjs`: Verify static source guards still pass.

---

## Verification Steps

Run the following test commands to ensure all checks pass offline without regressions:
1. `node factory/tests/test-boundary-hardening.mjs`
2. `node factory/tests/test-identity-trust-context.mjs`
3. `node factory/tests/test-factory-bind-policy.mjs`
4. `node factory/tests/test-composition-root-source.mjs`
