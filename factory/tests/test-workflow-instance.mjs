import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createWorkflowInstance } from '../lib/workflow-instance.mjs'
import { WorkflowDefinitionRegistry } from '../lib/workflow-definition-registry.mjs'
import { WorkflowProjectionStore } from '../lib/workflow-projection-store.mjs'

const namespace = '0d4bd471-df37-43d8-a8f7-c989f95e71d7'
const otherNamespace = '1d4bd471-df37-43d8-a8f7-c989f95e71d7'

const registry = new WorkflowDefinitionRegistry(new URL('../workflows', import.meta.url).pathname)
await registry.initialize()
const definition = await registry.resolveUnique('bmad-story')

const command = { workflowId: 'WZ-1', workflowType: 'bmad-story', title: 'Story' }
const execution = {
  runtimeId: 'agentos-primary',
  kind: 'agentos',
  agentId: 'ProductEngineer',
  caseId: 'case-1',
  actorId: 'user-1',
}

const derived = createWorkflowInstance(command, definition, execution, '2026-01-01T00:00:00.000Z')
assert.equal(derived.projection.steps.find((step) => step.id === 'implementation').responsibility.name, 'BmadBuilder')
assert.equal(derived.projection.steps.find((step) => step.id === 'ticket-analysis').status, 'ready')
assert.equal(derived.projection.steps.find((step) => step.id === 'implementation').status, 'pending')
assert.equal(derived.instance.definitionVersion, '1.0.0')
assert.equal(derived.instance.definitionHash, definition.definitionHash)
assert.deepEqual(derived.instance.relations, { rootWorkflowId: 'WZ-1' })

const root = await mkdtemp(join(tmpdir(), 'factory-instance-'))
try {
  const store = new WorkflowProjectionStore(root)
  await store.initialize()

  const created = await store.start(namespace, command, definition, execution)
  assert.equal(created.created, true)

  const retried = await store.start(namespace, command, definition, execution)
  assert.equal(retried.idempotent, true)
  const child = await store.start(namespace, { ...command, workflowId: 'WZ-2', relations: { parentWorkflowId: 'WZ-1', groupId: 'release-1' } }, definition, execution)
  assert.deepEqual(child.snapshot.instance.relations, { parentWorkflowId: 'WZ-1', groupId: 'release-1', rootWorkflowId: 'WZ-1' })
  assert.deepEqual((await store.descendants(namespace, 'WZ-1')).map((snapshot) => snapshot.projection.workflowId), ['WZ-2'])
  assert.equal((await store.start(otherNamespace, { ...command, workflowId: 'WZ-3', relations: { parentWorkflowId: 'WZ-1' } }, definition, execution)).error.code, 'PARENT_WORKFLOW_NOT_FOUND')
  assert.equal((await store.start(namespace, { ...command, workflowId: 'self', relations: { parentWorkflowId: 'self' } }, definition, execution)).error.code, 'WORKFLOW_RELATION_CYCLE')
  assert.equal((await store.start(namespace, { ...command, relations: { groupId: 'changed-group' } }, definition, execution)).error.code, 'WORKFLOW_IDENTITY_CONFLICT')

  assert.equal((await store.lookup(otherNamespace, 'WZ-1')).state, 'absent')

  const declarative = {
    schemaVersion: '2',
    workflowId: 'legacy',
    workflowType: 'bmad-story',
    title: 'Legacy',
    status: 'ready',
    steps: derived.projection.steps,
  }
  await store.publish(namespace, declarative, execution)
  const declarativeCollision = await store.start(
    namespace,
    { ...command, workflowId: 'legacy' },
    definition,
    execution,
  )
  assert.equal(declarativeCollision.error.code, 'WORKFLOW_ALREADY_EXISTS')

  await store.remove(namespace, 'WZ-1')
  const removedCollision = await store.start(namespace, command, definition, execution)
  assert.equal(removedCollision.error.code, 'WORKFLOW_REMOVED')
} finally {
  await rm(root, { recursive: true, force: true })
}
