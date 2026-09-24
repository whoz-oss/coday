import assert from 'node:assert/strict'
import test from 'node:test'
import { handleForgeWorkflowProjectionRequest } from '../dashboard/forge-workflow-projection-routes.mjs'

const NS = '123e4567-e89b-42d3-a456-426614174000'
async function request({ path = '/api/factory/forge/projections/WZ-1/sync', namespaceId = NS, body = {}, repoRoot = '/trusted/repo', publish = { ok: true, changed: true, snapshot: { revision: 1, projectionHash: 'hash' } } } = {}) {
  const responses = []; const events = []; let resolved = 0
  await handleForgeWorkflowProjectionRequest({ method: 'POST', path, url: new URL(`http://localhost${path}?namespaceId=${namespaceId}`), readBody: async () => body,
    send: (status, payload) => responses.push({ status, payload }), resolveRepoRoot: async () => { resolved++; return repoRoot },
    store: {}, sync: async ({ repoRoot: actualRoot }) => actualRoot === repoRoot
      ? (publish.ok ? { ok: true, changed: publish.changed, workflowId: 'forge-run-WZ-1', revision: publish.snapshot.revision, projectionHash: publish.snapshot.projectionHash } : publish)
      : { ok: false, error: { code: 'WRONG_REPO_ROOT' } },
    notifier: { publish: (...args) => events.push(args) }, log: { error() {} } })
  return { response: responses[0], events, resolved }
}

test('route rejects unsafe ticket, namespace and free-prose fields before repo resolution', async () => {
  assert.equal((await request({ path: '/api/factory/forge/projections/..%2Fsecret/sync' })).response.status, 400)
  assert.equal((await request({ namespaceId: 'bad' })).response.status, 400)
  const unknown = await request({ body: { prompt: 'translate gates' } }); assert.equal(unknown.response.status, 400); assert.equal(unknown.resolved, 0)
})

test('route uses trusted resolver and emits exactly once only for changed publication', async () => {
  const changed = await request(); assert.equal(changed.resolved, 1); assert.equal(changed.response.status, 201); assert.equal(changed.events.length, 1)
  const unchanged = await request({ publish: { ok: true, changed: false, snapshot: { revision: 1, projectionHash: 'hash' } } })
  assert.equal(unchanged.response.status, 200); assert.equal(unchanged.events.length, 0)
  const invalid = await request({ publish: { ok: false, error: { code: 'INVALID_PROJECTION' } } }); assert.equal(invalid.response.status, 422); assert.equal(invalid.events.length, 0)
})

test('missing authoritative repo uses a stable safe envelope', async () => {
  const missingRepo = await request({ repoRoot: null }); assert.deepEqual(missingRepo.response.payload.error.code, 'NAMESPACE_REPO_UNAVAILABLE')
})

test('FORGE_RUN_NOT_FOUND maps to 404 without an event', async () => {
  const missing = await request({ publish: { ok: false, error: { code: 'FORGE_RUN_NOT_FOUND' } } })
  assert.equal(missing.response.status, 404); assert.equal(missing.response.payload.error.code, 'FORGE_RUN_NOT_FOUND'); assert.equal(missing.events.length, 0)
})
