// Focused Stage 6A lifecycle regression coverage. Intentionally not executed by the implementing agent.
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { WORKFLOW_STORE_ERROR_CODES, WorkflowProjectionStore } from '../lib/workflow-projection-store.mjs'

let passed = 0, failed = 0
function expect(name, actual, expected) { const ok = JSON.stringify(actual) === JSON.stringify(expected); console.log(`${ok ? '✓' : '✗'} ${name}`); if (!ok) console.log(`  expected=${JSON.stringify(expected)} actual=${JSON.stringify(actual)}`); ok ? passed++ : failed++ }
const A = '11111111-1111-4111-8111-111111111111', B = '22222222-2222-4222-8222-222222222222'
const projection = (workflowId = 'wf-1', title = 'Workflow') => ({ schemaVersion: '1', workflowId, workflowType: 'delivery', title, status: 'ready', steps: [] })
const root = await mkdtemp(join(tmpdir(), 'factory-lifecycle-'))
try {
  const store = new WorkflowProjectionStore(root); await store.initialize(); await store.publish(A, projection())
  const removed = await store.remove(A, 'wf-1', { actorId: 'operator-1', prose: 'must-not-persist' })
  expect('remove moves active to removed', [(await store.list(A)).length, (await store.listRemoved(A)).length], [0, 1])
  const paths = store.paths(A, 'wf-1'); const facts = (await readFile(paths.trashEvents, 'utf8')).trim().split('\n').map(JSON.parse)
  expect('remove fact is allow-listed', facts.at(-1), { kind: 'workflow_removed', generation: 1, timestamp: facts.at(-1).timestamp, actorId: 'operator-1' })
  expect('publication blocked by tombstone', (await store.publish(A, projection('wf-1', 'Recreated'))).error.code, WORKFLOW_STORE_ERROR_CODES.WORKFLOW_REMOVED)
  expect('namespace isolation', (await store.publish(B, projection())).ok, true)
  expect('second remove invalid transition', (await store.remove(A, 'wf-1')).error.code, WORKFLOW_STORE_ERROR_CODES.INVALID_LIFECYCLE_TRANSITION)
  expect('missing remove', (await store.remove(A, 'missing')).error.code, WORKFLOW_STORE_ERROR_CODES.WORKFLOW_NOT_FOUND)
  const restored = await store.restore(A, 'wf-1', { actorId: 'operator-2' })
  expect('restore returns active revision', [restored.ok, restored.snapshot.revision, (await store.listRemoved(A)).length], [true, 1, 0])
  expect('restore without tombstone invalid/absent', (await store.restore(A, 'wf-1')).error.code, WORKFLOW_STORE_ERROR_CODES.WORKFLOW_NOT_FOUND)
  await store.remove(A, 'wf-1'); expect('purge removed workflow', (await store.purge(A, 'wf-1', { purgedBy: 'admin' })).ok, true)
  expect('retained tombstone blocks recreation', (await store.publish(A, projection())).error.code, WORKFLOW_STORE_ERROR_CODES.WORKFLOW_REMOVED)
  expect('purge idempotently tolerates already purged', (await store.purge(A, 'wf-1')).alreadyPurged, true)
  expect('internal clear allows intentional recreation', (await store.clearPurgedTombstone(A, 'wf-1')).ok, true)
  expect('recreation after explicit clear', (await store.publish(A, projection())).ok, true)

  await store.publish(A, projection('old')); await store.publish(A, projection('new')); await store.remove(A, 'old'); await store.remove(A, 'new')
  const oldPaths = store.paths(A, 'old'); const oldStone = JSON.parse(await readFile(oldPaths.tombstone, 'utf8')); oldStone.removedAt = '2000-01-01T00:00:00.000Z'; await writeFile(oldPaths.tombstone, `${JSON.stringify(oldStone)}\n`)
  const retention = await store.purgeRemovedBefore(A, '2020-01-01T00:00:00.000Z')
  expect('retention cutoff', [retention.examined, retention.eligible, retention.purged], [2, 1, 1])
  const newPaths = store.paths(A, 'new'); await writeFile(newPaths.tombstone, '{corrupt')
  let corrupt = false; try { await store.purgeRemovedBefore(A, new Date()) } catch (error) { corrupt = error.code === WORKFLOW_STORE_ERROR_CODES.CORRUPT_STORAGE }
  expect('corrupt retention state fails closed', corrupt, true)

  await store.publish(A, projection('race')); const original = store._remove.bind(store); let release; const gate = new Promise((resolve) => { release = resolve }); store._remove = async (...args) => { await gate; return original(...args) }
  const removing = store.remove(A, 'race'); const publishing = store.publish(A, projection('race', 'Concurrent')); release(); await removing
  expect('publish/remove share serialization lock', (await publishing).error.code, WORKFLOW_STORE_ERROR_CODES.WORKFLOW_REMOVED)

  // A publication interrupted after its durable fact but before snapshot commit is
  // recovered before remove decides whether active state exists.
  const recoverId = 'recover-before-remove'; const recoverPaths = store.paths(A, recoverId)
  await store.publish(A, projection(recoverId));
  const recoverSnapshot = JSON.parse(await readFile(recoverPaths.snapshot, 'utf8'))
  const nextProjection = projection(recoverId, 'Recovered revision')
  const { hashWorkflowProjection } = await import('../lib/workflow-projection.mjs')
  const pending = { revision: 2, projectionHash: hashWorkflowProjection(nextProjection), projection: nextProjection }
  await writeFile(recoverPaths.pending, `${JSON.stringify(pending)}\n`)
  await writeFile(recoverPaths.events, `${await readFile(recoverPaths.events, 'utf8')}${JSON.stringify({ kind: 'projection_published', revision: 2, projectionHash: pending.projectionHash, changedStepIds: [], timestamp: new Date().toISOString() })}\n`)
  await writeFile(recoverPaths.snapshot, `${JSON.stringify(recoverSnapshot)}\n`)
  const recoveredRemoval = await store.remove(A, recoverId)
  expect('remove recovers committed pending publication first', [recoveredRemoval.ok, recoveredRemoval.snapshot.revision], [true, 2])

  // Every injected boundary is retriable. Completed removal is represented by
  // exactly one generation-scoped semantic fact in the trash journal.
  for (const boundary of ['after-recovery', 'after-tombstone', 'after-rename', 'after-removal-fact', 'after-finalize']) {
    const id = `fault-${boundary}`; let injected = false
    const faultStore = new WorkflowProjectionStore(root, { lifecycleFault: async (point) => { if (!injected && point === boundary) { injected = true; throw new Error(`fault:${point}`) } } })
    await faultStore.publish(A, projection(id))
    try { await faultStore.remove(A, id) } catch {}
    const retry = await faultStore.remove(A, id)
    expect(`fault recovery ${boundary}`, boundary === 'after-finalize' ? retry.error?.code : retry.ok, boundary === 'after-finalize' ? WORKFLOW_STORE_ERROR_CODES.INVALID_LIFECYCLE_TRANSITION : true)
    const retryPaths = faultStore.paths(A, id)
    const removalFacts = (await readFile(retryPaths.trashEvents, 'utf8')).trim().split('\n').map(JSON.parse).filter((fact) => fact.kind === 'workflow_removed' && fact.generation === 1)
    expect(`single removal fact ${boundary}`, removalFacts.length, 1)
  }
} finally { await rm(root, { recursive: true, force: true }) }
console.log(`\nResult: ${passed} passed, ${failed} failed`); process.exit(failed ? 1 : 0)
