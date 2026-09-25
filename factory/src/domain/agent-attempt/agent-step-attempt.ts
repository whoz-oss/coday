/**
 * Pure agent-step-attempt domain: attempt statuses, the attempt record shape
 * and the invariants an attempt must satisfy before it is persisted.
 *
 * Authority: this TypeScript source is bundled into
 * `factory/runtime/factory-operational.mjs`; `factory/lib/agent-step-attempt-store.mjs`
 * is a stateless compatibility facade re-exporting from that bundle.
 *
 * Domain purity: this module must not import `node:fs`, HTTP clients, AgentOS or
 * a Git CLI. It carries no `node:*` dependency at all.
 */

/** Ordered lifecycle statuses an agent step attempt can take. */
export const AGENT_STEP_ATTEMPT_STATUSES = Object.freeze([
  'starting',
  'running',
  'succeeded',
  'failed',
  'indeterminate',
  'interrupted',
] as const)

export type AgentStepAttemptStatus = (typeof AGENT_STEP_ATTEMPT_STATUSES)[number]

/** Terminal statuses: an attempt that reached one of them is finished. */
export const AGENT_STEP_ATTEMPT_TERMINAL_STATUSES = Object.freeze([
  'succeeded',
  'failed',
  'indeterminate',
  'interrupted',
] as const)

export type AgentStepAttemptTerminalStatus = (typeof AGENT_STEP_ATTEMPT_TERMINAL_STATUSES)[number]

/**
 * Allowed status transitions keyed by the current status. A status absent from
 * the map (a terminal one) allows no further transition.
 */
export const AGENT_STEP_ATTEMPT_TRANSITIONS: Readonly<Record<string, readonly AgentStepAttemptStatus[]>> =
  Object.freeze({
    starting: Object.freeze(['running', 'failed', 'interrupted'] as const),
    running: Object.freeze(['succeeded', 'failed', 'indeterminate', 'interrupted'] as const),
  })

/** A durable record of one attempt at executing an agent workflow step. */
export interface AgentStepAttempt {
  attemptId: string
  workflowId: string
  workflowRevisionAtStart: number
  stepId: string
  attemptNumber: number
  namespaceId: string
  runtimeId: string
  caseId: string | null
  agentName: string
  briefHash: string
  status: AgentStepAttemptStatus
  startedAt: string
  finishedAt: string | null
  evidenceId: string | null
  failureCode: string | null
}

/** Immutable fields of an attempt: a transition may never change them. */
export const AGENT_STEP_ATTEMPT_IMMUTABLE_FIELDS: readonly (keyof AgentStepAttempt)[] = Object.freeze([
  'workflowId',
  'workflowRevisionAtStart',
  'stepId',
  'attemptNumber',
  'namespaceId',
  'runtimeId',
  'agentName',
  'briefHash',
  'startedAt',
])

const SAFE_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/
const BRIEF_HASH = /^sha256:[0-9a-f]{64}$/

/** True when `value` is one of the known attempt statuses. */
export function isAgentStepAttemptStatus(value: unknown): value is AgentStepAttemptStatus {
  return typeof value === 'string' && (AGENT_STEP_ATTEMPT_STATUSES as readonly string[]).includes(value)
}

/** True when `value` is a terminal attempt status. */
export function isAgentStepAttemptTerminal(value: unknown): value is AgentStepAttemptTerminalStatus {
  return typeof value === 'string' && (AGENT_STEP_ATTEMPT_TERMINAL_STATUSES as readonly string[]).includes(value)
}

/** True when `value` is a parseable instant. */
export function isValidAgentStepAttemptInstant(value: unknown): value is string {
  return typeof value === 'string' && !Number.isNaN(Date.parse(value))
}

/**
 * Validates the complete attempt invariants, throwing `INVALID_AGENT_STEP_ATTEMPT`
 * on any violation. Returns the attempt typed as `AgentStepAttempt` on success.
 */
export function validateAgentStepAttempt(attempt: unknown): AgentStepAttempt {
  const record = (attempt ?? {}) as Record<string, unknown>
  if (
    !attempt ||
    typeof attempt !== 'object' ||
    !SAFE_ID.test(String(record.attemptId ?? '')) ||
    !SAFE_ID.test(String(record.workflowId ?? '')) ||
    !SAFE_ID.test(String(record.stepId ?? '')) ||
    !SAFE_ID.test(String(record.namespaceId ?? '')) ||
    typeof record.runtimeId !== 'string' ||
    !record.runtimeId ||
    typeof record.agentName !== 'string' ||
    !record.agentName ||
    !BRIEF_HASH.test(String(record.briefHash ?? '')) ||
    !Number.isSafeInteger(record.workflowRevisionAtStart) ||
    (record.workflowRevisionAtStart as number) < 1 ||
    !Number.isSafeInteger(record.attemptNumber) ||
    (record.attemptNumber as number) < 1 ||
    !isAgentStepAttemptStatus(record.status) ||
    !isValidAgentStepAttemptInstant(record.startedAt)
  )
    throw new Error('INVALID_AGENT_STEP_ATTEMPT')
  if (record.caseId !== null && typeof record.caseId !== 'string') throw new Error('INVALID_AGENT_STEP_ATTEMPT')
  const terminal = isAgentStepAttemptTerminal(record.status)
  if (terminal !== isValidAgentStepAttemptInstant(record.finishedAt) || (!terminal && record.finishedAt !== null))
    throw new Error('INVALID_AGENT_STEP_ATTEMPT')
  if (record.status === 'starting' && record.caseId !== null) throw new Error('INVALID_AGENT_STEP_ATTEMPT')
  if (record.status !== 'starting' && !record.caseId) throw new Error('INVALID_AGENT_STEP_ATTEMPT')
  return attempt as AgentStepAttempt
}
