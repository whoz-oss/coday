import { randomUUID, createHash } from 'node:crypto'

export const WORKFLOW_STATUSES = Object.freeze([
  'pending',
  'ready',
  'running',
  'waiting_human',
  'blocked',
  'completed',
  'failed',
  'cancelled',
])
export const WORKFLOW_TRANSITIONS = Object.freeze({
  pending: Object.freeze(['ready']),
  ready: Object.freeze(['running', 'blocked', 'failed', 'cancelled']),
  running: Object.freeze(['waiting_human', 'blocked', 'completed', 'failed', 'cancelled']),
  waiting_human: Object.freeze(['running', 'blocked', 'failed', 'cancelled']),
  blocked: Object.freeze(['ready', 'running', 'failed', 'cancelled']),
  completed: Object.freeze([]),
  failed: Object.freeze([]),
  cancelled: Object.freeze([]),
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
const deny = (code, reason, extra = {}) => ({ allowed: false, code, reason, ...extra })
export function validateWorkflowTransitionRequest(input, expectedWorkflowId) {
  if (!input || typeof input !== 'object' || Array.isArray(input) || Object.keys(input).some((k) => !FIELDS.has(k)))
    return { ok: false, error: { code: 'INVALID_TRANSITION_REQUEST' } }
  if (input.requestId !== undefined) return { ok: false, error: { code: 'UNTRUSTED_REQUEST_ID' } }
  if (
    input.workflowId !== expectedWorkflowId ||
    !SAFE_ID.test(input.workflowId ?? '') ||
    !SAFE_ID.test(input.stepId ?? '')
  )
    return { ok: false, error: { code: 'INVALID_TRANSITION_REQUEST' } }
  if (
    !Number.isSafeInteger(input.expectedRevision) ||
    input.expectedRevision < 1 ||
    !WORKFLOW_STATUSES.includes(input.requestedStatus)
  )
    return { ok: false, error: { code: 'INVALID_TRANSITION_REQUEST' } }
  if (
    !Array.isArray(input.evidenceIds) ||
    input.evidenceIds.length > 100 ||
    new Set(input.evidenceIds).size !== input.evidenceIds.length ||
    input.evidenceIds.some((id) => typeof id !== 'string' || !SAFE_ID.test(id))
  )
    return { ok: false, error: { code: 'INVALID_TRANSITION_REQUEST' } }
  if (
    input.idempotencyKey !== undefined &&
    (typeof input.idempotencyKey !== 'string' ||
      !input.idempotencyKey ||
      input.idempotencyKey.length > 128 ||
      /[\r\n]/.test(input.idempotencyKey))
  )
    return { ok: false, error: { code: 'INVALID_TRANSITION_REQUEST' } }
  return {
    ok: true,
    value: {
      requestId: randomUUID(),
      workflowId: input.workflowId,
      stepId: input.stepId,
      expectedRevision: input.expectedRevision,
      requestedStatus: input.requestedStatus,
      evidenceIds: [...input.evidenceIds],
      ...(input.idempotencyKey ? { idempotencyKey: input.idempotencyKey } : {}),
    },
  }
}
export const transitionSemanticHash = (request) =>
  createHash('sha256')
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
export const transitionScopeHash = (namespaceId, request, execution) =>
  createHash('sha256')
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

export function evaluateHumanCheckpointOpen({ request, snapshot, definition, execution }) {
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
  const declared = definition.steps.find((step) => step.id === request.stepId),
    current = instance.steps.find((step) => step.id === request.stepId)
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
  if (!factoryHumanGate && !originalController)
    return deny('ACTOR_NOT_AUTHORIZED', 'execution_cannot_open_human_gate')
  return { allowed: true }
}

export function evaluateHumanResolutionTransition({ request, snapshot, definition, evidence, execution }) {
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
    const bridged = {
      ...snapshot,
      instance: {
        ...snapshot.instance,
        steps: snapshot.instance.steps.map((step) =>
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
    return evaluated.code === 'ACTOR_NOT_AUTHORIZED' &&
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
  const selected = request.evidenceIds.map((id) => evidence.find((item) => item.evidenceId === id)).filter(Boolean)
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

export function evaluateWorkflowTransition({ request, snapshot, definition, evidence, execution }) {
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
  const declared = definition.steps.find((s) => s.id === request.stepId),
    current = instance.steps.find((s) => s.id === request.stepId)
  if (!declared || !current) return deny('STEP_NOT_FOUND', 'step_not_found')
  if (request.expectedRevision !== snapshot.revision || instance.revision !== snapshot.revision)
    return deny('REVISION_CONFLICT', 'revision_mismatch')
  if (!WORKFLOW_TRANSITIONS[current.status]?.includes(request.requestedStatus))
    return deny('ILLEGAL_TRANSITION', 'transition_not_allowed')
  if (['ready', 'running', 'completed'].includes(request.requestedStatus)) {
    const missing = declared.dependsOn.filter((id) => instance.steps.find((s) => s.id === id)?.status !== 'completed')
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
  const selected = []
  for (const id of request.evidenceIds) {
    const item = evidence.find((e) => e.evidenceId === id)
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
    const negative=selected.find((e)=>e.kind==='agent-result'&&['fail','indeterminate'].includes(e.outcome)&&e.source?.kind===execution.kind&&e.source?.runtimeId===execution.runtimeId&&e.source?.agentId===execution.agentId&&e.source?.caseId===execution.caseId&&e.source?.threadId===execution.threadId)
    if(!negative)return deny('NEGATIVE_EVIDENCE_REQUIRED','matching_agent_result_negative_required',{missingEvidence:['agent-result:fail-or-indeterminate']})
  }
  if (request.requestedStatus === 'ready' && current.status === 'blocked') {
    const controller=instance.controllerExecution??snapshot.controllerExecution
    if(
      execution.kind!=='factory-control-plane'||
      execution.runtimeId!=='factory-dashboard'||
      execution.agentId!=='factory-runner'||
      typeof execution.actorId!=='string'||
      execution.actorId.length===0||
      !controller||
      controller.caseId!==execution.caseId
    )
      return deny('ACTOR_NOT_AUTHORIZED','manual_retry_requires_factory_controller_and_human_actor')
    const retry=selected.find((e)=>e.kind==='human-decision'&&e.outcome==='pass'&&e.source?.kind==='factory-human'&&typeof e.source?.actorId==='string'&&e.source.actorId.length>0)
    if(!retry)return deny('RETRY_EVIDENCE_REQUIRED','trusted_manual_retry_evidence_required',{missingEvidence:['human-decision:pass']})
  }
  if (request.requestedStatus === 'completed') {
    if (factoryHuman) {
      const decision = selected.find(
        (e) =>
          e.kind === 'human-decision' &&
          e.outcome === 'pass' &&
          e.source?.kind === 'factory-human' &&
          e.source?.actorId === execution.actorId
      )
      if (!decision)
        return deny('PASS_EVIDENCE_REQUIRED', 'matching_human_decision_required', {
          missingEvidence: ['human-decision:pass'],
        })
    } else if (factoryOracle) {
      const pass = selected.find(
        (e) =>
          e.kind === 'oracle-result' &&
          e.outcome === 'pass' &&
          e.source?.kind === 'factory-oracle' &&
          e.facts?.oracleId === declared.responsibility.name
      )
      if (!pass)
        return deny('PASS_EVIDENCE_REQUIRED', 'matching_oracle_result_pass_required', {
          missingEvidence: ['oracle-result:pass'],
        })
    } else {
      if (selected.some((e) => e.kind === 'agent-result' && ['fail', 'indeterminate'].includes(e.outcome)))
        return deny('EVIDENCE_NEGATIVE', 'agent_result_not_pass')
      const pass = selected.find(
        (e) =>
          e.kind === 'agent-result' &&
          e.outcome === 'pass' &&
          e.source?.kind === execution.kind &&
          e.source?.runtimeId === execution.runtimeId &&
          e.source?.agentId === execution.agentId &&
          e.source?.caseId === execution.caseId &&
          e.source?.threadId === execution.threadId
      )
      if (!pass)
        return deny('PASS_EVIDENCE_REQUIRED', 'matching_agent_result_pass_required', {
          missingEvidence: ['agent-result:pass'],
        })
    }
  }
  return { allowed: true }
}

export function applyWorkflowTransition(snapshot, definition, request, observedAt = new Date().toISOString()) {
  const previous = new Map(snapshot.instance.steps.map((s) => [s.id, s.status]))
  previous.set(request.stepId, request.requestedStatus)
  if (request.requestedStatus === 'completed')
    for (const step of definition.steps)
      if (previous.get(step.id) === 'pending' && step.dependsOn.every((id) => previous.get(id) === 'completed'))
        previous.set(step.id, 'ready')
  const statuses = [...previous.values()]
  const status = statuses.every((s) => s === 'completed')
    ? 'completed'
    : statuses.some((s) => s === 'failed')
      ? 'failed'
      : statuses.some((s) => s === 'waiting_human')
        ? 'waiting_human'
        : statuses.some((s) => s === 'blocked')
          ? 'blocked'
          : statuses.some((s) => s === 'running')
            ? 'running'
            : statuses.some((s) => s === 'ready')
              ? 'ready'
              : 'pending'
  const revision = snapshot.revision + 1
  const instance = {
    ...snapshot.instance,
    revision,
    status,
    steps: snapshot.instance.steps.map((s) => ({ ...s, status: previous.get(s.id) })),
    updatedAt: observedAt,
  }
  const projection = {
    ...snapshot.projection,
    status,
    steps: snapshot.projection.steps.map((s) => ({ ...s, status: previous.get(s.id) })),
  }
  return { ...snapshot, instance, projection, revision }
}

/** Dedicated application path after evaluateHumanCheckpointOpen authorizes ready → waiting_human. */
export function applyHumanCheckpointOpen(snapshot, definition, request, observedAt = new Date().toISOString()) {
  const statuses = new Map(snapshot.instance.steps.map((step) => [step.id, step.status]))
  statuses.set(request.stepId, 'waiting_human')
  const revision = snapshot.revision + 1
  const instance = {
    ...snapshot.instance,
    revision,
    status: 'waiting_human',
    steps: snapshot.instance.steps.map((step) => ({ ...step, status: statuses.get(step.id) })),
    updatedAt: observedAt,
  }
  const projection = {
    ...snapshot.projection,
    status: 'waiting_human',
    steps: snapshot.projection.steps.map((step) => ({ ...step, status: statuses.get(step.id) })),
  }
  return { ...snapshot, instance, projection, revision }
}
