/**
 * Identity chain & enriched TrustContext tests (Milestone B, wave B1, task T3).
 *
 * Verifies the HTTP-boundary identity chain end to end, offline, against the
 * local Fake IdP:
 *
 *   1.  Fake IdP JWT round-trip (issue + verify).
 *   2.  JWT rejection: wrong secret, tampered payload, expired, wrong audience.
 *   3.  Signed proxy header round-trip.
 *   4.  Signed proxy rejection: forged signature, wrong secret, stale timestamp.
 *   5.  `extractTrustContext` accepts a valid JWT and populates enriched fields.
 *   6.  Service tokens: `principalType: 'service'` + `serviceIdentityId`.
 *   7.  Invalid / tampered / expired JWT is ignored (falls back, never trusted).
 *   8.  Signed proxy headers are accepted by the boundary.
 *   9.  Forged / unsigned identity headers are rejected and ignored.
 *   10. Client-supplied membership headers are never trusted (server-side only).
 *   11. Legacy TrustContext fields stay backward compatible.
 *   12. Loopback vs anonymous fallback is correct.
 *   13. Impersonation / delegation is disabled by default.
 *   14. `validateTrustContext` accepts the produced contexts.
 *
 * Usage : node factory/tests/test-identity-trust-context.mjs
 * Code de sortie : 0 = tous les cas passent, 1 = au moins un échec.
 */

import assert from 'node:assert/strict'

import {
  DEFAULT_FAKE_IDP_SECRET,
  LocalDevMembershipResolver,
  MockMembershipResolver,
  createAnonymousTrustContext,
  createLoopbackDevTrustContext,
  hasProxySignature,
  isAuthenticationMethod,
  isPrincipalType,
  issueJwt,
  issueServiceToken,
  signProxyHeaders,
  validateTrustContext,
  verifyJwt,
  verifyProxyHeaders,
} from '../src/domain/identity/index.ts'
import { extractTrustContext } from '../dashboard/http-utils.mjs'

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

let passed = 0
let failed = 0

function test(name, fn) {
  try {
    fn()
    console.log(`✓ ${name}`)
    passed++
  } catch (error) {
    console.error(`✗ ${name}\n   ${error?.message ?? error}`)
    failed++
  }
}

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

const SECRET = 'test-fake-idp-secret'
const WRONG_SECRET = 'another-secret'
const LOOPBACK = '127.0.0.1'
const REMOTE = '10.0.0.7'

const resolver = new MockMembershipResolver({
  'user-1': {
    organizationId: 'org-alpha',
    workstreamId: 'ws-alpha',
    squadId: 'squad-alpha',
    roles: ['admin', 'reviewer'],
  },
  'svc-1': {
    organizationId: 'org-alpha',
    workstreamId: 'ws-beta',
    squadId: null,
    roles: ['service-runner'],
  },
})

const bindPolicy = {
  trustMode: 'loopback-only',
  identity: { membershipResolver: resolver, fakeIdpSecret: SECRET },
}

function makeReq({ headers = {}, remoteAddress = LOOPBACK } = {}) {
  return { headers, socket: { remoteAddress } }
}

/** Re-encode a token's payload swapping `from` for `to`, without re-signing. */
function tamperJwt(token, from, to) {
  const parts = token.split('.')
  const payload = JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8'))
  const serialized = JSON.stringify(payload).split(from).join(to)
  parts[1] = Buffer.from(serialized, 'utf8').toString('base64url')
  return parts.join('.')
}

// ---------------------------------------------------------------------------
// 1. Fake IdP JWT round-trip
// ---------------------------------------------------------------------------

test('issueJwt/verifyJwt: valid token with principal + scopes + audience', () => {
  const token = issueJwt(
    { principalId: 'user-1', principalType: 'human', scopes: ['read', 'write'], audience: 'coday-factory' },
    SECRET,
  )
  const result = verifyJwt(token, SECRET)
  assert.equal(result.valid, true)
  assert.equal(result.claims.principalId, 'user-1')
  assert.equal(result.claims.principalType, 'human')
  assert.deepEqual(result.claims.scopes, ['read', 'write'])
  assert.equal(result.claims.aud, 'coday-factory')
  assert.equal(result.claims.iss, 'coday-fake-idp')
})

test('verifyJwt: default audience and issuer are applied', () => {
  const result = verifyJwt(issueJwt({ principalId: 'user-1' }, SECRET), SECRET)
  assert.equal(result.valid, true)
  assert.equal(result.claims.aud, 'coday-factory')
  assert.equal(result.claims.sub, 'user-1')
})

// ---------------------------------------------------------------------------
// 2. JWT rejection
// ---------------------------------------------------------------------------

test('verifyJwt rejects a token signed with another secret', () => {
  const token = issueJwt({ principalId: 'user-1' }, SECRET)
  assert.equal(verifyJwt(token, WRONG_SECRET).valid, false)
  assert.equal(verifyJwt(token, WRONG_SECRET).reason, 'invalid-signature')
})

test('verifyJwt rejects a tampered payload', () => {
  const token = issueJwt({ principalId: 'user-1', scopes: ['read'] }, SECRET)
  const tampered = tamperJwt(token, 'user-1', 'attacker')
  const result = verifyJwt(tampered, SECRET)
  assert.equal(result.valid, false)
  assert.equal(result.reason, 'invalid-signature')
})

test('verifyJwt rejects an expired token', () => {
  const expired = issueJwt({ principalId: 'user-1' }, SECRET, -60)
  const result = verifyJwt(expired, SECRET)
  assert.equal(result.valid, false)
  assert.equal(result.reason, 'expired')
})

test('verifyJwt rejects an unexpected audience', () => {
  const token = issueJwt({ principalId: 'user-1', audience: 'coday-factory' }, SECRET)
  assert.equal(verifyJwt(token, SECRET, { audience: 'other-audience' }).valid, false)
  assert.equal(verifyJwt(token, SECRET, { audience: 'other-audience' }).reason, 'invalid-audience')
})

test('verifyJwt rejects malformed input', () => {
  assert.equal(verifyJwt('not-a-jwt', SECRET).valid, false)
  assert.equal(verifyJwt(null, SECRET).reason, 'missing-token')
})

// ---------------------------------------------------------------------------
// 3. Signed proxy header round-trip
// ---------------------------------------------------------------------------

test('signProxyHeaders/verifyProxyHeaders: valid service headers', () => {
  const headers = signProxyHeaders(
    { principalId: 'svc-1', principalType: 'service', serviceIdentityId: 'svc-1', scopes: ['build', 'deploy'] },
    SECRET,
  )
  assert.equal(hasProxySignature(headers), true)
  const result = verifyProxyHeaders(headers, SECRET)
  assert.equal(result.valid, true)
  assert.equal(result.claims.principalId, 'svc-1')
  assert.equal(result.claims.principalType, 'service')
  assert.equal(result.claims.serviceIdentityId, 'svc-1')
  assert.deepEqual(result.claims.scopes, ['build', 'deploy'])
})

// ---------------------------------------------------------------------------
// 4. Signed proxy rejection
// ---------------------------------------------------------------------------

test('verifyProxyHeaders rejects a forged signature', () => {
  const headers = signProxyHeaders({ principalId: 'svc-1', principalType: 'service' }, SECRET)
  assert.equal(verifyProxyHeaders({ ...headers, 'x-proxy-signature': 'forged' }, SECRET).valid, false)
})

test('verifyProxyHeaders rejects headers signed with another secret', () => {
  const headers = signProxyHeaders({ principalId: 'svc-1', principalType: 'service' }, SECRET)
  assert.equal(verifyProxyHeaders(headers, WRONG_SECRET).valid, false)
})

test('verifyProxyHeaders rejects a stale timestamp (replay window)', () => {
  const headers = signProxyHeaders(
    { principalId: 'svc-1', principalType: 'service' },
    SECRET,
    { timestamp: Date.now() - 10 * 60 * 1000 },
  )
  assert.equal(verifyProxyHeaders(headers, SECRET).valid, false)
  assert.equal(verifyProxyHeaders(headers, SECRET).reason, 'stale-timestamp')
})

test('verifyProxyHeaders rejects tampered principal id', () => {
  const headers = signProxyHeaders({ principalId: 'svc-1', principalType: 'service' }, SECRET)
  assert.equal(verifyProxyHeaders({ ...headers, 'x-proxy-principal-id': 'attacker' }, SECRET).valid, false)
})

// ---------------------------------------------------------------------------
// 5. Boundary accepts a valid JWT
// ---------------------------------------------------------------------------

test('extractTrustContext: valid JWT populates enriched identity fields', () => {
  const token = issueJwt({ principalId: 'user-1', principalType: 'human', scopes: ['read'] }, SECRET)
  const ctx = extractTrustContext(makeReq({ headers: { authorization: `Bearer ${token}` }, remoteAddress: REMOTE }), bindPolicy)
  assert.equal(ctx.authenticationMethod, 'jwt')
  assert.equal(ctx.principalId, 'user-1')
  assert.equal(ctx.principalType, 'human')
  assert.deepEqual(ctx.scopes, ['read'])
  assert.equal(ctx.serviceIdentityId, null)
  assert.equal(ctx.authenticationMethod, 'jwt')
  // Memberships resolved server-side.
  assert.equal(ctx.organizationId, 'org-alpha')
  assert.equal(ctx.workstreamId, 'ws-alpha')
  assert.equal(ctx.squadId, 'squad-alpha')
  assert.deepEqual(ctx.roles, ['admin', 'reviewer'])
})

// ---------------------------------------------------------------------------
// 6. Service identity tokens
// ---------------------------------------------------------------------------

test('issueServiceToken/extractTrustContext: service principal with audience + scopes', () => {
  const token = issueServiceToken(
    { serviceIdentityId: 'svc-1', audience: 'coday-factory', scopes: ['build'] },
    SECRET,
  )
  const claims = verifyJwt(token, SECRET)
  assert.equal(claims.valid, true)
  assert.equal(claims.claims.principalType, 'service')
  assert.equal(claims.claims.serviceIdentityId, 'svc-1')

  const ctx = extractTrustContext(makeReq({ headers: { authorization: `Bearer ${token}` }, remoteAddress: REMOTE }), bindPolicy)
  assert.equal(ctx.authenticationMethod, 'jwt')
  assert.equal(ctx.principalType, 'service')
  assert.equal(ctx.principalId, 'svc-1')
  assert.equal(ctx.serviceIdentityId, 'svc-1')
  assert.deepEqual(ctx.scopes, ['build'])
  assert.deepEqual(ctx.roles, ['service-runner'])
  assert.equal(ctx.workstreamId, 'ws-beta')
})

// ---------------------------------------------------------------------------
// 7. Invalid JWT is ignored (fallback, never trusted)
// ---------------------------------------------------------------------------

test('extractTrustContext: tampered JWT is ignored (loopback fallback, no forged identity)', () => {
  const token = issueJwt({ principalId: 'user-1', principalType: 'human' }, SECRET)
  const tampered = tamperJwt(token, 'user-1', 'attacker')
  const ctx = extractTrustContext(makeReq({ headers: { authorization: `Bearer ${tampered}` } }), bindPolicy)
  assert.equal(ctx.authenticationMethod, 'loopback-dev')
  assert.notEqual(ctx.principalId, 'attacker')
  assert.equal(ctx.principalId, 'local-dev-user')
})

test('extractTrustContext: expired JWT is ignored (remote anonymous fallback)', () => {
  const expired = issueJwt({ principalId: 'user-1' }, SECRET, -60)
  const ctx = extractTrustContext(
    makeReq({ headers: { authorization: `Bearer ${expired}` }, remoteAddress: REMOTE }),
    bindPolicy,
  )
  assert.equal(ctx.authenticationMethod, 'anonymous')
  assert.equal(ctx.principalId, null)
  assert.deepEqual(ctx.scopes, [])
  assert.equal(ctx.organizationId, null)
  assert.deepEqual(ctx.roles, [])
})

test('extractTrustContext: JWT signed with another secret is ignored', () => {
  const token = issueJwt({ principalId: 'attacker' }, WRONG_SECRET)
  const ctx = extractTrustContext(makeReq({ headers: { authorization: `Bearer ${token}` } }), bindPolicy)
  assert.equal(ctx.authenticationMethod, 'loopback-dev')
  assert.equal(ctx.principalId, 'local-dev-user')
})

// ---------------------------------------------------------------------------
// 8. Signed proxy headers accepted by the boundary
// ---------------------------------------------------------------------------

test('extractTrustContext: signed proxy headers authenticate the principal', () => {
  const headers = signProxyHeaders(
    { principalId: 'svc-1', principalType: 'service', serviceIdentityId: 'svc-1', scopes: ['x'] },
    SECRET,
  )
  const ctx = extractTrustContext(makeReq({ headers, remoteAddress: REMOTE }), bindPolicy)
  assert.equal(ctx.authenticationMethod, 'proxy-signature')
  assert.equal(ctx.principalId, 'svc-1')
  assert.equal(ctx.principalType, 'service')
  assert.equal(ctx.serviceIdentityId, 'svc-1')
  assert.deepEqual(ctx.scopes, ['x'])
  assert.deepEqual(ctx.roles, ['service-runner'])
})

// ---------------------------------------------------------------------------
// 9. Forged / unsigned identity headers are rejected
// ---------------------------------------------------------------------------

test('extractTrustContext: forged x-proxy-* headers are ignored (loopback)', () => {
  const forged = {
    'x-proxy-principal-id': 'attacker',
    'x-proxy-principal-type': 'service',
    'x-proxy-service-identity-id': 'attacker',
    'x-proxy-scopes': 'superuser',
    'x-proxy-timestamp': String(Date.now()),
    'x-proxy-signature': 'forged-signature',
  }
  const ctx = extractTrustContext(makeReq({ headers: forged }), bindPolicy)
  assert.equal(ctx.authenticationMethod, 'loopback-dev')
  assert.notEqual(ctx.principalId, 'attacker')
  assert.equal(ctx.principalId, 'local-dev-user')
  assert.deepEqual(ctx.scopes, ['*'])
})

test('extractTrustContext: forged x-proxy-* headers are ignored (remote anonymous)', () => {
  const forged = {
    'x-proxy-principal-id': 'attacker',
    'x-proxy-principal-type': 'service',
    'x-proxy-timestamp': String(Date.now()),
    'x-proxy-signature': 'forged-signature',
  }
  const ctx = extractTrustContext(makeReq({ headers: forged, remoteAddress: REMOTE }), bindPolicy)
  assert.equal(ctx.authenticationMethod, 'anonymous')
  assert.equal(ctx.principalId, null)
  assert.equal(ctx.principalType, 'human')
  assert.deepEqual(ctx.scopes, [])
})

test('extractTrustContext: headers signed with another secret are ignored', () => {
  const headers = signProxyHeaders({ principalId: 'attacker', principalType: 'service' }, WRONG_SECRET)
  const ctx = extractTrustContext(makeReq({ headers, remoteAddress: REMOTE }), bindPolicy)
  assert.equal(ctx.authenticationMethod, 'anonymous')
  assert.equal(ctx.principalId, null)
})

// ---------------------------------------------------------------------------
// 10. Memberships are never read from client headers
// ---------------------------------------------------------------------------

test('extractTrustContext: client membership headers are ignored (loopback principal)', () => {
  const headers = {
    'x-factory-actor-id': 'user-1',
    'x-organization-id': 'org-evil',
    'x-workstream-id': 'ws-evil',
    'x-squad-id': 'squad-evil',
    'x-roles': 'superadmin',
  }
  const ctx = extractTrustContext(makeReq({ headers }), bindPolicy)
  assert.equal(ctx.principalId, 'user-1')
  assert.equal(ctx.organizationId, 'org-alpha')
  assert.equal(ctx.workstreamId, 'ws-alpha')
  assert.equal(ctx.squadId, 'squad-alpha')
  assert.deepEqual(ctx.roles, ['admin', 'reviewer'])
})

test('extractTrustContext: client membership headers cannot override a valid JWT', () => {
  const token = issueJwt({ principalId: 'user-1', principalType: 'human', scopes: ['read'] }, SECRET)
  const headers = {
    authorization: `Bearer ${token}`,
    'x-organization-id': 'org-evil',
    'x-workstream-id': 'ws-evil',
    'x-roles': 'superadmin',
  }
  const ctx = extractTrustContext(makeReq({ headers, remoteAddress: REMOTE }), bindPolicy)
  assert.equal(ctx.organizationId, 'org-alpha')
  assert.equal(ctx.workstreamId, 'ws-alpha')
  assert.deepEqual(ctx.roles, ['admin', 'reviewer'])
})

test('extractTrustContext: an unknown principal resolves to no membership', () => {
  const token = issueJwt({ principalId: 'ghost', principalType: 'human' }, SECRET)
  const ctx = extractTrustContext(makeReq({ headers: { authorization: `Bearer ${token}` }, remoteAddress: REMOTE }), bindPolicy)
  // The MockMembershipResolver falls back to a local-dev default for known
  // shapes; use a strict resolver to prove an unknown principal gets nothing.
  const strict = new LocalDevMembershipResolver({}, false)
  const strictCtx = extractTrustContext(
    makeReq({ headers: { authorization: `Bearer ${token}` }, remoteAddress: REMOTE }),
    { trustMode: 'loopback-only', identity: { membershipResolver: strict, fakeIdpSecret: SECRET } },
  )
  assert.equal(strictCtx.principalId, 'ghost')
  assert.equal(strictCtx.organizationId, null)
  assert.deepEqual(strictCtx.roles, [])
  // sanity: the default resolver did resolve *something* server-side
  assert.equal(ctx.principalId, 'ghost')
})

// ---------------------------------------------------------------------------
// 11. Legacy compatibility
// ---------------------------------------------------------------------------

test('extractTrustContext: legacy fields are preserved', () => {
  const headers = {
    'x-factory-namespace-id': 'ns-1',
    'x-factory-case-id': 'case-1',
    'x-factory-actor-id': 'user-1',
    'x-factory-authority-id': 'auth-1',
    'x-factory-runtime-id': 'runtime-1',
    'x-factory-agent-id': 'agent-1',
    'x-factory-thread-id': 'thread-1',
    'x-correlation-id': 'corr-123',
  }
  const ctx = extractTrustContext(makeReq({ headers }), bindPolicy)
  assert.equal(ctx.namespaceId, 'ns-1')
  assert.equal(ctx.caseId, 'case-1')
  assert.equal(ctx.actorId, 'user-1')
  assert.equal(ctx.authorityId, 'auth-1')
  assert.equal(ctx.runtimeId, 'runtime-1')
  assert.equal(ctx.agentId, 'agent-1')
  assert.equal(ctx.threadId, 'thread-1')
  assert.equal(ctx.correlationId, 'corr-123')
  assert.equal(ctx.trustMode, 'loopback-only')
  assert.equal(ctx.loopback, true)
})

test('extractTrustContext: correlation id is generated when absent', () => {
  const ctx = extractTrustContext(makeReq({}), bindPolicy)
  assert.equal(typeof ctx.correlationId, 'string')
  assert.ok(ctx.correlationId.startsWith('coday-corr-'))
})

// ---------------------------------------------------------------------------
// 12. Loopback vs anonymous fallback
// ---------------------------------------------------------------------------

test('extractTrustContext: loopback → loopback-dev, remote → anonymous', () => {
  const loopbackCtx = extractTrustContext(makeReq({ remoteAddress: LOOPBACK }), bindPolicy)
  assert.equal(loopbackCtx.loopback, true)
  assert.equal(loopbackCtx.authenticationMethod, 'loopback-dev')
  assert.equal(loopbackCtx.principalId, 'local-dev-user')
  assert.deepEqual(loopbackCtx.scopes, ['*'])

  const remoteCtx = extractTrustContext(makeReq({ remoteAddress: REMOTE }), bindPolicy)
  assert.equal(remoteCtx.loopback, false)
  assert.equal(remoteCtx.authenticationMethod, 'anonymous')
  assert.equal(remoteCtx.principalId, null)
  assert.deepEqual(remoteCtx.scopes, [])
})

// ---------------------------------------------------------------------------
// 13. Impersonation disabled by default
// ---------------------------------------------------------------------------

test('extractTrustContext: impersonation/delegation claims are dropped', () => {
  const token = issueJwt(
    {
      principalId: 'user-1',
      principalType: 'human',
      impersonatedBy: 'admin',
      delegationChain: ['admin', 'user-1'],
    },
    SECRET,
  )
  const ctx = extractTrustContext(makeReq({ headers: { authorization: `Bearer ${token}` }, remoteAddress: REMOTE }), bindPolicy)
  assert.equal(ctx.impersonatedBy, null)
  assert.equal(ctx.delegationChain, null)
})

test('createAnonymous/createLoopback factories keep impersonation null', () => {
  const anonymous = createAnonymousTrustContext({ principalId: null })
  const loopback = createLoopbackDevTrustContext({ principalId: 'local-dev-user' })
  assert.equal(anonymous.authenticationMethod, 'anonymous')
  assert.equal(anonymous.impersonatedBy, null)
  assert.equal(anonymous.delegationChain, null)
  assert.equal(loopback.authenticationMethod, 'loopback-dev')
  assert.deepEqual(loopback.scopes, ['*'])
  assert.equal(loopback.impersonatedBy, null)
  assert.equal(loopback.delegationChain, null)
})

// ---------------------------------------------------------------------------
// 14. Validation + default resolver
// ---------------------------------------------------------------------------

test('validateTrustContext accepts the produced contexts', () => {
  const ctx = extractTrustContext(makeReq({}), bindPolicy)
  const report = validateTrustContext(ctx)
  assert.equal(report.valid, true, report.errors.join('; '))
  assert.equal(isPrincipalType(ctx.principalType), true)
  assert.equal(isAuthenticationMethod(ctx.authenticationMethod), true)
})

test('validateTrustContext flags an impersonating context', () => {
  const ctx = extractTrustContext(makeReq({}), bindPolicy)
  const report = validateTrustContext({ ...ctx, impersonatedBy: 'admin' })
  assert.equal(report.valid, false)
  assert.ok(report.errors.some((error) => error.includes('impersonatedBy')))
})

test('extractTrustContext: default resolver is used when none is injected', () => {
  const ctx = extractTrustContext(makeReq({}))
  assert.equal(ctx.authenticationMethod, 'loopback-dev')
  assert.equal(ctx.organizationId, 'org-local-dev')
  assert.deepEqual(ctx.roles, ['developer'])
})

test('DEFAULT_FAKE_IDP_SECRET is exported for dev wiring', () => {
  assert.equal(typeof DEFAULT_FAKE_IDP_SECRET, 'string')
})

console.log(`\nResult: ${passed} passed, ${failed} failed`)
process.exit(failed ? 1 : 0)
