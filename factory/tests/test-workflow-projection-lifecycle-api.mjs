// Stage 6A HTTP/SSE lifecycle contract coverage. Intentionally unexecuted here.
import { handleWorkflowProjectionRequest } from '../dashboard/workflow-projection-routes.mjs'
let passed = 0, failed = 0
function expect(name, actual, expected) { const ok = JSON.stringify(actual) === JSON.stringify(expected); console.log(`${ok ? '✓' : '✗'} ${name}`); if (!ok) console.log(`  expected=${JSON.stringify(expected)} actual=${JSON.stringify(actual)}`); ok ? passed++ : failed++ }
const NS = '11111111-1111-4111-8111-111111111111'
const snapshot = { revision: 3, projectionHash: 'hash', projection: { workflowId: 'wf-1' } }
async function request(store, method, path, body, notifications = []) { const url = new URL(path, 'http://localhost'); let response; await handleWorkflowProjectionRequest({ method, path: url.pathname, url, readBody: async () => body, send: (status, payload) => { response = { status, payload } }, store, notifier: { publish: (...args) => notifications.push(args) }, log: { error() {} } }); return response }
let notes = []
let response = await request({ remove: async () => ({ ok: true, snapshot }) }, 'DELETE', `/api/factory/workflows/wf-1?namespaceId=${NS}`, { actorId: 'actor-1' }, notes)
expect('remove success envelope', response, { status: 200, payload: { data: { workflowId: 'wf-1', namespaceId: NS, state: 'removed' } } })
expect('remove exact SSE after success', notes, [[NS, { workflowId: 'wf-1', namespaceId: NS }, 'workflow-projection-removed']])
notes = []; response = await request({ restore: async () => ({ ok: true, snapshot }) }, 'POST', `/api/factory/workflows/wf-1/restore?namespaceId=${NS}`, undefined, notes)
expect('restore exact SSE', notes, [[NS, { workflowId: 'wf-1', namespaceId: NS, revision: 3 }, 'workflow-projection-restored']])
notes = []; response = await request({ purge: async () => ({ ok: true }) }, 'DELETE', `/api/factory/workflows/wf-1/purge?namespaceId=${NS}`, undefined, notes)
expect('purge exact SSE', notes, [[NS, { workflowId: 'wf-1', namespaceId: NS }, 'workflow-projection-purged']])
notes = []; response = await request({ remove: async () => ({ ok: false, error: { code: 'WORKFLOW_NOT_FOUND' } }) }, 'DELETE', `/api/factory/workflows/missing?namespaceId=${NS}`, undefined, notes)
expect('not found envelope and no SSE', [response.status, response.payload.error.code, notes.length], [404, 'WORKFLOW_NOT_FOUND', 0])
response = await request({ restore: async () => ({ ok: false, error: { code: 'INVALID_LIFECYCLE_TRANSITION' } }) }, 'POST', `/api/factory/workflows/wf-1/restore?namespaceId=${NS}`, undefined)
expect('invalid transition status', [response.status, response.payload.error.code], [409, 'INVALID_LIFECYCLE_TRANSITION'])
response = await request({}, 'DELETE', '/api/factory/workflows/wf-1', undefined)
expect('namespace required', [response.status, response.payload.error.code], [400, 'INVALID_NAMESPACE_ID'])
response = await request({}, 'DELETE', `/api/factory/workflows/wf-1?namespaceId=${NS}`, { actorId: 'ok', narrative: 'no' })
expect('unknown actor field rejected', [response.status, response.payload.error.code], [400, 'INVALID_ACTOR_ATTRIBUTION'])
response = await request({ listRemoved: async () => [snapshot] }, 'GET', `/api/factory/workflows?namespaceId=${NS}&state=removed`)
expect('removed list envelope', [response.status, response.payload.data.state, response.payload.data.items.length], [200, 'removed', 1])
notes = []; response = await request({ purge: async () => { throw new Error('/secret/path') } }, 'DELETE', `/api/factory/workflows/wf-1/purge?namespaceId=${NS}`, undefined, notes)
expect('safe storage failure and no SSE', [response.status, response.payload, notes.length], [500, { error: { code: 'WORKFLOW_STORAGE_FAILURE', message: 'Workflow projection storage is unavailable.' } }, 0])
console.log(`\nResult: ${passed} passed, ${failed} failed`); process.exit(failed ? 1 : 0)
