import { handleDeliveryRequest } from '../lib/delivery-controller.mjs'

let passed = 0, failed = 0
function expect(name, actual, expected) { const ok = JSON.stringify(actual) === JSON.stringify(expected); console.log(`${ok ? '✓' : '✗'} ${name}`); ok ? passed++ : failed++ }
const identity = async () => ({ namespaceId: '11111111-1111-4111-8111-111111111111', caseId: '22222222-2222-4222-8222-222222222222', actorId: 'human' })
for (const forbidden of ['repoRoot', 'root', 'remote', 'url', 'command', 'credentials', 'token', 'owner', 'repo', 'worktreePath']) {
  let response
  const controller = { checkpoint: async (_identity, _workflow, body) => ({ ok: false, status: 400, error: { code: Object.hasOwn(body, forbidden) ? 'UNTRUSTED_DELIVERY_INPUT' : 'MISSED' } }) }
  await handleDeliveryRequest({ method: 'POST', path: '/api/factory/workflows/wf/delivery/checkpoint', readBody: async () => ({ [forbidden]: 'attacker-controlled' }), send: (status, body) => { response = { status, body } }, identity, controller })
  expect(`client ${forbidden} rejected`, response.body.error.code, 'UNTRUSTED_DELIVERY_INPUT')
}
let response
await handleDeliveryRequest({ method: 'GET', path: '/api/factory/workflows/wf/delivery', readBody: async () => ({}), send: (status, body) => { response = { status, body } }, identity: async () => null, controller: {} })
expect('trusted context required', response.status, 401)
console.log(`\nResult: ${passed} passed, ${failed} failed`); process.exit(failed ? 1 : 0)
