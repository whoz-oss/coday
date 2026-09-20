import { randomUUID } from 'node:crypto'

export const WORKFLOW_EVIDENCE_KINDS = Object.freeze(['agent-result', 'artifact', 'oracle-result', 'human-decision'])
export const WORKFLOW_EVIDENCE_OUTCOMES = Object.freeze(['pass', 'fail', 'indeterminate'])
export const WORKFLOW_EVIDENCE_LIMITS = Object.freeze({ idempotencyKey: 128, artifactRef: 1024, facts: 32, factKey: 64, factValue: 256 })
const SAFE_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/
const HASH = /^sha256:[0-9a-f]{64}$/
const INPUT_FIELDS = new Set(['workflowId', 'stepId', 'kind', 'outcome', 'artifactRef', 'artifactHash', 'facts', 'idempotencyKey'])
const FACT_KEYS = new Set(['resultCode', 'category', 'attempt', 'durationMs', 'itemCount', 'oracleId', 'oracleVersion', 'oracleHash', 'commandId', 'cwdId', 'exitCode', 'signal', 'timedOut', 'classification', 'executed', 'fromCache', 'upToDate', 'skipped', 'outputHash', 'outputTruncated', 'interactionId', 'actionId', 'decisionTextHash'])

function invalid(path, reason = 'invalid_value') { return { ok: false, error: { code: 'INVALID_EVIDENCE', path, reason } } }
function boundedText(value, maximum) { return typeof value === 'string' && value.length > 0 && value.length <= maximum && !/[\r\n]/.test(value) }

export function validateWorkflowEvidenceInput(input, expectedWorkflowId) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return invalid('$', 'not_object')
  if (Object.keys(input).some((field) => !INPUT_FIELDS.has(field))) return invalid('$', 'unknown_field')
  if (input.workflowId !== expectedWorkflowId || !SAFE_ID.test(input.workflowId ?? '')) return invalid('workflowId')
  if (!SAFE_ID.test(input.stepId ?? '')) return invalid('stepId')
  if (!WORKFLOW_EVIDENCE_KINDS.includes(input.kind)) return invalid('kind')
  if (input.idempotencyKey !== undefined && !boundedText(input.idempotencyKey, WORKFLOW_EVIDENCE_LIMITS.idempotencyKey)) return invalid('idempotencyKey')
  if (input.kind === 'artifact') {
    if (input.outcome !== undefined || input.facts !== undefined) return invalid('$', 'artifact_fields')
    if (!boundedText(input.artifactRef, WORKFLOW_EVIDENCE_LIMITS.artifactRef)) return invalid('artifactRef')
    if (!HASH.test(input.artifactHash ?? '')) return invalid('artifactHash')
  } else {
    if (['oracle-result', 'human-decision'].includes(input.kind) && input.outcome === undefined) return invalid('outcome')
    if (input.artifactRef !== undefined || input.artifactHash !== undefined) return invalid('$', 'agent_result_fields')
    if (input.outcome !== undefined && !WORKFLOW_EVIDENCE_OUTCOMES.includes(input.outcome)) return invalid('outcome')
    if (!input.facts || typeof input.facts !== 'object' || Array.isArray(input.facts)) return invalid('facts')
    const entries = Object.entries(input.facts)
    if (entries.length === 0 || entries.length > WORKFLOW_EVIDENCE_LIMITS.facts) return invalid('facts')
    for (const [key, value] of entries) {
      if (!FACT_KEYS.has(key) || key.length > WORKFLOW_EVIDENCE_LIMITS.factKey) return invalid(`facts.${key}`, 'unsupported_fact')
      if (!(typeof value === 'boolean' || (typeof value === 'number' && Number.isSafeInteger(value)) || boundedText(value, WORKFLOW_EVIDENCE_LIMITS.factValue))) return invalid(`facts.${key}`)
    }
  }
  return { ok: true, value: { workflowId: input.workflowId, stepId: input.stepId, kind: input.kind, ...(input.outcome ? { outcome: input.outcome } : {}), ...(input.kind === 'artifact' ? { artifactRef: input.artifactRef, artifactHash: input.artifactHash } : { facts: { ...input.facts } }), ...(input.idempotencyKey ? { idempotencyKey: input.idempotencyKey } : {}) } }
}

export function createWorkflowEvidence(validated, namespaceId, source, observedAt = new Date().toISOString(), evidenceId = randomUUID()) {
  const { idempotencyKey: _idempotencyKey, ...facts } = validated
  return Object.freeze({ evidenceId, namespaceId, ...facts, source: Object.freeze({ ...source }), observedAt })
}
