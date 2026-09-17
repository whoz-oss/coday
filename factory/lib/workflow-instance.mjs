import { createHash } from 'node:crypto'

export const WORKFLOW_GOVERNANCE_MODE = 'governed'

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical)
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])]))
  return value
}

export function workflowStartCommandHash(command, definition) {
  return createHash('sha256').update(JSON.stringify(canonical({ workflowId: command.workflowId, workflowType: command.workflowType, title: command.title, definitionVersion: definition.version, definitionHash: definition.definitionHash }))).digest('hex')
}

export function createWorkflowInstance(command, definition, controllerExecution, observedAt = new Date().toISOString()) {
  const steps = definition.steps.map((step) => ({
    id: step.id,
    name: step.name,
    status: step.dependsOn.length === 0 ? 'ready' : 'pending',
    dependsOn: [...step.dependsOn],
    responsibility: { ...step.responsibility },
  }))
  const instance = {
    governanceMode: WORKFLOW_GOVERNANCE_MODE,
    workflowId: command.workflowId,
    workflowType: definition.workflowType,
    definitionVersion: definition.version,
    definitionHash: definition.definitionHash,
    revision: 1,
    title: command.title,
    status: 'ready',
    steps: steps.map(({ id, status }) => ({ id, status })),
    controllerExecution: { ...controllerExecution, observedAt },
    createdAt: observedAt,
    updatedAt: observedAt,
  }
  const projection = { schemaVersion: '2', workflowId: instance.workflowId, workflowType: instance.workflowType, title: instance.title, status: instance.status, steps }
  return { instance, projection, creationCommandHash: workflowStartCommandHash(command, definition) }
}
