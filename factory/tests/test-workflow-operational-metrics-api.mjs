// Phase 10C route contract source coverage. Intentionally not executed in this change.
import assert from 'node:assert/strict'
import { handleWorkflowOperationalMetricsRequest } from '../dashboard/workflow-operational-metrics-routes.mjs'

const NS = '11111111-1111-4111-8111-111111111111'
const fixedNow = '2026-01-02T03:04:05.000Z'
const projection = (scope) => ({ schemaVersion: '1', observedAt: fixedNow, scope: { kind: scope, workflowId: 'wf-1', includedWorkflowIds: scope === 'self' ? ['wf-1'] : ['wf-1', 'wf-2'] }, metrics: {}, capabilities: { llmUsage: { available: false, reason: 'llm_usage_capture_not_implemented' }, cost: { available: false, reason: 'pricing_and_cost_projection_not_implemented' }, dora: { available: false, reason: 'dora_metrics_not_implemented' }, rollbackRate: { available: false, reason: 'rollback_rate_not_implemented' } } })

async function request(query, overrides = {}) {
  let response
  const url = new URL(`/api/factory/workflows/wf-1/metrics?${query}`, 'http://localhost')
  const service = overrides.service ?? { workflowStore: { read: async () => ({ projection: { workflowId: 'wf-1' } }) }, project: async ({ scope, observedAt }) => ({ ...projection(scope), observedAt }) }
  await handleWorkflowOperationalMetricsRequest({ method: 'GET', path: url.pathname, url, service, clock: { now: () => new Date(fixedNow) }, send: (status, body) => { response = { status, body } }, log: { error() {} } })
  return response
}

let response = await request(`namespaceId=${NS}&scope=self`)
assert.equal(response.status, 200)
assert.equal(response.body.data.scope.kind, 'self')
assert.equal(response.body.data.observedAt, fixedNow)
assert.equal(response.body.data.capabilities.llmUsage.available, false)
assert.equal(response.body.data.capabilities.cost.available, false)
assert.equal(response.body.data.capabilities.dora.available, false)
assert.equal(response.body.data.capabilities.rollbackRate.available, false)

response = await request(`namespaceId=${NS}&scope=descendants&observedAt=${encodeURIComponent(fixedNow)}`)
assert.deepEqual(response.body.data.scope.includedWorkflowIds, ['wf-1', 'wf-2'])
response = await request(`namespaceId=${NS}&scope=global`)
assert.deepEqual([response.status, response.body.error.code], [400, 'INVALID_METRICS_SCOPE'])
response = await request(`namespaceId=${NS}&scope=self&observedAt=not-an-instant`)
assert.deepEqual([response.status, response.body.error.code], [400, 'INVALID_OBSERVED_AT'])
response = await request(`namespaceId=${NS}&scope=self`, { service: { workflowStore: { read: async () => null } } })
assert.deepEqual([response.status, response.body.error.code], [404, 'WORKFLOW_NOT_FOUND'])
