import { createHash, timingSafeEqual } from 'node:crypto'

/**
 * Pure agent-step-result domain: the structured business result schema, its
 * validation, capability-issued / result-submitted record shapes, canonical
 * hashing and constant-time token comparison.
 *
 * Authority: this TypeScript source is bundled into
 * `factory/runtime/factory-operational.mjs`; `factory/lib/agent-step-result-store.mjs`
 * is a stateless compatibility facade re-exporting from that bundle.
 *
 * Domain purity: only `node:crypto` is allowed; no `node:fs`, HTTP client,
 * AgentOS or Git CLI.
 */

export const AGENT_STEP_RESULT_STATUSES = Object.freeze(['PASS', 'FAIL'] as const)

export type AgentStepResultStatus = (typeof AGENT_STEP_RESULT_STATUSES)[number]

/** Bounded limits of the structured business result schema. */
export const AGENT_STEP_RESULT_LIMITS = Object.freeze({
  summary: 2000,
  modifiedFiles: 1000,
  modifiedFileLength: 1024,
  artifacts: 8,
  artifactKind: 128,
  artifactContentBytes: 262144,
  findings: 100,
  findingCode: 128,
  findingSummary: 1000,
  findingFile: 1024,
})

export type AgentStepResultFindingSeverity = 'info' | 'warning' | 'error' | 'blocking'

export interface AgentStepResultArtifact {
  kind: string
  encoding: 'markdown'
  content: string
  [key: string]: unknown
}

export interface AgentStepResultClaim {
  modifiedFiles: string[]
}

export interface AgentStepResultFinding {
  severity: AgentStepResultFindingSeverity
  code: string
  summary: string
  file?: string
  line?: number
  [key: string]: unknown
}

/** The authoritative business result a worker submits for a step attempt. */
export interface AgentStepResultBusiness {
  status: AgentStepResultStatus
  summary: string
  claims: AgentStepResultClaim
  artifacts?: AgentStepResultArtifact[]
  findings?: AgentStepResultFinding[]
}

/** Identity a result store issues a submission capability for. */
export interface AgentStepResultCapabilityIdentity {
  attemptId: string
  workflowId: string
  stepId: string
  namespaceId: string
  caseId: string
  agentName: string
  briefHash: string
}

/** The identity a submission declares, checked against the issued capability. */
export interface AgentStepResultObservedIdentity {
  attemptId: string
  caseId: string
  agentName: string
}

/** A capability-issued ledger event. */
export interface AgentStepResultCapabilityIssued extends AgentStepResultCapabilityIdentity {
  type: 'capability-issued'
  capabilityId: string
  tokenHash: string
  issuedAt: string
  expiresAt: string
  submissionBudget: number
}

/** A result-submitted ledger event. */
export interface AgentStepResultSubmitted {
  type: 'result-submitted'
  resultId: string
  attemptId: string
  workflowId: string
  stepId: string
  namespaceId: string
  caseId: string
  agentName: string
  briefHash: string
  status: AgentStepResultStatus
  summary: string
  artifacts: AgentStepResultArtifact[]
  claims: AgentStepResultClaim
  findings: AgentStepResultFinding[]
  submittedAt: string
  resultHash: string
}

export type AgentStepResultLedgerEvent = AgentStepResultCapabilityIssued | AgentStepResultSubmitted

const SAFE_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/

const BUSINESS_FIELDS = new Set(['status', 'summary', 'artifacts', 'claims', 'findings'])
const ARTIFACT_FIELDS = new Set(['kind', 'encoding', 'content'])
const FINDING_FIELDS = new Set(['severity', 'code', 'summary', 'file', 'line'])
const FINDING_SEVERITIES = new Set(['info', 'warning', 'error', 'blocking'])

/** True when `value` matches the factory safe-identifier grammar. */
export function isSafeAgentStepResultId(value: unknown): boolean {
  return SAFE_ID.test(String(value ?? ''))
}

/** `sha256:<hex>` digest of a string or byte content. */
export function sha256(value: string | Uint8Array): string {
  return `sha256:${createHash('sha256').update(value).digest('hex')}`
}

/** Recursively sorts object keys so structurally equal values serialize identically. */
export function canonicalizeAgentStepResult(value: unknown): unknown {
  if (Array.isArray(value)) return value.map((entry) => canonicalizeAgentStepResult(entry))
  if (value !== null && typeof value === 'object') {
    const record = value as Record<string, unknown>
    return Object.fromEntries(
      Object.keys(record)
        .sort()
        .map((key) => [key, canonicalizeAgentStepResult(record[key])])
    )
  }
  return value
}

/** Canonical JSON string of `value` (sorted keys, no whitespace surprises). */
export function canonicalAgentStepResultJson(value: unknown): string {
  return JSON.stringify(canonicalizeAgentStepResult(value))
}

/** `sha256` digest of the canonical JSON form of `value`. */
export function hashAgentStepResult(value: unknown): string {
  return sha256(canonicalAgentStepResultJson(value))
}

/** `sha256` digest of a structured agent result (canonical JSON form). */
export function hashStructuredAgentResult(value: unknown): string {
  return sha256(canonicalAgentStepResultJson(value))
}

/** `sha256` digest of an agent brief. */
export function hashAgentBrief(brief: string): string {
  return sha256(brief)
}

/** Constant-time comparison of two strings, false on any mismatch or error. */
export function safeEqual(a: string, b: string): boolean {
  try {
    const x = Buffer.from(a)
    const y = Buffer.from(b)
    return x.length === y.length && timingSafeEqual(x, y)
  } catch {
    return false
  }
}

/** Collision-free key of an attempt inside a namespace/store scope. */
export function agentStepAttemptKey(namespaceId: string, storageId: string, attemptId: string): string {
  return `${namespaceId}\0${storageId}\0${attemptId}`
}

/** Validates the structured business result schema exactly. */
export function validateAgentStepResultBusiness(value: unknown): value is AgentStepResultBusiness {
  if (
    !value ||
    typeof value !== 'object' ||
    Array.isArray(value) ||
    Object.keys(value).some((key) => !BUSINESS_FIELDS.has(key))
  )
    return false
  const record = value as Record<string, unknown>
  if (
    !['PASS', 'FAIL'].includes(record.status as string) ||
    typeof record.summary !== 'string' ||
    record.summary.length === 0 ||
    record.summary.length > AGENT_STEP_RESULT_LIMITS.summary
  )
    return false
  const claims = record.claims
  if (
    !claims ||
    typeof claims !== 'object' ||
    Array.isArray(claims) ||
    Object.keys(claims).some((key) => key !== 'modifiedFiles') ||
    !Array.isArray((claims as Record<string, unknown>).modifiedFiles) ||
    ((claims as Record<string, unknown>).modifiedFiles as unknown[]).length > AGENT_STEP_RESULT_LIMITS.modifiedFiles ||
    ((claims as Record<string, unknown>).modifiedFiles as unknown[]).some(
      (file) =>
        typeof file !== 'string' || file.length === 0 || file.length > AGENT_STEP_RESULT_LIMITS.modifiedFileLength
    )
  )
    return false
  const artifacts = record.artifacts
  if (
    artifacts !== undefined &&
    (!Array.isArray(artifacts) ||
      artifacts.length > AGENT_STEP_RESULT_LIMITS.artifacts ||
      artifacts.some(
        (artifact) =>
          !artifact ||
          typeof artifact !== 'object' ||
          Array.isArray(artifact) ||
          Object.keys(artifact).some((key) => !ARTIFACT_FIELDS.has(key)) ||
          typeof (artifact as Record<string, unknown>).kind !== 'string' ||
          ((artifact as Record<string, unknown>).kind as string).length === 0 ||
          ((artifact as Record<string, unknown>).kind as string).length > AGENT_STEP_RESULT_LIMITS.artifactKind ||
          (artifact as Record<string, unknown>).encoding !== 'markdown' ||
          typeof (artifact as Record<string, unknown>).content !== 'string' ||
          ((artifact as Record<string, unknown>).content as string).length === 0 ||
          Buffer.byteLength((artifact as Record<string, unknown>).content as string, 'utf8') >
            AGENT_STEP_RESULT_LIMITS.artifactContentBytes
      ))
  )
    return false
  const findings = record.findings
  if (
    findings !== undefined &&
    (!Array.isArray(findings) ||
      findings.length > AGENT_STEP_RESULT_LIMITS.findings ||
      findings.some((finding) => {
        if (!finding || typeof finding !== 'object' || Array.isArray(finding)) return true
        const entry = finding as Record<string, unknown>
        if (Object.keys(entry).some((key) => !FINDING_FIELDS.has(key))) return true
        if (!FINDING_SEVERITIES.has(entry.severity as string)) return true
        if (
          typeof entry.code !== 'string' ||
          entry.code.length === 0 ||
          entry.code.length > AGENT_STEP_RESULT_LIMITS.findingCode
        )
          return true
        if (
          typeof entry.summary !== 'string' ||
          entry.summary.length === 0 ||
          entry.summary.length > AGENT_STEP_RESULT_LIMITS.findingSummary
        )
          return true
        if (
          entry.file !== undefined &&
          (typeof entry.file !== 'string' ||
            entry.file.length === 0 ||
            entry.file.length > AGENT_STEP_RESULT_LIMITS.findingFile)
        )
          return true
        if (entry.line !== undefined && (!Number.isSafeInteger(entry.line) || (entry.line as number) < 1)) return true
        return false
      }))
  )
    return false
  return true
}
