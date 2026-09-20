/**
 * Phase 10D — Namespace/group/root operational metrics rollup projector.
 *
 * DESIGN CONTRACT
 * ───────────────
 * This module selects a bounded set of active workflows from a namespace
 * and delegates the per-metric computation to the existing Phase 10B
 * projectFactoryOperationalMetrics() projector. It never duplicates metric
 * logic — it only implements the three selector modes and the honest
 * truncation/completeness propagation required for aggregates.
 *
 * SELECTOR SEMANTICS
 * ──────────────────
 * scope=namespace : every active workflow in the namespace (all roots +
 *                   all descendants).
 * scope=group     : active workflows whose effective stored relation
 *                   groupId exactly matches the requested groupId.
 *                   groupId is metadata, not a hierarchy edge.
 * scope=root      : active workflows whose effective stored relation
 *                   rootWorkflowId exactly matches the requested
 *                   rootWorkflowId. Legacy workflows with no relations
 *                   are independent roots (rootWorkflowId === workflowId).
 *
 * LIFECYCLE
 * ─────────
 * Only active workflows are included. Removed workflows are excluded
 * because their journals are moved to trash and the current metrics service
 * has no authoritative unified active+removed reader. Purged workflows are
 * never included. The response always carries lifecycleScope: 'active'.
 *
 * LIMIT AND TRUNCATION
 * ────────────────────
 * A deterministic safety bound is applied after selector filtering and
 * before metric projection. Workflows are sorted by workflowId before
 * slicing. Truncation is never silent: truncated=true, matchedWorkflowCount,
 * and includedWorkflowCount are always present, and all duration/timing
 * metrics are marked incomplete with reason 'selection_truncated'.
 */

import { storedWorkflowRelations } from './workflow-relations.mjs'
import { validateWorkflowProjectionId } from './workflow-projection.mjs'
import { projectFactoryOperationalMetrics } from './factory-operational-metrics-projector.mjs'

export const NAMESPACE_METRICS_SCOPES = Object.freeze(['namespace', 'group', 'root'])
export const NAMESPACE_METRICS_DEFAULT_LIMIT = 100
export const NAMESPACE_METRICS_MAX_LIMIT = 500

/**
 * Validate and parse the limit query parameter.
 * Returns { ok: true, limit } or { ok: false, code, message }.
 */
export function parseNamespaceMetricsLimit(raw, defaultLimit = NAMESPACE_METRICS_DEFAULT_LIMIT, maxLimit = NAMESPACE_METRICS_MAX_LIMIT) {
  if (raw === undefined || raw === null) return { ok: true, limit: defaultLimit }
  const parsed = Number(raw)
  if (!Number.isInteger(parsed) || parsed < 1) return { ok: false, code: 'INVALID_LIMIT', message: `limit must be a positive integer (default ${defaultLimit}, max ${maxLimit}).` }
  if (parsed > maxLimit) return { ok: false, code: 'INVALID_LIMIT', message: `limit must not exceed ${maxLimit}.` }
  return { ok: true, limit: parsed }
}

/**
 * Select active workflows from the namespace snapshot list according to scope.
 *
 * Returns { ok: true, selected: WorkflowSnapshot[] } or { ok: false, code, message }.
 */
export function selectWorkflows(allActive, scope, { groupId, rootWorkflowId } = {}) {
  if (!NAMESPACE_METRICS_SCOPES.includes(scope)) {
    return { ok: false, code: 'INVALID_SCOPE', message: 'scope must be namespace, group, or root.' }
  }

  if (scope === 'namespace') {
    return { ok: true, selected: allActive }
  }

  if (scope === 'group') {
    if (groupId === undefined) return { ok: false, code: 'MISSING_GROUP_ID', message: 'groupId is required when scope=group.' }
    const validated = validateWorkflowProjectionId(groupId, 'groupId')
    if (!validated.ok) return { ok: false, code: 'INVALID_GROUP_ID', message: 'groupId is invalid.' }
    const selected = allActive.filter((snapshot) => {
      const relations = storedWorkflowRelations(snapshot)
      return relations.groupId === groupId
    })
    return { ok: true, selected }
  }

  // scope === 'root'
  if (rootWorkflowId === undefined) return { ok: false, code: 'MISSING_ROOT_WORKFLOW_ID', message: 'rootWorkflowId is required when scope=root.' }
  const validatedRoot = validateWorkflowProjectionId(rootWorkflowId, 'rootWorkflowId')
  if (!validatedRoot.ok) return { ok: false, code: 'INVALID_ROOT_WORKFLOW_ID', message: 'rootWorkflowId is invalid.' }
  const selected = allActive.filter((snapshot) => {
    const relations = storedWorkflowRelations(snapshot)
    return relations.rootWorkflowId === rootWorkflowId
  })
  // The root workflow itself must be present in the active set.
  if (!selected.some((snapshot) => snapshot?.projection?.workflowId === rootWorkflowId)) {
    return { ok: false, code: 'ROOT_WORKFLOW_NOT_FOUND', message: 'Root workflow was not found in the active set for this namespace.' }
  }
  return { ok: true, selected }
}

/**
 * Apply the deterministic safety limit to a selected workflow list.
 * Sorts by workflowId before slicing.
 *
 * Returns { workflows, matchedWorkflowCount, includedWorkflowCount, truncated }.
 */
export function applyNamespaceMetricsLimit(selected, limit) {
  const sorted = [...selected].sort((a, b) => {
    const idA = a?.projection?.workflowId ?? ''
    const idB = b?.projection?.workflowId ?? ''
    return idA.localeCompare(idB)
  })
  const matchedWorkflowCount = sorted.length
  const truncated = matchedWorkflowCount > limit
  const workflows = truncated ? sorted.slice(0, limit) : sorted
  return { workflows, matchedWorkflowCount, includedWorkflowCount: workflows.length, truncated }
}

/**
 * Project namespace-scoped operational metrics for a bounded selection.
 *
 * Delegates metric computation to the existing Phase 10B projector,
 * using scope='descendants' for multi-workflow sets so that cycle time
 * uses envelope semantics, review time uses interval union, and WIP
 * counts unique states.
 *
 * TRUNCATION PROPAGATION
 * When truncated=true, all duration/timing metrics are forced to
 * incomplete with reason 'selection_truncated' appended. WIP is still
 * counted over the included set (explicitly noted as partial).
 */
export function projectNamespaceOperationalMetrics({ namespaceId, scope, selector, observedAt, workflows, timingsByWorkflowId, interactions, deliveries, deliveryEvidence, matchedWorkflowCount, includedWorkflowCount, truncated }) {
  if (workflows.length === 0) {
    return buildEmptyRollup({ namespaceId, scope, selector, observedAt, matchedWorkflowCount, includedWorkflowCount, truncated })
  }

  const projection = projectFactoryOperationalMetrics({
    workflowId: workflows[0].projection.workflowId,
    scope: 'selection',
    observedAt,
    workflows,
    timingsByWorkflowId,
    interactions,
    deliveries,
    deliveryEvidence,
  })

  // Apply truncation to completeness: mark all duration metrics incomplete.
  const metrics = truncated ? applyTruncationToMetrics(projection.metrics) : projection.metrics

  return {
    schemaVersion: '1',
    observedAt: projection.observedAt,
    selector: buildSelector(scope, selector),
    lifecycleScope: 'active',
    matchedWorkflowCount,
    includedWorkflowCount,
    truncated,
    scope: {
      kind: 'namespace-rollup',
      namespaceId,
      includedWorkflowIds: projection.scope.includedWorkflowIds,
    },
    metrics,
    capabilities: projection.capabilities,
  }
}

function buildSelector(scope, { groupId, rootWorkflowId } = {}) {
  if (scope === 'group') return { scope, groupId }
  if (scope === 'root') return { scope, rootWorkflowId }
  return { scope }
}

function buildEmptyRollup({ namespaceId, scope, selector, observedAt, matchedWorkflowCount, includedWorkflowCount, truncated }) {
  const unavailable = (reason, sources) => ({
    available: false, complete: false, reasons: [reason], sourceCategories: sources,
  })
  return {
    schemaVersion: '1',
    observedAt,
    selector: buildSelector(scope, selector),
    lifecycleScope: 'active',
    matchedWorkflowCount,
    includedWorkflowCount,
    truncated,
    scope: {
      kind: 'namespace-rollup',
      namespaceId,
      includedWorkflowIds: [],
    },
    metrics: {
      cycleTime: unavailable('no_active_workflows_in_selection', ['workflow-journal']),
      reviewTime: unavailable('no_active_workflows_in_selection', ['human-interaction-journal']),
      currentWip: { available: true, complete: true, reasons: [], sourceCategories: ['workflow-snapshot'], value: { semantics: 'unique_workflows_in_non_terminal_states', count: 0, byState: {} } },
      deploymentDelay: unavailable('no_active_workflows_in_selection', ['delivery-operation-journal']),
      workflowCreatedToProductionVerified: unavailable('no_active_workflows_in_selection', ['delivery-operation-journal']),
    },
    capabilities: {
      llmUsage: { available: false, reason: 'llm_usage_capture_not_implemented' },
      cost: { available: false, reason: 'pricing_and_cost_projection_not_implemented' },
      dora: { available: false, reason: 'dora_metrics_not_implemented' },
      rollbackRate: { available: false, reason: 'rollback_rate_not_implemented' },
    },
  }
}

function markIncomplete(metric, reason) {
  if (!metric.available) return metric
  return {
    ...metric,
    complete: false,
    reasons: [...new Set([...(metric.reasons ?? []), reason])].sort(),
  }
}

function applyTruncationToMetrics(metrics) {
  return {
    ...metrics,
    cycleTime: markIncomplete(metrics.cycleTime, 'selection_truncated'),
    reviewTime: markIncomplete(metrics.reviewTime, 'selection_truncated'),
    deploymentDelay: markIncomplete(metrics.deploymentDelay, 'selection_truncated'),
    workflowCreatedToProductionVerified: markIncomplete(metrics.workflowCreatedToProductionVerified, 'selection_truncated'),
    // currentWip: counts the included set; marked incomplete separately below.
    currentWip: markIncomplete(metrics.currentWip, 'selection_truncated'),
  }
}
