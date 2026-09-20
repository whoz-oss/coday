import { validateWorkflowProjectionId } from './workflow-projection.mjs'

export const WORKFLOW_RELATION_ERROR_CODES = Object.freeze({
  INVALID_RELATIONS: 'INVALID_RELATIONS',
  PARENT_WORKFLOW_NOT_FOUND: 'PARENT_WORKFLOW_NOT_FOUND',
  WORKFLOW_RELATION_CYCLE: 'WORKFLOW_RELATION_CYCLE',
})

function optionalId(value, path) {
  if (value === undefined) return { ok: true, value: undefined }
  const validated = validateWorkflowProjectionId(value, path)
  return validated.ok ? { ok: true, value } : { ok: false, error: { code: WORKFLOW_RELATION_ERROR_CODES.INVALID_RELATIONS, details: validated.error } }
}

export function validateWorkflowRelationsInput(relations) {
  if (relations === undefined) return { ok: true, relations: {} }
  if (!relations || typeof relations !== 'object' || Array.isArray(relations) || Object.keys(relations).some((key) => !['parentWorkflowId', 'groupId'].includes(key))) return { ok: false, error: { code: WORKFLOW_RELATION_ERROR_CODES.INVALID_RELATIONS } }
  const parent = optionalId(relations.parentWorkflowId, 'relations.parentWorkflowId')
  if (!parent.ok) return parent
  const group = optionalId(relations.groupId, 'relations.groupId')
  if (!group.ok) return group
  return { ok: true, relations: { ...(parent.value ? { parentWorkflowId: parent.value } : {}), ...(group.value ? { groupId: group.value } : {}) } }
}

export function independentWorkflowRelations(workflowId) {
  return { rootWorkflowId: workflowId }
}

export function storedWorkflowRelations(snapshot) {
  const workflowId = snapshot?.projection?.workflowId
  const relations = snapshot?.instance?.relations ?? snapshot?.relations
  if (!relations) return independentWorkflowRelations(workflowId)
  if (!relations.rootWorkflowId) throw new TypeError('Stored workflow relations require rootWorkflowId')
  return { ...relations }
}

export function deriveWorkflowRelations(workflowId, requested, parentSnapshot) {
  if (requested.parentWorkflowId && !parentSnapshot) throw new TypeError('Parent snapshot is required')
  const parentRelations = requested.parentWorkflowId ? storedWorkflowRelations(parentSnapshot) : null
  return {
    ...(requested.parentWorkflowId ? { parentWorkflowId: requested.parentWorkflowId } : {}),
    ...(requested.groupId ? { groupId: requested.groupId } : {}),
    rootWorkflowId: parentRelations?.rootWorkflowId ?? workflowId,
  }
}

export function collectWorkflowDescendants(workflows, workflowId) {
  const byParent = new Map()
  for (const snapshot of workflows) {
    const childId = snapshot?.projection?.workflowId
    const parentId = snapshot?.instance?.relations?.parentWorkflowId ?? snapshot?.relations?.parentWorkflowId
    if (!childId || !parentId) continue
    const children = byParent.get(parentId) ?? []
    children.push(snapshot)
    byParent.set(parentId, children)
  }
  const seen = new Set([workflowId])
  const descendants = []
  const pending = [...(byParent.get(workflowId) ?? [])]
  while (pending.length) {
    const snapshot = pending.shift()
    const childId = snapshot.projection.workflowId
    if (seen.has(childId)) continue
    seen.add(childId)
    descendants.push(snapshot)
    pending.push(...(byParent.get(childId) ?? []))
  }
  return descendants
}
