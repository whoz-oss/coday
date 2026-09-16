import assert from 'node:assert/strict'
import test from 'node:test'
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { adaptForgeRunToWorkflowProjection } from '../lib/forge-workflow-adapter.mjs'
import { syncForgeWorkflowProjection } from '../lib/forge-workflow-sync.mjs'
import { validateWorkflowProjection } from '../lib/workflow-projection.mjs'
import { WorkflowProjectionStore } from '../lib/workflow-projection-store.mjs'

const gate = (humanDecision = null, startedAt = null, decidedAt = null) => ({ humanDecision, startedAt, decidedAt })
const run = (decisions = [null, null, null, null], outcome = 'in-progress') => ({
  ticketId: 'WZ-34411', ticketSummary: '[Front] Design System Bootstrap',
  gates: Object.fromEntries(decisions.map((decision, index) => [`gate_${index + 1}`, decision === null ? gate() : gate(decision, `2026-09-0${index + 1}T10:00:00Z`, `2026-09-0${index + 1}T11:00:00Z`)])),
  runOutcome: { status: outcome },
})

test('completed copied-run shape yields stable validated four-gate projection', () => {
  const result = adaptForgeRunToWorkflowProjection(run(['approved', 'approved', 'approved', 'approved'], 'completed'))
  assert.equal(result.ok, true); assert.equal(result.projection.workflowId, 'forge-run-WZ-34411')
  assert.deepEqual(result.projection.steps.map(({ id, name, dependsOn }) => ({ id, name, dependsOn })), [
    { id: 'gate-1', name: 'Ticket', dependsOn: [] }, { id: 'gate-2', name: 'Spec', dependsOn: ['gate-1'] },
    { id: 'gate-3', name: 'Tech Review', dependsOn: ['gate-2'] }, { id: 'gate-4', name: 'Func Review', dependsOn: ['gate-3'] },
  ])
  assert.equal(validateWorkflowProjection(result.projection).ok, true)
})

test('supported decisions are explicit and started undecided work is running, not waiting_human', () => {
  for (const decision of ['approved', 'approved-with-changes']) assert.equal(adaptForgeRunToWorkflowProjection(run([decision])).projection.steps[0].status, 'completed')
  assert.equal(adaptForgeRunToWorkflowProjection(run(['rejected'])).projection.steps[0].status, 'failed')
  const active = run(); active.gates.gate_1 = gate(null, '2026-09-01T10:00:00Z', null)
  assert.equal(adaptForgeRunToWorkflowProjection(active).projection.steps[0].status, 'running')
})

test('unknown values and impossible gate order fail closed; absent optional gates stay pending', () => {
  assert.equal(adaptForgeRunToWorkflowProjection(run(['maybe'])).error.code, 'UNKNOWN_FORGE_DECISION')
  assert.equal(adaptForgeRunToWorkflowProjection(run([], 'paused')).error.code, 'UNKNOWN_FORGE_OUTCOME')
  const impossible = run(); impossible.gates.gate_2 = gate(null, '2026-09-02T10:00:00Z', null)
  assert.equal(adaptForgeRunToWorkflowProjection(impossible).error.code, 'IMPOSSIBLE_FORGE_GATE_ORDER')
  const absent = run(); delete absent.gates.gate_4
  assert.equal(adaptForgeRunToWorkflowProjection(absent).projection.steps[3].status, 'pending')
})

test('timestamps are validated within each gate only', () => {
  for (const [startedAt, decidedAt, reason] of [['not-a-date', null, 'invalid_timestamp'], [null, '2026-09-01T11:00:00Z', 'decided_without_start'], ['2026-09-01T12:00:00Z', '2026-09-01T11:00:00Z', 'decision_before_start']]) {
    const value = run(); value.gates.gate_1 = gate(null, startedAt, decidedAt)
    assert.equal(adaptForgeRunToWorkflowProjection(value).error.details.reason, reason)
  }
  const nonMonotonic = run(['approved', 'approved', 'approved']); nonMonotonic.gates.gate_3 = gate('approved', '2026-08-01T10:00:00Z', '2026-08-01T11:00:00Z')
  assert.equal(adaptForgeRunToWorkflowProjection(nonMonotonic).ok, true)
})

test('abandoned is cancelled and preserves terminal decisions', () => {
  const result = adaptForgeRunToWorkflowProjection(run(['approved'], 'abandoned'))
  assert.equal(result.projection.status, 'cancelled'); assert.deepEqual(result.projection.steps.map((step) => step.status), ['completed', 'cancelled', 'cancelled', 'cancelled'])
})

test('sync reads authoritative YAML and generic store provides idempotence', async () => {
  const root = await mkdtemp(join(tmpdir(), 'forge-sync-')); const data = join(root, 'data'); const repo = join(root, 'repo')
  try {
    await mkdir(join(repo, 'forge/state/forge-runs'), { recursive: true })
    await writeFile(join(repo, 'forge/state/forge-runs/WZ-34411.yaml'), `ticket_id: WZ-34411\nticket_summary: Test\ngate_1:\n  started_at: null\n  decided_at: null\n  human_decision: null\nrun_outcome:\n  status: in-progress\n`)
    const store = new WorkflowProjectionStore(data); await store.initialize(); const namespaceId = '123e4567-e89b-42d3-a456-426614174000'
    const first = await syncForgeWorkflowProjection({ repoRoot: repo, namespaceId, ticketId: 'WZ-34411', store })
    const second = await syncForgeWorkflowProjection({ repoRoot: repo, namespaceId, ticketId: 'WZ-34411', store })
    assert.equal(first.changed, true); assert.equal(second.changed, false); assert.equal(second.revision, first.revision)
    const before = await store.read(namespaceId, first.workflowId)
    await writeFile(join(repo, 'forge/state/forge-runs/WZ-34411.yaml'), `ticket_id: WZ-34411\nticket_summary: "unterminated\ngate_1:\n  started_at: null\n  decided_at: null\n  human_decision: null\nrun_outcome:\n  status: in-progress\n`)
    const corrupt = await syncForgeWorkflowProjection({ repoRoot: repo, namespaceId, ticketId: 'WZ-34411', store })
    assert.equal(corrupt.ok, false)
    const after = await store.read(namespaceId, first.workflowId)
    assert.equal(after.revision, before.revision); assert.equal(after.projectionHash, before.projectionHash); assert.deepEqual(after.projection, before.projection)
  } finally { await rm(root, { recursive: true, force: true }) }
})
