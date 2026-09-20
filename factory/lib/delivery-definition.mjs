import { createHash } from 'node:crypto'

export const DELIVERY_DEFINITION_SCHEMA_VERSION = '1'
export const DELIVERY_STAGES = Object.freeze(['implementation-ready', 'artifact-ready', 'release-approved', 'deployed', 'production-verified'])
export const DELIVERY_EVIDENCE_KINDS = Object.freeze(['implementation-result', 'artifact', 'oracle-result', 'human-decision', 'deployment-result', 'smoke-result', 'rollback-result'])
const SAFE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/
const SEMVER = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/
const TOP = new Set(['schemaVersion', 'deliveryType', 'version', 'title', 'checkpoints', 'artifactPolicy', 'promotionPolicy', 'deploymentPolicy', 'retentionPolicy'])
const CHECKPOINT = new Set(['stage', 'responsibility', 'requiredEvidence'])
const RESPONSIBILITY = new Set(['kind', 'name'])
const EVIDENCE = new Set(['kind', 'outcome', 'oracleId'])
const fail = (path, reason = 'invalid_value') => ({ ok: false, error: { code: 'INVALID_DELIVERY_DEFINITION', path, reason } })
const canonical = (value) => Array.isArray(value) ? value.map(canonical) : value && typeof value === 'object' ? Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])])) : value

export function validateDeliveryDefinition(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input) || Object.keys(input).some((key) => !TOP.has(key))) return fail('$')
  if (input.schemaVersion !== DELIVERY_DEFINITION_SCHEMA_VERSION) return fail('schemaVersion')
  if (!SAFE.test(input.deliveryType ?? '') || !SEMVER.test(input.version ?? '') || typeof input.title !== 'string' || !input.title.trim() || input.title.length > 256) return fail('$')
  if (!Array.isArray(input.checkpoints) || input.checkpoints.length !== DELIVERY_STAGES.length) return fail('checkpoints')
  const checkpoints = []
  for (let index = 0; index < input.checkpoints.length; index++) {
    const raw = input.checkpoints[index]
    if (!raw || typeof raw !== 'object' || Array.isArray(raw) || Object.keys(raw).some((key) => !CHECKPOINT.has(key)) || raw.stage !== DELIVERY_STAGES[index]) return fail(`checkpoints[${index}]`)
    if (!raw.responsibility || Object.keys(raw.responsibility).some((key) => !RESPONSIBILITY.has(key)) || !['code', 'human'].includes(raw.responsibility.kind) || !SAFE.test(raw.responsibility.name ?? '')) return fail(`checkpoints[${index}].responsibility`)
    if (raw.stage === 'release-approved' && raw.responsibility.kind !== 'human') return fail(`checkpoints[${index}].responsibility`, 'release_requires_human')
    if (raw.stage !== 'release-approved' && raw.responsibility.kind !== 'code') return fail(`checkpoints[${index}].responsibility`, 'factory_code_required')
    if (!Array.isArray(raw.requiredEvidence) || raw.requiredEvidence.length === 0 || raw.requiredEvidence.length > 16) return fail(`checkpoints[${index}].requiredEvidence`)
    const requiredEvidence = []
    for (let evidenceIndex = 0; evidenceIndex < raw.requiredEvidence.length; evidenceIndex++) {
      const item = raw.requiredEvidence[evidenceIndex]
      if (!item || typeof item !== 'object' || Array.isArray(item) || Object.keys(item).some((key) => !EVIDENCE.has(key)) || !DELIVERY_EVIDENCE_KINDS.includes(item.kind) || !['pass', 'fail', 'indeterminate', 'approved', 'rejected'].includes(item.outcome)) return fail(`checkpoints[${index}].requiredEvidence[${evidenceIndex}]`)
      if (item.oracleId !== undefined && !SAFE.test(item.oracleId)) return fail(`checkpoints[${index}].requiredEvidence[${evidenceIndex}].oracleId`)
      requiredEvidence.push({ kind: item.kind, outcome: item.outcome, ...(item.oracleId ? { oracleId: item.oracleId } : {}) })
    }
    checkpoints.push({ stage: raw.stage, responsibility: { ...raw.responsibility }, requiredEvidence })
  }
  for (const [field, maximum] of [['artifactPolicy', 32], ['promotionPolicy', 32], ['deploymentPolicy', 32], ['retentionPolicy', 16]]) {
    const value = input[field]
    if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).length === 0 || Object.keys(value).length > maximum) return fail(field)
  }
  return { ok: true, definition: canonical({ ...input, checkpoints }) }
}

export function hashDeliveryDefinition(definition) {
  return createHash('sha256').update(JSON.stringify(canonical(definition))).digest('hex')
}

export function defaultDeliveryDefinition() {
  return {
    schemaVersion: '1', deliveryType: 'factory-delivery', version: '1.0.0', title: 'Governed Factory delivery',
    checkpoints: [
      { stage: 'implementation-ready', responsibility: { kind: 'code', name: 'implementation-policy' }, requiredEvidence: [{ kind: 'implementation-result', outcome: 'pass' }] },
      { stage: 'artifact-ready', responsibility: { kind: 'code', name: 'artifact-oracle' }, requiredEvidence: [{ kind: 'artifact', outcome: 'pass' }, { kind: 'oracle-result', outcome: 'pass' }] },
      { stage: 'release-approved', responsibility: { kind: 'human', name: 'release-approver' }, requiredEvidence: [{ kind: 'human-decision', outcome: 'approved' }] },
      { stage: 'deployed', responsibility: { kind: 'code', name: 'deployment-control-plane' }, requiredEvidence: [{ kind: 'deployment-result', outcome: 'pass' }] },
      { stage: 'production-verified', responsibility: { kind: 'code', name: 'production-smoke' }, requiredEvidence: [{ kind: 'smoke-result', outcome: 'pass' }] },
    ],
    artifactPolicy: { requireBuildAndTests: true, extensibleChecks: 'sast sca secrets sbom signature provenance' },
    promotionPolicy: { ordered: true, automaticMerge: false, requireFactoryEvidence: true },
    deploymentPolicy: { environmentsFromTrustedConfiguration: true, requireRollbackCapability: true },
    retentionPolicy: { deleteWorktreeBeforeProductionVerified: false },
  }
}
