/**
 * HTTP boundary hardening tests (Milestone B, wave B6, task T2b).
 *
 * Locks the fail-closed contract of the Factory HTTP edge for a shared,
 * multi-user server, WITHOUT changing any public signature:
 *
 *   1. `anonymous` = strictly zero privilege (`scopes: []`, `roles: []`,
 *      `principalId: null`, no org/workstream/squad).
 *   2. `loopback-dev` is refused unless `FACTORY_ALLOW_LOOPBACK_DEV === 'true'`.
 *   3. `loopback-dev` is refused for a non-loopback socket address even when the
 *      flag is set.
 *   4. The wildcard `*` scope is only ever attributed by the boundary to an
 *      explicitly-authorized `loopback-dev` request.
 *   5. CORS is restricted to a configured allow-list
 *      (`FACTORY_ALLOWED_ORIGINS` / `FACTORY_CORS_ORIGIN`); no hardcoded `*`.
 *   6. Remote unauthenticated access fails closed, including for protected
 *      admin operations.
 *
 * Usage : node factory/tests/test-boundary-hardening.mjs
 * Code de sortie : 0 = tous les cas passent, 1 = au moins un échec.
 */

import assert from 'node:assert/strict'

import { MockMembershipResolver, issueJwt, signProxyHeaders } from '../src/domain/identity/index.ts'
import { checkAdminAuthorization, extractTrustContext, requireAdminRole, resolveCorsOrigin, send } from '../dashboard/http-utils.mjs'
import { createIdentityBoundaryOptions, loadConfig, resolveFactoryBindPolicy } from '../dashboard/composition-root.mjs'

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

const SECRET = 'boundary-hardening-secret'
const LOOPBACK = '127.0.0.1'
const LOOPBACK_V6 = '::1'
const REMOTE = '203.0.113.9'
const ORIGINAL_LOOPBACK_FLAG = process.env.FACTORY_ALLOW_LOOPBACK_DEV

// Strict resolver: an unknown principal resolves to no membership at all.
const resolver = new MockMembershipResolver(
  { 'user-1': { organizationId: 'org-1', workstreamId: 'ws-1', squadId: 'sq-1', roles: ['admin'] } },
  false,
)

const identity = () => ({ membershipResolver: resolver, fakeIdpSecret: SECRET })

function makeReq({ headers = {}, remoteAddress = LOOPBACK } = {}) {
  return { headers, socket: { remoteAddress } }
}

function withLoopbackFlag(value, fn) {
  if (value === undefined) delete process.env.FACTORY_ALLOW_LOOPBACK_DEV
  else process.env.FACTORY_ALLOW_LOOPBACK_DEV = value
  try {
    return fn()
  } finally {
    if (ORIGINAL_LOOPBACK_FLAG === undefined) delete process.env.FACTORY_ALLOW_LOOPBACK_DEV
    else process.env.FACTORY_ALLOW_LOOPBACK_DEV = ORIGINAL_LOOPBACK_FLAG
  }
}

// ---------------------------------------------------------------------------
// 1. anonymous = zero privilege
// ---------------------------------------------------------------------------

test('anonymous remote request resolves to strictly zero privilege', () => {
  const ctx = extractTrustContext(makeReq({ remoteAddress: REMOTE }), { identity: identity() })
  assert.equal(ctx.authenticationMethod, 'anonymous')
  assert.equal(ctx.principalId, null)
  assert.deepEqual(ctx.scopes, [])
  assert.deepEqual(ctx.roles, [])
  assert.equal(ctx.organizationId, null)
  assert.equal(ctx.workstreamId, null)
  assert.equal(ctx.squadId, null)
  assert.equal(ctx.serviceIdentityId, null)
})

test('anonymous is forced to zero privilege even with a permissive resolver', () => {
  const permissive = {
    resolveMembership: () => ({ organizationId: 'org-x', workstreamId: 'ws-x', squadId: 'sq-x', roles: ['admin'] }),
  }
  const ctx = extractTrustContext(makeReq({ remoteAddress: REMOTE }), {
    identity: { membershipResolver: permissive, fakeIdpSecret: SECRET },
  })
  assert.equal(ctx.authenticationMethod, 'anonymous')
  assert.equal(ctx.principalId, null)
  assert.deepEqual(ctx.roles, [])
  assert.equal(ctx.organizationId, null)
  assert.equal(ctx.workstreamId, null)
  assert.equal(ctx.squadId, null)
})

// ---------------------------------------------------------------------------
// 2. loopback refused without the explicit flag
// ---------------------------------------------------------------------------

test('loopback is refused (anonymous) when FACTORY_ALLOW_LOOPBACK_DEV is unset', () => {
  withLoopbackFlag(undefined, () => {
    const ctx = extractTrustContext(makeReq({ remoteAddress: LOOPBACK }), { identity: identity() })
    assert.equal(ctx.authenticationMethod, 'anonymous')
    assert.equal(ctx.principalId, null)
    assert.deepEqual(ctx.scopes, [])
    assert.deepEqual(ctx.roles, [])
  })
})

test('loopback is refused (anonymous) when the flag is not exactly "true"', () => {
  for (const value of ['1', 'TRUE', 'True', 'yes', 'false', '']) {
    withLoopbackFlag(value, () => {
      const ctx = extractTrustContext(makeReq({ remoteAddress: LOOPBACK }), { identity: identity() })
      assert.equal(ctx.authenticationMethod, 'anonymous', `flag=${JSON.stringify(value)} must refuse loopback-dev`)
      assert.deepEqual(ctx.scopes, [], `flag=${JSON.stringify(value)} must not grant the wildcard`)
    })
  }
})

test('explicit allowLoopbackDev:false wins over a bare policy', () => {
  const ctx = extractTrustContext(makeReq({ remoteAddress: LOOPBACK }), {
    allowLoopbackDev: false,
    identity: identity(),
  })
  assert.equal(ctx.authenticationMethod, 'anonymous')
})

test('createIdentityBoundaryOptions gates allowLoopbackDev on the env flag', () => {
  assert.equal(createIdentityBoundaryOptions({}).allowLoopbackDev, false)
  assert.equal(createIdentityBoundaryOptions({ FACTORY_ALLOW_LOOPBACK_DEV: 'true' }).allowLoopbackDev, true)
  assert.equal(createIdentityBoundaryOptions({ FACTORY_ALLOW_LOOPBACK_DEV: 'TRUE' }).allowLoopbackDev, false)
  assert.equal(createIdentityBoundaryOptions({ FACTORY_ALLOW_LOOPBACK_DEV: '1' }).allowLoopbackDev, false)
})

test('loadConfig default bind policy refuses loopback-dev without opt-in', () => {
  withLoopbackFlag(undefined, () => {
    const config = loadConfig({ FACTORY_BIND_HOST: '127.0.0.1' })
    assert.equal(config.bindPolicy.identity.allowLoopbackDev, false)
  })
})

// ---------------------------------------------------------------------------
// 3. non-loopback refused even with the flag
// ---------------------------------------------------------------------------

test('a non-loopback address stays anonymous even with the flag on', () => {
  const ctx = extractTrustContext(makeReq({ remoteAddress: REMOTE }), {
    allowLoopbackDev: true,
    identity: identity(),
  })
  assert.equal(ctx.authenticationMethod, 'anonymous')
  assert.equal(ctx.principalId, null)
  assert.deepEqual(ctx.scopes, [])
})

test('a spoofed X-Forwarded-For does not make a remote socket loopback', () => {
  const ctx = extractTrustContext(
    makeReq({ headers: { 'x-forwarded-for': '127.0.0.1' }, remoteAddress: REMOTE }),
    { allowLoopbackDev: true, identity: identity() },
  )
  assert.equal(ctx.authenticationMethod, 'anonymous')
  assert.deepEqual(ctx.scopes, [])
})

// ---------------------------------------------------------------------------
// 4. loopback-dev only when loopback AND explicitly allowed
// ---------------------------------------------------------------------------

test('loopback + explicit opt-in yields loopback-dev with the wildcard scope', () => {
  const ctx = extractTrustContext(makeReq({ remoteAddress: LOOPBACK }), {
    allowLoopbackDev: true,
    identity: identity(),
  })
  assert.equal(ctx.authenticationMethod, 'loopback-dev')
  assert.deepEqual(ctx.scopes, ['*'])
})

test('IPv6 loopback is accepted with the flag', () => {
  const ctx = extractTrustContext(makeReq({ remoteAddress: LOOPBACK_V6 }), {
    allowLoopbackDev: true,
    identity: identity(),
  })
  assert.equal(ctx.authenticationMethod, 'loopback-dev')
  assert.deepEqual(ctx.scopes, ['*'])
})

test('the env flag enables loopback-dev for a bare policy', () => {
  withLoopbackFlag('true', () => {
    const ctx = extractTrustContext(makeReq({ remoteAddress: LOOPBACK }), { identity: identity() })
    assert.equal(ctx.authenticationMethod, 'loopback-dev')
    assert.deepEqual(ctx.scopes, ['*'])
  })
})

test('loadConfig + env flag enables loopback-dev end to end', () => {
  withLoopbackFlag('true', () => {
    const config = loadConfig({ FACTORY_BIND_HOST: '127.0.0.1', FACTORY_ALLOW_LOOPBACK_DEV: 'true' })
    assert.equal(config.bindPolicy.identity.allowLoopbackDev, true)
    const ctx = extractTrustContext(makeReq({ remoteAddress: LOOPBACK }), config.bindPolicy)
    assert.equal(ctx.authenticationMethod, 'loopback-dev')
    assert.deepEqual(ctx.scopes, ['*'])
  })
})

// ---------------------------------------------------------------------------
// 5. the wildcard is never attributed outside loopback-dev
// ---------------------------------------------------------------------------

test('a valid JWT keeps exactly its verified scopes (no implicit wildcard)', () => {
  const token = issueJwt({ principalId: 'user-1', scopes: ['read'] }, SECRET)
  const ctx = extractTrustContext(
    makeReq({ headers: { authorization: `Bearer ${token}` }, remoteAddress: REMOTE }),
    { allowLoopbackDev: true, identity: identity() },
  )
  assert.equal(ctx.authenticationMethod, 'jwt')
  assert.deepEqual(ctx.scopes, ['read'])
  assert.ok(!ctx.scopes.includes('*'))
})

test('a signed proxy request keeps exactly its verified scopes (no implicit wildcard)', () => {
  const headers = signProxyHeaders({ principalId: 'svc-1', principalType: 'service', scopes: ['build'] }, SECRET)
  const ctx = extractTrustContext(makeReq({ headers, remoteAddress: REMOTE }), {
    allowLoopbackDev: true,
    identity: identity(),
  })
  assert.equal(ctx.authenticationMethod, 'proxy-signature')
  assert.deepEqual(ctx.scopes, ['build'])
  assert.ok(!ctx.scopes.includes('*'))
})

test('refused loopback never carries the wildcard scope', () => {
  withLoopbackFlag(undefined, () => {
    const ctx = extractTrustContext(makeReq({ remoteAddress: LOOPBACK }), { identity: identity() })
    assert.deepEqual(ctx.scopes, [])
    assert.ok(!ctx.scopes.includes('*'))
  })
})

// ---------------------------------------------------------------------------
// 6. CORS restricted to a configured allow-list
// ---------------------------------------------------------------------------

test('resolveCorsOrigin reflects an allow-listed origin', () => {
  assert.equal(
    resolveCorsOrigin({ headers: { origin: 'https://ok.example' } }, ['https://ok.example']),
    'https://ok.example',
  )
})

test('resolveCorsOrigin denies a non-listed origin', () => {
  assert.equal(resolveCorsOrigin({ headers: { origin: 'https://evil.example' } }, ['https://ok.example']), null)
})

test('resolveCorsOrigin denies everything when unconfigured or origin-less', () => {
  assert.equal(resolveCorsOrigin({ headers: { origin: 'https://ok.example' } }, []), null)
  assert.equal(resolveCorsOrigin({ headers: { origin: 'https://ok.example' } }, undefined), null)
  assert.equal(resolveCorsOrigin({ headers: {} }, ['https://ok.example']), null)
})

test('resolveCorsOrigin honors an explicit wildcard opt-in', () => {
  assert.equal(resolveCorsOrigin({ headers: { origin: 'https://any.example' } }, ['*']), '*')
})

test('loadConfig parses FACTORY_ALLOWED_ORIGINS / FACTORY_CORS_ORIGIN and defaults to safe', () => {
  const none = loadConfig({ FACTORY_BIND_HOST: '127.0.0.1' })
  assert.deepEqual(none.corsAllowedOrigins, [])

  const list = loadConfig({
    FACTORY_BIND_HOST: '127.0.0.1',
    FACTORY_ALLOWED_ORIGINS: 'https://a.example, https://b.example',
  })
  assert.deepEqual(list.corsAllowedOrigins, ['https://a.example', 'https://b.example'])

  const alias = loadConfig({ FACTORY_BIND_HOST: '127.0.0.1', FACTORY_CORS_ORIGIN: 'https://c.example' })
  assert.deepEqual(alias.corsAllowedOrigins, ['https://c.example'])
})

test('send() emits the resolved CORS origin and omits it when unconfigured', () => {
  const capture = () => {
    const state = {}
    const res = {
      writeHead: (status, headers) => {
        state.status = status
        state.headers = headers
      },
      end: (data) => {
        state.body = data
      },
    }
    return { state, res }
  }

  const allowed = capture()
  allowed.res.corsOrigin = 'https://ok.example'
  send(allowed.res, 200, { ok: true })
  assert.equal(allowed.state.headers['Access-Control-Allow-Origin'], 'https://ok.example')
  assert.equal(allowed.state.headers['Vary'], 'Origin')

  const denied = capture()
  denied.res.corsOrigin = null
  send(denied.res, 200, { ok: true })
  assert.equal('Access-Control-Allow-Origin' in denied.state.headers, false)
})

// ---------------------------------------------------------------------------
// 7. remote unauthenticated access fails closed
// ---------------------------------------------------------------------------

test('anonymous cannot pass admin authorization (fail-closed)', () => {
  const ctx = extractTrustContext(makeReq({ remoteAddress: REMOTE }), { identity: identity() })
  assert.equal(checkAdminAuthorization(ctx).authorized, false)
  assert.throws(
    () => requireAdminRole(ctx),
    (error) => error.statusCode === 403 && error.code === 'FORBIDDEN_ADMIN_REQUIRED',
  )
})

test('loopback-dev with the wildcard does pass admin authorization', () => {
  const ctx = extractTrustContext(makeReq({ remoteAddress: LOOPBACK }), {
    allowLoopbackDev: true,
    identity: identity(),
  })
  assert.equal(checkAdminAuthorization(ctx).authorized, true)
  assert.equal(requireAdminRole(ctx), true)
})

test('a refused loopback request cannot pass admin authorization', () => {
  withLoopbackFlag(undefined, () => {
    const ctx = extractTrustContext(makeReq({ remoteAddress: LOOPBACK }), { identity: identity() })
    assert.equal(checkAdminAuthorization(ctx).authorized, false)
  })
})

// ---------------------------------------------------------------------------
// 8. bind policy fails closed for remote exposure
// ---------------------------------------------------------------------------

test('resolveFactoryBindPolicy rejects a remote bind without explicit opt-in', () => {
  assert.throws(() => resolveFactoryBindPolicy({ FACTORY_BIND_HOST: '0.0.0.0' }), /loopback/)
})

test('resolveFactoryBindPolicy flags an explicit unsafe remote bind', () => {
  const policy = resolveFactoryBindPolicy({
    FACTORY_BIND_HOST: '0.0.0.0',
    FACTORY_UNSAFE_ALLOW_REMOTE_BIND: 'true',
  })
  assert.equal(policy.trustMode, 'unsafe-remote-unauthenticated')
})

test('a remote request under the unsafe-remote policy still resolves to anonymous', () => {
  const policy = resolveFactoryBindPolicy({
    FACTORY_BIND_HOST: '0.0.0.0',
    FACTORY_UNSAFE_ALLOW_REMOTE_BIND: 'true',
  })
  const ctx = extractTrustContext(makeReq({ remoteAddress: REMOTE }), { ...policy, identity: identity() })
  assert.equal(ctx.authenticationMethod, 'anonymous')
  assert.deepEqual(ctx.scopes, [])
  assert.equal(ctx.principalId, null)
})

console.log(`\nResult: ${passed} passed, ${failed} failed`)
process.exit(failed ? 1 : 0)
