import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import {
  WORKFLOW_PROJECTION_ERROR_CODES,
  hashWorkflowProjection,
  validateWorkflowProjection,
} from '../lib/workflow-projection.mjs'
import { WORKFLOW_STORE_ERROR_CODES, WorkflowProjectionStore } from '../lib/workflow-projection-store.mjs'

let passed = 0
let failed = 0
function expect(name, value, expected) {
  const ok = JSON.stringify(value) === JSON.stringify(expected)
  console.log(`${ok ? '\u2713' : '\u2717'} ${name}`)
  if (!ok) console.log(`  expected=${JSON.stringify(expected)} actual=${JSON.stringify(value)}`)
  if (ok) passed++; else failed++
}

const base = {
  schemaVersion: '1', workflowId: 'wf-1', workflowType: 'delivery', title: 'Delivery', status: 'ready',
  steps: [
    { id: 'analyse', name: 'Analyse', status: 'completed' },
    { id: 'build', name: 'Build', status: 'ready', dependsOn: ['analyse'] },
  ],
}

expect('valid contract', validateWorkflowProjection(base).ok, true)
expect('unsafe workflow id', validateWorkflowProjection({ ...base, workflowId: '../x' }).error.code, WORKFLOW_PROJECTION_ERROR_CODES.INVALID_ID)
expect('duplicate step ids', validateWorkflowProjection({ ...base, steps: [base.steps[0], base.steps[0]] }).error.code, WORKFLOW_PROJECTION_ERROR_CODES.DUPLICATE_STEP_ID)
expect('missing dependency', validateWorkflowProjection({ ...base, steps: [{ ...base.steps[0], dependsOn: ['missing'] }] }).error.code, WORKFLOW_PROJECTION_ERROR_CODES.MISSING_DEPENDENCY)
expect('dependency cycle', validateWorkflowProjection({ ...base, steps: [{ id: 'a', name: 'A', status: 'ready', dependsOn: ['b'] }, { id: 'b', name: 'B', status: 'ready', dependsOn: ['a'] }] }).error.code, WORKFLOW_PROJECTION_ERROR_CODES.DEPENDENCY_CYCLE)
expect('expectedRevision excluded from hash', hashWorkflowProjection(validateWorkflowProjection(base).projection), hashWorkflowProjection(validateWorkflowProjection({ ...base, expectedRevision: 99 }).projection))

const root = await mkdtemp(join(tmpdir(), 'factory-projection-'))
try {
  const store = new WorkflowProjectionStore(root)
  await store.initialize()
  const created = await store.publish('namespace-1', base, { agentId: 'agent-1' })
  expect('creation revision', [created.ok, created.changed, created.snapshot.revision], [true, true, 1])
  const identical = await store.publish('namespace-1', { ...base, expectedRevision: 1 })
  expect('identical publication', [identical.changed, identical.snapshot.revision], [false, 1])
  const conflict = await store.publish('namespace-1', { ...base, title: 'Changed', expectedRevision: 0 })
  expect('revision conflict', conflict.error.code, WORKFLOW_STORE_ERROR_CODES.REVISION_CONFLICT)
  const changed = await store.publish('namespace-1', { ...base, title: 'Changed', expectedRevision: 1 })
  expect('changed publication', [changed.changed, changed.snapshot.revision], [true, 2])
  expect('namespace list', (await store.list('namespace-1')).map((item) => item.projection.workflowId), ['wf-1'])
  expect('other namespace list', await store.list('namespace-2'), [])
  const paths = store.paths('namespace-1', 'wf-1')
  expect('raw workflow id not used as directory', paths.directory.endsWith('/wf-1'), false)
  const facts = (await readFile(paths.events, 'utf8')).trim().split('\n').map(JSON.parse)
  expect('facts-only event revisions', facts.map((fact) => [fact.kind, fact.revision, fact.agentId]), [['projection_created', 1, 'agent-1'], ['projection_published', 2, undefined]])
  expect('publication facts contain bounded transition deltas', facts.map((fact) => fact.transitionDelta.workflow), [{ from: null, to: 'ready' }, null])
  const timing = await store.timing('namespace-1', 'wf-1', new Date(facts.at(-1).observedAt))
  expect('idempotent publication creates no timing transition', [facts.length, timing.transitionCount], [2, 1])
} finally {
  await rm(root, { recursive: true, force: true })
}

console.log(`\nResult: ${passed} passed, ${failed} failed`)
process.exit(failed > 0 ? 1 : 0)
