/**
 * Run-scoped human review gate — in-process registry + IPC bridge.
 *
 * ## Architecture
 *
 * The workflow (us-loop.mjs) is a child process spawned by the dashboard server.
 * They share no memory. The bridge between them uses two channels:
 *
 *   1. stdout IPC signal  (workflow → server)
 *      When the workflow reaches the gate, it writes a JSON line on stdout:
 *        {"__factory_gate":"open","runId":"…","findings":"…","outcomes":[…]}
 *      The server reads stdout line-by-line and stores the gate in its
 *      in-process registry keyed by runId.
 *
 *   2. Run-scoped reply file  (server → workflow)
 *      When the human POSTs a decision, the server writes:
 *        factory/runs/<runId>.gate-reply  (JSON: {decision, message})
 *      The workflow polls for this file every GATE_POLL_MS.
 *      No global singleton. Two concurrent runs use different files.
 *
 * ## No timeout
 *
 * waitForHumanDecision() waits indefinitely. It resolves only when:
 *   - The reply file appears (human decision), or
 *   - rejectAllPendingGates() is called (SIGTERM).
 *
 * ## Completed historical runs
 *
 * A terminated Node process cannot resume. Runs with humanDecision:fail in their
 * JSONL are permanently terminal. The GET endpoint returns { status:'terminal' }
 * for these. No fake resumption is attempted. Only future pending gates (from
 * live workflow processes) can receive decisions.
 */

import { writeFileSync, readFileSync, existsSync, unlinkSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const RUNS_DIR = join(__dirname, '..', 'runs')

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Interval between reply-file polls in the workflow process. */
export const GATE_POLL_MS = 2_000

// ---------------------------------------------------------------------------
// In-process gate registry (used by dashboard server.mjs)
// ---------------------------------------------------------------------------

/**
 * Gate type discriminator.
 *   'adversarial-review' — adversarial reviewer FAIL gate (existing behaviour)
 *   'oracle'             — deterministic oracle failure requiring human triage
 *
 * @typedef {{
 *   runId: string,
 *   gateType?: 'adversarial-review' | 'oracle',
 *   findings: string,
 *   outcomes: Array<{ reviewerName: string, verdict: string|null, hasCritical: boolean, summary: string|null }>,
 *   oracleGate?: object,
 *   allowedDecisions: string[],
 *   openedAt: string,
 * }} PendingGate
 */

/** @type {Map<string, PendingGate>} */
const _gateRegistry = new Map()

/**
 * Register a pending gate for a run (called by dashboard server when it reads
 * the __factory_gate:open stdout line from the workflow child process).
 *
 * @param {PendingGate} gate
 */
export function registerGate(gate) {
  _gateRegistry.set(gate.runId, gate)
}

/**
 * Remove a gate from the registry (called after the decision is written,
 * or on run stop/SIGTERM at the server level).
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
 * Write the human decision to the run-scoped reply file.
 * The workflow process polls for this file.
 *
 * @param {string} runId
 * @param {'retry'|'ignore'|'fail'} decision
 * @param {string} [message]
 * @returns {{ ok: boolean, error?: string }}
 */
export function writeGateReply(runId, decision, message = '') {
  const replyPath = gateReplyPath(runId)
  try {
    const content = JSON.stringify({ decision, message: message.trim() })
    writeFileSync(replyPath, content, 'utf8')
    unregisterGate(runId)
    return { ok: true }
  } catch (err) {
    return { ok: false, error: String(err) }
  }
}

// ---------------------------------------------------------------------------
// Workflow-side helpers (used by us-loop.mjs)
// ---------------------------------------------------------------------------

/**
 * Path of the run-scoped gate reply file.
 * Written by the dashboard server, polled by the workflow.
 *
 * @param {string} runId
 * @returns {string}
 */
export function gateReplyPath(runId) {
  return join(RUNS_DIR, `${runId}.gate-reply`)
}

/**
 * Emit the gate-open signal on stdout.
 *
 * The dashboard server reads the workflow's stdout line-by-line.
 * Lines that parse as JSON with __factory_gate === 'open' are intercepted
 * and stored in the server's in-process gate registry.
 *
 * @param {string} runId
 * @param {object} reviewResult  AdversarialReviewResult from adversarial-review.mjs
 */
export function emitGateOpen(runId, reviewResult) {
  const outcomes = reviewResult.outcomes.map((o) => ({
    reviewerName: o.reviewerName,
    verdict: o.verdict,
    hasCritical: o.hasCritical,
    summary: o.rawOutput
      ? o.rawOutput.slice(0, 4000) + (o.rawOutput.length > 4000 ? '\n...(truncated)' : '')
      : null,
  }))

  const signal = {
    __factory_gate: 'open',
    runId,
    gateType: 'adversarial-review',
    findings: buildFindingsText(reviewResult),
    outcomes,
    allowedDecisions: ['retry', 'ignore', 'fail'],
    openedAt: new Date().toISOString(),
  }

  // Write as a single line on stdout — the server reads stdout line-by-line.
  process.stdout.write(JSON.stringify(signal) + '\n')
}

/**
 * Emit an oracle-failure gate-open signal on stdout.
 *
 * Used when a deterministic oracle fails for a non-product reason
 * (BASELINE_FAILURE, ORACLE_INFRASTRUCTURE, INDETERMINATE_OUT_OF_SCOPE).
 * The human can either continue (quarantine the oracle and proceed) or fail
 * the run.
 *
 * Allowed decisions: 'continue' | 'fail'
 *
 * @param {string} runId
 * @param {object} oracleInfo
 */
export function emitOracleGateOpen(runId, oracleInfo) {
  const findingsLines = [
    `**Oracle gate: ${oracleInfo.classification}**`,
    '',
    `Oracle: \`${oracleInfo.oracleName}\``,
    `Command: \`${oracleInfo.command}\``,
    `CWD: \`${oracleInfo.cwd}\``,
    '',
    `Classification: **${oracleInfo.classification}**`,
    `Reason: ${oracleInfo.reason}`,
    '',
    `Baseline evidence: ${oracleInfo.baselineEvidence}`,
  ]

  if (oracleInfo.newDiagnosticLines && oracleInfo.newDiagnosticLines.length > 0) {
    findingsLines.push('', '**New diagnostics:**', '```', ...oracleInfo.newDiagnosticLines.slice(0, 20), '```')
  }

  findingsLines.push(
    '',
    '---',
    '**Continue** = quarantine this oracle and proceed to adversarial review.',
    'The oracle remains visibly failed/quarantined; it is never rewritten as passed.',
    '**Fail** = stop the run immediately.',
  )

  const signal = {
    __factory_gate: 'open',
    runId,
    gateType: 'oracle',
    findings: findingsLines.join('\n'),
    outcomes: [],
    oracleGate: oracleInfo,
    allowedDecisions: ['continue', 'fail'],
    openedAt: new Date().toISOString(),
  }

  process.stdout.write(JSON.stringify(signal) + '\n')
}

/**
 * Wait for a human decision on the review gate.
 *
 * Polls for factory/runs/<runId>.gate-reply every GATE_POLL_MS.
 * Returns as soon as the reply file exists or the process receives SIGTERM.
 * Never times out — waits indefinitely.
 *
 * The reply file is cleaned up after reading.
 *
 * @param {string} runId
 * @param {object} log
 * @returns {Promise<{ decision: 'retry'|'ignore'|'fail', message: string }>}
 */
export async function waitForHumanDecision(runId, log, gateType = 'adversarial-review') {
  const replyPath = gateReplyPath(runId)

  // Clean up any stale reply file from a previous interrupted run.
  try { unlinkSync(replyPath) } catch { /* absent = ok */ }

  if (gateType === 'oracle') {
    log.error('\u25b6 HUMAN GATE: oracle failure \u2014 waiting for human decision.')
    log.error(`  Dashboard: GET /api/factory/runs/${runId}/review-gate`)
    log.error('  Allowed decisions: continue | fail')
    log.error('  No timeout \u2014 gate waits until human decision or SIGTERM.')
  } else {
    log.error('\u25b6 HUMAN GATE: adversarial review FAIL \u2014 waiting for human decision.')
    log.error(`  Dashboard: GET /api/factory/runs/${runId}/review-gate`)
    log.error('  Allowed decisions: retry | ignore | fail')
    log.error('  No timeout \u2014 gate waits until human decision or SIGTERM.')
  }

  return new Promise((resolve) => {
    // Store resolver so rejectAllPendingGates() can resolve it on SIGTERM.
    _pendingResolvers.set(runId, resolve)

    const poll = setInterval(() => {
      if (!existsSync(replyPath)) return

      clearInterval(poll)
      _pendingResolvers.delete(runId)

      try {
        const raw = readFileSync(replyPath, 'utf8')
        const parsed = JSON.parse(raw)
        // Oracle gate: 'continue' | 'fail'; adversarial-review gate: 'ignore' | 'retry' | 'fail'
        const allValid = ['ignore', 'retry', 'continue']
        const decision = allValid.includes(parsed.decision) ? parsed.decision : 'fail'
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
 * Populated by waitForHumanDecision(), drained by rejectAllPendingGates().
 *
 * @type {Map<string, Function>}
 */
const _pendingResolvers = new Map()

/**
 * Resolve all pending gates to { decision: 'fail', message: '' }.
 * Called by shutdown.mjs on SIGTERM before process.exit().
 *
 * This ensures the workflow's Promise resolves cleanly instead of leaking,
 * so the shutdown handler can write run_end correctly.
 */
export function rejectAllPendingGates() {
  for (const [runId, resolve] of _pendingResolvers) {
    try {
      resolve({ decision: 'fail', message: '' })
    } catch { /* ignore */ }
    _pendingResolvers.delete(runId)
  }
  // Also clean up any stale reply files for pending gates.
  // (The workflow will have already exited, but we clean up defensively.)
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
// Internal helpers
// ---------------------------------------------------------------------------

/**
 * Build a human-readable findings text from the review result.
 *
 * @param {object} reviewResult
 * @returns {string}
 */
function buildFindingsText(reviewResult) {
  const lines = [
    `**${reviewResult.failCount}/${reviewResult.reviewerCount} reviewer(s) returned FAIL**`,
    '',
  ]
  for (const o of reviewResult.outcomes) {
    if (o.verdict === 'FAIL' && o.rawOutput) {
      lines.push(`## ${o.reviewerName} \u2014 FAIL`)
      lines.push('')
      lines.push(o.rawOutput.slice(0, 4000))
      lines.push('')
      lines.push('---')
      lines.push('')
    }
  }
  return lines.join('\n')
}
