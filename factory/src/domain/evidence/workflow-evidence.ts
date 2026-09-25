import { randomUUID } from 'node:crypto'

/**
 * Pure workflow evidence domain: canonical evidence kinds/outcomes/limits,
 * input validation and durable-record materialization.
 *
 * Authority: this TypeScript source is bundled into
 * `factory/runtime/factory-operational.mjs`; `factory/lib/workflow-evidence.mjs`
 * is a stateless compatibility facade re-exporting from that bundle.
 *
 * Domain purity: this module must not import `node:fs`, HTTP clients, AgentOS or
 * a Git CLI. Only `node:crypto` is allowed.
 */

export const WORKFLOW_EVIDENCE_KINDS = Object.freeze([
  'agent-result',
  'artifact',
  'oracle-result',
  'human-decision',
] as const)

export type WorkflowEvidenceKind = (typeof WORKFLOW_EVIDENCE_KINDS)[number]

export const WORKFLOW_EVIDENCE_OUTCOMES = Object.freeze(['pass', 'fail', 'indeterminate'] as const)

export type WorkflowEvidenceOutcome = (typeof WORKFLOW_EVIDENCE_OUTCOMES)[number]

export const WORKFLOW_EVIDENCE_LIMITS = Object.freeze({
  idempotencyKey: 128,
  artifactRef: 1024,
  facts: 32,
  factKey: 64,
  factValue: 256,
})

export interface WorkflowEvidenceInput {
  workflowId: string
  stepId: string
  kind: WorkflowEvidenceKind
  outcome?: WorkflowEvidenceOutcome
  artifactRef?: string
  artifactHash?: string
  facts?: Record<string, unknown>
  idempotencyKey?: string
}

export interface ValidatedArtifactEvidence {
  workflowId: string
  stepId: string
  kind: 'artifact'
  artifactRef: string
  artifactHash: string
  idempotencyKey?: string
}

export interface ValidatedFactsEvidence {
  workflowId: string
  stepId: string
  kind: WorkflowEvidenceKind
  outcome?: WorkflowEvidenceOutcome
  facts: Record<string, unknown>
  idempotencyKey?: string
}

export type ValidatedWorkflowEvidence = ValidatedArtifactEvidence | ValidatedFactsEvidence

export interface WorkflowEvidenceSource {
  kind?: string
  runtimeId?: string
  agentId?: string
  actorId?: string
  caseId?: string
  threadId?: string
  [key: string]: unknown
}

export interface WorkflowEvidence {
  evidenceId: string
  namespaceId: string
  workflowId: string
  stepId: string
  kind: string
  outcome?: string
  artifactRef?: string
  artifactHash?: string
  facts?: Record<string, unknown>
  source: WorkflowEvidenceSource
  observedAt: string
}

export type ValidateEvidenceResult =
  | { ok: true; value: ValidatedWorkflowEvidence }
  | { ok: false; error: { code: 'INVALID_EVIDENCE'; path: string; reason: string } }

const SAFE_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/
const HASH = /^sha256:[0-9a-f]{64}$/
const INPUT_FIELDS = new Set([
  'workflowId',
  'stepId',
  'kind',
  'outcome',
  'artifactRef',
  'artifactHash',
  'facts',
  'idempotencyKey',
])
const FACT_KEYS = new Set([
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

function invalid(path: string, reason = 'invalid_value'): ValidateEvidenceResult {
  return { ok: false, error: { code: 'INVALID_EVIDENCE', path, reason } }
}

function boundedText(value: unknown, maximum: number): value is string {
  return typeof value === 'string' && value.length > 0 && value.length <= maximum && !/[\r\n]/.test(value)
}

function isEvidenceKind(value: unknown): value is WorkflowEvidenceKind {
  return typeof value === 'string' && (WORKFLOW_EVIDENCE_KINDS as readonly string[]).includes(value)
}

function isEvidenceOutcome(value: unknown): value is WorkflowEvidenceOutcome {
  return typeof value === 'string' && (WORKFLOW_EVIDENCE_OUTCOMES as readonly string[]).includes(value)
}

export function validateWorkflowEvidenceInput(input: unknown, expectedWorkflowId: string): ValidateEvidenceResult {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return invalid('$', 'not_object')
  const record = input as Record<string, unknown>
  if (Object.keys(record).some((field) => !INPUT_FIELDS.has(field))) return invalid('$', 'unknown_field')
  if (record.workflowId !== expectedWorkflowId || !SAFE_ID.test(String(record.workflowId ?? '')))
    return invalid('workflowId')
  if (!SAFE_ID.test(String(record.stepId ?? ''))) return invalid('stepId')
  if (!isEvidenceKind(record.kind)) return invalid('kind')
  if (
    record.idempotencyKey !== undefined &&
    !boundedText(record.idempotencyKey, WORKFLOW_EVIDENCE_LIMITS.idempotencyKey)
  )
    return invalid('idempotencyKey')
  if (record.kind === 'artifact') {
    if (record.outcome !== undefined || record.facts !== undefined) return invalid('$', 'artifact_fields')
    if (!boundedText(record.artifactRef, WORKFLOW_EVIDENCE_LIMITS.artifactRef)) return invalid('artifactRef')
    if (!HASH.test(String(record.artifactHash ?? ''))) return invalid('artifactHash')
    return {
      ok: true,
      value: {
        workflowId: record.workflowId as string,
        stepId: record.stepId as string,
        kind: 'artifact',
        artifactRef: record.artifactRef,
        artifactHash: record.artifactHash as string,
        ...(record.idempotencyKey ? { idempotencyKey: record.idempotencyKey as string } : {}),
      },
    }
  }
  if (['oracle-result', 'human-decision'].includes(record.kind) && record.outcome === undefined)
    return invalid('outcome')
  if (record.artifactRef !== undefined || record.artifactHash !== undefined) return invalid('$', 'agent_result_fields')
  if (record.outcome !== undefined && !isEvidenceOutcome(record.outcome)) return invalid('outcome')
  if (!record.facts || typeof record.facts !== 'object' || Array.isArray(record.facts)) return invalid('facts')
  const entries = Object.entries(record.facts as Record<string, unknown>)
  if (entries.length === 0 || entries.length > WORKFLOW_EVIDENCE_LIMITS.facts) return invalid('facts')
  for (const [key, value] of entries) {
    if (!FACT_KEYS.has(key) || key.length > WORKFLOW_EVIDENCE_LIMITS.factKey)
      return invalid(`facts.${key}`, 'unsupported_fact')
    if (
      !(
        typeof value === 'boolean' ||
        (typeof value === 'number' && Number.isSafeInteger(value)) ||
        boundedText(value, WORKFLOW_EVIDENCE_LIMITS.factValue)
      )
    )
      return invalid(`facts.${key}`)
  }
  return {
    ok: true,
    value: {
      workflowId: record.workflowId as string,
      stepId: record.stepId as string,
      kind: record.kind,
      ...(record.outcome ? { outcome: record.outcome } : {}),
      facts: { ...(record.facts as Record<string, unknown>) },
      ...(record.idempotencyKey ? { idempotencyKey: record.idempotencyKey as string } : {}),
    },
  }
}

export function createWorkflowEvidence(
  validated: ValidatedWorkflowEvidence,
  namespaceId: string,
  source: WorkflowEvidenceSource,
  observedAt: string = new Date().toISOString(),
  evidenceId: string = randomUUID()
): WorkflowEvidence {
  const { idempotencyKey, ...rest } = validated
  void idempotencyKey
  const record: WorkflowEvidence = {
    evidenceId,
    namespaceId,
    ...rest,
    source: Object.freeze({ ...source }),
    observedAt,
  }
  return Object.freeze(record)
}
