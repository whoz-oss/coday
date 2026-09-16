/**
 * Run-scoped human review gate — in-process registry + IPC bridge.
 *
 * ## Architecture
 *
 * The workflow (us-loop.mjs) is a child process spawned by the dashboard server.
 * They share no memory. The bridge between them uses two channels:
 *
 *   1. stdout IPC signal  (workflow → server)
 *      When the workflow reaches the gate, it writes a JSON line on stdout.
 *      The server reads stdout line-by-line, validates the IPC secret and runId,
 *      then stores the gate in its in-process registry keyed by gateInstanceId.
 *
 *   2. Gate-instance reply file  (server → workflow)
 *      When the human POSTs a decision, the server writes:
 *        factory/runs/<runId>.<gateInstanceId>.gate-reply  (JSON)
 *      The workflow polls for this file every GATE_POLL_MS.
 *      No global singleton. Two concurrent runs use different files.
 *      A stale reply for a prior gate cannot be consumed by a later gate.
 *
 * ## Authentication
 *
 * Each child process receives a per-child random IPC secret in its environment
 * (FACTORY_GATE_IPC_SECRET). The server only accepts gate signals that carry
 * this exact secret and whose runId matches the tracked run. The secret is
 * never logged or exposed in any signal field other than the validation check.
 *
 * ## Gate instance identity
 *
 * Each gate opening generates a cryptographically random gateInstanceId.
 * Reply payloads must carry the exact gateInstanceId of the currently pending
 * gate. A duplicate POST, a stale reply, or a wrong-gate reply is rejected.
 *
 * ## Single-use semantics
 *
 * Once a reply is accepted, the gate is removed from the registry and its
 * reply file is consumed. A second POST returns 409 CONFLICT.
 *
 * ## No timeout
 *
 * waitForHumanDecision() waits indefinitely. It resolves only when:
 *   - The reply file appears (human decision), or
 *   - rejectAllPendingGates() is called (SIGTERM).
 */

import { writeFileSync, readFileSync, existsSync, unlinkSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { randomBytes } from 'node:crypto'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const RUNS_DIR = join(__dirname, '..', 'runs')

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Interval between reply-file polls in the workflow process. */
export const GATE_POLL_MS = 2_000

/** Known gate types and their allowed decisions (allow-list). */
const GATE_ALLOWED_DECISIONS = /** @type {const} */ ({
  'adversarial-review': ['retry', 'ignore', 'fail'],
  'oracle':             ['continue', 'fail'],
})

/** Maximum length for findings string in IPC signal. */
const MAX_FINDINGS_LENGTH = 8_000

/** Maximum number of outcomes in IPC signal. */
const MAX_OUTCOMES = 20

/** Maximum string length per outcome field. */
const MAX_OUTCOME_FIELD = 500

// ---------------------------------------------------------------------------
// Safe token validation
// ---------------------------------------------------------------------------

/**
 * Validate a machine-safe token: alphanumeric, hyphens, underscores, dots.
 * Used for runId, gateInstanceId, oracle name components.
 *
 * @param {unknown} value
 * @returns {value is string}
 */
function isSafeToken(value) {
  return typeof value === 'string' && value.length > 0 && value.length <= 128 && /^[A-Za-z0-9._-]+$/.test(value)
}

// ---------------------------------------------------------------------------
// In-process gate registry (used by dashboard server.mjs)
// ---------------------------------------------------------------------------

/**
 * @typedef {{
 *   runId: string,
 *   gateInstanceId: string,
 *   gateType: 'adversarial-review' | 'oracle',
 *   findings: string,
 *   outcomes: Array<{ reviewerName: string, verdict: string|null, hasCritical: boolean, summary: string|null }>,
 *   oracleGate?: object,
 *   allowedDecisions: string[],
 *   openedAt: string,
 * }} PendingGate
 */

/** @type {Map<string, PendingGate>} keyed by runId (one pending gate per run at a time) */
const _gateRegistry = new Map()

/**
 * Validate and register a pending gate from a trusted, authenticated IPC signal.
 * Called by dashboard server ONLY after verifying IPC secret and runId.
 *
 * Rejects unknown gate types, invalid gateInstanceId, or malformed payloads.
 *
 * @param {object} signal  Already-authenticated signal (secret stripped before this call)
 * @returns {{ ok: boolean, error?: string }}
 */
export function registerGate(signal) {
  const gateType = signal?.gateType
  if (!GATE_ALLOWED_DECISIONS[gateType]) {
    return { ok: false, error: `Unknown gate type: ${gateType}` }
  }
  if (!isSafeToken(signal?.gateInstanceId)) {
    return { ok: false, error: 'Invalid gateInstanceId' }
  }
  if (!isSafeToken(signal?.runId)) {
    return { ok: false, error: 'Invalid runId' }
  }
  // Validate findings
  const findings = typeof signal.findings === 'string'
    ? signal.findings.slice(0, MAX_FINDINGS_LENGTH)
    : ''
  // Validate outcomes
  const rawOutcomes = Array.isArray(signal.outcomes) ? signal.outcomes.slice(0, MAX_OUTCOMES) : []
  const outcomes = rawOutcomes.map((o) => ({
    reviewerName: typeof o?.reviewerName === 'string' ? o.reviewerName.slice(0, MAX_OUTCOME_FIELD) : '',
    verdict: typeof o?.verdict === 'string' ? o.verdict.slice(0, 32) : null,
    hasCritical: Boolean(o?.hasCritical),
    summary: typeof o?.summary === 'string' ? o.summary.slice(0, MAX_OUTCOME_FIELD) : null,
  }))
  // oracleGate: only structured machine fields (no prose)
  let oracleGate
  if (signal.oracleGate && typeof signal.oracleGate === 'object') {
    oracleGate = {
      oracleName: isSafeToken(signal.oracleGate.oracleName) ? signal.oracleGate.oracleName : null,
      classification: typeof signal.oracleGate.classification === 'string' ? signal.oracleGate.classification.slice(0, 64) : null,
      exitCode: Number.isFinite(Number(signal.oracleGate.exitCode)) ? Number(signal.oracleGate.exitCode) : null,
      artifactRef: isSafeToken(signal.oracleGate.artifactRef) ? signal.oracleGate.artifactRef : null,
      artifactHash: typeof signal.oracleGate.artifactHash === 'string' && /^[0-9a-f]{64}$/.test(signal.oracleGate.artifactHash)
        ? signal.oracleGate.artifactHash : null,
      newDiagnosticCount: Number.isFinite(Number(signal.oracleGate.newDiagnosticCount)) ? Number(signal.oracleGate.newDiagnosticCount) : 0,
      synthesisStatus: typeof signal.oracleGate.synthesisStatus === 'string' ? signal.oracleGate.synthesisStatus.slice(0, 32) : null,
    }
  }

  // Reject if a gate is already pending for this run (one-pending-gate-per-run invariant).
  // The workflow emits exactly one gate at a time per run; a second signal while one
  // is pending indicates a bug or injection attempt — fail-closed.
  if (_gateRegistry.has(signal.runId)) {
    return { ok: false, error: `Gate already pending for run ${signal.runId} — concurrent registration rejected` }
  }

  const gate = {
    runId: signal.runId,
    gateInstanceId: signal.gateInstanceId,
    gateType,
    findings,
    outcomes,
    ...(oracleGate ? { oracleGate } : {}),
    allowedDecisions: GATE_ALLOWED_DECISIONS[gateType],
    openedAt: new Date().toISOString(),
  }
  _gateRegistry.set(signal.runId, gate)
  return { ok: true }
}

/**
 * Remove a gate from the registry.
 *
 * @param {string} runId
 */
export function unregisterGate(runId) {
  _gateRegistry.delete(runId)
}

/**
 * Get the current pending gate for a run, or null.
 *
 * @param {string} runId
 * @returns {PendingGate|null}
 */
export function getGate(runId) {
  return _gateRegistry.get(runId) ?? null
}

/**
 * Write the human decision to the gate-instance reply file.
 * Validates the gateInstanceId against the current pending gate.
 * Single-use: removes the gate from the registry.
 *
 * @param {string} runId
 * @param {string} gateInstanceId  Must match currently pending gate
 * @param {string} decision
 * @param {string} [message]
 * @returns {{ ok: boolean, error?: string, status?: number }}
 */
export function writeGateReply(runId, gateInstanceId, decision, message = '') {
  const pending = _gateRegistry.get(runId)
  if (!pending) {
    return { ok: false, status: 404, error: 'No pending gate for this run' }
  }
  if (pending.gateInstanceId !== gateInstanceId) {
    return { ok: false, status: 409, error: 'Gate instance mismatch — stale or duplicate reply' }
  }
  if (!pending.allowedDecisions.includes(decision)) {
    return { ok: false, status: 400, error: `Decision "${decision}" not allowed for gate type "${pending.gateType}". Allowed: ${pending.allowedDecisions.join(', ')}` }
  }
  const replyPath = gateInstanceReplyPath(runId, gateInstanceId)
  try {
    const content = JSON.stringify({ decision, message: typeof message === 'string' ? message.trim() : '', gateInstanceId })
    writeFileSync(replyPath, content, 'utf8')
    unregisterGate(runId)
    return { ok: true }
  } catch (err) {
    return { ok: false, status: 500, error: String(err) }
  }
}

// ---------------------------------------------------------------------------
// Workflow-side helpers (used by us-loop.mjs)
// ---------------------------------------------------------------------------

/**
 * Path of the gate-instance reply file.
 * Written by the dashboard server, polled by the workflow.
 * Gate-instance-specific: a stale reply from a prior gate cannot be consumed.
 *
 * @param {string} runId
 * @param {string} gateInstanceId
 * @returns {string}
 */
export function gateInstanceReplyPath(runId, gateInstanceId) {
  return join(RUNS_DIR, `${runId}.${gateInstanceId}.gate-reply`)
}

/**
 * Kept for backward compatibility with any call sites that don't yet use
 * gate instance IDs. Prefer gateInstanceReplyPath.
 *
 * @deprecated Use gateInstanceReplyPath
 * @param {string} runId
 * @returns {string}
 */
export function gateReplyPath(runId) {
  return join(RUNS_DIR, `${runId}.gate-reply`)
}

/**
 * Generate a cryptographically strong gate instance ID.
 * Used by workflow side when opening a gate.
 *
 * @returns {string}
 */
export function generateGateInstanceId() {
  return randomBytes(16).toString('hex')
}

/**
 * Emit the adversarial-review gate-open signal on stdout.
 *
 * Carries only deterministic facts; no LLM prose in the IPC signal itself.
 * The server must validate the IPC secret and runId before registering.
 *
 * @param {string} runId
 * @param {string} gateInstanceId
 * @param {object} reviewResult  AdversarialReviewResult from adversarial-review.mjs
 */
export function emitGateOpen(runId, gateInstanceId, reviewResult) {
  const outcomes = (reviewResult.outcomes ?? []).slice(0, MAX_OUTCOMES).map((o) => ({
    reviewerName: typeof o.reviewerName === 'string' ? o.reviewerName.slice(0, MAX_OUTCOME_FIELD) : '',
    verdict: typeof o.verdict === 'string' ? o.verdict.slice(0, 32) : null,
    hasCritical: Boolean(o.hasCritical),
    // summary: omit LLM prose from IPC; server can fetch artifact if needed
    summary: null,
  }))

  const signal = {
    __factory_gate: 'open',
    runId,
    gateInstanceId,
    gateType: 'adversarial-review',
    findings: buildFindingsText(reviewResult).slice(0, MAX_FINDINGS_LENGTH),
    outcomes,
    allowedDecisions: GATE_ALLOWED_DECISIONS['adversarial-review'],
    openedAt: new Date().toISOString(),
  }

  // Secret is injected by the server into the child's environment.
  // It must be present in every signal so the server can authenticate.
  if (process.env.FACTORY_GATE_IPC_SECRET) {
    signal._ipcSecret = process.env.FACTORY_GATE_IPC_SECRET
  }

  process.stdout.write(JSON.stringify(signal) + '\n')
}

/**
 * Emit an oracle-failure gate-open signal on stdout.
 *
 * Carries only deterministic facts plus artifact reference/hash.
 * NO LLM prose in the IPC signal. The human-facing findings are built
 * server-side from the structured fields and fetched artifact.
 *
 * @param {string} runId
 * @param {string} gateInstanceId
 * @param {object} oracleInfo  Structured oracle facts
 */
export function emitOracleGateOpen(runId, gateInstanceId, oracleInfo) {
  // Build a compact, deterministic findings summary (no prose from LLM)
  const findingsLines = [
    `Oracle gate: ${oracleInfo.classification}`,
    `Oracle: ${oracleInfo.oracleName}`,
    `Classification: ${oracleInfo.classification}`,
    `New diagnostics: ${oracleInfo.newDiagnosticCount ?? 0}`,
    `Synthesis status: ${oracleInfo.synthesisStatus ?? 'none'}`,
    oracleInfo.artifactRef ? `Artifact: ${oracleInfo.artifactRef}` : null,
    '',
    'Continue = quarantine oracle and proceed. Fail = stop run.',
  ].filter(Boolean)

  const signal = {
    __factory_gate: 'open',
    runId,
    gateInstanceId,
    gateType: 'oracle',
    findings: findingsLines.join('\n').slice(0, MAX_FINDINGS_LENGTH),
    outcomes: [],
    // oracleGate: structured machine facts only
    oracleGate: {
      oracleName: isSafeToken(oracleInfo.oracleName) ? oracleInfo.oracleName : null,
      classification: typeof oracleInfo.classification === 'string' ? oracleInfo.classification.slice(0, 64) : null,
      exitCode: Number.isFinite(Number(oracleInfo.exitCode)) ? Number(oracleInfo.exitCode) : null,
      artifactRef: isSafeToken(oracleInfo.artifactRef) ? oracleInfo.artifactRef : null,
      artifactHash: typeof oracleInfo.artifactHash === 'string' && /^[0-9a-f]{64}$/.test(oracleInfo.artifactHash)
        ? oracleInfo.artifactHash : null,
      newDiagnosticCount: Number.isFinite(Number(oracleInfo.newDiagnosticCount)) ? Number(oracleInfo.newDiagnosticCount) : 0,
      synthesisStatus: typeof oracleInfo.synthesisStatus === 'string' ? oracleInfo.synthesisStatus.slice(0, 32) : null,
    },
    allowedDecisions: GATE_ALLOWED_DECISIONS['oracle'],
    openedAt: new Date().toISOString(),
  }

  if (process.env.FACTORY_GATE_IPC_SECRET) {
    signal._ipcSecret = process.env.FACTORY_GATE_IPC_SECRET
  }

  process.stdout.write(JSON.stringify(signal) + '\n')
}

/**
 * Wait for a human decision on the gate instance.
 *
 * Polls for factory/runs/<runId>.<gateInstanceId>.gate-reply every GATE_POLL_MS.
 * Validates the gateInstanceId in the reply to prevent stale consumption.
 * Returns as soon as the reply file exists or the process receives SIGTERM.
 * Never times out — waits indefinitely.
 *
 * @param {string} runId
 * @param {string} gateInstanceId
 * @param {object} log
 * @param {'adversarial-review'|'oracle'} [gateType]
 * @returns {Promise<{ decision: string, message: string }>}
 */
export async function waitForHumanDecision(runId, gateInstanceId, log, gateType = 'adversarial-review') {
  const replyPath = gateInstanceReplyPath(runId, gateInstanceId)
  const allowedDecisions = GATE_ALLOWED_DECISIONS[gateType] ?? ['fail']

  // Clean up any stale reply file for this gate instance.
  try { unlinkSync(replyPath) } catch { /* absent = ok */ }

  if (gateType === 'oracle') {
    log.error('\u25b6 HUMAN GATE: oracle failure \u2014 waiting for human decision.')
    log.error(`  Dashboard: GET /api/factory/runs/${runId}/review-gate`)
    log.error(`  Allowed decisions: ${allowedDecisions.join(' | ')}`)
    log.error('  No timeout \u2014 gate waits until human decision or SIGTERM.')
  } else {
    log.error('\u25b6 HUMAN GATE: adversarial review FAIL \u2014 waiting for human decision.')
    log.error(`  Dashboard: GET /api/factory/runs/${runId}/review-gate`)
    log.error(`  Allowed decisions: ${allowedDecisions.join(' | ')}`)
    log.error('  No timeout \u2014 gate waits until human decision or SIGTERM.')
  }

  return new Promise((resolve) => {
    _pendingResolvers.set(runId, resolve)

    const poll = setInterval(() => {
      if (!existsSync(replyPath)) return

      clearInterval(poll)
      _pendingResolvers.delete(runId)

      try {
        const raw = readFileSync(replyPath, 'utf8')
        const parsed = JSON.parse(raw)
        // Validate gate instance ID in reply
        if (parsed.gateInstanceId !== gateInstanceId) {
          log.error(`Gate instance mismatch in reply file (expected ${gateInstanceId}, got ${parsed.gateInstanceId}). Decision = fail.`)
          resolve({ decision: 'fail', message: '' })
          return
        }
        const decision = allowedDecisions.includes(parsed.decision) ? parsed.decision : 'fail'
        const message = typeof parsed.message === 'string' ? parsed.message : ''
        log.error(`Decision received: ${decision}${message ? ' (with message)' : ''}`)
        resolve({ decision, message })
      } catch (err) {
        log.error(`Error reading gate reply: ${err}. Decision = fail.`)
        resolve({ decision: 'fail', message: '' })
      } finally {
        try { unlinkSync(replyPath) } catch { /* ok */ }
      }
    }, GATE_POLL_MS)
  })
}

// ---------------------------------------------------------------------------
// Shutdown integration (called by shutdown.mjs on SIGTERM)
// ---------------------------------------------------------------------------

/**
 * Map of runId -> Promise resolver for pending waitForHumanDecision() calls.
 *
 * @type {Map<string, Function>}
 */
const _pendingResolvers = new Map()

/**
 * Resolve all pending gates to { decision: 'fail', message: '' }.
 * Called by shutdown.mjs on SIGTERM before process.exit().
 */
export function rejectAllPendingGates() {
  for (const [runId, resolve] of _pendingResolvers) {
    try {
      resolve({ decision: 'fail', message: '' })
    } catch { /* ignore */ }
    _pendingResolvers.delete(runId)
  }
}

/**
 * Returns true if there is a pending gate for the given runId.
 *
 * @param {string} runId
 * @returns {boolean}
 */
export function hasPendingGate(runId) {
  return _pendingResolvers.has(runId)
}

// ---------------------------------------------------------------------------
// IPC signal validation (used by dashboard server.mjs)
// ---------------------------------------------------------------------------

/**
 * Validate an IPC gate signal from a child process stdout line.
 * Must be called with the tracked runId and expected IPC secret for that child.
 *
 * Rejects signals that:
 *   - Don't carry the correct IPC secret
 *   - Have a runId that doesn't match the tracked run
 *   - Have an unknown gate type
 *   - Have an invalid gateInstanceId
 *   - Carry unexpected/oversized fields
 *
 * @param {object} signal  Parsed JSON from stdout
 * @param {string} trackedRunId  The runId this child process was started for
 * @param {string} ipcSecret  The per-child secret passed in the child's environment
 * @returns {{ ok: boolean, error?: string }}
 */
export function validateGateSignal(signal, trackedRunId, ipcSecret) {
  if (!signal || typeof signal !== 'object') {
    return { ok: false, error: 'Not an object' }
  }
  if (signal.__factory_gate !== 'open') {
    return { ok: false, error: 'Not a gate signal' }
  }
  // Authenticate: secret must match
  if (!ipcSecret || signal._ipcSecret !== ipcSecret) {
    return { ok: false, error: 'IPC secret mismatch — signal rejected' }
  }
  // runId must match tracked run
  if (signal.runId !== trackedRunId) {
    return { ok: false, error: `runId mismatch (expected ${trackedRunId}, got ${signal.runId})` }
  }
  // Gate type must be known
  if (!GATE_ALLOWED_DECISIONS[signal.gateType]) {
    return { ok: false, error: `Unknown gate type: ${signal.gateType}` }
  }
  // gateInstanceId must be a safe token
  if (!isSafeToken(signal.gateInstanceId)) {
    return { ok: false, error: 'Invalid gateInstanceId' }
  }
  return { ok: true }
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

/**
 * Build a human-readable findings text from the review result.
 * This is only for the adversarial-review gate and should not include LLM prose
 * from individual reviewer outputs in the IPC signal itself.
 *
 * @param {object} reviewResult
 * @returns {string}
 */
function buildFindingsText(reviewResult) {
  const lines = [
    `${reviewResult.failCount ?? '?'}/${reviewResult.reviewerCount ?? '?'} reviewer(s) returned FAIL`,
    '',
    'See artifact for full reviewer outputs.',
  ]
  return lines.join('\n')
}
