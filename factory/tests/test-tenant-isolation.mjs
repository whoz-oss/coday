/**
 * Tenant isolation & entitlement enforcement tests (Milestone B, wave B6, task B6-T3).
 *
 * Offline, no framework and no network: exits 0 when every case passes, 1
 * otherwise. The suite proves that multi-tenant scoping and entitlement
 * authorization are *effective end to end*, using an in-memory SQL client and
 * the offline identity primitives:
 *
 *   1.  Tenant scope resolution from a verified `TrustContext` (fail-closed).
 *   2.  Cross-workstream READ rejection across repositories.
 *   3.  Cross-workstream WRITE rejection across repositories (no side effect).
 *   4.  Cross-organization isolation (read + write).
 *   5.  AgentOS `ADMIN` -> `admin` / `MEMBER` -> `dev` entitlement mapping.
 *   6.  Namespace-scoped admin: an admin of one workstream is NOT admin of another.
 *   7.  Client header tampering is discarded by `extractTrustContext`.
 *   8.  Artifact admin commands (purge / legal-hold / GC) pass the real
 *       entitlement check; unauthenticated or non-admin callers get 403
 *       `FORBIDDEN_ADMIN_REQUIRED`.
 *
 * Usage : node factory/tests/test-tenant-isolation.mjs
 * Code de sortie : 0 = tous les cas passent, 1 = au moins un échec.
 */

import assert from 'node:assert/strict'

import {
  ADMIN_AUTHORIZATION_REASONS,
  MockMembershipResolver,
  TENANT_SCOPE_REASONS,
  authorizeAdminAccess,
  hasAdminEntitlement,
  issueJwt,
  requireTenantScope,
  resolvePrincipalEntitlements,
  resolveTenantScope,
  sameTenantScope,
} from '../src/domain/identity/index.ts'
import {
  MemoryArtifactStore,
  SqlAgentStepResultRepository,
  SqlWorkflowEvidenceRepository,
  SqlWorkflowInstanceRepository,
  SqlWorkUnitRepository,
  createSqlArtifactMetadataRepository,
} from '../runtime/factory-operational.mjs'
import { checkAdminAuthorization, extractTrustContext, requireAdminRole } from '../dashboard/http-utils.mjs'
import { handleArtifactAdminRequest } from '../dashboard/artifact-admin-routes.mjs'
import { createInMemorySqlClient } from './support/in-memory-sql-client.mjs'

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

let passed = 0
let failed = 0

async function test(name, fn) {
  try {
    await fn()
    passed++
    console.log(`✓ ${name}`)
  } catch (error) {
    failed++
    console.log(`✗ ${name}`)
    console.log(`  ${error?.stack ?? error}`)
  }
}

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

const SECRET = 'tenant-isolation-secret'
const REMOTE = '203.0.113.9'
const LOOPBACK = '127.0.0.1'
const NAMESPACE = '11111111-1111-4111-8111-111111111111'
const BRIEF_HASH = `sha256:${'a'.repeat(64)}`

const ORG_A = 'org-a'
const ORG_B = 'org-b'
const WS_A = 'ws-a'
const WS_B = 'ws-b'

const DEFINITION = {
  workflowType: 'demo',
  version: '1.0.0',
  definitionHash: 'a'.repeat(64),
  steps: [{ id: 'build', name: 'Build', responsibility: { kind: 'agent', name: 'worker' }, dependsOn: [] }],
}
const CONTROLLER = { runtimeId: 'rt-1', kind: 'factory', agentId: 'worker', caseId: 'case-1', actorId: 'actor-1' }
const START_COMMAND = { workflowId: 'wf-iso-1', workflowType: 'demo', title: 'Isolation run' }

async function thrownCode(fn) {
  try {
    await fn()
    return null
  } catch (error) {
    return error?.code ?? error?.message ?? String(error)
  }
}

function makeReq({ headers = {}, remoteAddress = LOOPBACK } = {}) {
  return { headers, socket: { remoteAddress } }
}

// The same in-memory database is shared by every tenant-scoped repository, so a
// leak (unscoped query) is observable as data crossing the tenant boundary.
function makeClient() {
  return createInMemorySqlClient()
}

// ---------------------------------------------------------------------------
// 1. Tenant scope resolution (from the verified TrustContext, fail-closed)
// ---------------------------------------------------------------------------

await test('resolveTenantScope returns the verified organization + workstream', () => {
  const decision = resolveTenantScope({
    authenticationMethod: 'jwt',
    organizationId: ORG_A,
    workstreamId: WS_A,
  })
  assert.deepEqual(decision, { scope: { organizationId: ORG_A, workstreamId: WS_A }, reason: null })
  assert.deepEqual(requireTenantScope({ organizationId: ORG_A, workstreamId: WS_A }), {
    organizationId: ORG_A,
    workstreamId: WS_A,
  })
  assert.equal(sameTenantScope({ organizationId: ORG_A, workstreamId: WS_A }, { organizationId: ORG_A, workstreamId: WS_A }), true)
  assert.equal(sameTenantScope({ organizationId: ORG_A, workstreamId: WS_A }, { organizationId: ORG_B, workstreamId: WS_A }), false)
})

await test('resolveTenantScope fails closed on anonymous / missing / blank scope', () => {
  assert.deepEqual(resolveTenantScope(null), { scope: null, reason: TENANT_SCOPE_REASONS.MISSING_TRUST_CONTEXT })
  assert.deepEqual(resolveTenantScope({ authenticationMethod: 'anonymous', organizationId: ORG_A, workstreamId: WS_A }), {
    scope: null,
    reason: TENANT_SCOPE_REASONS.UNAUTHENTICATED,
  })
  assert.deepEqual(resolveTenantScope({ organizationId: ORG_A }), {
    scope: null,
    reason: TENANT_SCOPE_REASONS.MISSING_WORKSTREAM_ID,
  })
  assert.deepEqual(resolveTenantScope({ workstreamId: WS_A }), {
    scope: null,
    reason: TENANT_SCOPE_REASONS.MISSING_ORGANIZATION_ID,
  })
  assert.throws(
    () => requireTenantScope({ authenticationMethod: 'anonymous', organizationId: ORG_A, workstreamId: WS_A }),
    (error) => error.code === 'TENANT_SCOPE_REQUIRED' && error.reason === TENANT_SCOPE_REASONS.UNAUTHENTICATED,
  )
})

// ---------------------------------------------------------------------------
// 2 & 3. Cross-workstream read + write rejection (repositories)
// ---------------------------------------------------------------------------

await test('repository scope is threaded from the authenticated TrustContext, never from headers', async () => {
  const resolver = new MockMembershipResolver(
    {
      'owner@example.com': { organizationId: ORG_A, workstreamId: WS_A, squadId: null, roles: ['admin'] },
      'other@example.com': { organizationId: ORG_A, workstreamId: WS_B, squadId: null, roles: ['dev'] },
    },
    false,
  )
  const bindPolicy = { identity: { membershipResolver: resolver, fakeIdpSecret: SECRET } }
  const contextFor = (principalId) =>
    extractTrustContext(
      makeReq({
        remoteAddress: REMOTE,
        headers: {
          authorization: `Bearer ${issueJwt({ principalId }, SECRET)}`,
          // Tampered client headers must not influence the resolved scope.
          'x-organization-id': 'org-evil',
          'x-workstream-id': 'ws-evil',
        },
      }),
      bindPolicy,
    )

  const scopeA = requireTenantScope(contextFor('owner@example.com'))
  const scopeB = requireTenantScope(contextFor('other@example.com'))
  assert.deepEqual(scopeA, { organizationId: ORG_A, workstreamId: WS_A })
  assert.deepEqual(scopeB, { organizationId: ORG_A, workstreamId: WS_B })

  const client = makeClient()
  const repoA = new SqlWorkflowInstanceRepository(client, scopeA)
  const repoB = new SqlWorkflowInstanceRepository(client, scopeB)
  await repoA.create(NAMESPACE, START_COMMAND, DEFINITION, CONTROLLER)

  assert.ok(await repoA.get(NAMESPACE, START_COMMAND.workflowId), 'the authenticated owner sees its instance')
  assert.equal(await repoB.get(NAMESPACE, START_COMMAND.workflowId), null, 'the other workstream does not')
})

await test('workflow instances: another workstream cannot read or mutate this one', async () => {
  const client = makeClient()
  const repoA = new SqlWorkflowInstanceRepository(client, { organizationId: ORG_A, workstreamId: WS_A })
  const repoB = new SqlWorkflowInstanceRepository(client, { organizationId: ORG_A, workstreamId: WS_B })

  await repoA.create(NAMESPACE, START_COMMAND, DEFINITION, CONTROLLER)

  // READ rejection: invisible / null / empty.
  assert.equal(await repoB.get(NAMESPACE, START_COMMAND.workflowId), null)
  assert.deepEqual(await repoB.list(NAMESPACE), [])
  assert.ok(await repoA.get(NAMESPACE, START_COMMAND.workflowId), 'the owner still sees its instance')

  // WRITE rejection: a transition / removal scoped to another workstream is refused.
  assert.equal(
    await thrownCode(() =>
      repoB.transition(NAMESPACE, START_COMMAND.workflowId, {
        request: {
          requestId: 'req-cross',
          workflowId: START_COMMAND.workflowId,
          stepId: 'build',
          expectedRevision: 1,
          requestedStatus: 'running',
          evidenceIds: [],
        },
        definition: DEFINITION,
        evidence: [],
        execution: { kind: 'factory', runtimeId: 'rt-1', agentId: 'worker', caseId: 'case-1' },
      }),
    ),
    'WORKFLOW_NOT_FOUND',
  )
  assert.equal(await thrownCode(() => repoB.remove(NAMESPACE, START_COMMAND.workflowId)), 'WORKFLOW_NOT_FOUND')

  // No side effect on the owner's aggregate.
  const untouched = await repoA.get(NAMESPACE, START_COMMAND.workflowId)
  assert.equal(untouched.instance.revision, 1)
})

await test('workflow evidence: another workstream cannot read this one', async () => {
  const client = makeClient()
  const repoA = new SqlWorkflowEvidenceRepository(client, { organizationId: ORG_A, workstreamId: WS_A })
  const repoB = new SqlWorkflowEvidenceRepository(client, { organizationId: ORG_A, workstreamId: WS_B })

  const source = { kind: 'factory', runtimeId: 'rt-1', agentId: 'worker' }
  const storageId = 'wf-1'
  await repoA.record(
    NAMESPACE,
    storageId,
    { workflowId: 'wf-1', stepId: 'build', kind: 'agent-result', outcome: 'pass', facts: { attempt: 1 } },
    source,
  )

  assert.equal((await repoA.list(NAMESPACE, storageId)).length, 1)
  assert.deepEqual(await repoB.list(NAMESPACE, storageId), [])
})

await test('work units: another workstream cannot read or mutate this one', async () => {
  const client = makeClient()
  const repoA = new SqlWorkUnitRepository(client, { organizationId: ORG_A, workstreamId: WS_A })
  const repoB = new SqlWorkUnitRepository(client, { organizationId: ORG_A, workstreamId: WS_B })

  await repoA.create({ workUnitId: 'unit-iso', unitType: 'build' })

  assert.equal(await repoB.get('unit-iso'), null)
  assert.deepEqual(await repoB.list(), [])
  assert.equal(await thrownCode(() => repoB.update('unit-iso', { priority: 5 }, 1)), 'NOT_FOUND')
  assert.equal(await thrownCode(() => repoB.transition('unit-iso', 'assigned', 1)), 'NOT_FOUND')

  const untouched = await repoA.get('unit-iso')
  assert.equal(untouched.revision, 1)
  assert.equal(untouched.status, 'created')
})

await test('agent-step results: a capability token from another workstream is not redeemable', async () => {
  const client = makeClient()
  const repoA = new SqlAgentStepResultRepository(client, { organizationId: ORG_A, workstreamId: WS_A })
  const repoB = new SqlAgentStepResultRepository(client, { organizationId: ORG_A, workstreamId: WS_B })

  const identity = {
    attemptId: 'attempt-cross',
    workflowId: 'wf-cap',
    stepId: 'step-1',
    namespaceId: NAMESPACE,
    caseId: 'case-1',
    agentName: 'Agent',
    briefHash: BRIEF_HASH,
  }
  const issued = await repoA.issue(NAMESPACE, 'storage-cap', identity)
  const observed = { attemptId: identity.attemptId, caseId: identity.caseId, agentName: identity.agentName }
  const business = { status: 'PASS', summary: 'ok', claims: { modifiedFiles: ['a.ts'] } }

  // Cross-workstream redemption is refused (the token scan is tenant-scoped).
  const cross = await repoB.submit(issued.token, business, observed)
  assert.deepEqual(cross, { ok: false, code: 'RESULT_CAPABILITY_INVALID' })

  // The owner can still redeem it.
  const own = await repoA.submit(issued.token, business, observed)
  assert.equal(own.ok, true)
})

await test('artifact metadata: another workstream cannot read or mutate this one', async () => {
  const client = makeClient()
  const repository = createSqlArtifactMetadataRepository(client)
  const scopeA = { organizationId: ORG_A, workstreamId: WS_A }
  const scopeB = { organizationId: ORG_A, workstreamId: WS_B }

  const metadata = {
    id: 'art-iso-1',
    owner: 'ns/flow',
    hash: `sha256:${'b'.repeat(64)}`,
    size: 3,
    contentType: 'text/plain',
    availabilityStatus: 'available',
    retentionStatus: 'active',
    legalHold: false,
    createdAt: new Date().toISOString(),
  }
  await repository.saveMetadata(metadata, 'objects/art-iso-1', scopeA)

  // READ rejection.
  assert.equal(await repository.getMetadata('art-iso-1', scopeB), null)
  assert.ok(await repository.getMetadata('art-iso-1', scopeA), 'the owner still reads its row')

  // WRITE rejection: no legal hold, no purge.
  assert.equal(await repository.updateLegalHold('art-iso-1', true, 'cross', new Date(), scopeB), null)
  assert.equal(await repository.purgeArtifact('art-iso-1', 'cross', new Date(), scopeB), false)

  const untouched = await repository.getMetadata('art-iso-1', scopeA)
  assert.equal(untouched.legalHold, false)
  assert.equal(untouched.availabilityStatus, 'available')
})

// ---------------------------------------------------------------------------
// 4. Cross-organization isolation
// ---------------------------------------------------------------------------

await test('workflow instances: another organization cannot read or mutate this one', async () => {
  const client = makeClient()
  const repoA = new SqlWorkflowInstanceRepository(client, { organizationId: ORG_A, workstreamId: WS_A })
  const otherOrg = new SqlWorkflowInstanceRepository(client, { organizationId: ORG_B, workstreamId: WS_A })

  await repoA.create(NAMESPACE, START_COMMAND, DEFINITION, CONTROLLER)

  assert.equal(await otherOrg.get(NAMESPACE, START_COMMAND.workflowId), null)
  assert.deepEqual(await otherOrg.list(NAMESPACE), [])
  assert.equal(await thrownCode(() => otherOrg.remove(NAMESPACE, START_COMMAND.workflowId)), 'WORKFLOW_NOT_FOUND')
  assert.ok(await repoA.get(NAMESPACE, START_COMMAND.workflowId))
})

await test('work units: another organization cannot read or mutate this one', async () => {
  const client = makeClient()
  const repoA = new SqlWorkUnitRepository(client, { organizationId: ORG_A, workstreamId: WS_A })
  const otherOrg = new SqlWorkUnitRepository(client, { organizationId: ORG_B, workstreamId: WS_A })

  await repoA.create({ workUnitId: 'unit-org', unitType: 'test' })
  assert.equal(await otherOrg.get('unit-org'), null)
  assert.equal(await thrownCode(() => otherOrg.update('unit-org', { priority: 3 }, 1)), 'NOT_FOUND')
  assert.ok(await repoA.get('unit-org'))
})

// ---------------------------------------------------------------------------
// 5. Real entitlement resolution (AgentOS role mapping)
// ---------------------------------------------------------------------------

await test('AgentOS ADMIN maps to admin, MEMBER maps to dev (fail-closed otherwise)', () => {
  assert.equal(hasAdminEntitlement({ roles: ['ADMIN'], scopes: [] }), true)
  assert.equal(hasAdminEntitlement({ roles: ['admin'], scopes: [] }), true)
  assert.equal(hasAdminEntitlement({ roles: ['MEMBER'], scopes: [] }), false)
  assert.equal(hasAdminEntitlement({ roles: ['dev'], scopes: [] }), false)
  assert.equal(hasAdminEntitlement({ roles: [], scopes: ['admin:*'] }), true)
  assert.equal(hasAdminEntitlement({ roles: [], scopes: ['*'] }), true)
  assert.equal(hasAdminEntitlement({ roles: ['viewer'], scopes: ['read'] }), false)
  assert.equal(hasAdminEntitlement(null), false)

  const entitlements = resolvePrincipalEntitlements({ roles: ['MEMBER'], principalId: 'dev@example.com' })
  assert.deepEqual(entitlements.roles, ['dev'])
  assert.equal(entitlements.principalId, 'dev@example.com')
  assert.equal(entitlements.isAdmin, false)
})

await test('an unauthenticated context has zero privilege even with admin roles', () => {
  assert.equal(hasAdminEntitlement({ authenticationMethod: 'anonymous', roles: ['admin'], scopes: ['*'] }), false)
  const decision = authorizeAdminAccess({ authenticationMethod: 'anonymous', roles: ['admin'], scopes: [] })
  assert.deepEqual(decision, { authorized: false, reason: ADMIN_AUTHORIZATION_REASONS.UNAUTHENTICATED })
})

// ---------------------------------------------------------------------------
// 6. Namespace-scoped admin (cross-workstream admin is refused)
// ---------------------------------------------------------------------------

await test('an admin of one workstream is NOT admin of another', () => {
  const wsAdmin = {
    authenticationMethod: 'jwt',
    principalId: 'admin@example.com',
    roles: ['admin'],
    organizationId: ORG_A,
    workstreamId: WS_A,
  }
  // Role check (no target) passes.
  assert.equal(checkAdminAuthorization(wsAdmin).authorized, true)
  // Same namespace passes.
  assert.equal(authorizeAdminAccess(wsAdmin, { organizationId: ORG_A, workstreamId: WS_A }).authorized, true)
  // Another workstream in the same organization is refused.
  assert.deepEqual(authorizeAdminAccess(wsAdmin, { organizationId: ORG_A, workstreamId: WS_B }), {
    authorized: false,
    reason: ADMIN_AUTHORIZATION_REASONS.OUT_OF_NAMESPACE,
  })
  // Another organization is refused.
  assert.equal(authorizeAdminAccess(wsAdmin, { organizationId: ORG_B, workstreamId: WS_A }).authorized, false)
})

await test('namespace admin derived from the verified TrustContext end to end', () => {
  const resolver = new MockMembershipResolver({
    'admin@example.com': { organizationId: ORG_A, workstreamId: WS_A, squadId: null, roles: ['admin'] },
    'member@example.com': { organizationId: ORG_A, workstreamId: WS_A, squadId: null, roles: ['dev'] },
  })
  const bindPolicy = { identity: { membershipResolver: resolver, fakeIdpSecret: SECRET } }

  const adminCtx = extractTrustContext(
    makeReq({ headers: { authorization: `Bearer ${issueJwt({ principalId: 'admin@example.com' }, SECRET)}` }, remoteAddress: REMOTE }),
    bindPolicy,
  )
  assert.equal(adminCtx.authenticationMethod, 'jwt')
  assert.equal(adminCtx.workstreamId, WS_A)
  assert.equal(authorizeAdminAccess(adminCtx, { organizationId: ORG_A, workstreamId: WS_A }).authorized, true)
  assert.equal(authorizeAdminAccess(adminCtx, { organizationId: ORG_A, workstreamId: WS_B }).authorized, false)

  const memberCtx = extractTrustContext(
    makeReq({ headers: { authorization: `Bearer ${issueJwt({ principalId: 'member@example.com' }, SECRET)}` }, remoteAddress: REMOTE }),
    bindPolicy,
  )
  assert.equal(memberCtx.authenticationMethod, 'jwt')
  assert.deepEqual(memberCtx.roles, ['dev'])
  assert.equal(checkAdminAuthorization(memberCtx).authorized, false)
  assert.throws(
    () => requireAdminRole(memberCtx),
    (error) => error.statusCode === 403 && error.code === 'FORBIDDEN_ADMIN_REQUIRED',
  )
})

// ---------------------------------------------------------------------------
// 7. Client header tampering is discarded at the boundary
// ---------------------------------------------------------------------------

await test('unauthenticated client headers never grant identity or tenant scope', () => {
  const resolver = new MockMembershipResolver(
    { 'user-1': { organizationId: ORG_A, workstreamId: WS_A, squadId: null, roles: ['admin'] } },
    false,
  )
  const bindPolicy = { identity: { membershipResolver: resolver, fakeIdpSecret: SECRET } }
  const ctx = extractTrustContext(
    makeReq({
      remoteAddress: REMOTE,
      headers: { 'x-organization-id': 'org-evil', 'x-workstream-id': 'ws-evil', 'x-roles': 'admin' },
    }),
    bindPolicy,
  )
  assert.equal(ctx.authenticationMethod, 'anonymous')
  assert.equal(ctx.organizationId, null)
  assert.equal(ctx.workstreamId, null)
  assert.deepEqual(ctx.roles, [])
  assert.deepEqual(ctx.scopes, [])
  assert.equal(checkAdminAuthorization(ctx).authorized, false)
})

await test('even an authenticated caller keeps only its verified membership, not its headers', () => {
  const resolver = new MockMembershipResolver(
    { 'user-1': { organizationId: ORG_A, workstreamId: WS_A, squadId: null, roles: ['admin'] } },
    false,
  )
  const bindPolicy = { identity: { membershipResolver: resolver, fakeIdpSecret: SECRET } }
  const token = issueJwt({ principalId: 'user-1', principalType: 'human', scopes: [] }, SECRET)
  const ctx = extractTrustContext(
    makeReq({
      remoteAddress: REMOTE,
      headers: {
        authorization: `Bearer ${token}`,
        'x-organization-id': 'org-evil',
        'x-workstream-id': 'ws-evil',
        'x-roles': 'viewer',
      },
    }),
    bindPolicy,
  )
  assert.equal(ctx.authenticationMethod, 'jwt')
  assert.equal(ctx.organizationId, ORG_A)
  assert.equal(ctx.workstreamId, WS_A)
  assert.deepEqual(ctx.roles, ['admin'])
  assert.notEqual(ctx.organizationId, 'org-evil')
  assert.notEqual(ctx.workstreamId, 'ws-evil')
})

// ---------------------------------------------------------------------------
// 8. Artifact admin commands pass the real entitlement check
// ---------------------------------------------------------------------------

class FakeS3ObjectClient {
  constructor() {
    this.objects = new Map()
  }

  async putObject(key, body) {
    this.objects.set(key, Uint8Array.from(body))
  }

  async getObject(key) {
    const body = this.objects.get(key)
    if (!body) return null
    const objects = this.objects
    return {
      stream: (async function* () {
        yield objects.get(key)
      })(),
    }
  }

  async headObject(key) {
    return this.objects.has(key)
  }

  async deleteObject(key) {
    return this.objects.delete(key)
  }

  async listObjectKeys(prefix) {
    return [...this.objects.keys()].filter((key) => key.startsWith(prefix))
  }
}

function createRouteHarness() {
  const context = { response: null }
  return {
    context,
    send: (status, body) => {
      context.response = { status, body }
    },
    readBody: async () => context.body ?? {},
  }
}

async function seedArtifact(store, retentionDays = 0) {
  return store.putArtifact({
    owner: 'namespace-admin',
    contentType: 'application/octet-stream',
    data: new TextEncoder().encode('payload'),
    retentionDays,
  })
}

await test('artifact admin commands reject unauthenticated and non-admin principals with 403', async () => {
  const store = new MemoryArtifactStore()
  const metadata = await seedArtifact(store)
  const blobClient = new FakeS3ObjectClient()

  const contexts = [
    { label: 'anonymous', trustContext: { authenticationMethod: 'anonymous', roles: [], scopes: [] } },
    { label: 'missing', trustContext: null },
    { label: 'member', trustContext: { roles: ['dev'], scopes: [], organizationId: ORG_A, workstreamId: WS_A } },
    { label: 'viewer', trustContext: { roles: ['viewer'], scopes: [] } },
    {
      label: 'unauthenticated-with-admin-roles',
      trustContext: { authenticationMethod: 'anonymous', roles: ['admin'], scopes: ['admin:*'] },
    },
  ]

  for (const { label, trustContext } of contexts) {
    const harness = createRouteHarness()
    await handleArtifactAdminRequest({
      method: 'POST',
      path: `/api/factory/admin/artifacts/${metadata.id}/legal-hold`,
      trustContext,
      readBody: async () => ({ legalHold: true }),
      send: harness.send,
      store,
      blobClient,
    })
    assert.equal(harness.context.response.status, 403, `${label} should be refused`)
    assert.equal(harness.context.response.body.error.code, 'FORBIDDEN_ADMIN_REQUIRED', `${label} error code`)
  }

  // The refused contexts never mutated the artifact.
  const after = await store.getArtifactMetadata(metadata.id)
  assert.equal(after.legalHold, false)
})

await test('artifact admin commands succeed for an admin and for loopback-dev', async () => {
  const store = new MemoryArtifactStore()
  const metadata = await seedArtifact(store)
  const blobClient = new FakeS3ObjectClient()

  // Admin role (AgentOS ADMIN -> admin) succeeds on legal hold.
  const admin = createRouteHarness()
  await handleArtifactAdminRequest({
    method: 'POST',
    path: `/api/factory/admin/artifacts/${metadata.id}/legal-hold`,
    trustContext: {
      authenticationMethod: 'jwt',
      principalId: 'admin@example.com',
      roles: ['admin'],
      organizationId: ORG_A,
      workstreamId: WS_A,
    },
    readBody: async () => ({ legalHold: true, reason: 'litigation' }),
    send: admin.send,
    store,
    blobClient,
  })
  assert.equal(admin.context.response.status, 200)
  assert.equal(admin.context.response.body.data.legalHold, true)

  // Loopback-dev wildcard scope succeeds on purge once the hold is released.
  const release = createRouteHarness()
  await handleArtifactAdminRequest({
    method: 'POST',
    path: `/api/factory/admin/artifacts/${metadata.id}/legal-hold`,
    trustContext: { authenticationMethod: 'loopback-dev', roles: [], scopes: ['*'] },
    readBody: async () => ({ legalHold: false }),
    send: release.send,
    store,
    blobClient,
  })
  assert.equal(release.context.response.status, 200)

  const purge = createRouteHarness()
  await handleArtifactAdminRequest({
    method: 'POST',
    path: `/api/factory/admin/artifacts/${metadata.id}/purge`,
    trustContext: { authenticationMethod: 'loopback-dev', roles: [], scopes: ['*'] },
    readBody: async () => ({ reason: 'admin-purge' }),
    send: purge.send,
    store,
    blobClient,
  })
  assert.equal(purge.context.response.status, 200)
  assert.equal(purge.context.response.body.data.success, true)

  // GC under an admin context succeeds and reclaims an orphaned staging object.
  await blobClient.putObject('uploads/orphan.part', new TextEncoder().encode('leftover'))
  const gc = createRouteHarness()
  await handleArtifactAdminRequest({
    method: 'POST',
    path: '/api/factory/admin/artifacts/gc',
    trustContext: { authenticationMethod: 'jwt', roles: ['ADMIN'], organizationId: ORG_A, workstreamId: WS_A },
    readBody: async () => ({}),
    send: gc.send,
    store,
    blobClient,
  })
  assert.equal(gc.context.response.status, 200)
  assert.deepEqual(gc.context.response.body.data.reclaimedStagingKeys, ['uploads/orphan.part'])
})

// ---------------------------------------------------------------------------

console.log(`\nResult: ${passed} passed, ${failed} failed`)
process.exit(failed ? 1 : 0)
