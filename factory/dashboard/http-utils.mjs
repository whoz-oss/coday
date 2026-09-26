/**
 * HTTP utilities — transport-level primitives shared across route modules.
 *
 * These functions have no dependency on any store, service, or configuration.
 * They centralise the three transport concerns the Factory HTTP boundary owns:
 *
 *   1. Correlation IDs — every response carries `X-Correlation-Id`; an inbound
 *      header is propagated, otherwise a trace id is generated.
 *   2. A single error contract — `{ "error": { "code", "message", "details" } }`,
 *      produced by `sendError`/`errorBody` and enforced by `send` for legacy
 *      string payloads still emitted by older route modules.
 *   3. TrustContext extraction — identity headers and the loopback/remote bind
 *      policy are resolved once, at the edge, and passed to handlers.
 */

import { randomUUID } from 'node:crypto'

import {
  DEFAULT_FAKE_IDP_SECRET,
  LocalDevMembershipResolver,
  LOOPBACK_DEV_PRINCIPAL_ID,
  authorizeAdminAccess,
  hasProxySignature,
  isPrincipalType,
  resolveMembershipSync,
  verifyJwt,
  verifyProxyHeaders,
} from '../src/domain/identity/index.ts'

/** Canonical header used for request/response correlation. */
export const CORRELATION_ID_HEADER = 'x-correlation-id'

/** Deterministic fallback error codes so a string error is never opaque. */
const ERROR_CODE_BY_STATUS = Object.freeze({
  400: 'BAD_REQUEST',
  401: 'UNAUTHENTICATED',
  403: 'FORBIDDEN',
  404: 'NOT_FOUND',
  405: 'METHOD_NOT_ALLOWED',
  409: 'CONFLICT',
  410: 'GONE',
  415: 'UNSUPPORTED_MEDIA_TYPE',
  422: 'UNPROCESSABLE_ENTITY',
  429: 'TOO_MANY_REQUESTS',
  500: 'INTERNAL_ERROR',
  501: 'NOT_IMPLEMENTED',
  502: 'BAD_GATEWAY',
  503: 'SERVICE_UNAVAILABLE',
})

/** Generate a correlation id when the caller supplied none. */
export function generateCorrelationId() {
  return `coday-corr-${randomUUID()}`
}

/**
 * Explicit admin authorization guard for factory-artifact governance commands.
 *
 * This is the single, named authorization point every admin use case must pass
 * through: purge, legal-hold management and garbage collection. Since B6-T3 it
 * performs *real entitlement resolution* — delegating to
 * `authorizeAdminAccess` in `src/domain/identity/entitlements.ts` — against the
 * already-resolved, verified `TrustContext` (never client headers, never
 * defaults).
 *
 * The principal passes only when it carries the AgentOS-derived `admin` role
 * (directory `ADMIN` -> Factory `admin`), the explicit `admin:*` scope, or the
 * loopback-dev wildcard `*` scope. An anonymous context, a missing context and
 * a member-only principal all fail closed. The signature is unchanged from the
 * B5 seam so every call site stays put; namespace-scoped checks additionally
 * reject an admin acting outside their own organization/workstream.
 *
 * Pure and never throws: returns a structured decision so callers can either
 * branch on it or translate it through {@link requireAdminRole}.
 *
 * @param {object|null|undefined} trustContext
 * @returns {{ authorized: boolean, reason: string|null }}
 */
export function checkAdminAuthorization(trustContext) {
  return authorizeAdminAccess(trustContext)
}

/**
 * Enforce {@link checkAdminAuthorization}, throwing a transport-ready 403 error
 * (`FORBIDDEN_ADMIN_REQUIRED`) when the principal is not an admin. Returns
 * `true` when authorized, so a caller can `requireAdminRole(trust)` inline.
 *
 * @param {object|null|undefined} trustContext
 * @returns {true}
 */
export function requireAdminRole(trustContext) {
  const check = checkAdminAuthorization(trustContext)
  if (!check.authorized) {
    const error = new Error(`Admin authorization required (${check.reason})`)
    error.statusCode = 403
    error.code = 'FORBIDDEN_ADMIN_REQUIRED'
    error.reason = check.reason
    throw error
  }
  return true
}

/**
 * Resolve the correlation id for a request: propagate the inbound header when
 * present, otherwise mint one. Never throws on malformed headers.
 *
 * @param {import('node:http').IncomingMessage} req
 * @returns {string}
 */
export function resolveCorrelationId(req) {
  const raw = req?.headers?.[CORRELATION_ID_HEADER]
  const value = Array.isArray(raw) ? raw[0] : raw
  return typeof value === 'string' && value.trim() ? value.trim().slice(0, 256) : generateCorrelationId()
}

/**
 * Build the standardized error envelope.
 *
 * `details` is intentionally omitted when not supplied so a route module's
 * direct (unit-tested) return value stays minimal; the transport `send`
 * normalizer fills `details: null` at the HTTP boundary, so every real
 * response still conforms to `{ error: { code, message, details } }`.
 *
 * @param {string} code
 * @param {string} [message]
 * @param {any} [details]
 * @returns {{ error: { code: string, message: string, details?: any } }}
 */
export function errorBody(code, message, details) {
  const error = { code, message: message ?? code }
  if (details !== undefined) error.details = details
  return { error }
}

/**
 * Upgrade legacy error payloads (`{ error: 'message' }` or a partial object) to
 * the canonical envelope. Non-error bodies are returned untouched.
 *
 * @param {number} status
 * @param {any} body
 * @returns {any}
 */
export function normalizeErrorBody(status, body) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) return body
  if (!Object.hasOwn(body, 'error')) return body
  const fallback = ERROR_CODE_BY_STATUS[status] ?? 'ERROR'
  if (typeof body.error === 'string') {
    return { ...body, error: { code: fallback, message: body.error, details: null } }
  }
  if (body.error && typeof body.error === 'object') {
    const code = typeof body.error.code === 'string' ? body.error.code : fallback
    return {
      ...body,
      error: { ...body.error, code, message: body.error.message ?? code, details: body.error.details ?? null },
    }
  }
  return body
}

/**
 * Send a JSON (or plain-text) HTTP response with CORS + correlation headers.
 *
 * `res.correlationId` is set by the composition root per request; when present
 * it is echoed on every response so a caller can trace one request end-to-end.
 *
 * `res.corsOrigin` is likewise resolved once per request by the composition
 * root from the configured allow-list ({@link resolveCorsOrigin}). The CORS
 * header is only emitted when an origin was explicitly authorized, so a
 * shared/remote deployment never falls back to a hardcoded wildcard.
 *
 * @param {import('node:http').ServerResponse} res
 * @param {number} status
 * @param {object|string|null} body
 * @param {string} [ct]
 * @param {Record<string, string>} [extraHeaders]
 */
export function send(res, status, body, ct = 'application/json', extraHeaders = {}) {
  const normalized = normalizeErrorBody(status, body)
  const data = typeof normalized === 'string' ? normalized : JSON.stringify(normalized)
  const headers = { 'Content-Type': ct, ...extraHeaders }
  if (res?.corsOrigin) {
    headers['Access-Control-Allow-Origin'] = res.corsOrigin
    if (res.corsOrigin !== '*') headers['Vary'] = 'Origin'
  }
  if (res?.correlationId) headers[CORRELATION_ID_HEADER] = res.correlationId
  res.writeHead(status, headers)
  res.end(data)
}

/**
 * Resolve the `Access-Control-Allow-Origin` value for a request, fail-closed.
 *
 * The allow-list comes from `FACTORY_ALLOWED_ORIGINS` / `FACTORY_CORS_ORIGIN`
 * (parsed by the composition root). Rules:
 *
 *   - no configured allow-list → `null` (same-origin only, no CORS header);
 *   - request without an `Origin` header → `null` (nothing to reflect);
 *   - an explicit `*` entry → `*` (deliberate operator opt-in);
 *   - an exact match in the list → the request origin (reflected);
 *   - anything else → `null` (cross-origin access denied).
 *
 * @param {{ headers?: Record<string, string|string[]|undefined> }|null|undefined} req
 * @param {string[]|null|undefined} allowedOrigins
 * @returns {string|null}
 */
export function resolveCorsOrigin(req, allowedOrigins) {
  const raw = req?.headers?.origin
  const origin = Array.isArray(raw) ? raw[0] : raw
  if (typeof origin !== 'string' || origin.length === 0) return null
  if (!Array.isArray(allowedOrigins) || allowedOrigins.length === 0) return null
  if (allowedOrigins.includes('*')) return '*'
  return allowedOrigins.includes(origin) ? origin : null
}

/**
 * Emit a standardized error response through a route's `send` delegate.
 *
 * @param {(status: number, body: unknown) => void} send
 * @param {number} status
 * @param {string} code
 * @param {string} [message]
 * @param {any} [details]
 */
export function sendError(send, status, code, message, details) {
  return send(status, errorBody(code, message, details))
}

/**
 * Read and JSON-parse the request body.
 * Returns {} on empty or invalid JSON (never throws).
 *
 * @param {import('node:http').IncomingMessage} req
 * @returns {Promise<object>}
 */
export function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = ''
    req.on('data', (c) => { data += c })
    req.on('end', () => { try { resolve(JSON.parse(data || '{}')) } catch { resolve({}) } })
    req.on('error', reject)
  })
}

/**
 * Validate that a Story-edit request body contains only allowed fields.
 *
 * @param {unknown} body
 * @returns {boolean}
 */
export function isAllowedStoryEditRequestBody(body) {
  return (
    !!body &&
    typeof body === 'object' &&
    !Array.isArray(body) &&
    Object.keys(body).every((key) =>
      ['analysisExecutionId', 'namespaceId', 'agentName', 'expectedSpecHash', 'supplement'].includes(key),
    )
  )
}

const LOOPBACK_ADDRESSES = new Set(['127.0.0.1', '::1', '::ffff:127.0.0.1'])

/** True when a socket address is a loopback address (or unavailable). */
export function isLoopbackAddress(address) {
  if (typeof address !== 'string') return true
  return LOOPBACK_ADDRESSES.has(address) || address.startsWith('127.')
}

/**
 * Resolve whether `loopback-dev` is explicitly permitted.
 *
 * An explicit `bindPolicy.allowLoopbackDev` (or its non-enumerable
 * `bindPolicy.identity.allowLoopbackDev`) wins; otherwise the strict
 * `FACTORY_ALLOW_LOOPBACK_DEV === 'true'` environment flag is required.
 * Absent, `false`, or any other value refuses the mode (fail-closed).
 *
 * @param {{ allowLoopbackDev?: unknown, identity?: { allowLoopbackDev?: unknown } }} [bindPolicy]
 * @returns {boolean}
 */
function resolveAllowLoopbackDev(bindPolicy) {
  const policyValue = bindPolicy?.allowLoopbackDev ?? bindPolicy?.identity?.allowLoopbackDev
  if (policyValue !== undefined) return policyValue === true || policyValue === 'true'
  return process.env?.FACTORY_ALLOW_LOOPBACK_DEV === 'true'
}

/**
 * Fallback membership resolver used when the composition root injects none.
 * Guarantees memberships stay server-side even on the default code path.
 */
const DEFAULT_MEMBERSHIP_RESOLVER = new LocalDevMembershipResolver()

/** Pick the principal id from verified JWT claims (`principalId` then `sub`). */
function pickPrincipalId(claims) {
  if (typeof claims?.principalId === 'string' && claims.principalId.length > 0) return claims.principalId
  if (typeof claims?.sub === 'string' && claims.sub.length > 0) return claims.sub
  return null
}

/**
 * Extract the trusted identity + security context at the HTTP boundary.
 *
 * Identity is never inferred from the message body or the working directory;
 * it comes only from a *verified* credential:
 *
 *   1. a valid `Authorization: Bearer <jwt>` verified against the Fake IdP;
 *   2. otherwise signed proxy headers (`x-proxy-signature`, ...) verified
 *      against the shared secret;
 *   3. otherwise a loopback-dev context — and only when the socket is a
 *      loopback address AND `loopback-dev` is explicitly allowed via
 *      `bindPolicy.allowLoopbackDev` / `FACTORY_ALLOW_LOOPBACK_DEV === 'true'`;
 *   4. otherwise an anonymous context with strictly zero privilege
 *      (`scopes: []`, `roles: []`, `principalId: null`). A refused loopback or
 *      a remote caller without a verified credential never gets the wildcard.
 *
 * Any identity header supplied WITHOUT a valid signature is ignored — in
 * particular the `x-proxy-*` family — so a client cannot forge a principal.
 * Memberships (`organizationId`, `workstreamId`, `squadId`, `roles`) are never
 * read from client headers: they are resolved server-side from the principal
 * by the injected `MembershipResolver`.
 *
 * The bind policy (loopback-only by default) is threaded through so downstream
 * handlers can reason about the transport trust level; it may also carry the
 * boundary's identity options non-enumerably (`bindPolicy.identity`).
 *
 * Impersonation / delegation is disabled by default (`impersonatedBy` and
 * `delegationChain` are always `null`).
 *
 * @param {import('node:http').IncomingMessage} req
 * @param {{ trustMode?: string, allowLoopbackDev?: boolean|string, identity?: { membershipResolver?: object, fakeIdpSecret?: string, allowLoopbackDev?: boolean|string } }} [bindPolicy]
 * @returns {{
 *   namespaceId: string|null, caseId: string|null, actorId: string|null,
 *   authorityId: string|null, runtimeId: string|null, agentId: string|null,
 *   threadId: string|null, trustMode: string, loopback: boolean,
 *   principalId: string|null, principalType: 'human'|'service',
 *   organizationId: string|null, workstreamId: string|null, squadId: string|null,
 *   roles: string[], scopes: string[], correlationId: string|null,
 *   authenticationMethod: 'jwt'|'proxy-signature'|'loopback-dev'|'anonymous',
 *   serviceIdentityId: string|null,
 *   impersonatedBy: null, delegationChain: null,
 * }}
 */
export function extractTrustContext(req, bindPolicy = {}) {
  const header = (name) => {
    const raw = req?.headers?.[name]
    const value = Array.isArray(raw) ? raw[0] : raw
    return typeof value === 'string' && value.length > 0 ? value : null
  }
  const identityOptions = bindPolicy?.identity ?? {}
  const membershipResolver = identityOptions.membershipResolver ?? DEFAULT_MEMBERSHIP_RESOLVER
  const fakeIdpSecret = identityOptions.fakeIdpSecret ?? DEFAULT_FAKE_IDP_SECRET
  const loopback = isLoopbackAddress(req?.socket?.remoteAddress)
  const allowLoopbackDev = resolveAllowLoopbackDev(bindPolicy)

  // Legacy fields — semantics preserved for existing dashboard routes.
  const legacy = {
    namespaceId: header('x-factory-namespace-id'),
    caseId: header('x-factory-case-id'),
    actorId: header('x-factory-actor-id'),
    authorityId: header('x-factory-authority-id'),
    runtimeId: header('x-factory-runtime-id'),
    agentId: header('x-factory-agent-id'),
    threadId: header('x-factory-thread-id'),
    trustMode: bindPolicy?.trustMode ?? 'loopback-only',
    loopback,
  }

  // 1. JWT — `Authorization: Bearer <token>` verified against the Fake IdP.
  let authenticationMethod = 'anonymous'
  let principalId = null
  let principalType = 'human'
  let serviceIdentityId = null
  let scopes = []

  const authorization = header('authorization')
  const bearerMatch = authorization ? /^Bearer\s+(.+)$/i.exec(authorization) : null
  const jwt = bearerMatch ? bearerMatch[1].trim() : null
  if (jwt) {
    const verification = verifyJwt(jwt, fakeIdpSecret)
    if (verification.valid && verification.claims) {
      authenticationMethod = 'jwt'
      principalId = pickPrincipalId(verification.claims)
      principalType = isPrincipalType(verification.claims.principalType) ? verification.claims.principalType : 'human'
      serviceIdentityId = typeof verification.claims.serviceIdentityId === 'string' ? verification.claims.serviceIdentityId : null
      scopes = Array.isArray(verification.claims.scopes)
        ? verification.claims.scopes.filter((scope) => typeof scope === 'string')
        : []
    }
    // A present-but-invalid/expired/tampered token is ignored (never trusted);
    // control falls through to proxy signature verification then to fallback.
  }

  // 2. Signed proxy headers — only trusted when the signature verifies.
  if (authenticationMethod === 'anonymous' && hasProxySignature(req?.headers)) {
    const verification = verifyProxyHeaders(req.headers, fakeIdpSecret)
    if (verification.valid && verification.claims) {
      authenticationMethod = 'proxy-signature'
      principalId = verification.claims.principalId
      principalType = isPrincipalType(verification.claims.principalType) ? verification.claims.principalType : 'human'
      serviceIdentityId = verification.claims.serviceIdentityId ?? null
      scopes = Array.isArray(verification.claims.scopes) ? verification.claims.scopes : []
    }
    // Unsigned or badly signed identity headers are discarded, not trusted.
  }

  // 3. Fallback — loopback development vs unauthenticated anonymous.
  // `loopback-dev` requires BOTH a loopback socket AND an explicit opt-in;
  // otherwise the caller stays anonymous with zero privilege (fail-closed).
  if (authenticationMethod === 'anonymous') {
    if (loopback && allowLoopbackDev) {
      authenticationMethod = 'loopback-dev'
      principalId = header('x-factory-actor-id') ?? LOOPBACK_DEV_PRINCIPAL_ID
      principalType = 'human'
      serviceIdentityId = null
      scopes = ['*']
    } else {
      authenticationMethod = 'anonymous'
      principalId = null
      principalType = 'human'
      serviceIdentityId = null
      scopes = []
    }
  }

  // 4. Memberships are resolved server-side from the authenticated principal.
  // Client headers (x-organization-id, x-workstream-id, x-roles, ...) are
  // deliberately never consulted here. An anonymous caller is forced to a
  // strictly empty membership even if the injected resolver is permissive.
  const membership =
    authenticationMethod === 'anonymous'
      ? { organizationId: null, workstreamId: null, squadId: null, roles: [] }
      : resolveMembershipSync(membershipResolver, principalId, principalType)

  return {
    ...legacy,
    principalId,
    principalType,
    organizationId: membership.organizationId ?? null,
    workstreamId: membership.workstreamId ?? null,
    squadId: membership.squadId ?? null,
    roles: membership.roles ?? [],
    scopes,
    correlationId: resolveCorrelationId(req),
    authenticationMethod,
    serviceIdentityId,
    // Impersonation / delegation disabled by default.
    impersonatedBy: null,
    delegationChain: null,
  }
}
