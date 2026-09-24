import { projectWorkflowTiming } from '../lib/workflow-timing-projector.mjs'

let failed = 0
function expect(name, actual, expected) { const ok = JSON.stringify(actual) === JSON.stringify(expected); console.log(`${ok ? '✓' : '✗'} ${name}`); if (!ok) { failed++; console.log(`  expected=${JSON.stringify(expected)} actual=${JSON.stringify(actual)}`) } }
const fact = (revision, observedAt, workflow, steps = [], kind = 'projection_published') => ({ kind, revision, observedAt, transitionDelta: { workflow, steps } })
const facts = [
  fact(1, '2026-01-01T00:00:00Z', { from: null, to: 'running' }, [{ kind: 'added', stepId: 's', status: { from: null, to: 'running' } }], 'projection_created'),
  fact(2, '2026-01-01T00:00:10Z', { from: 'running', to: 'waiting_human' }, [{ kind: 'status_changed', stepId: 's', status: { from: 'running', to: 'waiting_human' } }]),
  fact(3, '2026-01-01T00:00:20Z', { from: 'waiting_human', to: 'running' }, [{ kind: 'status_changed', stepId: 's', status: { from: 'waiting_human', to: 'running' } }]),
  fact(4, '2026-01-01T00:00:30Z', { from: 'running', to: 'blocked' }, [{ kind: 'status_changed', stepId: 's', status: { from: 'running', to: 'completed' } }]),
]
let timing = projectWorkflowTiming(facts, '2026-01-01T00:00:40Z')
expect('creation and open blocked interval', [timing.complete, timing.createdAt, timing.activeMs, timing.waitingHumanMs, timing.blockedMs, timing.transitionCount], [true, '2026-01-01T00:00:00.000Z', 20000, 10000, 10000, 4])
expect('step attempts and completion', [timing.steps[0].activeMs, timing.steps[0].waitingHumanMs, timing.steps[0].attemptCount, timing.steps[0].firstCompletedAt], [20000, 10000, 2, '2026-01-01T00:00:30.000Z'])
const reopened = [...facts, fact(5, '2026-01-01T00:00:40Z', { from: 'blocked', to: 'completed' }, [{ kind: 'status_changed', stepId: 's', status: { from: 'completed', to: 'ready' } }]), fact(6, '2026-01-01T00:00:50Z', { from: 'completed', to: 'completed' }, [{ kind: 'status_changed', stepId: 's', status: { from: 'ready', to: 'completed' } }])]
timing = projectWorkflowTiming(reopened, '2026-01-01T00:01:00Z')
expect('reopening preserves first and last completion', [timing.steps[0].firstCompletedAt, timing.steps[0].lastCompletedAt, timing.steps[0].attemptCount], ['2026-01-01T00:00:30.000Z', '2026-01-01T00:00:50.000Z', 3])
const legacy = projectWorkflowTiming([{ kind: 'projection_created', revision: 1, timestamp: 'bad' }], '2026-01-01T00:00:00Z', { snapshot: { projection: { status: 'ready', steps: [{ id: 's', status: 'pending' }] } } })
expect('malformed legacy is honest and fail closed', [legacy.complete, legacy.totalElapsedMs, legacy.currentStatus, legacy.currentStatusSince], [false, 0, 'ready', null])
const nonMonotonic = projectWorkflowTiming([facts[0], fact(2, '2025-12-31T23:59:59Z', { from: 'running', to: 'completed' })], '2026-01-01T00:00:10Z')
expect('non-monotonic duration never negative', [nonMonotonic.complete, nonMonotonic.activeMs >= 0], [false, true])
console.log(`\nResult: ${failed ? 'failed' : 'passed'}`)
process.exit(failed ? 1 : 0)
