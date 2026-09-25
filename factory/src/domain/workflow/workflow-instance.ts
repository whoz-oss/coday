import { createHash } from 'node:crypto'
import type { WorkflowStepDefinition, WorkflowStepResponsibility } from './workflow-definition.js'

/**
 * Pure workflow instance domain: start-command hashing and instance/projection
 * materialization.
 *
 * Authority: this TypeScript source is bundled into
 * `factory/runtime/factory-operational.mjs`; `factory/lib/workflow-instance.mjs`
 * is a stateless compatibility facade re-exporting from that bundle.
 *
 * Domain purity: this module must not import `node:fs`, HTTP clients, AgentOS or
 * a Git CLI. Only `node:crypto` is allowed.
 */

export const WORKFLOW_GOVERNANCE_MODE = 'governed' as const

export interface WorkflowStartCommand {
  workflowId: string
  workflowType: string
  title: string
  relations?: Record<string, unknown>
}

export interface WorkflowDefinitionInput {
  workflowType: string
  version: string
  definitionHash: string
  steps: WorkflowStepDefinition[]
}

export interface ControllerExecutionInput {
  runtimeId: string
  kind: string
  agentId: string
  caseId: string
  actorId: string
}

export interface WorkflowInstanceStep {
  id: string
  status: 'ready' | 'pending'
}

export interface WorkflowProjectionStep {
  id: string
  name: string
  status: 'ready' | 'pending'
  dependsOn: string[]
  responsibility: WorkflowStepResponsibility
}

export interface WorkflowInstance {
  governanceMode: typeof WORKFLOW_GOVERNANCE_MODE
  workflowId: string
  workflowType: string
  definitionVersion: string
  definitionHash: string
  revision: number
  title: string
  status: 'ready'
  steps: WorkflowInstanceStep[]
  relations: Record<string, unknown>
  controllerExecution: ControllerExecutionInput & { observedAt: string }
  environmentRef: null
  deliveryRef: null
  createdAt: string
  updatedAt: string
}

export interface WorkflowProjection {
  schemaVersion: '2'
  workflowId: string
  workflowType: string
  title: string
  status: 'ready'
  steps: WorkflowProjectionStep[]
}

export interface CreateWorkflowInstanceResult {
  instance: WorkflowInstance
  projection: WorkflowProjection
  creationCommandHash: string
}

/**
 * Pure counterpart of `lib/workflow-relations.mjs` independent relations. Kept
 * local so the domain never imports the I/O-bearing legacy relations module.
 */
function independentWorkflowRelations(workflowId: string): { rootWorkflowId: string } {
  return { rootWorkflowId: workflowId }
}

function canonicalize(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonicalize)
  if (value !== null && typeof value === 'object') {
    const record = value as Record<string, unknown>
    return Object.fromEntries(
      Object.keys(record)
        .sort()
        .map((key) => [key, canonicalize(record[key])])
    )
  }
  return value
}

export function workflowStartCommandHash(command: WorkflowStartCommand, definition: WorkflowDefinitionInput): string {
  const relations = command.relations ?? independentWorkflowRelations(command.workflowId)
  return createHash('sha256')
    .update(
      JSON.stringify(
        canonicalize({
          workflowId: command.workflowId,
          workflowType: command.workflowType,
          title: command.title,
          relations: { ...relations },
          definitionVersion: definition.version,
          definitionHash: definition.definitionHash,
        })
      )
    )
    .digest('hex')
}

export function createWorkflowInstance(
  command: WorkflowStartCommand,
  definition: WorkflowDefinitionInput,
  controllerExecution: ControllerExecutionInput,
  observedAt: string = new Date().toISOString()
): CreateWorkflowInstanceResult {
  const steps: WorkflowProjectionStep[] = definition.steps.map((step) => ({
    id: step.id,
    name: step.name,
    status: step.dependsOn.length === 0 ? 'ready' : 'pending',
    dependsOn: [...step.dependsOn],
    responsibility: { ...step.responsibility },
  }))
  const instance: WorkflowInstance = {
    governanceMode: WORKFLOW_GOVERNANCE_MODE,
    workflowId: command.workflowId,
    workflowType: definition.workflowType,
    definitionVersion: definition.version,
    definitionHash: definition.definitionHash,
    revision: 1,
    title: command.title,
    status: 'ready',
    steps: steps.map(({ id, status }) => ({ id, status })),
    relations: { ...(command.relations ?? independentWorkflowRelations(command.workflowId)) },
    controllerExecution: { ...controllerExecution, observedAt },
    environmentRef: null,
    deliveryRef: null,
    createdAt: observedAt,
    updatedAt: observedAt,
  }
  const projection: WorkflowProjection = {
    schemaVersion: '2',
    workflowId: instance.workflowId,
    workflowType: instance.workflowType,
    title: instance.title,
    status: instance.status,
    steps,
  }
  return { instance, projection, creationCommandHash: workflowStartCommandHash(command, definition) }
}
