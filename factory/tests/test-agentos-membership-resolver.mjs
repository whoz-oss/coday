/**
 * `AgentOsMembershipResolver` offline tests (Milestone B, wave B6, task B6-T1).
 *
 * Verifies the AgentOS directory → Factory membership mapping and its security
 * guarantees, entirely offline (no network, no AgentOS, no compiler):
 *
 *   1.  Role derivation: `ADMIN` → `admin`, `MEMBER` → `dev`.
 *   2.  Case normalization (`AdMiN` → `admin`, `DeVeLoPeR` → `dev`).
 *   3.  Role de-duplication.
 *   4.  Namespace projection (`namespaceId` / `namespaceExternalId` →
 *       `workstreamId`), `squadId` strictly `null`.
 *   5.  Instance `organizationId` (option, env, default).
 *   6.  Service principals: `service-runner`, never admin, no directory call.
 *   7.  Async directory clients (promise resolution).
 *   8.  Cache hit returns synchronously and satisfies `resolveMembershipSync`.
 *   9.  TTL expiry re-fetches; `primeCache` / `getCachedMembership` /
 *       `clearCache` behave.
 *   10. Fail-closed: directory down, unknown principal, invalid response.
 *   11. Fail-closed default when an async resolver is used synchronously.
 *
 * Usage : node factory/tests/test-agentos-membership-resolver.mjs
 * Exit code : 0 = all cases pass, 1 = at least one failure.
 */

import assert from 'node:assert/strict'

import { AgentOsMembershipResolver, EMPTY_MEMBERSHIP, resolveMembershipSync } from '../src/domain/identity/index.ts'

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

let passed = 0
let failed = 0
const tests = []

function test(name, fn) {
  tests.push([name, fn])
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/** Run `fn` with the given env overrides applied, then restore them. */
function withEnv(vars, fn) {
  const saved = {}
  for (const [key, value] of Object.entries(vars)) {
    saved[key] = process.env[key]
    if (value === undefined) delete process.env[key]
    else process.env[key] = value
  }
  try {
    return fn()
  } finally {
    for (const [key, value] of Object.entries(saved)) {
      if (value === undefined) delete process.env[key]
      else process.env[key] = value
    }
  }
}

// ---------------------------------------------------------------------------
// Fake AgentOS directory client (in-memory, offline)
// ---------------------------------------------------------------------------

class FakeDirectoryClient {
  constructor(users = {}, { mode = 'sync' } = {}) {
    this.users = new Map(Object.entries(users))
    this.mode = mode
    this.calls = 0
    this.error = null
    this.requestedIds = []
  }

  setUser(principalId, user) {
    this.users.set(principalId, user)
  }

  getDirectoryUser(principalId) {
    this.calls++
    this.requestedIds.push(principalId)
    if (this.error) {
      if (this.mode === 'async') return Promise.reject(this.error)
      throw this.error
    }
    const result = this.users.has(principalId) ? this.users.get(principalId) : null
    if (this.mode === 'async') return Promise.resolve(result)
    return result
  }
}

const ADMIN_USER = {
  principalId: 'admin@example.com',
  roles: ['ADMIN'],
  groups: [],
  namespaceId: 'ns-alpha',
}

// ---------------------------------------------------------------------------
// 1-3. Role derivation & normalization
// ---------------------------------------------------------------------------

test('maps AgentOS ADMIN to Factory admin', () => {
  const client = new FakeDirectoryClient({ 'admin@example.com': ADMIN_USER })
  const resolver = new AgentOsMembershipResolver({ directoryClient: client, cacheTtlMs: 0 })
  const membership = resolver.resolveMembership('admin@example.com', 'human')
  assert.deepEqual(membership.roles, ['admin'])
})

test('maps AgentOS MEMBER to Factory dev', () => {
  const client = new FakeDirectoryClient({
    'member@example.com': { principalId: 'member@example.com', roles: ['MEMBER'] },
  })
  const resolver = new AgentOsMembershipResolver({ directoryClient: client, cacheTtlMs: 0 })
  const membership = resolver.resolveMembership('member@example.com', 'human')
  assert.deepEqual(membership.roles, ['dev'])
})

test('normalizes role casing (AdMiN -> admin, DeVeLoPeR -> dev)', () => {
  const client = new FakeDirectoryClient({
    'mixed@example.com': { principalId: 'mixed@example.com', roles: ['AdMiN', 'DeVeLoPeR'] },
  })
  const resolver = new AgentOsMembershipResolver({ directoryClient: client, cacheTtlMs: 0 })
  const membership = resolver.resolveMembership('mixed@example.com', 'human')
  assert.deepEqual(membership.roles, ['admin', 'dev'])
})

test('de-duplicates roles across roles and groups', () => {
  const client = new FakeDirectoryClient({
    'dupe@example.com': {
      principalId: 'dupe@example.com',
      roles: ['ADMIN', 'admin', 'MEMBER'],
      groups: ['ADMIN', 'member'],
    },
  })
  const resolver = new AgentOsMembershipResolver({ directoryClient: client, cacheTtlMs: 0 })
  const membership = resolver.resolveMembership('dupe@example.com', 'human')
  assert.deepEqual(membership.roles, ['admin', 'dev'])
})

test('normalizes an unknown role token to lowercase', () => {
  const client = new FakeDirectoryClient({
    'reviewer@example.com': { principalId: 'reviewer@example.com', roles: ['REVIEWER'] },
  })
  const resolver = new AgentOsMembershipResolver({ directoryClient: client, cacheTtlMs: 0 })
  const membership = resolver.resolveMembership('reviewer@example.com', 'human')
  assert.deepEqual(membership.roles, ['reviewer'])
})

// ---------------------------------------------------------------------------
// 4. Namespace projection & squad id
// ---------------------------------------------------------------------------

test('projects namespaceId to workstreamId and forces squadId to null', () => {
  const client = new FakeDirectoryClient({
    'ns@example.com': { principalId: 'ns@example.com', roles: ['MEMBER'], namespaceId: 'ns-alpha' },
  })
  const resolver = new AgentOsMembershipResolver({ directoryClient: client, cacheTtlMs: 0 })
  const membership = resolver.resolveMembership('ns@example.com', 'human')
  assert.equal(membership.workstreamId, 'ns-alpha')
  assert.equal(membership.squadId, null)
})

test('falls back to namespaceExternalId when namespaceId is absent', () => {
  const client = new FakeDirectoryClient({
    'ext@example.com': {
      principalId: 'ext@example.com',
      roles: ['MEMBER'],
      namespaceExternalId: 'external-ns',
    },
  })
  const resolver = new AgentOsMembershipResolver({ directoryClient: client, cacheTtlMs: 0 })
  const membership = resolver.resolveMembership('ext@example.com', 'human')
  assert.equal(membership.workstreamId, 'external-ns')
})

test('namespaceId wins over namespaceExternalId and empty strings are ignored', () => {
  const client = new FakeDirectoryClient({
    'both@example.com': {
      principalId: 'both@example.com',
      roles: ['MEMBER'],
      namespaceId: 'ns-internal',
      namespaceExternalId: 'ns-external',
    },
    'blank@example.com': {
      principalId: 'blank@example.com',
      roles: ['MEMBER'],
      namespaceId: '   ',
      namespaceExternalId: 'ignored-fallback',
    },
  })
  const resolver = new AgentOsMembershipResolver({ directoryClient: client, cacheTtlMs: 0 })
  assert.equal(resolver.resolveMembership('both@example.com', 'human').workstreamId, 'ns-internal')
  assert.equal(resolver.resolveMembership('blank@example.com', 'human').workstreamId, 'ignored-fallback')
})

// ---------------------------------------------------------------------------
// 5. organizationId configuration
// ---------------------------------------------------------------------------

test('uses the configured organizationId option', () => {
  const client = new FakeDirectoryClient({ 'admin@example.com': ADMIN_USER })
  const resolver = new AgentOsMembershipResolver({
    directoryClient: client,
    organizationId: 'org-custom',
    cacheTtlMs: 0,
  })
  const membership = resolver.resolveMembership('admin@example.com', 'human')
  assert.equal(membership.organizationId, 'org-custom')
})

test('falls back to FACTORY_ORGANIZATION_ID then to org-local-dev', () => {
  const client = new FakeDirectoryClient({ 'admin@example.com': ADMIN_USER })
  withEnv({ FACTORY_ORGANIZATION_ID: 'org-from-env' }, () => {
    const fromEnv = new AgentOsMembershipResolver({ directoryClient: client, cacheTtlMs: 0 })
    assert.equal(fromEnv.resolveMembership('admin@example.com', 'human').organizationId, 'org-from-env')
  })
  withEnv({ FACTORY_ORGANIZATION_ID: undefined }, () => {
    const fromDefault = new AgentOsMembershipResolver({ directoryClient: client, cacheTtlMs: 0 })
    assert.equal(fromDefault.resolveMembership('admin@example.com', 'human').organizationId, 'org-local-dev')
  })
})

// ---------------------------------------------------------------------------
// 6. Service principals
// ---------------------------------------------------------------------------

test('service principal gets service-runner, configured org, null workstream/squad', () => {
  const client = new FakeDirectoryClient({
    'svc@example.com': { principalId: 'svc@example.com', roles: ['ADMIN'], namespaceId: 'ns-svc' },
  })
  const resolver = new AgentOsMembershipResolver({
    directoryClient: client,
    organizationId: 'org-svc',
    cacheTtlMs: 0,
  })
  const membership = resolver.resolveMembership('svc@example.com', 'service')
  assert.deepEqual(membership, {
    organizationId: 'org-svc',
    workstreamId: null,
    squadId: null,
    roles: ['service-runner'],
  })
  assert.equal(client.calls, 0, 'service principals must not hit the directory')
})

test('service principal is never granted admin even if directory reports ADMIN', () => {
  const client = new FakeDirectoryClient({
    'svc-admin@example.com': { principalId: 'svc-admin@example.com', roles: ['ADMIN'] },
  })
  const resolver = new AgentOsMembershipResolver({ directoryClient: client, cacheTtlMs: 0 })
  const membership = resolver.resolveMembership('svc-admin@example.com', 'service')
  assert.equal(membership.roles.includes('admin'), false)
  assert.deepEqual(membership.roles, ['service-runner'])
})

// ---------------------------------------------------------------------------
// 7. Async directory clients
// ---------------------------------------------------------------------------

test('async directory client resolves through a promise', async () => {
  const client = new FakeDirectoryClient({ 'async@example.com': ADMIN_USER }, { mode: 'async' })
  const resolver = new AgentOsMembershipResolver({ directoryClient: client, cacheTtlMs: 1000 })
  const pending = resolver.resolveMembership('async@example.com', 'human')
  assert.equal(typeof pending.then, 'function')
  const membership = await pending
  assert.deepEqual(membership.roles, ['admin'])
  assert.equal(membership.workstreamId, 'ns-alpha')
})

// ---------------------------------------------------------------------------
// 8. Cache hit is synchronous & compatible with resolveMembershipSync
// ---------------------------------------------------------------------------

test('cache hit returns synchronously and is compatible with resolveMembershipSync', async () => {
  const client = new FakeDirectoryClient({ 'cached@example.com': ADMIN_USER }, { mode: 'async' })
  const resolver = new AgentOsMembershipResolver({ directoryClient: client, cacheTtlMs: 60_000 })

  await resolver.resolveMembership('cached@example.com', 'human')
  const second = resolver.resolveMembership('cached@example.com', 'human')
  assert.equal(typeof second.then, 'undefined', 'cached resolution must be synchronous')
  assert.deepEqual(second.roles, ['admin'])
  assert.equal(client.calls, 1, 'cache hit must not re-query the directory')

  const viaBoundary = resolveMembershipSync(resolver, 'cached@example.com', 'human')
  assert.deepEqual(viaBoundary.roles, ['admin'])
  assert.equal(viaBoundary.organizationId, 'org-local-dev')
  assert.equal(viaBoundary.workstreamId, 'ns-alpha')
})

test('resolver.resolveMembershipSync serves cached values without blocking', async () => {
  const client = new FakeDirectoryClient({ 'method@example.com': ADMIN_USER }, { mode: 'async' })
  const resolver = new AgentOsMembershipResolver({ directoryClient: client, cacheTtlMs: 60_000 })

  // Un-warmed async lookup fails closed synchronously.
  assert.deepEqual(resolver.resolveMembershipSync('method@example.com', 'human'), EMPTY_MEMBERSHIP)

  await resolver.resolveMembership('method@example.com', 'human')
  const sync = resolver.resolveMembershipSync('method@example.com', 'human')
  assert.equal(typeof sync.then, 'undefined')
  assert.deepEqual(sync.roles, ['admin'])

  // Service principals resolve synchronously without any directory access.
  assert.deepEqual(resolver.resolveMembershipSync('svc@example.com', 'service'), {
    organizationId: 'org-local-dev',
    workstreamId: null,
    squadId: null,
    roles: ['service-runner'],
  })
})

test('un-warmed async resolver fails closed at the synchronous boundary', () => {
  const client = new FakeDirectoryClient({ 'cold@example.com': ADMIN_USER }, { mode: 'async' })
  const resolver = new AgentOsMembershipResolver({ directoryClient: client, cacheTtlMs: 60_000 })
  const viaBoundary = resolveMembershipSync(resolver, 'cold@example.com', 'human')
  assert.deepEqual(viaBoundary, { organizationId: null, workstreamId: null, squadId: null, roles: [] })
})

// ---------------------------------------------------------------------------
// 9. TTL expiry & cache management helpers
// ---------------------------------------------------------------------------

test('cache entry expires after the configured TTL', async () => {
  const client = new FakeDirectoryClient({ 'ttl@example.com': ADMIN_USER })
  const resolver = new AgentOsMembershipResolver({ directoryClient: client, cacheTtlMs: 30 })

  resolver.resolveMembership('ttl@example.com', 'human')
  resolver.resolveMembership('ttl@example.com', 'human')
  assert.equal(client.calls, 1, 'within TTL the cached value is reused')

  await sleep(60)
  const refreshed = resolver.resolveMembership('ttl@example.com', 'human')
  assert.equal(client.calls, 2, 'after TTL the directory is queried again')
  assert.deepEqual(refreshed.roles, ['admin'])
})

test('honours FACTORY_MEMBERSHIP_CACHE_TTL_MS when no option is given', async () => {
  const client = new FakeDirectoryClient({ 'envttl@example.com': ADMIN_USER })
  const resolver = withEnv({ FACTORY_MEMBERSHIP_CACHE_TTL_MS: '25' }, () =>
    new AgentOsMembershipResolver({ directoryClient: client })
  )
  resolver.resolveMembership('envttl@example.com', 'human')
  assert.equal(client.calls, 1)
  assert.notEqual(resolver.getCachedMembership('envttl@example.com'), null)
  await sleep(50)
  assert.equal(resolver.getCachedMembership('envttl@example.com'), null)
})

test('primeCache / getCachedMembership / clearCache manage the cache', () => {
  const client = new FakeDirectoryClient({})
  const resolver = new AgentOsMembershipResolver({ directoryClient: client, cacheTtlMs: 60_000 })

  resolver.primeCache('primed@example.com', {
    organizationId: 'org-x',
    workstreamId: 'ws-x',
    squadId: null,
    roles: ['admin'],
  })
  const cached = resolver.getCachedMembership('primed@example.com')
  assert.deepEqual(cached, {
    organizationId: 'org-x',
    workstreamId: 'ws-x',
    squadId: null,
    roles: ['admin'],
  })
  assert.equal(client.calls, 0, 'primeCache never touches the directory')
  assert.equal(resolver.cacheSize, 1)

  // The primed value is served synchronously without I/O.
  const served = resolver.resolveMembership('primed@example.com', 'human')
  assert.equal(typeof served.then, 'undefined')
  assert.deepEqual(served.roles, ['admin'])
  assert.equal(client.calls, 0)

  resolver.clearCache('primed@example.com')
  assert.equal(resolver.getCachedMembership('primed@example.com'), null)
  assert.equal(resolver.cacheSize, 0)

  resolver.primeCache('a@example.com', { organizationId: null, workstreamId: null, squadId: null, roles: [] })
  resolver.primeCache('b@example.com', { organizationId: null, workstreamId: null, squadId: null, roles: [] })
  resolver.clearCache()
  assert.equal(resolver.cacheSize, 0)
})

test('a non-positive TTL disables caching', () => {
  const client = new FakeDirectoryClient({ 'nocache@example.com': ADMIN_USER })
  const resolver = new AgentOsMembershipResolver({ directoryClient: client, cacheTtlMs: 0 })
  resolver.resolveMembership('nocache@example.com', 'human')
  assert.equal(resolver.getCachedMembership('nocache@example.com'), null)
  resolver.resolveMembership('nocache@example.com', 'human')
  assert.equal(client.calls, 2)
})

// ---------------------------------------------------------------------------
// 10-11. Fail-closed guarantees
// ---------------------------------------------------------------------------

test('fails closed when the directory throws (sync)', () => {
  const client = new FakeDirectoryClient()
  client.error = new Error('directory unavailable')
  const resolver = new AgentOsMembershipResolver({ directoryClient: client, cacheTtlMs: 1000 })
  const membership = resolver.resolveMembership('unknown@example.com', 'human')
  assert.deepEqual(membership, EMPTY_MEMBERSHIP)
})

test('fails closed when the directory rejects (async)', async () => {
  const client = new FakeDirectoryClient({}, { mode: 'async' })
  client.error = new Error('directory unavailable')
  const resolver = new AgentOsMembershipResolver({ directoryClient: client, cacheTtlMs: 1000 })
  const membership = await resolver.resolveMembership('unknown@example.com', 'human')
  assert.deepEqual(membership, EMPTY_MEMBERSHIP)
})

test('fails closed for an unknown principal (null response)', () => {
  const client = new FakeDirectoryClient()
  const resolver = new AgentOsMembershipResolver({ directoryClient: client, cacheTtlMs: 1000 })
  const membership = resolver.resolveMembership('nobody@example.com', 'human')
  assert.deepEqual(membership, EMPTY_MEMBERSHIP)
  assert.equal(resolver.getCachedMembership('nobody@example.com'), null)
})

test('fails closed for invalid response structures and never throws', () => {
  const invalidResponses = [
    'ADMIN',
    42,
    ['ADMIN'],
    { roles: 'ADMIN' },
    { roles: [1, 2] },
    { roles: ['ADMIN'], groups: 'team' },
    { namespaceId: 42 },
    { principalId: 7 },
  ]
  for (const response of invalidResponses) {
    const client = new FakeDirectoryClient({ 'bad@example.com': response })
    const resolver = new AgentOsMembershipResolver({ directoryClient: client, cacheTtlMs: 1000 })
    let membership
    assert.doesNotThrow(() => {
      membership = resolver.resolveMembership('bad@example.com', 'human')
    })
    assert.deepEqual(membership, EMPTY_MEMBERSHIP)
  }
})

test('returns EMPTY_MEMBERSHIP synchronously for a null/blank principal id', () => {
  const client = new FakeDirectoryClient({})
  const resolver = new AgentOsMembershipResolver({ directoryClient: client, cacheTtlMs: 1000 })
  for (const principalId of [null, '', '   ']) {
    const membership = resolver.resolveMembership(principalId, 'human')
    assert.equal(typeof membership.then, 'undefined')
    assert.deepEqual(membership, EMPTY_MEMBERSHIP)
  }
  assert.equal(client.calls, 0)
})

// ---------------------------------------------------------------------------
// Runner
// ---------------------------------------------------------------------------

async function run() {
  for (const [name, fn] of tests) {
    try {
      await fn()
      console.log(`✓ ${name}`)
      passed++
    } catch (error) {
      console.error(`✗ ${name}\n   ${error?.stack ?? error?.message ?? error}`)
      failed++
    }
  }
  console.log(`\nResult: ${passed} passed, ${failed} failed`)
  process.exitCode = failed === 0 ? 0 : 1
}

await run()
