import { createHash, randomUUID } from 'node:crypto'

/**
 * Pure workflow transition policy domain: request validation, status state
 * machines, semantic/scope hashing and transition evaluation.
 *
 * Authority: this TypeScript source is bundled into
 * `factory/runtime/factory-operational.mjs`; `factory/lib/workflow-transition-policy.mjs`
 * is a stateless compatibility facade re-exporting from that bundle.
 *
 * Domain purity: this module must not import `node:fs`, HTTP clients, AgentOS or
 * a Git CLI. Only `node:crypto` is allowed.
 */

export const WORKFLOW_STATUSES = Object.freeze([
  'pending',
  'ready',
  'running',
  'waiting_human',
  'blocked',
  'completed',
  'failed',
  'cancelled',
] as const)

export type WorkflowStatus = (typeof WORKFLOW_STATUSES)[number]

export const WORKFLOW_TRANSITIONS: Readonly<Record<WorkflowStatus, readonly WorkflowStatus[]>> = Object.freeze({
  pending: Object.freeze(['ready'] as const),
  ready: Object.freeze(['running', 'blocked', 'failed', 'cancelled'] as const),
  running: Object.freeze(['waiting_human', 'blocked', 'completed', 'failed', 'cancelled'] as const),
  waiting_human: Object.freeze(['running', 'blocked', 'failed', 'cancelled'] as const),
  blocked: Object.freeze(['ready', 'running', 'failed', 'cancelled'] as const),
  completed: Object.freeze([] as const),
  failed: Object.freeze([] as const),
  cancelled: Object.freeze([] as const),
})

const SAFE_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/
const FIELDS = new Set([
  'requestId',
  'workflowId',
  'stepId',
  'expectedRevision',
  'requestedStatus',
  'evidenceIds',
  'idempotencyKey',
])

export interface WorkflowExecution {
  namespaceId?: string
  kind: string
  runtimeId: string
  agentId?: string
  actorId?: string
  caseId?: string
  threadId?: string
}

export interface WorkflowTransitionRequest {
  requestId: string
  workflowId: string
  stepId: string
  expectedRevision: number
  requestedStatus: WorkflowStatus
  evidenceIds: string[]
  idempotencyKey?: string
}

export type ValidateTransitionRequestResult =
  | { ok: true; value: WorkflowTransitionRequest }
  | { ok: false; error: { code: string } }

export interface WorkflowInstanceStepState {
  id: string
  status: string
  [key: string]: unknown
}

export interface WorkflowPolicyInstance {
  governanceMode?: string
  workflowType: string
  definitionVersion: string
  definitionHash: string
  revision: number
  status: string
  steps: WorkflowInstanceStepState[]
  controllerExecution?: WorkflowExecution
  updatedAt?: string
  [key: string]: unknown
}

export interface WorkflowProjectionStepState {
  id: string
  status: string
  [key: string]: unknown
}

export interface WorkflowPolicyProjection {
  status: string
  steps: WorkflowProjectionStepState[]
  [key: string]: unknown
}

export interface WorkflowSnapshot {
  revision: number
  governanceMode?: string
  definitionVersion?: string
  definitionHash?: string
  controllerExecution?: WorkflowExecution
  instance: WorkflowPolicyInstance
  projection: WorkflowPolicyProjection
  [key: string]: unknown
}

export interface WorkflowPolicyStepResponsibility {
  kind: string
  name?: string
}

export interface WorkflowPolicyStepDefinition {
  id: string
  dependsOn: string[]
  responsibility: WorkflowPolicyStepResponsibility
}

export interface WorkflowPolicyDefinition {
  workflowType: string
  version: string
  definitionHash: string
  steps: WorkflowPolicyStepDefinition[]
}

export interface WorkflowPolicyEvidenceSource {
  kind?: string
  runtimeId?: string
  agentId?: string
  actorId?: string
  caseId?: string
  threadId?: string
}

export interface WorkflowPolicyEvidence {
  evidenceId: string
  namespaceId?: string
  workflowId?: string
  stepId?: string
  kind: string
  outcome?: string
  source?: WorkflowPolicyEvidenceSource
  facts?: Record<string, unknown>
}

export interface TransitionDenial {
  allowed: false
  code: string
  reason: string
  missingEvidence?: string[]
}

export type TransitionDecision = { allowed: true } | TransitionDenial

export interface WorkflowTransitionEvaluationInput {
  request: WorkflowTransitionRequest
  snapshot: WorkflowSnapshot | null | undefined
  definition: WorkflowPolicyDefinition | null | undefined
  evidence: WorkflowPolicyEvidence[]
  execution: WorkflowExecution
}

export interface HumanCheckpointOpenEvaluationInput {
  request: WorkflowTransitionRequest
  snapshot: WorkflowSnapshot | null | undefined
  definition: WorkflowPolicyDefinition | null | undefined
  execution: WorkflowExecution
}

export interface HumanResolutionTransitionEvaluationInput {
  request: WorkflowTransitionRequest
  snapshot: WorkflowSnapshot | null | undefined
  definition: WorkflowPolicyDefinition | null | undefined
  evidence: WorkflowPolicyEvidence[]
  execution: WorkflowExecution
}

function deny(code: string, reason: string, extra: { missingEvidence?: string[] } = {}): TransitionDenial {
  return { allowed: false, code, reason, ...extra }
}

function invalidTransitionRequest(): ValidateTransitionRequestResult {
  return { ok: false, error: { code: 'INVALID_TRANSITION_REQUEST' } }
}

function isWorkflowStatus(value: unknown): value is WorkflowStatus {
  return typeof value === 'string' && (WORKFLOW_STATUSES as readonly string[]).includes(value)
}

export function validateWorkflowTransitionRequest(
  input: unknown,
  expectedWorkflowId: string
): ValidateTransitionRequestResult {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return invalidTransitionRequest()
  const record = input as Record<string, unknown>
  if (Object.keys(record).some((key) => !FIELDS.has(key))) return invalidTransitionRequest()
  if (record.requestId !== undefined) return { ok: false, error: { code: 'UNTRUSTED_REQUEST_ID' } }
  const workflowId = record.workflowId
  const stepId = record.stepId
  const expectedRevision = record.expectedRevision
  const requestedStatus = record.requestedStatus
  const evidenceIds = record.evidenceIds
  const idempotencyKey = record.idempotencyKey
  if (
    workflowId !== expectedWorkflowId ||
    !SAFE_ID.test(String(workflowId ?? '')) ||
    !SAFE_ID.test(String(stepId ?? ''))
  )
    return invalidTransitionRequest()
  if (!Number.isSafeInteger(expectedRevision) || (expectedRevision as number) < 1 || !isWorkflowStatus(requestedStatus))
    return invalidTransitionRequest()
  if (
    !Array.isArray(evidenceIds) ||
    evidenceIds.length > 100 ||
    new Set(evidenceIds).size !== evidenceIds.length ||
    evidenceIds.some((id) => typeof id !== 'string' || !SAFE_ID.test(id))
  )
    return invalidTransitionRequest()
  if (
    idempotencyKey !== undefined &&
    (typeof idempotencyKey !== 'string' ||
      !idempotencyKey ||
      idempotencyKey.length > 128 ||
      /[\r\n]/.test(idempotencyKey))
  )
    return invalidTransitionRequest()
  return {
    ok: true,
    value: {
      requestId: randomUUID(),
      workflowId: workflowId as string,
      stepId: stepId as string,
      expectedRevision: expectedRevision as number,
      requestedStatus,
      evidenceIds: [...(evidenceIds as string[])],
      ...(idempotencyKey ? { idempotencyKey: idempotencyKey as string } : {}),
    },
  }
}

export function transitionSemanticHash(request: WorkflowTransitionRequest): string {
  return createHash('sha256')
    .update(
      JSON.stringify({
        workflowId: request.workflowId,
        stepId: request.stepId,
        expectedRevision: request.expectedRevision,
        requestedStatus: request.requestedStatus,
        evidenceIds: [...request.evidenceIds].sort(),
      })
    )
    .digest('hex')
}

export function transitionScopeHash(
  namespaceId: string | undefined,
  request: Pick<WorkflowTransitionRequest, 'workflowId' | 'stepId' | 'idempotencyKey'>,
  execution: WorkflowExecution
): string {
  return createHash('sha256')
    .update(
      JSON.stringify({
        namespaceId,
        workflowId: request.workflowId,
        stepId: request.stepId,
        source: {
          kind: execution.kind,
          runtimeId: execution.runtimeId,
          agentId: execution.agentId,
          actorId: execution.actorId,
          caseId: execution.caseId,
          threadId: execution.threadId,
        },
        idempotencyKey: request.idempotencyKey,
      })
    )
    .digest('hex')
}

export function evaluateHumanCheckpointOpen({
  request,
  snapshot,
  definition,
  execution,
}: HumanCheckpointOpenEvaluationInput): TransitionDecision {
  if (!snapshot) return deny('WORKFLOW_NOT_FOUND', 'workflow_not_found')
  if (snapshot.governanceMode !== 'governed' || snapshot.instance?.governanceMode !== 'governed')
    return deny('WORKFLOW_NOT_GOVERNED', 'workflow_not_governed')
  if (!definition) return deny('WORKFLOW_DEFINITION_NOT_FOUND', 'definition_not_found')
  const instance = snapshot.instance
  if (
    instance.workflowType !== definition.workflowType ||
    instance.definitionVersion !== definition.version ||
    instance.definitionHash !== definition.definitionHash ||
    snapshot.definitionVersion !== definition.version ||
    snapshot.definitionHash !== definition.definitionHash
  )
    return deny('WORKFLOW_DEFINITION_MISMATCH', 'definition_identity_mismatch')
  const declared = definition.steps.find((step) => step.id === request.stepId)
  const current = instance.steps.find((step) => step.id === request.stepId)
  if (!declared || !current) return deny('STEP_NOT_FOUND', 'step_not_found')
  if (request.expectedRevision !== snapshot.revision || instance.revision !== snapshot.revision)
    return deny('REVISION_CONFLICT', 'revision_mismatch')
  if (declared.responsibility?.kind !== 'human') return deny('ACTOR_NOT_AUTHORIZED', 'step_is_not_human_owned')
  if (current.status !== 'ready') return deny('ILLEGAL_TRANSITION', 'human_step_is_not_ready')
  const missing = declared.dependsOn.filter(
    (id) => instance.steps.find((step) => step.id === id)?.status !== 'completed'
  )
  if (missing.length)
    return deny('DEPENDENCIES_NOT_SATISFIED', 'dependencies_not_completed', { missingEvidence: missing })
  if (request.requestedStatus !== 'waiting_human' || request.evidenceIds.length !== 0)
    return deny('ACTOR_NOT_AUTHORIZED', 'human_gate_opener_can_only_open_checkpoint')
  const factoryHumanGate =
    execution.kind === 'factory-human-gate' &&
    execution.runtimeId === 'factory-dashboard' &&
    execution.agentId === 'factory-runner' &&
    execution.actorId === undefined
  const controller = instance.controllerExecution ?? snapshot.controllerExecution
  const originalController =
    controller &&
    controller.kind === execution.kind &&
    controller.runtimeId === execution.runtimeId &&
    controller.agentId === execution.agentId &&
    controller.caseId === execution.caseId &&
    controller.threadId === execution.threadId
  if (!factoryHumanGate && !originalController) return deny('ACTOR_NOT_AUTHORIZED', 'execution_cannot_open_human_gate')
  return { allowed: true }
}

export function evaluateHumanResolutionTransition({
  request,
  snapshot,
  definition,
  evidence,
  execution,
}: HumanResolutionTransitionEvaluationInput): TransitionDecision {
  if (
    execution.kind === 'factory-human-gate' ||
    execution.kind !== 'factory-human' ||
    execution.runtimeId !== 'factory-dashboard' ||
    typeof execution.actorId !== 'string' ||
    execution.actorId.length === 0
  )
    return deny('ACTOR_NOT_AUTHORIZED', 'human_resolution_requires_authenticated_human')
  const current = snapshot?.instance?.steps?.find((step) => step.id === request.stepId)
  if (current?.status !== 'waiting_human') return deny('INTERACTION_STALE', 'human_step_is_not_waiting')
  if (!['completed', 'failed'].includes(request.requestedStatus))
    return deny('ILLEGAL_TRANSITION', 'human_resolution_target_not_allowed')
  if (request.requestedStatus === 'completed') {
    const present = snapshot as WorkflowSnapshot
    const bridged: WorkflowSnapshot = {
      ...present,
      instance: {
        ...present.instance,
        steps: present.instance.steps.map((step) =>
          step.id === request.stepId ? { ...step, status: 'running' } : step
        ),
      },
    }
    const decision = evidence.find(
      (item) =>
        request.evidenceIds.includes(item.evidenceId) &&
        item.kind === 'human-decision' &&
        item.outcome === 'pass' &&
        item.source?.kind === 'factory-human' &&
        item.source?.actorId === execution.actorId
    )
    if (!decision)
      return deny('PASS_EVIDENCE_REQUIRED', 'matching_human_decision_required', {
        missingEvidence: ['human-decision:pass'],
      })
    const evaluated = evaluateWorkflowTransition({
      request: { ...request, requestedStatus: 'completed' },
      snapshot: bridged,
      definition,
      evidence,
      execution: { ...execution, kind: 'factory-human-resolution' },
    })
    return !evaluated.allowed &&
      evaluated.code === 'ACTOR_NOT_AUTHORIZED' &&
      evaluated.reason === 'runtime_cannot_transition_step_responsibility'
      ? { allowed: true }
      : evaluated
  }
  const completion = evaluateWorkflowTransition({
    request: { ...request, requestedStatus: 'failed' },
    snapshot,
    definition,
    evidence,
    execution,
  })
  if (!completion.allowed) return completion
  const selected = request.evidenceIds
    .map((id) => evidence.find((item) => item.evidenceId === id))
    .filter((item): item is WorkflowPolicyEvidence => Boolean(item))
  return selected.some(
    (item) =>
      item.kind === 'human-decision' &&
      item.outcome === 'fail' &&
      item.source?.kind === 'factory-human' &&
      item.source?.actorId === execution.actorId
  )
    ? { allowed: true }
    : deny('FAIL_EVIDENCE_REQUIRED', 'matching_human_decision_fail_required', {
        missingEvidence: ['human-decision:fail'],
      })
}

export function evaluateWorkflowTransition({
  request,
  snapshot,
  definition,
  evidence,
  execution,
}: WorkflowTransitionEvaluationInput): TransitionDecision {
  if (!snapshot) return deny('WORKFLOW_NOT_FOUND', 'workflow_not_found')
  if (snapshot.governanceMode !== 'governed' || snapshot.instance?.governanceMode !== 'governed')
    return deny('WORKFLOW_NOT_GOVERNED', 'workflow_not_governed')
  if (!definition) return deny('WORKFLOW_DEFINITION_NOT_FOUND', 'definition_not_found')
  const instance = snapshot.instance
  if (
    instance.workflowType !== definition.workflowType ||
    instance.definitionVersion !== definition.version ||
    instance.definitionHash !== definition.definitionHash ||
    snapshot.definitionVersion !== definition.version ||
    snapshot.definitionHash !== definition.definitionHash
  )
    return deny('WORKFLOW_DEFINITION_MISMATCH', 'definition_identity_mismatch')
  const declared = definition.steps.find((step) => step.id === request.stepId)
  const current = instance.steps.find((step) => step.id === request.stepId)
  if (!declared || !current) return deny('STEP_NOT_FOUND', 'step_not_found')
  if (request.expectedRevision !== snapshot.revision || instance.revision !== snapshot.revision)
    return deny('REVISION_CONFLICT', 'revision_mismatch')
  if (!WORKFLOW_TRANSITIONS[current.status as WorkflowStatus]?.includes(request.requestedStatus))
    return deny('ILLEGAL_TRANSITION', 'transition_not_allowed')
  if (['ready', 'running', 'completed'].includes(request.requestedStatus)) {
    const missing = declared.dependsOn.filter(
      (id) => instance.steps.find((step) => step.id === id)?.status !== 'completed'
    )
    if (missing.length)
      return deny('DEPENDENCIES_NOT_SATISFIED', 'dependencies_not_completed', { missingEvidence: missing })
  }
  const factoryOracle =
    declared.responsibility.kind === 'code' &&
    execution.kind === 'factory-oracle' &&
    execution.runtimeId === 'factory-dashboard'
  const factoryHuman =
    declared.responsibility.kind === 'human' &&
    execution.kind === 'factory-human' &&
    execution.runtimeId === 'factory-dashboard' &&
    typeof execution.actorId === 'string' &&
    execution.actorId.length > 0
  const factoryRetry =
    declared.responsibility.kind === 'agent' &&
    current.status === 'blocked' &&
    request.requestedStatus === 'ready' &&
    execution.kind === 'factory-control-plane' &&
    execution.runtimeId === 'factory-dashboard' &&
    execution.agentId === 'factory-runner' &&
    typeof execution.actorId === 'string' &&
    execution.actorId.length > 0
  if (declared.responsibility.kind !== 'agent' && !factoryOracle && !factoryHuman)
    return deny('ACTOR_NOT_AUTHORIZED', 'runtime_cannot_transition_step_responsibility')
  if (
    declared.responsibility.kind === 'agent' &&
    !factoryRetry &&
    declared.responsibility.name &&
    declared.responsibility.name !== execution.agentId
  )
    return deny('ACTOR_NOT_AUTHORIZED', 'agent_responsibility_mismatch')
  if (factoryHuman && current.status !== 'waiting_human') return deny('INTERACTION_STALE', 'human_step_is_not_waiting')
  const selected: WorkflowPolicyEvidence[] = []
  for (const id of request.evidenceIds) {
    const item = evidence.find((candidate) => candidate.evidenceId === id)
    if (!item) return deny('EVIDENCE_NOT_FOUND', 'evidence_not_found', { missingEvidence: [id] })
    if (
      item.namespaceId !== execution.namespaceId ||
      item.workflowId !== request.workflowId ||
      item.stepId !== request.stepId
    )
      return deny('EVIDENCE_SCOPE_MISMATCH', 'evidence_scope_mismatch')
    selected.push(item)
  }
  if (request.requestedStatus === 'blocked' && declared.responsibility.kind === 'agent') {
    const negative = selected.find(
      (item) =>
        item.kind === 'agent-result' &&
        ['fail', 'indeterminate'].includes(item.outcome ?? '') &&
        item.source?.kind === execution.kind &&
        item.source?.runtimeId === execution.runtimeId &&
        item.source?.agentId === execution.agentId &&
        item.source?.caseId === execution.caseId &&
        item.source?.threadId === execution.threadId
    )
    if (!negative)
      return deny('NEGATIVE_EVIDENCE_REQUIRED', 'matching_agent_result_negative_required', {
        missingEvidence: ['agent-result:fail-or-indeterminate'],
      })
  }
  if (request.requestedStatus === 'ready' && current.status === 'blocked') {
    const controller = instance.controllerExecution ?? snapshot.controllerExecution
    if (
      execution.kind !== 'factory-control-plane' ||
      execution.runtimeId !== 'factory-dashboard' ||
      execution.agentId !== 'factory-runner' ||
      typeof execution.actorId !== 'string' ||
      execution.actorId.length === 0 ||
      !controller ||
      controller.caseId !== execution.caseId
    )
      return deny('ACTOR_NOT_AUTHORIZED', 'manual_retry_requires_factory_controller_and_human_actor')
    const retry = selected.find(
      (item) =>
        item.kind === 'human-decision' &&
        item.outcome === 'pass' &&
        item.source?.kind === 'factory-human' &&
        typeof item.source?.actorId === 'string' &&
        item.source.actorId.length > 0
    )
    if (!retry)
      return deny('RETRY_EVIDENCE_REQUIRED', 'trusted_manual_retry_evidence_required', {
        missingEvidence: ['human-decision:pass'],
      })
  }
  if (request.requestedStatus === 'completed') {
    if (factoryHuman) {
      const decision = selected.find(
        (item) =>
          item.kind === 'human-decision' &&
          item.outcome === 'pass' &&
          item.source?.kind === 'factory-human' &&
          item.source?.actorId === execution.actorId
      )
      if (!decision)
        return deny('PASS_EVIDENCE_REQUIRED', 'matching_human_decision_required', {
          missingEvidence: ['human-decision:pass'],
        })
    } else if (factoryOracle) {
      const pass = selected.find(
        (item) =>
          item.kind === 'oracle-result' &&
          item.outcome === 'pass' &&
          item.source?.kind === 'factory-oracle' &&
          item.facts?.oracleId === declared.responsibility.name
      )
      if (!pass)
        return deny('PASS_EVIDENCE_REQUIRED', 'matching_oracle_result_pass_required', {
          missingEvidence: ['oracle-result:pass'],
        })
    } else {
      if (
        selected.some((item) => item.kind === 'agent-result' && ['fail', 'indeterminate'].includes(item.outcome ?? ''))
      )
        return deny('EVIDENCE_NEGATIVE', 'agent_result_not_pass')
      const pass = selected.find(
        (item) =>
          item.kind === 'agent-result' &&
          item.outcome === 'pass' &&
          item.source?.kind === execution.kind &&
          item.source?.runtimeId === execution.runtimeId &&
          item.source?.agentId === execution.agentId &&
          item.source?.caseId === execution.caseId &&
          item.source?.threadId === execution.threadId
      )
      if (!pass)
        return deny('PASS_EVIDENCE_REQUIRED', 'matching_agent_result_pass_required', {
          missingEvidence: ['agent-result:pass'],
        })
    }
  }
  return { allowed: true }
}

export function applyWorkflowTransition(
  snapshot: WorkflowSnapshot,
  definition: WorkflowPolicyDefinition,
  request: WorkflowTransitionRequest,
  observedAt: string = new Date().toISOString()
): WorkflowSnapshot {
  const previous = new Map<string, string>(snapshot.instance.steps.map((step) => [step.id, step.status]))
  previous.set(request.stepId, request.requestedStatus)
  if (request.requestedStatus === 'completed')
    for (const step of definition.steps)
      if (previous.get(step.id) === 'pending' && step.dependsOn.every((id) => previous.get(id) === 'completed'))
        previous.set(step.id, 'ready')
  const statuses = [...previous.values()]
  const status = statuses.every((status) => status === 'completed')
    ? 'completed'
    : statuses.some((status) => status === 'failed')
      ? 'failed'
      : statuses.some((status) => status === 'waiting_human')
        ? 'waiting_human'
        : statuses.some((status) => status === 'blocked')
          ? 'blocked'
          : statuses.some((status) => status === 'running')
            ? 'running'
            : statuses.some((status) => status === 'ready')
              ? 'ready'
              : 'pending'
  const revision = snapshot.revision + 1
  const instance: WorkflowPolicyInstance = {
    ...snapshot.instance,
    revision,
    status,
    steps: snapshot.instance.steps.map((step) => ({ ...step, status: previous.get(step.id) as string })),
    updatedAt: observedAt,
  }
  const projection: WorkflowPolicyProjection = {
    ...snapshot.projection,
    status,
    steps: snapshot.projection.steps.map((step) => ({ ...step, status: previous.get(step.id) as string })),
  }
  return { ...snapshot, instance, projection, revision }
}

/** Dedicated application path after evaluateHumanCheckpointOpen authorizes ready → waiting_human. */
export function applyHumanCheckpointOpen(
  snapshot: WorkflowSnapshot,
  definition: WorkflowPolicyDefinition,
  request: WorkflowTransitionRequest,
  observedAt: string = new Date().toISOString()
): WorkflowSnapshot {
  const statuses = new Map<string, string>(snapshot.instance.steps.map((step) => [step.id, step.status]))
  statuses.set(request.stepId, 'waiting_human')
  const revision = snapshot.revision + 1
  const instance: WorkflowPolicyInstance = {
    ...snapshot.instance,
    revision,
    status: 'waiting_human',
    steps: snapshot.instance.steps.map((step) => ({ ...step, status: statuses.get(step.id) as string })),
    updatedAt: observedAt,
  }
  const projection: WorkflowPolicyProjection = {
    ...snapshot.projection,
    status: 'waiting_human',
    steps: snapshot.projection.steps.map((step) => ({ ...step, status: statuses.get(step.id) as string })),
  }
  return { ...snapshot, instance, projection, revision }
}
