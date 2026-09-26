/**
 * Pure read-only Forge Ledger → generic workflow projection adapter.
 *
 * Step 1 of the Forge Ledger convergence: map the append-only Forge JSONL
 * journal (`factory/src/domain/forge-bmad/forge-ledger.ts`) onto the generic
 * workflow constructs (state/step transitions, `workflow_evidence`,
 * `human_interactions`) WITHOUT switching authority. The Forge ledger stays
 * primary; this module only *reads* parsed events and derives a projection.
 *
 * It performs no I/O whatsoever: no `node:fs`, no database, no network, no
 * `node:crypto`. It is a pure function over `readonly ForgeLedgerEvent[]` and
 * it reuses the pure validators `validateWorkflowEvidenceInput` and
 * `validateHumanInteractionOpenInput`.
 *
 * Every field the generic vocabulary cannot represent is *reported*, never
 * silently dropped: see `ForgeLedgerGenericProjectionResult.unmappedEvents`.
 * The current `FACT_KEYS` whitelist is mirrored read-only in
 * `FORGE_FACT_KEY_WHITELIST`; it is NOT modified here.
 *
 * See `docs/forge-ledger-mapping.md` for the exhaustive cartography.
 */

import type { ForgeLedgerEvent } from './types.js'
import {
  validateWorkflowEvidenceInput,
  type WorkflowEvidenceInput,
  type WorkflowEvidenceOutcome,
  type WorkflowEvidenceSource,
} from '../evidence/workflow-evidence.js'
import {
  validateHumanInteractionOpenInput,
  type NormalizedHumanInteractionInput,
} from '../interaction/workflow-human-interaction.js'
import type { WorkflowStatus } from '../workflow/workflow-transition-policy.js'

/**
 * Read-only mirror of the `FACT_KEYS` whitelist of
 * `factory/src/domain/evidence/workflow-evidence.ts`.
 *
 * It exists only so the projection can tell a mappable fact from an unmappable
 * Forge field. It MUST stay in sync with that whitelist; extending it is an
 * explicit Step 2 decision that this module never applies.
 */
export const FORGE_FACT_KEY_WHITELIST: ReadonlySet<string> = new Set([
  'resultCode',
  'category',
  'attempt',
  'durationMs',
  'itemCount',
  'oracleId',
  'oracleVersion',
  'oracleHash',
  'commandId',
  'cwdId',
  'exitCode',
  'signal',
  'timedOut',
  'classification',
  'executed',
  'fromCache',
  'upToDate',
  'skipped',
  'outputHash',
  'outputTruncated',
  'interactionId',
  'actionId',
  'decisionTextHash',
  'briefHash',
  'claimsHash',
  'diffHash',
  'reviewPackageHash',
  'finalizationTurns',
])

/** One generic evidence candidate plus its validity verdict. */
export interface ForgeGenericEvidenceProjection {
  input: WorkflowEvidenceInput
  source: WorkflowEvidenceSource
  isValid: boolean
  validationError?: string
}

/** A human interaction opened by a Forge gate. */
export interface ForgeGenericHumanInteractionOpenProjection {
  type: 'open'
  openInput?: NormalizedHumanInteractionInput
  isValid: boolean
  validationError?: string
}

/** A human interaction reply derived from a recorded Forge decision. */
export interface ForgeGenericHumanInteractionReplyProjection {
  type: 'reply'
  replyData: {
    interactionId: string
    actorId: string
    outcome: 'approved' | 'rejected'
    reasonCode: string
    repliedAt: string
    workflowId?: string
    stepId?: string
  }
  isValid: boolean
  validationError?: string
}

export type ForgeGenericHumanInteractionProjection =
  | ForgeGenericHumanInteractionOpenProjection
  | ForgeGenericHumanInteractionReplyProjection

/** A workflow state/step transition derived from a Forge event. */
export interface ForgeGenericTransitionProjection {
  workflowId: string
  stepId: string
  status: WorkflowStatus
  reason: string
  at?: string
}

/** Why a Forge field or event could not be mapped. */
export type UnmappedForgeFieldReason = 'not_in_fact_whitelist' | 'unmapped_event_type' | 'unsupported_structure'

/** A Forge field/event that the generic vocabulary cannot represent. */
export interface UnmappedForgeField {
  event: string
  runId?: string
  storyRunId?: string
  field: string
  value: unknown
  reason: UnmappedForgeFieldReason
}

/** The complete read-only projection of a Forge ledger. */
export interface ForgeLedgerGenericProjectionResult {
  evidences: ForgeGenericEvidenceProjection[]
  interactions: ForgeGenericHumanInteractionProjection[]
  transitions: ForgeGenericTransitionProjection[]
  unmappedEvents: UnmappedForgeField[]
}

// ---------------------------------------------------------------------------
// Constants and small pure helpers
// ---------------------------------------------------------------------------

/** Fields consumed by the projection itself (identity, ordering, structure). */
const CONSUMED_FIELDS: ReadonlySet<string> = new Set([
  'schemaVersion',
  'event',
  'at',
  'runId',
  'storyRunId',
  'parentRunId',
  'runType',
  'ordinal',
  'gate',
  'attempt',
  'status',
  'outcome',
  'code',
  'name',
  'durationMs',
  'exitCode',
  'workflow',
  'workItem',
  'roots',
  'decisionId',
  'idempotencyKey',
  'decision',
  'executionId',
  'caseId',
  'role',
  'agentName',
  'namespaceId',
  'observedAt',
  'actorId',
  'authorityId',
  'editId',
  'campaignId',
  'artifact',
])

/** Forge status vocabulary → generic `WorkflowStatus`. */
const TRANSITION_STATUS: Readonly<Record<string, WorkflowStatus>> = Object.freeze({
  approved: 'completed',
  passed: 'completed',
  finished: 'completed',
  valid: 'completed',
  rejected: 'failed',
  failed: 'failed',
  invalid: 'failed',
  blocked: 'blocked',
  skipped: 'blocked',
  waiting_human: 'waiting_human',
  started: 'running',
  created: 'ready',
  not_started: 'ready',
})

/** Forge status vocabulary → generic evidence outcome. */
const EVIDENCE_OUTCOME: Readonly<Record<string, WorkflowEvidenceOutcome>> = Object.freeze({
  approved: 'pass',
  passed: 'pass',
  finished: 'pass',
  valid: 'pass',
  rejected: 'fail',
  failed: 'fail',
  invalid: 'fail',
  blocked: 'indeterminate',
  skipped: 'indeterminate',
})

const SAFE_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/

function safeId(value: unknown, fallback = 'unknown'): string {
  const raw = (typeof value === 'string' && value.length > 0 ? value : fallback)
    .replace(/[^A-Za-z0-9._-]/g, '-')
    .slice(0, 127)
  return SAFE_ID.test(raw) ? raw : `x${raw}`.slice(0, 128)
}

function boundedText(value: unknown, maximum: number): string | undefined {
  if (typeof value !== 'string' || value.length === 0 || value.length > maximum || /[\r\n]/.test(value))
    return undefined
  return value
}

function factText(value: unknown): string | undefined {
  return boundedText(value, 256)
}

function integerFact(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isSafeInteger(value) ? value : undefined
}

function runIdOf(event: ForgeLedgerEvent): string | undefined {
  return typeof event.runId === 'string' ? event.runId : undefined
}

function storyRunIdOf(event: ForgeLedgerEvent): string | undefined {
  return typeof event.storyRunId === 'string' ? event.storyRunId : undefined
}

function atOf(event: ForgeLedgerEvent): string | undefined {
  return typeof event.at === 'string' ? event.at : undefined
}

function attemptOf(event: ForgeLedgerEvent): number {
  return typeof event.attempt === 'number' && Number.isSafeInteger(event.attempt) && event.attempt >= 1
    ? event.attempt
    : 1
}

function toWorkflowStatus(value: unknown, fallback: WorkflowStatus = 'blocked'): WorkflowStatus {
  return typeof value === 'string' && value in TRANSITION_STATUS ? TRANSITION_STATUS[value]! : fallback
}

function toEvidenceOutcome(
  value: unknown,
  fallback: WorkflowEvidenceOutcome = 'indeterminate'
): WorkflowEvidenceOutcome {
  return typeof value === 'string' && value in EVIDENCE_OUTCOME ? EVIDENCE_OUTCOME[value]! : fallback
}

function addTransition(
  result: ForgeLedgerGenericProjectionResult,
  workflowId: unknown,
  stepId: string,
  status: WorkflowStatus,
  reason: string,
  at: unknown
): void {
  result.transitions.push({
    workflowId: safeId(workflowId),
    stepId: safeId(stepId),
    status,
    reason,
    ...(typeof at === 'string' ? { at } : {}),
  })
}

function addEvidence(
  result: ForgeLedgerGenericProjectionResult,
  input: WorkflowEvidenceInput,
  source: WorkflowEvidenceSource
): void {
  const validation = validateWorkflowEvidenceInput(input, input.workflowId)
  if (validation.ok) {
    result.evidences.push({ input, source, isValid: true })
    return
  }
  result.evidences.push({
    input,
    source,
    isValid: false,
    validationError: `${validation.error.path}: ${validation.error.reason}`,
  })
}

function addArtifactEvidence(
  result: ForgeLedgerGenericProjectionResult,
  workflowId: unknown,
  stepId: string,
  descriptor: unknown,
  source: WorkflowEvidenceSource
): void {
  if (!descriptor || typeof descriptor !== 'object' || Array.isArray(descriptor)) return
  const record = descriptor as Record<string, unknown>
  const artifactRef = boundedText(record.path, 1024)
  const artifactHash = typeof record.sha256 === 'string' ? record.sha256 : undefined
  if (!artifactRef || !artifactHash) return
  addEvidence(
    result,
    { workflowId: safeId(workflowId), stepId: safeId(stepId), kind: 'artifact', artifactRef, artifactHash },
    source
  )
}

function gateInteractionId(runId: unknown, gate: unknown, attempt: number): string {
  return safeId(`interaction_${String(runId ?? 'unknown')}_${String(gate ?? 'gate')}_${attempt}`)
}

function collectUnmapped(event: ForgeLedgerEvent, result: ForgeLedgerGenericProjectionResult, eventName: string): void {
  const runId = runIdOf(event)
  const storyRunId = storyRunIdOf(event)
  for (const [field, value] of Object.entries(event)) {
    if (CONSUMED_FIELDS.has(field)) continue
    const reason: UnmappedForgeFieldReason = FORGE_FACT_KEY_WHITELIST.has(field)
      ? 'unsupported_structure'
      : 'not_in_fact_whitelist'
    result.unmappedEvents.push({
      event: eventName,
      ...(runId ? { runId } : {}),
      ...(storyRunId ? { storyRunId } : {}),
      field,
      value,
      reason,
    })
  }
}

// ---------------------------------------------------------------------------
// Per-event projections
// ---------------------------------------------------------------------------

function projectRunStarted(event: ForgeLedgerEvent, result: ForgeLedgerGenericProjectionResult): void {
  addTransition(result, event.runId, 'epic-run', 'ready', 'run_started', atOf(event))
}

function projectStoryRunCreated(event: ForgeLedgerEvent, result: ForgeLedgerGenericProjectionResult): void {
  addTransition(result, event.runId, 'story-run', 'ready', 'story_run_created', atOf(event))
}

function projectGateStarted(event: ForgeLedgerEvent, result: ForgeLedgerGenericProjectionResult): void {
  const gate = typeof event.gate === 'string' ? event.gate : 'G1'
  addTransition(result, event.runId, gate, 'waiting_human', `gate_started:${gate}`, atOf(event))
  if (gate !== 'G1') return
  const runId = runIdOf(event)
  const requiredDecision = typeof event.requiredDecision === 'string' ? event.requiredDecision : 'human-decision'
  const attempt = attemptOf(event)
  const candidate = {
    workflowId: safeId(runId),
    stepId: safeId(gate),
    expectedRevision: attempt,
    kind: 'approval',
    prompt: `Forge gate ${gate} requires a human decision (${requiredDecision}).`,
    actions: [
      { id: 'approve', label: 'Approve', requestedStatus: 'completed' as const },
      { id: 'reject', label: 'Reject', requestedStatus: 'failed' as const },
    ],
    idempotencyKey: safeId(`forge-${String(runId ?? 'unknown')}-${gate}-${attempt}-open`),
    interactionId: gateInteractionId(runId, gate, attempt),
  }
  const openInput = validateHumanInteractionOpenInput(candidate)
  if (!openInput) {
    result.interactions.push({ type: 'open', isValid: false, validationError: 'invalid_open_input' })
    return
  }
  result.interactions.push({ type: 'open', openInput, isValid: true })
}

function projectHumanDecision(event: ForgeLedgerEvent, result: ForgeLedgerGenericProjectionResult): void {
  const runId = runIdOf(event)
  const gate = typeof event.gate === 'string' ? event.gate : 'G1'
  const rawDecision = event.decision
  const decision: Record<string, unknown> =
    rawDecision && typeof rawDecision === 'object' && !Array.isArray(rawDecision)
      ? (rawDecision as Record<string, unknown>)
      : {}
  const outcome = typeof decision.outcome === 'string' ? decision.outcome : undefined
  const reasonCode = typeof decision.reasonCode === 'string' ? decision.reasonCode : undefined
  const actorId = typeof decision.actorId === 'string' ? decision.actorId : 'unknown'
  const authorityId = typeof decision.authorityId === 'string' ? decision.authorityId : undefined
  const attempt = attemptOf(event)

  addTransition(
    result,
    runId,
    gate,
    outcome === 'approved' ? 'completed' : outcome === 'rejected' ? 'failed' : 'blocked',
    `human_decision_recorded:${outcome ?? 'unknown'}`,
    atOf(event)
  )

  const facts: Record<string, unknown> = { resultCode: factText(reasonCode) ?? 'human-decision' }
  facts.attempt = attempt
  const idempotencyKey = boundedText(event.idempotencyKey, 128)
  addEvidence(
    result,
    {
      workflowId: safeId(runId),
      stepId: safeId(gate),
      kind: 'human-decision',
      outcome: toEvidenceOutcome(outcome),
      facts,
      ...(idempotencyKey ? { idempotencyKey } : {}),
    },
    {
      kind: 'forge-ledger',
      actorId,
      gate,
      ...(authorityId ? { authorityId } : {}),
      ...(typeof event.decisionId === 'string' ? { decisionId: event.decisionId } : {}),
      ...(runId ? { runId } : {}),
    }
  )

  if (outcome !== 'approved' && outcome !== 'rejected') return
  result.interactions.push({
    type: 'reply',
    replyData: {
      interactionId: gateInteractionId(runId, gate, attempt),
      actorId,
      outcome,
      reasonCode: reasonCode ?? 'unspecified',
      repliedAt: atOf(event) ?? '',
      workflowId: safeId(runId),
      stepId: safeId(gate),
    },
    isValid: true,
  })
}

function projectG2(event: ForgeLedgerEvent, result: ForgeLedgerGenericProjectionResult): void {
  const runId = runIdOf(event)
  const attempt = attemptOf(event)
  addTransition(
    result,
    runId,
    'G2',
    toWorkflowStatus(event.status),
    `g2_evaluated:${String(event.status)}`,
    atOf(event)
  )
  addEvidence(
    result,
    {
      workflowId: safeId(runId),
      stepId: 'G2',
      kind: 'oracle-result',
      outcome: toEvidenceOutcome(event.status),
      facts: { resultCode: factText(event.code) ?? 'G2', attempt },
    },
    { kind: 'forge-ledger', gate: 'G2', ...(runId ? { runId } : {}) }
  )
  addArtifactEvidence(result, runId, 'G2', event.spec, { kind: 'forge-ledger', gate: 'G2' })
}

function projectG2Us(event: ForgeLedgerEvent, result: ForgeLedgerGenericProjectionResult): void {
  const storyRunId = storyRunIdOf(event)
  const attempt = attemptOf(event)
  addTransition(
    result,
    storyRunId,
    'G2-US',
    toWorkflowStatus(event.status),
    `g2_us_evaluated:${String(event.status)}`,
    atOf(event)
  )
  addEvidence(
    result,
    {
      workflowId: safeId(storyRunId),
      stepId: 'G2-US',
      kind: 'oracle-result',
      outcome: toEvidenceOutcome(event.status),
      facts: { resultCode: factText(event.code) ?? 'G2-US', attempt },
    },
    { kind: 'forge-ledger', gate: 'G2-US', ...(storyRunId ? { storyRunId } : {}) }
  )
  addArtifactEvidence(result, storyRunId, 'G2-US', event.storySpec, { kind: 'forge-ledger', gate: 'G2-US' })
}

function projectExecutionStarted(event: ForgeLedgerEvent, result: ForgeLedgerGenericProjectionResult): void {
  addTransition(result, event.storyRunId, 'analysis', 'running', 'agent_execution_started', atOf(event))
}

function projectExecutionFinished(event: ForgeLedgerEvent, result: ForgeLedgerGenericProjectionResult): void {
  const storyRunId = storyRunIdOf(event)
  addTransition(
    result,
    storyRunId,
    'analysis',
    toWorkflowStatus(event.status),
    `agent_execution_finished:${String(event.status)}`,
    atOf(event)
  )
  addEvidence(
    result,
    {
      workflowId: safeId(storyRunId),
      stepId: 'analysis',
      kind: 'agent-result',
      outcome: toEvidenceOutcome(event.status),
      facts: { resultCode: factText(event.status) ?? 'agent-result' },
    },
    {
      kind: 'forge-ledger',
      ...(typeof event.role === 'string' ? { runtimeId: event.role } : {}),
      ...(typeof event.agentName === 'string' ? { agentId: event.agentName } : {}),
      ...(typeof event.caseId === 'string' ? { caseId: event.caseId } : {}),
      ...(typeof event.namespaceId === 'string' ? { namespaceId: event.namespaceId } : {}),
      ...(typeof event.executionId === 'string' ? { executionId: event.executionId } : {}),
      ...(runIdOf(event) ? { runId: runIdOf(event) } : {}),
    }
  )
  addArtifactEvidence(result, storyRunId, 'analysis', event.artifact, { kind: 'forge-ledger' })
}

function projectAnalysisPlanValidated(event: ForgeLedgerEvent, result: ForgeLedgerGenericProjectionResult): void {
  const storyRunId = storyRunIdOf(event)
  addTransition(
    result,
    storyRunId,
    'analysis-plan',
    toWorkflowStatus(event.status),
    `story_analysis_plan_validated:${String(event.status)}`,
    atOf(event)
  )
  addEvidence(
    result,
    {
      workflowId: safeId(storyRunId),
      stepId: 'analysis-plan',
      kind: 'oracle-result',
      outcome: toEvidenceOutcome(event.status),
      facts: { resultCode: factText(event.code) ?? 'STORY_ANALYSIS_PLAN' },
    },
    { kind: 'forge-ledger', ...(storyRunId ? { storyRunId } : {}) }
  )
  addArtifactEvidence(result, storyRunId, 'analysis-plan', event.artifact, { kind: 'forge-ledger' })
}

function projectEditStarted(event: ForgeLedgerEvent, result: ForgeLedgerGenericProjectionResult): void {
  addTransition(result, event.storyRunId, 'edit', 'running', 'story_edit_started', atOf(event))
}

function projectEditFinished(event: ForgeLedgerEvent, result: ForgeLedgerGenericProjectionResult): void {
  const storyRunId = storyRunIdOf(event)
  addTransition(
    result,
    storyRunId,
    'edit',
    toWorkflowStatus(event.status),
    `story_edit_finished:${String(event.status)}`,
    atOf(event)
  )
  addEvidence(
    result,
    {
      workflowId: safeId(storyRunId),
      stepId: 'edit',
      kind: 'agent-result',
      outcome: toEvidenceOutcome(event.status),
      facts: { resultCode: factText(event.status) ?? 'story-edit' },
    },
    {
      kind: 'forge-ledger',
      ...(typeof event.caseId === 'string' ? { caseId: event.caseId } : {}),
      ...(typeof event.editId === 'string' ? { editId: event.editId } : {}),
      ...(runIdOf(event) ? { runId: runIdOf(event) } : {}),
    }
  )
}

function projectOraclesStarted(event: ForgeLedgerEvent, result: ForgeLedgerGenericProjectionResult): void {
  addTransition(result, event.storyRunId, 'oracles', 'running', 'story_oracles_started', atOf(event))
}

function projectOracleFinished(event: ForgeLedgerEvent, result: ForgeLedgerGenericProjectionResult): void {
  const storyRunId = storyRunIdOf(event)
  const name = typeof event.name === 'string' ? event.name : 'oracle'
  const facts: Record<string, unknown> = { resultCode: factText(event.code) ?? 'oracle' }
  const oracleId = factText(name)
  if (oracleId) facts.oracleId = oracleId
  const exitCode = integerFact(event.exitCode)
  if (exitCode !== undefined) facts.exitCode = exitCode
  const durationMs = integerFact(event.durationMs)
  if (durationMs !== undefined) facts.durationMs = durationMs
  addTransition(
    result,
    storyRunId,
    `oracle.${name}`,
    toWorkflowStatus(event.status),
    `story_oracle_finished:${name}`,
    atOf(event)
  )
  addEvidence(
    result,
    {
      workflowId: safeId(storyRunId),
      stepId: safeId(`oracle.${name}`),
      kind: 'oracle-result',
      outcome: toEvidenceOutcome(event.status),
      facts,
    },
    {
      kind: 'forge-ledger',
      ...(typeof event.campaignId === 'string' ? { campaignId: event.campaignId } : {}),
      ...(typeof event.editId === 'string' ? { editId: event.editId } : {}),
      ...(runIdOf(event) ? { runId: runIdOf(event) } : {}),
    }
  )
}

function projectG3(event: ForgeLedgerEvent, result: ForgeLedgerGenericProjectionResult): void {
  const storyRunId = storyRunIdOf(event)
  const attempt = attemptOf(event)
  addTransition(
    result,
    storyRunId,
    'G3',
    toWorkflowStatus(event.status),
    `story_g3_evaluated:${String(event.status)}`,
    atOf(event)
  )
  addEvidence(
    result,
    {
      workflowId: safeId(storyRunId),
      stepId: 'G3',
      kind: 'oracle-result',
      outcome: toEvidenceOutcome(event.status),
      facts: { resultCode: factText(event.status) ?? 'G3', attempt },
    },
    {
      kind: 'forge-ledger',
      ...(typeof event.campaignId === 'string' ? { campaignId: event.campaignId } : {}),
      ...(storyRunId ? { storyRunId } : {}),
    }
  )
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/**
 * Project a parsed Forge ledger onto the generic workflow vocabulary.
 *
 * Pure: it never touches the filesystem, a database or the network, and it
 * never mutates its input. Unmappable fields/events are reported in
 * `unmappedEvents` instead of being dropped.
 */
export function projectForgeLedgerToGeneric(events: readonly ForgeLedgerEvent[]): ForgeLedgerGenericProjectionResult {
  const result: ForgeLedgerGenericProjectionResult = {
    evidences: [],
    interactions: [],
    transitions: [],
    unmappedEvents: [],
  }
  for (const event of events) {
    if (!event || typeof event !== 'object' || Array.isArray(event)) continue
    const name = typeof event.event === 'string' ? event.event : 'unknown'
    switch (name) {
      case 'run_started':
        projectRunStarted(event, result)
        break
      case 'story_run_created':
        projectStoryRunCreated(event, result)
        break
      case 'gate_started':
        projectGateStarted(event, result)
        break
      case 'human_decision_recorded':
        projectHumanDecision(event, result)
        break
      case 'g2_evaluated':
        projectG2(event, result)
        break
      case 'g2_us_evaluated':
        projectG2Us(event, result)
        break
      case 'agent_execution_started':
        projectExecutionStarted(event, result)
        break
      case 'agent_execution_finished':
        projectExecutionFinished(event, result)
        break
      case 'story_analysis_plan_validated':
        projectAnalysisPlanValidated(event, result)
        break
      case 'story_edit_started':
        projectEditStarted(event, result)
        break
      case 'story_edit_finished':
        projectEditFinished(event, result)
        break
      case 'story_oracles_started':
        projectOraclesStarted(event, result)
        break
      case 'story_oracle_finished':
        projectOracleFinished(event, result)
        break
      case 'story_g3_evaluated':
        projectG3(event, result)
        break
      default: {
        const runId = runIdOf(event)
        const storyRunId = storyRunIdOf(event)
        result.unmappedEvents.push({
          event: name,
          ...(runId ? { runId } : {}),
          ...(storyRunId ? { storyRunId } : {}),
          field: 'event',
          value: name,
          reason: 'unmapped_event_type',
        })
        continue
      }
    }
    collectUnmapped(event, result, name)
  }
  return result
}
