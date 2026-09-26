/**
 * Coday identity bridge tests (Milestone B, wave B6, task B6-T2a).
 *
 * Verifies, entirely offline (no network, no AgentOS, no compiler), that a
 * Coday identity (an email resolved by the trusted proxy) can be bridged into
 * a signed Fake IdP JWT that the *unmodified* `extractTrustContext` boundary
 * accepts:
 *
 *   1.  Email -> bridge JWT -> verified TrustContext (`authenticationMethod`
 *       `'jwt'`, `principalId === email`, `principalType`, `scopes`).
 *   2.  Defaults: `principalType` is `'human'`, `scopes` is `[]`.
 *   3.  Custom `principalType: 'service'` and `audience`.
 *   4.  Rejection / fallback: unsigned, wrong secret, expired, tampered.
 *   5.  Fail-closed on missing / blank / malformed email and parameters.
 *   6.  `CodayIdentityBridge` class API mirrors the free functions.
 *   7.  Unsigned client headers never authorize a request.
 *
 * Usage : node factory/tests/test-coday-identity-bridge.mjs
 * Exit code : 0 = all cases pass, 1 = at least one failure.
 */

import assert from 'node:assert/strict'

import {
  CodayIdentityBridge,
  CodayIdentityError,
  issueCodayIdentityToken,
  mintCodayIdentityToken,
  normalizeCodayIdentity,
  tryMintCodayIdentityToken,
  issueJwt,
  verifyJwt,
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

const SECRET = 'test-coday-identity-secret'
const WRONG_SECRET = 'another-secret'
const LOOPBACK = '127.0.0.1'
const REMOTE = '10.0.0.7'
const EMAIL = 'user@whoz.com'

const bindPolicy = {
  trustMode: 'loopback-only',
  identity: { fakeIdpSecret: SECRET },
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

function assertThrowsIdentityError(fn, reason) {
  assert.throws(fn, (error) => {
    assert.ok(error instanceof CodayIdentityError, `expected CodayIdentityError, got ${error?.name}: ${error?.message}`)
    if (reason !== undefined) assert.equal(error.reason, reason)
    return true
  })
}

// ---------------------------------------------------------------------------
// 1. Email -> bridge JWT -> verified TrustContext
// ---------------------------------------------------------------------------

test('bridge JWT is verified by verifyJwt with principalId === email', () => {
  const token = mintCodayIdentityToken({ email: EMAIL, scopes: ['read', 'write'] }, SECRET)
  const result = verifyJwt(token, SECRET)
  assert.equal(result.valid, true)
  assert.equal(result.claims.principalId, EMAIL)
  assert.equal(result.claims.sub, EMAIL)
  assert.equal(result.claims.principalType, 'human')
  assert.deepEqual(result.claims.scopes, ['read', 'write'])
  assert.equal(result.claims.iss, 'coday-fake-idp')
})

test('extractTrustContext authenticates the bridged identity as a JWT', () => {
  const token = mintCodayIdentityToken({ email: EMAIL, scopes: ['read', 'write'] }, SECRET)
  const ctx = extractTrustContext(
    makeReq({ headers: { authorization: `Bearer ${token}` }, remoteAddress: REMOTE }),
    bindPolicy,
  )
  assert.equal(ctx.authenticationMethod, 'jwt')
  assert.equal(ctx.principalId, EMAIL)
  assert.equal(ctx.principalType, 'human')
  assert.deepEqual(ctx.scopes, ['read', 'write'])
  assert.equal(ctx.serviceIdentityId, null)
})

test('bridge trims the email but preserves its exact principalId', () => {
  const token = mintCodayIdentityToken({ email: '  user@whoz.com  ' }, SECRET)
  const ctx = extractTrustContext(
    makeReq({ headers: { authorization: `Bearer ${token}` }, remoteAddress: REMOTE }),
    bindPolicy,
  )
  assert.equal(ctx.principalId, 'user@whoz.com')
})

// ---------------------------------------------------------------------------
// 2. Defaults
// ---------------------------------------------------------------------------

test('principalType defaults to human and scopes to []', () => {
  const token = mintCodayIdentityToken({ email: EMAIL }, SECRET)
  const result = verifyJwt(token, SECRET)
  assert.equal(result.valid, true)
  assert.equal(result.claims.principalType, 'human')
  assert.deepEqual(result.claims.scopes, [])
  assert.equal(result.claims.aud, 'coday-factory')
})

test('issueCodayIdentityToken aliases mintCodayIdentityToken', () => {
  assert.equal(issueCodayIdentityToken, mintCodayIdentityToken)
})

// ---------------------------------------------------------------------------
// 3. Custom principalType + audience
// ---------------------------------------------------------------------------

test('service principal bridge carries principalType, audience and scopes', () => {
  const token = mintCodayIdentityToken(
    { email: EMAIL, principalType: 'service', audience: 'custom-audience', scopes: ['build'] },
    SECRET,
  )
  const result = verifyJwt(token, SECRET, { audience: 'custom-audience' })
  assert.equal(result.valid, true)
  assert.equal(result.claims.principalType, 'service')
  assert.deepEqual(result.claims.scopes, ['build'])
  assert.equal(verifyJwt(token, SECRET, { audience: 'other' }).reason, 'invalid-audience')

  const ctx = extractTrustContext(
    makeReq({ headers: { authorization: `Bearer ${token}` }, remoteAddress: REMOTE }),
    bindPolicy,
  )
  assert.equal(ctx.authenticationMethod, 'jwt')
  assert.equal(ctx.principalId, EMAIL)
  assert.equal(ctx.principalType, 'service')
  assert.deepEqual(ctx.scopes, ['build'])
})

// ---------------------------------------------------------------------------
// 4. Rejection / fallback
// ---------------------------------------------------------------------------

test('extractTrustContext ignores a token signed with the wrong secret', () => {
  const token = mintCodayIdentityToken({ email: EMAIL }, WRONG_SECRET)
  assert.equal(verifyJwt(token, SECRET).valid, false)
  assert.equal(verifyJwt(token, SECRET).reason, 'invalid-signature')
  const ctx = extractTrustContext(
    makeReq({ headers: { authorization: `Bearer ${token}` }, remoteAddress: REMOTE }),
    bindPolicy,
  )
  assert.notEqual(ctx.authenticationMethod, 'jwt')
  assert.equal(ctx.authenticationMethod, 'anonymous')
  assert.equal(ctx.principalId, null)
})

test('extractTrustContext ignores an expired bridged token', () => {
  const token = mintCodayIdentityToken({ email: EMAIL, expiresInSeconds: -60 }, SECRET)
  assert.equal(verifyJwt(token, SECRET).reason, 'expired')
  const ctx = extractTrustContext(
    makeReq({ headers: { authorization: `Bearer ${token}` }, remoteAddress: REMOTE }),
    bindPolicy,
  )
  assert.notEqual(ctx.authenticationMethod, 'jwt')
  assert.equal(ctx.authenticationMethod, 'anonymous')
  assert.equal(ctx.principalId, null)
})

test('extractTrustContext ignores a tampered bridged token', () => {
  const token = mintCodayIdentityToken({ email: EMAIL, scopes: ['read'] }, SECRET)
  const tampered = tamperJwt(token, EMAIL, 'attacker@whoz.com')
  assert.equal(verifyJwt(tampered, SECRET).reason, 'invalid-signature')
  const ctx = extractTrustContext(
    makeReq({ headers: { authorization: `Bearer ${tampered}` }, remoteAddress: REMOTE }),
    bindPolicy,
  )
  assert.notEqual(ctx.authenticationMethod, 'jwt')
  assert.notEqual(ctx.principalId, 'attacker@whoz.com')
})

test('extractTrustContext ignores an unsigned / malformed token', () => {
  for (const bearer of ['not-a-jwt', 'header.payload.', 'a.b.c']) {
    const ctx = extractTrustContext(
      makeReq({ headers: { authorization: `Bearer ${bearer}` }, remoteAddress: REMOTE }),
      bindPolicy,
    )
    assert.notEqual(ctx.authenticationMethod, 'jwt')
    assert.equal(ctx.principalId, null)
  }
})

// ---------------------------------------------------------------------------
// 5. Fail-closed validation
// ---------------------------------------------------------------------------

test('mintCodayIdentityToken fails closed on missing / blank email', () => {
  assertThrowsIdentityError(() => mintCodayIdentityToken({}, SECRET), 'missing-email')
  assertThrowsIdentityError(() => mintCodayIdentityToken(null, SECRET), 'invalid-options')
  assertThrowsIdentityError(() => mintCodayIdentityToken({ email: undefined }, SECRET), 'missing-email')
  assertThrowsIdentityError(() => mintCodayIdentityToken({ email: '' }, SECRET), 'blank-email')
  assertThrowsIdentityError(() => mintCodayIdentityToken({ email: '   ' }, SECRET), 'blank-email')
  assertThrowsIdentityError(() => mintCodayIdentityToken({ email: 123 }, SECRET), 'missing-email')
})

test('mintCodayIdentityToken fails closed on malformed email', () => {
  assertThrowsIdentityError(() => mintCodayIdentityToken({ email: 'not-an-email' }, SECRET), 'invalid-email')
  assertThrowsIdentityError(() => mintCodayIdentityToken({ email: 'user@whoz' }, SECRET), 'invalid-email')
  assertThrowsIdentityError(() => mintCodayIdentityToken({ email: 'a b@whoz.com' }, SECRET), 'invalid-email')
})

test('mintCodayIdentityToken fails closed on malformed parameters', () => {
  assertThrowsIdentityError(() => mintCodayIdentityToken({ email: EMAIL, scopes: 'read' }, SECRET), 'invalid-scopes')
  assertThrowsIdentityError(() => mintCodayIdentityToken({ email: EMAIL, scopes: [''] }, SECRET), 'invalid-scopes')
  assertThrowsIdentityError(
    () => mintCodayIdentityToken({ email: EMAIL, principalType: 'robot' }, SECRET),
    'invalid-principal-type',
  )
  assertThrowsIdentityError(() => mintCodayIdentityToken({ email: EMAIL, audience: '' }, SECRET), 'invalid-audience')
  assertThrowsIdentityError(
    () => mintCodayIdentityToken({ email: EMAIL, expiresInSeconds: Number.NaN }, SECRET),
    'invalid-expires-in',
  )
  assertThrowsIdentityError(() => mintCodayIdentityToken({ email: EMAIL }, ''), 'invalid-secret')
})

test('tryMintCodayIdentityToken returns null instead of throwing (fail-closed)', () => {
  assert.equal(tryMintCodayIdentityToken({}, SECRET), null)
  assert.equal(tryMintCodayIdentityToken({ email: '  ' }, SECRET), null)
  assert.equal(tryMintCodayIdentityToken({ email: 'nope' }, SECRET), null)
  assert.equal(typeof tryMintCodayIdentityToken({ email: EMAIL }, SECRET), 'string')
})

test('normalizeCodayIdentity exposes the validated, normalized identity', () => {
  const identity = normalizeCodayIdentity({ email: ' user@whoz.com ', scopes: [' a ', 'b'] }, SECRET)
  assert.equal(identity.email, 'user@whoz.com')
  assert.equal(identity.principalType, 'human')
  assert.deepEqual(identity.scopes, ['a', 'b'])
  assert.equal(identity.secret, SECRET)
  assert.equal(identity.audience, undefined)
  assert.equal(identity.expiresInSeconds, undefined)
})

// ---------------------------------------------------------------------------
// 6. Class API
// ---------------------------------------------------------------------------

test('CodayIdentityBridge.mintToken matches the free function', () => {
  const bridge = new CodayIdentityBridge(SECRET)
  const token = bridge.mintToken({ email: EMAIL, scopes: ['read'] })
  const result = verifyJwt(token, SECRET)
  assert.equal(result.valid, true)
  assert.equal(result.claims.principalId, EMAIL)
  assert.deepEqual(result.claims.scopes, ['read'])
  assert.equal(bridge.tryMintToken({ email: '' }), null)
})

test('CodayIdentityBridge rejects a non-string explicit secret', () => {
  assert.throws(() => new CodayIdentityBridge(123), CodayIdentityError)
  assert.throws(() => new CodayIdentityBridge(''), CodayIdentityError)
})

// ---------------------------------------------------------------------------
// 7. Unsigned client headers never authorize
// ---------------------------------------------------------------------------

test('forged unsigned identity headers do not authorize a remote request', () => {
  const forged = {
    'x-proxy-principal-id': 'attacker@whoz.com',
    'x-proxy-principal-type': 'service',
    'x-proxy-scopes': 'admin:*',
    'x-factory-actor-id': 'attacker@whoz.com',
    authorization: 'Bearer user@whoz.com',
  }
  const ctx = extractTrustContext(makeReq({ headers: forged, remoteAddress: REMOTE }), bindPolicy)
  assert.notEqual(ctx.authenticationMethod, 'jwt')
  assert.equal(ctx.authenticationMethod, 'anonymous')
  assert.equal(ctx.principalId, null)
})

test('a valid bridged JWT wins over unsigned forged headers', () => {
  const token = mintCodayIdentityToken({ email: EMAIL, scopes: ['read'] }, SECRET)
  const headers = {
    authorization: `Bearer ${token}`,
    'x-proxy-principal-id': 'attacker@whoz.com',
    'x-proxy-principal-type': 'service',
    'x-proxy-scopes': 'admin:*',
    'x-organization-id': 'org-evil',
  }
  const ctx = extractTrustContext(makeReq({ headers, remoteAddress: REMOTE }), bindPolicy)
  assert.equal(ctx.authenticationMethod, 'jwt')
  assert.equal(ctx.principalId, EMAIL)
  assert.equal(ctx.principalType, 'human')
  assert.deepEqual(ctx.scopes, ['read'])
})

// ---------------------------------------------------------------------------
// Regression cross-check: a raw issueJwt token still works with the bridge API
// ---------------------------------------------------------------------------

test('bridge and raw Fake IdP interoperate on the same secret', () => {
  const raw = issueJwt({ principalId: EMAIL, principalType: 'human', scopes: ['raw'] }, SECRET)
  const ctx = extractTrustContext(
    makeReq({ headers: { authorization: `Bearer ${raw}` }, remoteAddress: REMOTE }),
    bindPolicy,
  )
  assert.equal(ctx.authenticationMethod, 'jwt')
  assert.equal(ctx.principalId, EMAIL)
  assert.deepEqual(ctx.scopes, ['raw'])
})

console.log(`\nResult: ${passed} passed, ${failed} failed`)
process.exit(failed ? 1 : 0)
