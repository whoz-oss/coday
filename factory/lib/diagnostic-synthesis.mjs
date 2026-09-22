/**
 * Read-only interpretation of bounded deterministic oracle diagnostics.
 * This module never executes an oracle and never changes its verdict.
 */
import { createHash } from 'node:crypto'
import { mkdirSync, openSync, writeSync, closeSync } from 'node:fs'
import { O_WRONLY, O_CREAT, O_EXCL } from 'node:constants'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { extractJsonFragment } from './plan.mjs'
import { preflightReadOnlyAgent } from './review-engine.mjs'
import { registerActiveCase, unregisterActiveCase } from './active-case.mjs'

const __dirname = dirname(fileURLToPath(import.meta.url))
const RUNS_DIR = join(__dirname, '..', 'runs')
export const SYNTHESIS_STATUSES = new Set(['actionable', 'ambiguous', 'insufficient-evidence'])
const MAX_DIAGNOSTICS = 20
const MAX_FILES = 20
const MAX_SUMMARY_LENGTH = 600
const MAX_REASON_LENGTH = 600
const MAX_FIELD_LENGTH = 600
const MAX_FILE_PATH_LENGTH = 300
const MAX_ORACLE_NAME_LENGTH = 128
const MAX_COMMAND_LENGTH = 512
const MAX_CWD_LENGTH = 512
const MAX_IDENTITY_LENGTH = 512
const MAX_DIAGNOSTIC_LINE_LENGTH = 1_000
const MAX_TOTAL_PACKET_JSON_LENGTH = 32_000
const DEFAULT_TIMEOUT_MS = 5 * 60 * 1000

/** Artifact schema version. */
const ARTIFACT_SCHEMA_VERSION = 1

/**
 * Validate a machine-safe filename token: no path separators, traversal, or control chars.
 * Allows alphanumeric, hyphens, underscores, dots.
 *
 * @param {unknown} value
 * @returns {value is string}
 */
function isSafeFilenameToken(value) {
  return (
    typeof value === 'string' &&
    value.length > 0 &&
    value.length <= 128 &&
    !/[/\\\x00-\x1f]/.test(value) &&
    !value.includes('..') &&
    !/[^A-Za-z0-9._-]/.test(value)
  )
}

/**
 * Truncate a string to a maximum length, returning the truncated value.
 * @param {unknown} value
 * @param {number} max
 * @returns {string}
 */
function bounded(value, max) {
  if (typeof value !== 'string') return ''
  return value.length <= max ? value : value.slice(0, max)
}

/**
 * Clamp a numeric value to a finite non-negative integer.
 * @param {unknown} value
 * @returns {number}
 */
function clampFiniteNonNegativeInt(value) {
  const n = Number(value)
  if (!Number.isFinite(n) || n < 0) return 0
  return Math.floor(n)
}

/** Build the only evidence made available to the synthesizer. */
export function buildDiagnosticPacket({
  oracle,
  baseline,
  postEdit,
  classificationResult,
  plannedFiles,
  changedFiles,
}) {
  const limit = (values, max, maxLen = MAX_IDENTITY_LENGTH) =>
    [...new Set((values ?? []).filter((v) => typeof v === 'string' && v.length > 0))]
      .slice(0, max)
      .map((v) => bounded(v, maxLen))

  const packet = {
    schemaVersion: 1,
    oracle: {
      name: bounded(oracle.name, MAX_ORACLE_NAME_LENGTH),
      command: bounded(postEdit.command ?? oracle.command ?? '', MAX_COMMAND_LENGTH),
      cwd: bounded(oracle.cwd ?? '', MAX_CWD_LENGTH),
      exitCode: clampFiniteNonNegativeInt(postEdit.exitCode),
      durationMs: clampFiniteNonNegativeInt(postEdit.durationMs),
      timedOut: Boolean(postEdit.timedOut),
      emptySuccess: Boolean(postEdit.emptySuccess),
      tasks: {
        executed: clampFiniteNonNegativeInt(postEdit.tasks?.executed),
        fromCache: clampFiniteNonNegativeInt(postEdit.tasks?.fromCache),
        upToDate: clampFiniteNonNegativeInt(postEdit.tasks?.upToDate),
        skipped: clampFiniteNonNegativeInt(postEdit.tasks?.skipped),
      },
    },
    classification: bounded(classificationResult.classification, 64),
    baseline: baseline
      ? {
          exitCode: clampFiniteNonNegativeInt(baseline.exitCode),
          timedOut: Boolean(baseline.timedOut),
          emptySuccess: Boolean(baseline.emptySuccess),
          durationMs: clampFiniteNonNegativeInt(baseline.durationMs),
          diagnosticIdentities: limit(baseline.diagnosticIdentities, MAX_DIAGNOSTICS),
        }
      : null,
    postEdit: {
      diagnosticIdentities: limit(classificationResult.postEditIdentities, MAX_DIAGNOSTICS),
      newDiagnostics: limit(classificationResult.newDiagnostics, MAX_DIAGNOSTICS),
      preExistingDiagnostics: limit(classificationResult.preExistingDiagnostics, MAX_DIAGNOSTICS),
      diagnosticExcerpts: limit(classificationResult.newDiagnosticLines, MAX_DIAGNOSTICS, MAX_DIAGNOSTIC_LINE_LENGTH),
    },
    files: {
      planned: limit(plannedFiles, MAX_FILES, MAX_FILE_PATH_LENGTH),
      modified: limit(changedFiles, MAX_FILES, MAX_FILE_PATH_LENGTH),
    },
  }

  // Enforce total packet size
  const serialized = JSON.stringify(packet)
  if (serialized.length > MAX_TOTAL_PACKET_JSON_LENGTH) {
    // Reduce aggressively: trim excerpts first, then diagnostics
    packet.postEdit.diagnosticExcerpts = []
    const serialized2 = JSON.stringify(packet)
    if (serialized2.length > MAX_TOTAL_PACKET_JSON_LENGTH) {
      packet.postEdit.newDiagnostics = []
      packet.postEdit.preExistingDiagnostics = []
    }
  }

  return packet
}

/** Only indeterminate failures with actual evidence need interpretation. */
export function routeDiagnosticSynthesis(synthesis) {
  if (synthesis?.status === 'actionable') return 'editor'
  if (synthesis?.status === 'ambiguous' || synthesis?.status === 'insufficient-evidence') return 'human-gate'
  return 'none'
}

export function shouldSynthesizeDiagnostics({ classificationResult, postEdit }) {
  if (!classificationResult || postEdit?.timedOut || postEdit?.emptySuccess) return false
  if (classificationResult.classification === 'PRODUCT_REGRESSION') {
    return (classificationResult.newDiagnosticLines?.length ?? 0) === 0
  }
  return classificationResult.classification === 'INDETERMINATE_OUT_OF_SCOPE'
}

export function parseDiagnosticSynthesis(raw) {
  const fragment = extractJsonFragment(raw ?? '')
  if (!fragment) return { ok: false, errorCode: 'SYNTHESIS_NO_JSON' }
  let value
  try {
    value = JSON.parse(fragment)
  } catch {
    return { ok: false, errorCode: 'SYNTHESIS_INVALID_JSON' }
  }
  if (!value || !SYNTHESIS_STATUSES.has(value.status)) {
    return { ok: false, errorCode: 'SYNTHESIS_INVALID_RESULT' }
  }
  if (typeof value.summary !== 'string' || value.summary.length > MAX_SUMMARY_LENGTH) {
    return { ok: false, errorCode: 'SYNTHESIS_INVALID_RESULT' }
  }
  if (value.reason !== undefined && (typeof value.reason !== 'string' || value.reason.length > MAX_REASON_LENGTH)) {
    return { ok: false, errorCode: 'SYNTHESIS_INVALID_RESULT' }
  }
  const candidates = Array.isArray(value.diagnostics) ? value.diagnostics : []
  const files = Array.isArray(value.files) ? value.files : []
  if (
    candidates.length > MAX_DIAGNOSTICS ||
    files.length > MAX_FILES ||
    !candidates.every(validCandidate) ||
    !files.every((f) => typeof f === 'string' && f.length > 0 && f.length <= MAX_FILE_PATH_LENGTH)
  ) {
    return { ok: false, errorCode: 'SYNTHESIS_INVALID_RESULT' }
  }
  // Require actionable to have usable evidence
  if (value.status === 'actionable') {
    const hasEvidence = value.summary.trim().length > 0 && (candidates.length > 0 || files.length > 0)
    if (!hasEvidence) {
      return { ok: false, errorCode: 'SYNTHESIS_INSUFFICIENT_EVIDENCE' }
    }
  }
  return {
    ok: true,
    value: {
      status: value.status,
      summary: value.summary,
      reason: value.reason ?? null,
      diagnostics: candidates,
      files,
    },
  }
}

function validCandidate(candidate) {
  return (
    candidate &&
    typeof candidate === 'object' &&
    typeof candidate.diagnostic === 'string' &&
    candidate.diagnostic.length > 0 &&
    candidate.diagnostic.length <= MAX_FIELD_LENGTH &&
    typeof candidate.evidence === 'string' &&
    candidate.evidence.length <= MAX_FIELD_LENGTH &&
    typeof candidate.provenance === 'string' &&
    candidate.provenance.length <= 120
  )
}

export function buildDiagnosticSynthesisBrief(packet) {
  return [
    'You are a read-only diagnostic synthesizer. Interpret ONLY the JSON evidence below.',
    'You cannot write files, run commands, run oracles, or change any verdict. A failed oracle remains failed.',
    'Do not invent evidence. Return ONLY JSON:',
    '{"status":"actionable|ambiguous|insufficient-evidence","summary":"<=600 chars","reason":"optional <=600 chars","diagnostics":[{"diagnostic":"...","evidence":"...","provenance":"baseline|post-edit|classification"}],"files":["relative/path"]}',
    'Use actionable only when the bounded evidence supports a concrete editor next step AND you provide at least one diagnostic or file.',
    '```json',
    JSON.stringify(packet),
    '```',
  ].join('\n')
}

/**
 * @typedef {{
 *   ok: boolean,
 *   errorCode: string|null,
 *   rawOutput: string|null,
 *   synthesis: object|null,
 *   caseId: string|null,
 *   agentIdentity: string|null,
 *   turnStatus: string|null,
 *   killedByBudget: boolean,
 * }} SynthesisRunResult
 */

export async function runDiagnosticSynthesis({
  namespaceId,
  agentName,
  packet,
  agentOps,
  timeoutMs = DEFAULT_TIMEOUT_MS,
}) {
  // Preflight is wrapped in try/catch so any network/API exception also returns
  // a structured result rather than an unhandled rejection.
  let preflight
  try {
    preflight = await preflightReadOnlyAgent(namespaceId, agentName, agentOps, `diagnostic-synthesis:${agentName}`)
  } catch {
    return {
      ok: false,
      errorCode: 'SYNTHESIS_PREFLIGHT_ERROR',
      rawOutput: null,
      synthesis: null,
      caseId: null,
      agentIdentity: agentName,
      turnStatus: null,
      killedByBudget: false,
    }
  }
  if (!preflight.ok) {
    return {
      ok: false,
      errorCode: 'SYNTHESIS_PREFLIGHT_FAILED',
      rawOutput: null,
      synthesis: null,
      caseId: null,
      agentIdentity: agentName,
      turnStatus: null,
      killedByBudget: false,
    }
  }
  let caseId = null
  try {
    const created = await agentOps.createCase(namespaceId, 'diagnostic-synthesis')
    caseId = created.id
    registerActiveCase(caseId, 'diagnostic-synthesis')
    const turn = await agentOps.runAgentTurn(caseId, agentName, buildDiagnosticSynthesisBrief(packet), {
      startTimeoutMs: 30_000,
      workTimeoutMs: timeoutMs,
    })
    const turnStatus = turn.status
    const killedByBudget = Boolean(turn.killedByBudget)
    if (turn.status !== 'finished') {
      return {
        ok: false,
        errorCode: 'SYNTHESIS_AGENT_' + String(turn.status).toUpperCase(),
        rawOutput: null,
        synthesis: null,
        caseId,
        agentIdentity: agentName,
        turnStatus,
        killedByBudget,
      }
    }
    const parsed = parseDiagnosticSynthesis(turn.message)
    return parsed.ok
      ? {
          ok: true,
          errorCode: null,
          rawOutput: turn.message,
          synthesis: parsed.value,
          caseId,
          agentIdentity: agentName,
          turnStatus,
          killedByBudget,
        }
      : {
          ok: false,
          errorCode: parsed.errorCode,
          rawOutput: turn.message,
          synthesis: null,
          caseId,
          agentIdentity: agentName,
          turnStatus,
          killedByBudget,
        }
  } catch {
    return {
      ok: false,
      errorCode: 'SYNTHESIS_AGENT_ERROR',
      rawOutput: null,
      synthesis: null,
      caseId,
      agentIdentity: agentName,
      turnStatus: null,
      killedByBudget: false,
    }
  } finally {
    if (caseId) unregisterActiveCase(caseId)
  }
}

/**
 * Persist LLM prose outside JSONL in a structured JSON envelope.
 * Uses exclusive creation to prevent overwriting prior evidence.
 * Callers record only the returned structured reference.
 *
 * @param {string} runId  Must be a safe filename token
 * @param {string} oracleName  Must be a safe filename token
 * @param {number} revision  Non-negative integer
 * @param {number} attempt  Non-negative integer
 * @param {string} rawOutput  Arbitrary model text, stored as bounded string
 * @returns {{ artifactPath: string, artifactHash: string, artifactRef: string }|{ error: string }}
 */
export function writeDiagnosticSynthesisArtifact(runId, oracleName, revision, attempt, rawOutput) {
  if (!isSafeFilenameToken(runId)) {
    return { error: 'UNSAFE_ARTIFACT_COMPONENT:runId' }
  }
  if (!isSafeFilenameToken(oracleName)) {
    return { error: 'UNSAFE_ARTIFACT_COMPONENT:oracleName' }
  }
  const rev = clampFiniteNonNegativeInt(revision)
  const att = clampFiniteNonNegativeInt(attempt)
  const filename = `${runId}.diagnostic-synthesis-${oracleName}-${rev}-${att}.json`
  const path = join(RUNS_DIR, filename)
  const content = bounded(String(rawOutput ?? ''), 256_000)
  const hash = createHash('sha256').update(content).digest('hex')
  // Build structured envelope
  const envelope = JSON.stringify({
    schemaVersion: ARTIFACT_SCHEMA_VERSION,
    kind: 'diagnostic-synthesis',
    runId,
    oracleName,
    revision: rev,
    attempt: att,
    rawOutput: content,
    sha256: hash,
    writtenAt: new Date().toISOString(),
  })
  mkdirSync(RUNS_DIR, { recursive: true })
  // Exclusive creation: fail if artifact already exists
  let fd
  try {
    fd = openSync(path, O_WRONLY | O_CREAT | O_EXCL)
  } catch (err) {
    if (err?.code === 'EEXIST') {
      return { error: 'ARTIFACT_COLLISION' }
    }
    return { error: `ARTIFACT_WRITE_ERROR:${err?.code ?? 'UNKNOWN'}` }
  }
  try {
    writeSync(fd, envelope, 0, 'utf8')
  } finally {
    closeSync(fd)
  }
  const artifactRef = `${runId}.ds-${oracleName}-${rev}-${att}`
  return {
    artifactPath: `factory/runs/${filename}`,
    artifactHash: hash,
    artifactRef,
  }
}
