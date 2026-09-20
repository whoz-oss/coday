import { halfOpenIntervalEnvelope, halfOpenIntervalUnionDuration, mergeHalfOpenIntervals } from './interval-aggregation.mjs'
import { collectWorkflowDescendants } from './workflow-relations.mjs'

const TERMINAL = new Set(['completed', 'failed', 'cancelled'])
const WIP = new Set(['pending', 'ready', 'running', 'waiting_human', 'blocked'])
const DEPLOYMENT_ENDPOINTS = Object.freeze(['deployed', 'production-verified'])

function instant(value) {
  if (typeof value !== 'string') return null
  const milliseconds = Date.parse(value)
  return Number.isFinite(milliseconds) ? milliseconds : null
}

function availability({ value, complete, reasons = [], sources = [], extra = {} }) {
  return {
    available: value !== undefined,
    complete: Boolean(complete),
    reasons: [...new Set(reasons)].sort(),
    sourceCategories: [...new Set(sources)].sort(),
    ...(value !== undefined ? { value } : {}),
    ...extra,
  }
}

function unavailable(reason, sources = []) {
  return availability({ complete: false, reasons: [reason], sources })
}

function workflowId(snapshot) { return snapshot?.projection?.workflowId }

function includedSnapshots(workflows, rootWorkflowId, scope) {
  const root = workflows.find((snapshot) => workflowId(snapshot) === rootWorkflowId)
  if (!root) return []
  return scope === 'descendants' ? [root, ...collectWorkflowDescendants(workflows, rootWorkflowId)] : [root]
}

function intervalsFromTimings(items, startField, endField) {
  const intervals = []
  for (const item of items) {
    const start = instant(item.timing?.[startField]), end = instant(item.timing?.[endField])
    if (start !== null && end !== null && start < end) intervals.push({ start, end })
  }
  return intervals
}

function cycleTime(items, scope) {
  const sources = ['workflow-journal']
  const intervals = intervalsFromTimings(items, 'startedAt', 'lastCompletedAt')
  const reasons = items.flatMap(({ workflowId: id, timing }) => [
    ...(!timing?.complete ? (timing?.incompleteReasons ?? ['timing_projection_incomplete']).map((reason) => `${id}:${reason}`) : []),
    ...(!timing?.startedAt ? [`${id}:cycle_start_unavailable`] : []),
    ...(!timing?.lastCompletedAt ? [`${id}:workflow_completion_unavailable`] : []),
  ])
  if (!intervals.length) return availability({ complete: false, reasons: reasons.length ? reasons : ['cycle_endpoints_unavailable'], sources })
  if (scope === 'self') {
    const interval = intervals[0]
    return availability({ value: { semantics: 'workflow_interval', startAt: new Date(interval.start).toISOString(), endAt: new Date(interval.end).toISOString(), durationMs: interval.end - interval.start }, complete: reasons.length === 0 && intervals.length === items.length, reasons, sources })
  }
  const merged = mergeHalfOpenIntervals(intervals), envelope = halfOpenIntervalEnvelope(intervals)
  return availability({ value: { semantics: 'calendar_envelope_and_interval_union', envelope: { startAt: new Date(envelope.start).toISOString(), endAt: new Date(envelope.end).toISOString(), durationMs: envelope.end - envelope.start }, unionDurationMs: halfOpenIntervalUnionDuration(intervals), mergedIntervals: merged.map(({ start, end }) => ({ startAt: new Date(start).toISOString(), endAt: new Date(end).toISOString(), durationMs: end - start })) }, complete: reasons.length === 0 && intervals.length === items.length, reasons, sources })
}

function reviewTime(interactions, observedAt) {
  const sources = ['human-interaction-journal']
  const completed = [], unresolved = [], reasons = []
  for (const interaction of interactions) {
    if (interaction?.kind !== 'approval') continue
    const opened = instant(interaction.openedAt)
    if (opened === null) { reasons.push(`${interaction.interactionId ?? 'unknown'}:invalid_opened_at`); continue }
    if (interaction.status === 'replied') {
      const replied = instant(interaction.repliedAt)
      if (replied === null || replied <= opened) reasons.push(`${interaction.interactionId ?? 'unknown'}:invalid_replied_at`)
      else completed.push({ start: opened, end: replied })
    } else if (interaction.status === 'open') unresolved.push({ interactionId: interaction.interactionId, openedAt: new Date(opened).toISOString() })
    else reasons.push(`${interaction.interactionId ?? 'unknown'}:invalid_status`)
  }
  if (unresolved.length) reasons.push('unresolved_approval_interactions')
  const merged = mergeHalfOpenIntervals(completed)
  const value = { semantics: 'completed_approval_interval_union', durationMs: halfOpenIntervalUnionDuration(completed), mergedIntervals: merged.map(({ start, end }) => ({ startAt: new Date(start).toISOString(), endAt: new Date(end).toISOString(), durationMs: end - start })), completedInteractionCount: completed.length, unresolvedInteractions: unresolved }
  if (unresolved.length) {
    const observedMs = instant(observedAt)
    value.inProgressObservedApprovalTime = observedMs === null ? { available: false, reason: 'invalid_observed_at' } : { available: true, semantics: 'open_approval_intervals_closed_at_observed_at', observedAt: new Date(observedMs).toISOString(), durationMs: halfOpenIntervalUnionDuration(unresolved.map((item) => ({ start: instant(item.openedAt), end: observedMs })).filter((interval) => interval.start < interval.end)) }
  }
  return availability({ value, complete: reasons.length === 0, reasons, sources })
}

function currentWip(items) {
  const byState = {}
  const reasons = []
  for (const { workflowId: id, snapshot, timing } of items) {
    const state = timing?.currentStatus ?? snapshot?.projection?.status
    if (!state) { reasons.push(`${id}:current_state_unavailable`); continue }
    if (!WIP.has(state) && !TERMINAL.has(state)) { reasons.push(`${id}:unknown_current_state`); continue }
    if (WIP.has(state)) byState[state] = (byState[state] ?? 0) + 1
  }
  return availability({ value: { semantics: 'unique_workflows_in_non_terminal_states', nonTerminalStates: [...WIP], count: Object.values(byState).reduce((sum, count) => sum + count, 0), byState }, complete: reasons.length === 0, reasons, sources: ['workflow-snapshot', 'workflow-journal'] })
}

function successfulPromotions(deliveries, includedIds, evidence) {
  const evidenceById = new Map(evidence.map((item) => [item.evidenceId, item]))
  const results = []
  for (const delivery of deliveries) {
    if (!includedIds.has(delivery.snapshot?.workflowId)) continue
    const grouped = new Map()
    for (const operation of delivery.operations ?? []) {
      if (!operation?.operationId) continue
      const records = grouped.get(operation.operationId) ?? []
      records.push(operation); grouped.set(operation.operationId, records)
    }
    for (const [operationId, records] of grouped) {
      const succeeded = records.filter((record) => record.state === 'succeeded')
      if (succeeded.length !== 1) continue
      const operation = succeeded[0]
      if (operation.kind !== 'delivery_promoted') continue
      const timestamp = instant(operation.timestamp)
      const promotedEvidence = (operation.evidenceIds ?? []).map((id) => evidenceById.get(id)).filter(Boolean)
      results.push({ operationId, deliveryId: delivery.snapshot.deliveryId, workflowId: delivery.snapshot.workflowId, revision: operation.revision, timestamp, evidenceIds: operation.evidenceIds ?? [], evidence: promotedEvidence })
    }
  }
  return results
}

function deliveryStage(records, stage, requiredKind, requiredOutcome) {
  const expectedRevision = { 'release-approved': 3, deployed: 4, 'production-verified': 5 }[stage]
  const matching = records.filter((record) => record.revision === expectedRevision && record.timestamp !== null && record.evidence.some((item) => item.kind === requiredKind && item.outcome === requiredOutcome))
  return matching.sort((left, right) => left.revision - right.revision || left.timestamp - right.timestamp)[0] ?? null
}

function deliveryMetrics(deliveries, evidence, includedIds) {
  const sources = ['delivery-operation-journal', 'delivery-evidence-journal']
  const promotions = successfulPromotions(deliveries, includedIds, evidence)
  const byDelivery = new Map()
  for (const promotion of promotions) { const records = byDelivery.get(promotion.deliveryId) ?? []; records.push(promotion); byDelivery.set(promotion.deliveryId, records) }
  const intervals = [], leadIntervals = [], reasons = []
  for (const delivery of deliveries.filter((item) => includedIds.has(item.snapshot?.workflowId))) {
    const records = byDelivery.get(delivery.snapshot.deliveryId) ?? []
    const release = deliveryStage(records, 'release-approved', 'human-decision', 'approved')
    const deployed = deliveryStage(records, 'deployed', 'deployment-result', 'pass')
    const verified = deliveryStage(records, 'production-verified', 'smoke-result', 'pass')
    const endpoint = verified ?? deployed
    if (!release) { reasons.push(`${delivery.snapshot.deliveryId}:release_approval_proof_unavailable`); continue }
    if (!endpoint || endpoint.timestamp <= release.timestamp) { reasons.push(`${delivery.snapshot.deliveryId}:successful_delivery_endpoint_unavailable`); continue }
    intervals.push({ start: release.timestamp, end: endpoint.timestamp, endpoint: verified ? 'production-verified' : 'deployed', deliveryId: delivery.snapshot.deliveryId })
    const created = instant(delivery.snapshot.createdAt)
    if (created !== null && verified && verified.timestamp > created) leadIntervals.push({ start: created, end: verified.timestamp, deliveryId: delivery.snapshot.deliveryId })
  }
  const deploymentDelay = intervals.length ? availability({ value: { semantics: 'release_approved_to_successful_delivery_endpoint', intervals: intervals.map((item) => ({ deliveryId: item.deliveryId, endpoint: item.endpoint, startAt: new Date(item.start).toISOString(), endAt: new Date(item.end).toISOString(), durationMs: item.end - item.start })) }, complete: reasons.length === 0, reasons, sources }) : availability({ complete: false, reasons: reasons.length ? reasons : ['delivery_promotion_proof_unavailable'], sources })
  const leadReasons = deliveries.filter((item) => includedIds.has(item.snapshot?.workflowId)).length === leadIntervals.length ? [] : ['authoritative_creation_or_production_verification_unavailable']
  const lead = leadIntervals.length ? availability({ value: { semantics: 'workflow_created_to_production_verified', intervals: leadIntervals.map((item) => ({ deliveryId: item.deliveryId, startAt: new Date(item.start).toISOString(), endAt: new Date(item.end).toISOString(), durationMs: item.end - item.start })) }, complete: leadReasons.length === 0, reasons: leadReasons, sources }) : unavailable('authoritative_creation_or_production_verification_unavailable', sources)
  return { deploymentDelay, workflowCreatedToProductionVerified: lead }
}

export function projectFactoryOperationalMetrics({ workflowId: rootWorkflowId, scope = 'self', observedAt, workflows = [], timingsByWorkflowId = {}, interactions = [], deliveries = [], deliveryEvidence = [] }) {
  if (!['self', 'descendants'].includes(scope)) throw new TypeError('scope must be self or descendants')
  if (instant(observedAt) === null) throw new TypeError('observedAt must be a valid explicit instant')
  const snapshots = includedSnapshots(workflows, rootWorkflowId, scope)
  const seen = new Set(), items = []
  for (const snapshot of snapshots) {
    const id = workflowId(snapshot)
    if (!id || seen.has(id)) continue
    seen.add(id); items.push({ workflowId: id, snapshot, timing: timingsByWorkflowId[id] })
  }
  if (!items.length) return { schemaVersion: '1', observedAt: new Date(instant(observedAt)).toISOString(), scope: { kind: scope, workflowId: rootWorkflowId, includedWorkflowIds: [] }, metrics: { cycleTime: unavailable('workflow_not_found', ['workflow-journal']), reviewTime: unavailable('workflow_not_found', ['human-interaction-journal']), currentWip: unavailable('workflow_not_found', ['workflow-snapshot']), deploymentDelay: unavailable('workflow_not_found', ['delivery-operation-journal']), workflowCreatedToProductionVerified: unavailable('workflow_not_found', ['delivery-operation-journal']) }, capabilities: unsupportedCapabilities() }
  const includedIds = new Set(items.map((item) => item.workflowId))
  const filteredInteractions = interactions.filter((item) => includedIds.has(item.workflowId))
  const delivery = deliveryMetrics(deliveries, deliveryEvidence, includedIds)
  return { schemaVersion: '1', observedAt: new Date(instant(observedAt)).toISOString(), scope: { kind: scope, workflowId: rootWorkflowId, includedWorkflowIds: [...includedIds].sort() }, metrics: { cycleTime: cycleTime(items, scope), reviewTime: reviewTime(filteredInteractions, observedAt), currentWip: currentWip(items), ...delivery }, capabilities: unsupportedCapabilities() }
}

function unsupportedCapabilities() {
  return {
    llmUsage: { available: false, reason: 'llm_usage_capture_not_implemented' },
    cost: { available: false, reason: 'pricing_and_cost_projection_not_implemented' },
    dora: { available: false, reason: 'dora_metrics_not_implemented' },
    rollbackRate: { available: false, reason: 'rollback_rate_not_implemented' },
  }
}
