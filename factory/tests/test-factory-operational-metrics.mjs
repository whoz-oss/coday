import { projectFactoryOperationalMetrics } from '../lib/factory-operational-metrics-projector.mjs'

let failed = 0
function expect(name, actual, expected) { const ok = JSON.stringify(actual) === JSON.stringify(expected); console.log(`${ok ? '✓' : '✗'} ${name}`); if (!ok) { failed++; console.log({ expected, actual }) } }
const observedAt = '2026-01-01T02:00:00Z'
const snapshot = (id, parentWorkflowId, status = 'completed', deliveryId) => ({ projection: { workflowId: id, status }, instance: { relations: { rootWorkflowId: 'root', ...(parentWorkflowId ? { parentWorkflowId } : {}) }, ...(deliveryId ? { deliveryRef: { deliveryId } } : {}) } })
const timing = (startedAt, lastCompletedAt, currentStatus = 'completed') => ({ complete: true, incompleteReasons: [], startedAt, lastCompletedAt, currentStatus })
const evidence = [
  { evidenceId: 'approval', kind: 'human-decision', outcome: 'approved' },
  { evidenceId: 'deploy', kind: 'deployment-result', outcome: 'pass' },
  { evidenceId: 'smoke', kind: 'smoke-result', outcome: 'pass' },
]
const operation = (operationId, revision, timestamp, evidenceIds) => ({ operationId, kind: 'delivery_promoted', state: 'succeeded', revision, timestamp, evidenceIds })
const result = projectFactoryOperationalMetrics({
  workflowId: 'root', scope: 'descendants', observedAt,
  workflows: [snapshot('root', null, 'completed', 'delivery'), snapshot('child', 'root', 'running')],
  timingsByWorkflowId: { root: timing('2026-01-01T00:00:00Z', '2026-01-01T01:00:00Z'), child: { complete: true, incompleteReasons: [], startedAt: '2026-01-01T00:30:00Z', currentStatus: 'running' } },
  interactions: [
    { interactionId: 'a', workflowId: 'root', kind: 'approval', status: 'replied', openedAt: '2026-01-01T00:10:00Z', repliedAt: '2026-01-01T00:20:00Z' },
    { interactionId: 'b', workflowId: 'child', kind: 'approval', status: 'open', openedAt: '2026-01-01T01:30:00Z' },
  ],
  deliveries: [{ snapshot: { deliveryId: 'delivery', workflowId: 'root', createdAt: '2025-12-31T23:00:00Z' }, operations: [operation('release', 3, '2026-01-01T00:30:00Z', ['approval']), operation('deploy', 4, '2026-01-01T00:45:00Z', ['deploy']), operation('verify', 5, '2026-01-01T01:00:00Z', ['smoke'])] }],
  deliveryEvidence: evidence,
})
expect('descendants deduplicated and WIP counted once', [result.scope.includedWorkflowIds, result.metrics.currentWip.value.count, result.metrics.currentWip.value.byState], [['child', 'root'], 1, { running: 1 }])
expect('descendant cycle is honest interval envelope', [result.metrics.cycleTime.value.semantics, result.metrics.cycleTime.complete], ['calendar_envelope_and_interval_union', false])
expect('review unresolved remains incomplete with explicit in-progress measure', [result.metrics.reviewTime.complete, result.metrics.reviewTime.value.durationMs, result.metrics.reviewTime.value.inProgressObservedApprovalTime.semantics], [false, 600000, 'open_approval_intervals_closed_at_observed_at'])
expect('deployment uses production verified proof endpoint', [result.metrics.deploymentDelay.complete, result.metrics.deploymentDelay.value.intervals[0].endpoint, result.metrics.deploymentDelay.value.intervals[0].durationMs], [true, 'production-verified', 1800000])
expect('lead time is explicitly named and authoritative', [result.metrics.workflowCreatedToProductionVerified.available, result.metrics.workflowCreatedToProductionVerified.value.semantics], [true, 'workflow_created_to_production_verified'])
expect('unsupported values are not fabricated', [result.capabilities.llmUsage.available, result.capabilities.cost.available, result.capabilities.dora.available, result.capabilities.rollbackRate.available], [false, false, false, false])
const corrupt = projectFactoryOperationalMetrics({ workflowId: 'root', observedAt, workflows: [snapshot('root')], timingsByWorkflowId: { root: { complete: false, incompleteReasons: ['fact:1:non_monotonic_timestamp'], currentStatus: 'completed' } } })
expect('malformed timing fails closed', [corrupt.metrics.cycleTime.available, corrupt.metrics.cycleTime.complete, corrupt.metrics.cycleTime.reasons], [false, false, ['root:cycle_start_unavailable', 'root:fact:1:non_monotonic_timestamp', 'root:workflow_completion_unavailable']])
console.log(`\nResult: ${failed ? 'failed' : 'passed'}`)
process.exit(failed ? 1 : 0)
