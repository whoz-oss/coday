import { WorkflowProjectionSseHub } from '../dashboard/workflow-projection-sse.mjs'
import { handleWorkflowProjectionRequest } from '../dashboard/workflow-projection-routes.mjs'

let passed = 0, failed = 0
function expect(name, actual, expected) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected)
  console.log(`${ok ? '\u2713' : '\u2717'} ${name}`)
  if (!ok) console.log(`  expected=${JSON.stringify(expected)} actual=${JSON.stringify(actual)}`)
  if (ok) passed++; else failed++
}
const A = '11111111-1111-4111-8111-111111111111'
const B = '22222222-2222-4222-8222-222222222222'
const projection = { schemaVersion: '1', workflowId: 'wf-1', workflowType: 'delivery', title: 'W', status: 'ready', steps: [] }
const envelope = { projection, execution: { namespaceId: A, runtimeId: 'agentos-primary', kind: 'agentos', agentId: 'agent', caseId: 'case-1' } }

let timers = []
const hub = new WorkflowProjectionSseHub({
  setIntervalFn: (fn) => { timers.push(fn); return fn },
  clearIntervalFn: (fn) => { timers = timers.filter((item) => item !== fn) },
})
const framesA = [], framesB = []
let closeA
hub.subscribe(A, (frame) => framesA.push(frame), (remove) => { closeA = remove })
hub.subscribe(B, (frame) => framesB.push(frame), () => {})
hub.publish(A, { workflowId: 'wf-1', namespaceId: A, revision: 1 })
expect('event frame shape', framesA, [`event: workflow-projection-updated\ndata: ${JSON.stringify({ workflowId: 'wf-1', namespaceId: A, revision: 1 })}\n\n`])
expect('namespace isolation', framesB, [])
timers[0]()
expect('heartbeat frame', framesA.at(-1), ': heartbeat\n\n')
closeA()
expect('close cleanup', hub.size(A), 0)

const deadHub = new WorkflowProjectionSseHub({ setIntervalFn: () => 1, clearIntervalFn: () => {} })
deadHub.subscribe(A, () => { throw new Error('dead') }, () => {})
deadHub.subscribe(A, (frame) => framesA.push(frame), () => {})
deadHub.publish(A, { workflowId: 'wf-2', namespaceId: A, revision: 2 })
expect('dead client cleanup without blocking peer', hub.size(A) === 0 && deadHub.size(A), 1)

async function route(store, notifier, path = '/api/factory/workflows/wf-1/projection', body = envelope) {
  let response
  await handleWorkflowProjectionRequest({ method: 'PUT', path, url: new URL(path, 'http://localhost'), readBody: async () => body,
    send: (status, data) => { response = { status, data } }, store, notifier, openStream: () => {}, log: { error() {} } })
  return response
}
let notifications = []
const notifier = { publish: (...args) => notifications.push(args) }
await route({ publish: async () => ({ ok: true, changed: true, snapshot: { revision: 1, projectionHash: 'h', projection } }) }, notifier)
expect('changed publication emits once after success', notifications.length, 1)
notifications = []
await route({ publish: async () => ({ ok: true, changed: false, snapshot: { revision: 1, projectionHash: 'h', projection } }) }, notifier)
expect('unchanged emits zero', notifications.length, 0)
await route({ publish: async () => ({ ok: false, error: { code: 'REVISION_CONFLICT' } }) }, notifier)
expect('conflict emits zero', notifications.length, 0)
await route({ publish: async () => { throw new Error('storage') } }, notifier)
expect('error emits zero', notifications.length, 0)

let invalid
await handleWorkflowProjectionRequest({ method: 'GET', path: '/api/factory/workflows/stream', url: new URL('/api/factory/workflows/stream?namespaceId=bad', 'http://localhost'),
  readBody: async () => ({}), send: (status, body) => { invalid = { status, body } }, store: {}, notifier, openStream: () => { throw new Error('must not open') }, log: { error() {} } })
expect('invalid stream namespace rejected before open', [invalid.status, invalid.body.error.code], [400, 'INVALID_NAMESPACE_ID'])

console.log(`\nResult: ${passed} passed, ${failed} failed`)
process.exit(failed > 0 ? 1 : 0)
