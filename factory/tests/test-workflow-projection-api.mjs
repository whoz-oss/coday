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
const NS_EXPRESS = '33333333-3333-4333-8333-333333333333'
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
  await store.initialize()
  const envelope = { projection, execution: { namespaceId: NS_A, runtimeId: 'agentos-primary', kind: 'agentos', agentId: 'agent-1', caseId: 'case-1' } }
  const expressEnvelope = {
    projection: { ...projection, schemaVersion: '2', workflowId: 'express-wf', steps: [{ ...projection.steps[0], responsibility: { kind: 'agent', name: 'Sway' } }] },
    execution: { namespaceId: NS_EXPRESS, runtimeId: 'coday-express-transitional', kind: 'coday-express', agentId: 'Sway', threadId: '550e8400-e29b-41d4-a716-446655440000', actorId: 'benjamin.valdes' },
  }

  let response = await request(store, 'PUT', '/api/factory/workflows/express-wf/projection', expressEnvelope)
  expect('Coday Express v2 envelope', [response.status, response.body.data.controllerExecution.kind, response.body.data.controllerExecution.threadId], [201, 'coday-express', expressEnvelope.execution.threadId])

  response = await request(store, 'PUT', '/api/factory/workflows/wf-1/projection', envelope)
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
  expect('listing', [response.status, response.body.data.items.length, response.body.data.items[0].workflowId, response.body.data.items[0].controllerExecution.caseId], [200, 1, 'wf-1', 'case-1'])

  response = await request(store, 'GET', `/api/factory/workflows?namespaceId=${NS_B}&state=active`)
  expect('namespace isolation', [response.status, response.body.data.items.length], [200, 0])

  response = await request(store, 'GET', '/api/factory/workflows?state=active')
  expect('missing namespace', [response.status, response.body.error.code], [400, 'INVALID_NAMESPACE_ID'])

  response = await request(store, 'GET', `/api/factory/workflows?namespaceId=${NS_A}&state=trash`)
  expect('unsupported state', [response.status, response.body.error.code], [400, 'UNSUPPORTED_STATE'])

  response = await request(store, 'GET', `/api/factory/workflows/wf-1?namespaceId=${NS_A}`)
  expect('detail existing', [response.status, response.body.data.state, response.body.data.workflowId, response.body.data.revision, response.body.data.projection.schemaVersion, response.body.data.controllerExecution.runtimeId], [200, 'existing', 'wf-1', 2, '1', 'agentos-primary'])

  response = await request(store, 'GET', `/api/factory/workflows/express-wf?namespaceId=${NS_EXPRESS}`)
  expect('detail preserves v2 snapshot', [response.status, response.body.data.state, response.body.data.projection.schemaVersion, response.body.data.projection.steps[0].responsibility.kind], [200, 'existing', '2', 'agent'])

  response = await request(store, 'GET', `/api/factory/workflows/wf-1?namespaceId=${NS_B}`)
  expect('detail lookup is namespace scoped', [response.status, response.body.data.state], [200, 'absent'])

  response = await request(store, 'GET', `/api/factory/workflows/missing?namespaceId=${NS_A}`)
  expect('detail absent is an explicit successful lookup', [response.status, response.body.data], [200, { namespaceId: NS_A, workflowId: 'missing', state: 'absent' }])

  await store.remove(NS_EXPRESS, 'express-wf')
  response = await request(store, 'GET', `/api/factory/workflows/express-wf?namespaceId=${NS_EXPRESS}`)
  expect('detail removed', [response.status, response.body.data.state], [200, 'removed'])
  await store.purge(NS_EXPRESS, 'express-wf')
  response = await request(store, 'GET', `/api/factory/workflows/express-wf?namespaceId=${NS_EXPRESS}`)
  expect('detail purged', [response.status, response.body.data.state], [200, 'purged'])

  response = await request(store, 'GET', `/api/factory/workflows/wf-1/timing?namespaceId=${NS_A}`)
  expect('timing detail', [response.status, response.body.data.workflowId, response.body.data.timing.complete], [200, 'wf-1', true])
  response = await request(store, 'GET', `/api/factory/workflows/wf-1/timing?namespaceId=${NS_B}`)
  expect('timing namespace isolation', [response.status, response.body.error.code], [404, 'WORKFLOW_NOT_FOUND'])
  response = await request(store, 'GET', `/api/factory/workflows/missing/timing?namespaceId=${NS_A}`)
  expect('timing not found', [response.status, response.body.error.code], [404, 'WORKFLOW_NOT_FOUND'])

  const failingStore = { list: async () => { throw new Error('SECRET /private/path') } }
  response = await request(failingStore, 'GET', `/api/factory/workflows?namespaceId=${NS_A}&state=active`)
  expect('safe storage error', [response.status, response.body], [500, { error: { code: 'WORKFLOW_STORAGE_FAILURE', message: 'Workflow projection storage is unavailable.' } }])
} finally {
  await rm(root, { recursive: true, force: true })
}

console.log(`\nResult: ${passed} passed, ${failed} failed`)
process.exit(failed > 0 ? 1 : 0)
