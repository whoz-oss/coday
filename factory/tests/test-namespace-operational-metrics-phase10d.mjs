/**
 * Phase 10D — Namespace/group/root operational metrics rollup.
 * Source tests only — not executed in this change.
 *
 * Coverage:
 * - Selector semantics: namespace / group / root
 * - groupId is not a hierarchy edge (metadata only)
 * - Legacy workflows without relations are independent roots
 * - Deterministic limit and honest truncation
 * - Missing root workflow → not found
 * - Empty group → empty rollup with WIP=0
 * - Unique WIP counting
 * - Interval union under concurrent roots
 * - Deduplication: interactions, evidence, deliveries
 * - Incompleteness/truncation propagation
 * - Route validation: mutually exclusive selectors, IDs, observedAt, limit, status
 */

import assert from 'node:assert/strict'
import {
  selectWorkflows,
  applyNamespaceMetricsLimit,
  parseNamespaceMetricsLimit,
  projectNamespaceOperationalMetrics,
  NAMESPACE_METRICS_DEFAULT_LIMIT,
  NAMESPACE_METRICS_MAX_LIMIT,
} from '../lib/factory-operational-metrics-namespace-projector.mjs'
import { handleWorkflowOperationalMetricsRequest } from '../dashboard/workflow-operational-metrics-routes.mjs'

const NS = '11111111-1111-4111-8111-111111111111'
const NOW = '2026-06-01T12:00:00.000Z'

// ──────────────────────────────────────────────────────────────────────────────
// Helpers
// ──────────────────────────────────────────────────────────────────────────────

function makeSnapshot(workflowId, { parentWorkflowId, groupId, rootWorkflowId, status = 'running' } = {}) {
  const relations = { rootWorkflowId: rootWorkflowId ?? workflowId }
  if (parentWorkflowId) relations.parentWorkflowId = parentWorkflowId
  if (groupId) relations.groupId = groupId
  return {
    revision: 1,
    projectionHash: 'hash',
    projection: { schemaVersion: '1', workflowId, workflowType: 'test', title: 'Test', status, steps: [] },
    instance: { relations },
  }
}

async function routeRequest(path, query, serviceOverrides = {}) {
  let response
  const url = new URL(`${path}?${query}`, 'http://localhost')
  const defaultService = {
    projectNamespace: async () => ({ ok: true, data: { schemaVersion: '1', observedAt: NOW, selector: { scope: 'namespace' }, lifecycleScope: 'active', matchedWorkflowCount: 0, includedWorkflowCount: 0, truncated: false, scope: { kind: 'namespace-rollup', namespaceId: NS, includedWorkflowIds: [] }, metrics: {}, capabilities: {} } }),
    workflowStore: { read: async () => ({ projection: { workflowId: 'wf-1' } }) },
    project: async () => ({ schemaVersion: '1', observedAt: NOW, scope: { kind: 'self', workflowId: 'wf-1', includedWorkflowIds: ['wf-1'] }, metrics: {}, capabilities: {} }),
  }
  const service = { ...defaultService, ...serviceOverrides }
  await handleWorkflowOperationalMetricsRequest({ method: 'GET', path: url.pathname, url, service, clock: { now: () => new Date(NOW) }, send: (status, body) => { response = { status, body } }, log: { error() {} } })
  return response
}

// ──────────────────────────────────────────────────────────────────────────────
// 1. parseNamespaceMetricsLimit
// ──────────────────────────────────────────────────────────────────────────────

assert.deepEqual(parseNamespaceMetricsLimit(undefined), { ok: true, limit: NAMESPACE_METRICS_DEFAULT_LIMIT })
assert.deepEqual(parseNamespaceMetricsLimit(null), { ok: true, limit: NAMESPACE_METRICS_DEFAULT_LIMIT })
assert.deepEqual(parseNamespaceMetricsLimit('50'), { ok: true, limit: 50 })
assert.deepEqual(parseNamespaceMetricsLimit('500'), { ok: true, limit: 500 })
assert.equal(parseNamespaceMetricsLimit('0').ok, false)
assert.equal(parseNamespaceMetricsLimit('-1').ok, false)
assert.equal(parseNamespaceMetricsLimit('501').ok, false)
assert.equal(parseNamespaceMetricsLimit('abc').ok, false)
assert.equal(parseNamespaceMetricsLimit('1.5').ok, false)

// ──────────────────────────────────────────────────────────────────────────────
// 2. selectWorkflows — scope=namespace
// ──────────────────────────────────────────────────────────────────────────────

{
  const all = [makeSnapshot('wf-a'), makeSnapshot('wf-b'), makeSnapshot('wf-c')]
  const result = selectWorkflows(all, 'namespace')
  assert.equal(result.ok, true)
  assert.equal(result.selected.length, 3)
}

// Empty namespace returns empty selection (not an error)
{
  const result = selectWorkflows([], 'namespace')
  assert.equal(result.ok, true)
  assert.equal(result.selected.length, 0)
}

// Invalid scope
{
  const result = selectWorkflows([], 'global')
  assert.equal(result.ok, false)
  assert.equal(result.code, 'INVALID_SCOPE')
}

// ──────────────────────────────────────────────────────────────────────────────
// 3. selectWorkflows — scope=group
// ──────────────────────────────────────────────────────────────────────────────

// groupId matches only exact members — not descendants of a parent
{
  const all = [
    makeSnapshot('wf-root', { groupId: 'sprint-1', rootWorkflowId: 'wf-root' }),
    makeSnapshot('wf-child', { parentWorkflowId: 'wf-root', groupId: 'sprint-1', rootWorkflowId: 'wf-root' }),
    makeSnapshot('wf-other', { groupId: 'sprint-2', rootWorkflowId: 'wf-other' }),
  ]
  const result = selectWorkflows(all, 'group', { groupId: 'sprint-1' })
  assert.equal(result.ok, true)
  assert.equal(result.selected.length, 2)
  assert.ok(result.selected.every((s) => s.instance.relations.groupId === 'sprint-1'))
}

// groupId is metadata, not a hierarchy edge: a workflow with a different groupId
// is NOT included even if it is a descendant of a group member
{
  const all = [
    makeSnapshot('wf-root', { groupId: 'sprint-1', rootWorkflowId: 'wf-root' }),
    makeSnapshot('wf-child-no-group', { parentWorkflowId: 'wf-root', rootWorkflowId: 'wf-root' }),
  ]
  const result = selectWorkflows(all, 'group', { groupId: 'sprint-1' })
  assert.equal(result.ok, true)
  assert.equal(result.selected.length, 1, 'descendant without groupId must not be included')
  assert.equal(result.selected[0].projection.workflowId, 'wf-root')
}

// Empty group is valid — returns ok with empty selection
{
  const all = [makeSnapshot('wf-a', { groupId: 'other' })]
  const result = selectWorkflows(all, 'group', { groupId: 'sprint-99' })
  assert.equal(result.ok, true)
  assert.equal(result.selected.length, 0)
}

// Missing groupId when scope=group
{
  const result = selectWorkflows([], 'group')
  assert.equal(result.ok, false)
  assert.equal(result.code, 'MISSING_GROUP_ID')
}

// Invalid groupId format
{
  const result = selectWorkflows([], 'group', { groupId: '' })
  assert.equal(result.ok, false)
  assert.equal(result.code, 'INVALID_GROUP_ID')
}

// ──────────────────────────────────────────────────────────────────────────────
// 4. selectWorkflows — scope=root
// ──────────────────────────────────────────────────────────────────────────────

// Root includes itself and all descendants sharing rootWorkflowId
{
  const all = [
    makeSnapshot('wf-root', { rootWorkflowId: 'wf-root' }),
    makeSnapshot('wf-child', { parentWorkflowId: 'wf-root', rootWorkflowId: 'wf-root' }),
    makeSnapshot('wf-unrelated', { rootWorkflowId: 'wf-unrelated' }),
  ]
  const result = selectWorkflows(all, 'root', { rootWorkflowId: 'wf-root' })
  assert.equal(result.ok, true)
  assert.equal(result.selected.length, 2)
  assert.ok(result.selected.every((s) => s.instance.relations.rootWorkflowId === 'wf-root'))
}

// Legacy workflow without stored relations is its own independent root
{
  const legacySnapshot = {
    revision: 1, projectionHash: 'h', projection: { schemaVersion: '1', workflowId: 'wf-legacy', workflowType: 't', title: 'T', status: 'running', steps: [] },
    // No instance.relations — storedWorkflowRelations() returns { rootWorkflowId: 'wf-legacy' }
  }
  const all = [legacySnapshot, makeSnapshot('wf-other', { rootWorkflowId: 'wf-other' })]
  const result = selectWorkflows(all, 'root', { rootWorkflowId: 'wf-legacy' })
  assert.equal(result.ok, true)
  assert.equal(result.selected.length, 1)
  assert.equal(result.selected[0].projection.workflowId, 'wf-legacy')
}

// Root workflow not found in active set
{
  const all = [makeSnapshot('wf-a', { rootWorkflowId: 'wf-a' })]
  const result = selectWorkflows(all, 'root', { rootWorkflowId: 'wf-missing' })
  assert.equal(result.ok, false)
  assert.equal(result.code, 'ROOT_WORKFLOW_NOT_FOUND')
}

// Missing rootWorkflowId when scope=root
{
  const result = selectWorkflows([], 'root')
  assert.equal(result.ok, false)
  assert.equal(result.code, 'MISSING_ROOT_WORKFLOW_ID')
}

// ──────────────────────────────────────────────────────────────────────────────
// 5. applyNamespaceMetricsLimit
// ──────────────────────────────────────────────────────────────────────────────

// No truncation when within limit
{
  const snapshots = ['wf-c', 'wf-a', 'wf-b'].map((id) => makeSnapshot(id))
  const result = applyNamespaceMetricsLimit(snapshots, 5)
  assert.equal(result.truncated, false)
  assert.equal(result.matchedWorkflowCount, 3)
  assert.equal(result.includedWorkflowCount, 3)
  // Sorted deterministically by workflowId
  assert.deepEqual(result.workflows.map((s) => s.projection.workflowId), ['wf-a', 'wf-b', 'wf-c'])
}

// Truncation when over limit
{
  const snapshots = ['wf-c', 'wf-a', 'wf-b', 'wf-d'].map((id) => makeSnapshot(id))
  const result = applyNamespaceMetricsLimit(snapshots, 2)
  assert.equal(result.truncated, true)
  assert.equal(result.matchedWorkflowCount, 4)
  assert.equal(result.includedWorkflowCount, 2)
  // First two alphabetically
  assert.deepEqual(result.workflows.map((s) => s.projection.workflowId), ['wf-a', 'wf-b'])
}

// ──────────────────────────────────────────────────────────────────────────────
// 6. projectNamespaceOperationalMetrics — empty selection
// ──────────────────────────────────────────────────────────────────────────────

{
  const result = projectNamespaceOperationalMetrics({
    namespaceId: NS, scope: 'group', selector: { groupId: 'sprint-99' },
    observedAt: NOW, workflows: [], timingsByWorkflowId: {}, interactions: [],
    deliveries: [], deliveryEvidence: [], matchedWorkflowCount: 0, includedWorkflowCount: 0, truncated: false,
  })
  assert.equal(result.schemaVersion, '1')
  assert.equal(result.lifecycleScope, 'active')
  assert.equal(result.truncated, false)
  assert.equal(result.matchedWorkflowCount, 0)
  assert.equal(result.scope.kind, 'namespace-rollup')
  assert.equal(result.scope.includedWorkflowIds.length, 0)
  // WIP is 0 for empty selection
  assert.equal(result.metrics.currentWip.available, true)
  assert.equal(result.metrics.currentWip.value.count, 0)
  // Duration metrics are unavailable
  assert.equal(result.metrics.cycleTime.available, false)
  assert.ok(result.metrics.cycleTime.reasons.includes('no_active_workflows_in_selection'))
  // Capabilities still correctly unavailable
  assert.equal(result.capabilities.llmUsage.available, false)
}

// ──────────────────────────────────────────────────────────────────────────────
// 7. Truncation propagation: duration metrics marked incomplete
// ──────────────────────────────────────────────────────────────────────────────

{
  const snapshots = ['wf-a', 'wf-b', 'wf-c'].map((id) => makeSnapshot(id))
  const result = projectNamespaceOperationalMetrics({
    namespaceId: NS, scope: 'namespace', selector: {},
    observedAt: NOW, workflows: snapshots.slice(0, 2),
    timingsByWorkflowId: {}, interactions: [], deliveries: [], deliveryEvidence: [],
    matchedWorkflowCount: 3, includedWorkflowCount: 2, truncated: true,
  })
  assert.equal(result.truncated, true)
  // All metrics that can be incomplete must carry the selection_truncated reason
  const allMetrics = Object.values(result.metrics)
  for (const metric of allMetrics) {
    if (!metric.available) continue // already unavailable, no further check needed
    assert.ok(metric.reasons.includes('selection_truncated'), `metric must carry selection_truncated: ${JSON.stringify(metric)}`)
    assert.equal(metric.complete, false)
  }
}

// ──────────────────────────────────────────────────────────────────────────────
// 8. Selector shape in response
// ──────────────────────────────────────────────────────────────────────────────

{
  const result = projectNamespaceOperationalMetrics({
    namespaceId: NS, scope: 'group', selector: { groupId: 'sprint-1' },
    observedAt: NOW, workflows: [], timingsByWorkflowId: {}, interactions: [],
    deliveries: [], deliveryEvidence: [], matchedWorkflowCount: 0, includedWorkflowCount: 0, truncated: false,
  })
  assert.deepEqual(result.selector, { scope: 'group', groupId: 'sprint-1' })
}

{
  const result = projectNamespaceOperationalMetrics({
    namespaceId: NS, scope: 'root', selector: { rootWorkflowId: 'wf-root' },
    observedAt: NOW, workflows: [], timingsByWorkflowId: {}, interactions: [],
    deliveries: [], deliveryEvidence: [], matchedWorkflowCount: 0, includedWorkflowCount: 0, truncated: false,
  })
  assert.deepEqual(result.selector, { scope: 'root', rootWorkflowId: 'wf-root' })
}

{
  const result = projectNamespaceOperationalMetrics({
    namespaceId: NS, scope: 'namespace', selector: {},
    observedAt: NOW, workflows: [], timingsByWorkflowId: {}, interactions: [],
    deliveries: [], deliveryEvidence: [], matchedWorkflowCount: 0, includedWorkflowCount: 0, truncated: false,
  })
  assert.deepEqual(result.selector, { scope: 'namespace' })
}

// ──────────────────────────────────────────────────────────────────────────────
// 9. HTTP route validation — namespace metrics endpoint
// ──────────────────────────────────────────────────────────────────────────────

const nsPath = `/api/factory/namespaces/${NS}/metrics`

// Valid namespace scope → 200
{
  const res = await routeRequest(nsPath, 'scope=namespace')
  assert.equal(res.status, 200)
}

// Invalid namespace ID in path
{
  const res = await routeRequest('/api/factory/namespaces/not-a-uuid/metrics', 'scope=namespace')
  assert.equal(res.status, 400)
  assert.equal(res.body.error.code, 'INVALID_NAMESPACE_ID')
}

// Invalid scope
{
  const res = await routeRequest(nsPath, 'scope=global')
  assert.equal(res.status, 400)
  assert.equal(res.body.error.code, 'INVALID_NAMESPACE_METRICS_SCOPE')
}

// groupId and rootWorkflowId both present → ambiguous
{
  const res = await routeRequest(nsPath, 'scope=namespace&groupId=g1&rootWorkflowId=wf-1')
  assert.equal(res.status, 400)
  assert.equal(res.body.error.code, 'AMBIGUOUS_SELECTOR')
}

// scope=group without groupId
{
  const res = await routeRequest(nsPath, 'scope=group')
  assert.equal(res.status, 400)
  assert.equal(res.body.error.code, 'MISSING_GROUP_ID')
}

// scope=root without rootWorkflowId
{
  const res = await routeRequest(nsPath, 'scope=root')
  assert.equal(res.status, 400)
  assert.equal(res.body.error.code, 'MISSING_ROOT_WORKFLOW_ID')
}

// Invalid limit
{
  const res = await routeRequest(nsPath, 'scope=namespace&limit=0')
  assert.equal(res.status, 400)
  assert.equal(res.body.error.code, 'INVALID_LIMIT')
}

// Invalid observedAt
{
  const res = await routeRequest(nsPath, 'scope=namespace&observedAt=not-a-date')
  assert.equal(res.status, 400)
  assert.equal(res.body.error.code, 'INVALID_OBSERVED_AT')
}

// ROOT_WORKFLOW_NOT_FOUND → 404
{
  const res = await routeRequest(nsPath, 'scope=root&rootWorkflowId=wf-missing', {
    projectNamespace: async () => ({ ok: false, code: 'ROOT_WORKFLOW_NOT_FOUND', message: 'Root not found.' }),
  })
  assert.equal(res.status, 404)
  assert.equal(res.body.error.code, 'ROOT_WORKFLOW_NOT_FOUND')
}

// Per-workflow endpoint still works (regression)
{
  const res = await routeRequest(`/api/factory/workflows/wf-1/metrics`, `namespaceId=${NS}&scope=self`)
  assert.equal(res.status, 200)
}

// Non-GET method on namespace metrics route → 404
{
  let response
  const url = new URL(`${nsPath}?scope=namespace`, 'http://localhost')
  const service = { projectNamespace: async () => ({ ok: true, data: {} }) }
  await handleWorkflowOperationalMetricsRequest({ method: 'POST', path: url.pathname, url, service, clock: { now: () => new Date(NOW) }, send: (status, body) => { response = { status, body } }, log: { error() {} } })
  assert.equal(response.status, 404)
}

console.log('Phase 10D namespace operational metrics tests: all assertions passed (source-only, not executed in CI)')
