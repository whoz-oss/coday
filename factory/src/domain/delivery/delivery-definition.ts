/**
 * Pure delivery-definition domain: schema vocabulary, the five governed
 * delivery stages, evidence kinds, structural validation and content hashing.
 *
 * A delivery definition fixes the ordered checkpoints a work unit must cross
 * between `implementation-ready` and `production-verified`, the actor
 * responsible for each checkpoint and the evidence each checkpoint requires.
 *
 * Authority: this TypeScript source is bundled into
 * `factory/runtime/factory-operational.mjs`; `factory/lib/delivery-definition.mjs`
 * is a stateless compatibility facade re-exporting from that bundle.
 *
 * Domain purity: only `node:crypto` is used (content hashing); no `node:fs`,
 * HTTP, AgentOS or Git CLI dependency.
 */

import { createHash } from 'node:crypto'

/** Schema version of the delivery-definition document. */
export const DELIVERY_DEFINITION_SCHEMA_VERSION = '1'

/** The five governed delivery stages, in strict promotion order. */
export const DELIVERY_STAGES = Object.freeze([
  'implementation-ready',
  'artifact-ready',
  'release-approved',
  'deployed',
  'production-verified',
] as const)

/** One of the five governed delivery stages. */
export type DeliveryStage = (typeof DELIVERY_STAGES)[number]

/** Evidence kinds a checkpoint may require. */
export const DELIVERY_EVIDENCE_KINDS = Object.freeze([
  'implementation-result',
  'artifact',
  'oracle-result',
  'human-decision',
  'deployment-result',
  'smoke-result',
  'rollback-result',
] as const)

/** One of the evidence kinds a checkpoint may require. */
export type DeliveryEvidenceKind = (typeof DELIVERY_EVIDENCE_KINDS)[number]

/** Actor responsible for clearing a checkpoint. */
export interface DeliveryResponsibility {
  kind: 'code' | 'human'
  name: string
}

/** One evidence requirement attached to a checkpoint. */
export interface DeliveryRequiredEvidence {
  kind: string
  outcome: string
  oracleId?: string
}

/** A single ordered checkpoint of the delivery definition. */
export interface DeliveryCheckpoint {
  stage: string
  responsibility: DeliveryResponsibility
  requiredEvidence: DeliveryRequiredEvidence[]
}

/** A validated delivery definition in canonical (key-sorted) form. */
export interface DeliveryDefinition {
  schemaVersion: string
  deliveryType: string
  version: string
  title: string
  checkpoints: DeliveryCheckpoint[]
  artifactPolicy: Record<string, unknown>
  promotionPolicy: Record<string, unknown>
  deploymentPolicy: Record<string, unknown>
  retentionPolicy: Record<string, unknown>
}

/** Validation failure carrying the machine code, the JSON path and a reason. */
export interface DeliveryDefinitionFailure {
  ok: false
  error: { code: 'INVALID_DELIVERY_DEFINITION'; path: string; reason: string }
}

/** Result of validating a delivery definition. */
export type DeliveryDefinitionValidation = { ok: true; definition: DeliveryDefinition } | DeliveryDefinitionFailure

const SAFE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/
const SEMVER = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/
const TOP = new Set([
  'schemaVersion',
  'deliveryType',
  'version',
  'title',
  'checkpoints',
  'artifactPolicy',
  'promotionPolicy',
  'deploymentPolicy',
  'retentionPolicy',
])
const CHECKPOINT = new Set(['stage', 'responsibility', 'requiredEvidence'])
const RESPONSIBILITY = new Set(['kind', 'name'])
const EVIDENCE = new Set(['kind', 'outcome', 'oracleId'])
const fail = (path: string, reason = 'invalid_value'): DeliveryDefinitionFailure => ({
  ok: false,
  error: { code: 'INVALID_DELIVERY_DEFINITION', path, reason },
})

/**
 * Canonical JSON shape: object keys sorted recursively, arrays preserved in
 * order. Two definitions that differ only by key order hash identically.
 */
const canonical = (value: unknown): unknown =>
  Array.isArray(value)
    ? value.map(canonical)
    : value && typeof value === 'object'
      ? Object.fromEntries(
          Object.keys(value)
            .sort()
            .map((key) => [key, canonical((value as Record<string, unknown>)[key])])
        )
      : value

/** Validates a raw delivery definition, returning its canonical form on success. */
export function validateDeliveryDefinition(input: unknown): DeliveryDefinitionValidation {
  if (!input || typeof input !== 'object' || Array.isArray(input) || Object.keys(input).some((key) => !TOP.has(key)))
    return fail('$')
  const candidate = input as Record<string, unknown>
  if (candidate.schemaVersion !== DELIVERY_DEFINITION_SCHEMA_VERSION) return fail('schemaVersion')
  if (
    !SAFE.test((candidate.deliveryType ?? '') as string) ||
    !SEMVER.test((candidate.version ?? '') as string) ||
    typeof candidate.title !== 'string' ||
    !candidate.title.trim() ||
    candidate.title.length > 256
  )
    return fail('$')
  if (!Array.isArray(candidate.checkpoints) || candidate.checkpoints.length !== DELIVERY_STAGES.length)
    return fail('checkpoints')
  const checkpoints: DeliveryCheckpoint[] = []
  for (let index = 0; index < candidate.checkpoints.length; index++) {
    const raw = candidate.checkpoints[index] as Record<string, unknown>
    if (
      !raw ||
      typeof raw !== 'object' ||
      Array.isArray(raw) ||
      Object.keys(raw).some((key) => !CHECKPOINT.has(key)) ||
      raw.stage !== DELIVERY_STAGES[index]
    )
      return fail(`checkpoints[${index}]`)
    const responsibility = raw.responsibility as Record<string, unknown>
    if (
      !responsibility ||
      Object.keys(responsibility).some((key) => !RESPONSIBILITY.has(key)) ||
      !['code', 'human'].includes(responsibility.kind as string) ||
      !SAFE.test((responsibility.name ?? '') as string)
    )
      return fail(`checkpoints[${index}].responsibility`)
    if (raw.stage === 'release-approved' && responsibility.kind !== 'human')
      return fail(`checkpoints[${index}].responsibility`, 'release_requires_human')
    if (raw.stage !== 'release-approved' && responsibility.kind !== 'code')
      return fail(`checkpoints[${index}].responsibility`, 'factory_code_required')
    if (!Array.isArray(raw.requiredEvidence) || raw.requiredEvidence.length === 0 || raw.requiredEvidence.length > 16)
      return fail(`checkpoints[${index}].requiredEvidence`)
    const requiredEvidence: DeliveryRequiredEvidence[] = []
    for (let evidenceIndex = 0; evidenceIndex < raw.requiredEvidence.length; evidenceIndex++) {
      const item = raw.requiredEvidence[evidenceIndex] as Record<string, unknown>
      if (
        !item ||
        typeof item !== 'object' ||
        Array.isArray(item) ||
        Object.keys(item).some((key) => !EVIDENCE.has(key)) ||
        !(DELIVERY_EVIDENCE_KINDS as readonly string[]).includes(item.kind as string) ||
        !['pass', 'fail', 'indeterminate', 'approved', 'rejected'].includes(item.outcome as string)
      )
        return fail(`checkpoints[${index}].requiredEvidence[${evidenceIndex}]`)
      if (item.oracleId !== undefined && !SAFE.test(item.oracleId as string))
        return fail(`checkpoints[${index}].requiredEvidence[${evidenceIndex}].oracleId`)
      requiredEvidence.push({
        kind: item.kind as string,
        outcome: item.outcome as string,
        ...(item.oracleId ? { oracleId: item.oracleId as string } : {}),
      })
    }
    checkpoints.push({
      stage: raw.stage as string,
      responsibility: { ...(responsibility as unknown as DeliveryResponsibility) },
      requiredEvidence,
    })
  }
  for (const [field, maximum] of [
    ['artifactPolicy', 32],
    ['promotionPolicy', 32],
    ['deploymentPolicy', 32],
    ['retentionPolicy', 16],
  ] as Array<[string, number]>) {
    const value = candidate[field]
    if (
      !value ||
      typeof value !== 'object' ||
      Array.isArray(value) ||
      Object.keys(value).length === 0 ||
      Object.keys(value).length > maximum
    )
      return fail(field)
  }
  return { ok: true, definition: canonical({ ...candidate, checkpoints }) as DeliveryDefinition }
}

/** Stable SHA-256 hash of a definition's canonical JSON form. */
export function hashDeliveryDefinition(definition: unknown): string {
  return createHash('sha256')
    .update(JSON.stringify(canonical(definition)))
    .digest('hex')
}

/** The default governed Factory delivery definition. */
export function defaultDeliveryDefinition(): DeliveryDefinition {
  return {
    schemaVersion: '1',
    deliveryType: 'factory-delivery',
    version: '1.0.0',
    title: 'Governed Factory delivery',
    checkpoints: [
      {
        stage: 'implementation-ready',
        responsibility: { kind: 'code', name: 'implementation-policy' },
        requiredEvidence: [{ kind: 'implementation-result', outcome: 'pass' }],
      },
      {
        stage: 'artifact-ready',
        responsibility: { kind: 'code', name: 'artifact-oracle' },
        requiredEvidence: [
          { kind: 'artifact', outcome: 'pass' },
          { kind: 'oracle-result', outcome: 'pass' },
        ],
      },
      {
        stage: 'release-approved',
        responsibility: { kind: 'human', name: 'release-approver' },
        requiredEvidence: [{ kind: 'human-decision', outcome: 'approved' }],
      },
      {
        stage: 'deployed',
        responsibility: { kind: 'code', name: 'deployment-control-plane' },
        requiredEvidence: [{ kind: 'deployment-result', outcome: 'pass' }],
      },
      {
        stage: 'production-verified',
        responsibility: { kind: 'code', name: 'production-smoke' },
        requiredEvidence: [{ kind: 'smoke-result', outcome: 'pass' }],
      },
    ],
    artifactPolicy: { requireBuildAndTests: true, extensibleChecks: 'sast sca secrets sbom signature provenance' },
    promotionPolicy: { ordered: true, automaticMerge: false, requireFactoryEvidence: true },
    deploymentPolicy: { environmentsFromTrustedConfiguration: true, requireRollbackCapability: true },
    retentionPolicy: { deleteWorktreeBeforeProductionVerified: false },
  }
}
