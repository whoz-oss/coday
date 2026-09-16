import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { handleWorkflowProjectionRequest } from '../dashboard/workflow-projection-routes.mjs'
import { WorkflowProjectionStore } from '../lib/workflow-projection-store.mjs'

let passed = 0
let failed = 0
function expect(name, actual, expected) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected)
  console.log(`${ok ? '\u2713' : '\u2717'} ${name}`)
  if (!ok) console.log(`  expected=${JSON.stringify(expected)} actual=${JSON.stringify(actual)}`)
  if (ok) passed++; else failed++
}

const NS_A = '11111111-1111-4111-8111-111111111111'
const NS_B = '22222222-2222-4222-8222-222222222222'
const projection = {
  schemaVersion: '1', workflowId: 'wf-1', workflowType: 'delivery', title: 'Workflow', status: 'ready',
  steps: [{ id: 'step-1', name: 'First', status: 'ready' }],
}

async function request(store, method, pathname, body, log = { error() {} }) {
  const url = new URL(pathname, 'http://localhost')
  let response
  const handled = await handleWorkflowProjectionRequest({
    method, path: url.pathname, url, store, log,
    readBody: async () => body,
    send: (status, payload) => { response = { status, body: payload } },
  })
  return { handled, ...response }
}

const root = await mkdtemp(join(tmpdir(), 'factory-workflow-api-'))
try {
  const store = new WorkflowProjectionStore(root)
  const envelope = { projection, execution: { namespaceId: NS_A, agentId: 'agent-1' } }

  let response = await request(store, 'PUT', '/api/factory/workflows/wf-1/projection', envelope)
  expect('create', [response.status, response.body.data.changed, response.body.data.revision], [201, true, 1])

  response = await request(store, 'PUT', '/api/factory/workflows/wf-1/projection', envelope)
  expect('idempotent', [response.status, response.body.data.changed, response.body.data.revision], [200, false, 1])

  response = await request(store, 'PUT', '/api/factory/workflows/wf-1/projection', { ...envelope, projection: { ...projection, title: 'Updated', expectedRevision: 1 } })
  expect('update', [response.status, response.body.data.changed, response.body.data.revision], [200, true, 2])

  response = await request(store, 'PUT', '/api/factory/workflows/wf-1/projection', { ...envelope, projection: { ...projection, expectedRevision: 1 } })
  expect('conflict', [response.status, response.body.error.code], [409, 'REVISION_CONFLICT'])

  response = await request(store, 'PUT', '/api/factory/workflows/wf-1/projection', null)
  expect('malformed body', [response.status, response.body.error.code], [400, 'INVALID_REQUEST'])

  response = await request(store, 'PUT', '/api/factory/workflows/other/projection', envelope)
  expect('workflow id mismatch', [response.status, response.body.error.code], [400, 'WORKFLOW_ID_MISMATCH'])

  response = await request(store, 'PUT', '/api/factory/workflows/wf-1/projection', { ...envelope, execution: { namespaceId: NS_A, narrative: 'free prose' } })
  expect('unknown execution field', [response.status, response.body.error.code], [400, 'INVALID_EXECUTION'])

  response = await request(store, 'GET', `/api/factory/workflows?namespaceId=${NS_A}&state=active`)
  expect('listing', [response.status, response.body.data.items.length, response.body.data.items[0].workflowId], [200, 1, 'wf-1'])

  response = await request(store, 'GET', `/api/factory/workflows?namespaceId=${NS_B}&state=active`)
  expect('namespace isolation', [response.status, response.body.data.items.length], [200, 0])

  response = await request(store, 'GET', '/api/factory/workflows?state=active')
  expect('missing namespace', [response.status, response.body.error.code], [400, 'INVALID_NAMESPACE_ID'])

  response = await request(store, 'GET', `/api/factory/workflows?namespaceId=${NS_A}&state=trash`)
  expect('unsupported state', [response.status, response.body.error.code], [400, 'UNSUPPORTED_STATE'])

  response = await request(store, 'GET', `/api/factory/workflows/wf-1?namespaceId=${NS_A}`)
  expect('detail', [response.status, response.body.data.workflowId, response.body.data.revision], [200, 'wf-1', 2])

  response = await request(store, 'GET', `/api/factory/workflows/missing?namespaceId=${NS_A}`)
  expect('detail not found', [response.status, response.body.error.code], [404, 'WORKFLOW_NOT_FOUND'])

  const failingStore = { list: async () => { throw new Error('SECRET /private/path') } }
  response = await request(failingStore, 'GET', `/api/factory/workflows?namespaceId=${NS_A}&state=active`)
  expect('safe storage error', [response.status, response.body], [500, { error: { code: 'WORKFLOW_STORAGE_FAILURE', message: 'Workflow projection storage is unavailable.' } }])
} finally {
  await rm(root, { recursive: true, force: true })
}

console.log(`\nResult: ${passed} passed, ${failed} failed`)
process.exit(failed > 0 ? 1 : 0)
