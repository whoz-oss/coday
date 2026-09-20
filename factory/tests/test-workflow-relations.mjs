import assert from 'node:assert/strict'
import { collectWorkflowDescendants, deriveWorkflowRelations, independentWorkflowRelations, storedWorkflowRelations, validateWorkflowRelationsInput } from '../lib/workflow-relations.mjs'

assert.deepEqual(independentWorkflowRelations('root'), { rootWorkflowId: 'root' })
assert.deepEqual(storedWorkflowRelations({ projection: { workflowId: 'legacy' } }), { rootWorkflowId: 'legacy' })
assert.deepEqual(deriveWorkflowRelations('child', { parentWorkflowId: 'parent', groupId: 'release-1' }, { instance: { relations: { rootWorkflowId: 'root' } }, projection: { workflowId: 'parent' } }), { parentWorkflowId: 'parent', groupId: 'release-1', rootWorkflowId: 'root' })
assert.equal(validateWorkflowRelationsInput({ parentWorkflowId: 'parent', rootWorkflowId: 'forged' }).ok, false)
assert.throws(() => deriveWorkflowRelations('child', { parentWorkflowId: 'missing' }), TypeError)
assert.throws(() => storedWorkflowRelations({ projection: { workflowId: 'corrupt' }, instance: { relations: { parentWorkflowId: 'parent' } } }), TypeError)

const snapshots = [
  { projection: { workflowId: 'child' }, instance: { relations: { parentWorkflowId: 'root', rootWorkflowId: 'root' } } },
  { projection: { workflowId: 'grandchild' }, instance: { relations: { parentWorkflowId: 'child', rootWorkflowId: 'root' } } },
  { projection: { workflowId: 'group-only' }, instance: { relations: { groupId: 'root', rootWorkflowId: 'group-only' } } },
  { projection: { workflowId: 'grandchild' }, instance: { relations: { parentWorkflowId: 'child', rootWorkflowId: 'root' } } },
]
assert.deepEqual(collectWorkflowDescendants(snapshots, 'root').map((item) => item.projection.workflowId), ['child', 'grandchild'])
