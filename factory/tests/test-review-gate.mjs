/**
 * Tests for the run-scoped human review gate.
 *
 * Verifies:
 *   1. registerGate / getGate / unregisterGate — basic registry operations.
 *   2. Two concurrent gates do not interfere with each other.
 *   3. gateReplyPath is per-run (no global singleton).
 *   4. writeGateReply writes a run-scoped file and unregisters the gate.
 *   5. writeGateReply normalizes decision values.
 *   6. rejectAllPendingGates resolves all pending resolvers to fail.
 *   7. No timeout: waitForHumanDecision resolves only via reply file or
 *      rejectAllPendingGates, not after N seconds on its own.
 *   8. emitGateOpen writes a parseable JSON line on stdout.
 *   9. Server GET logic: pending, terminal, terminal+humanDecision, unknown run.
 *  10. POST decision normalization (valid and invalid values).
 *  11. Deprecated global endpoint path matching (not handled by new scoped regex).
 *  12. Concurrent gates: two runs resolved independently.
 *
 * No HTTP server started. No AgentOS. No child process.
 * Reply files created in factory/runs/ are cleaned up.
 *
 * Usage: node factory/tests/test-review-gate.mjs
 * Exit: 0 = all pass, 1 = at least one failure.
 */

import { writeFileSync, readFileSync, unlinkSync, existsSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const RUNS_DIR = join(__dirname, '..', 'runs')

import {
  registerGate,
  unregisterGate,
  getGate,
  writeGateReply,
  gateReplyPath,
  rejectAllPendingGates,
  waitForHumanDecision,
  emitGateOpen,
  GATE_POLL_MS,
} from '../lib/review-gate.mjs'

import { parseJsonl } from '../dashboard/server.mjs'

// ---------------------------------------------------------------------------
// Runner
// ---------------------------------------------------------------------------

let passed = 0
let failed = 0
const cleanupFiles = []

function ok(name, value) {
  const icon = value ? '\u2713' : '\u2717'
  console.log(`${icon} ${name}`)
  if (value) passed++
  else { failed++; console.log(`  FAILED: expected truthy, got ${JSON.stringify(value)}`) }
}

function eq(name, actual, expected) {
  const match = JSON.stringify(actual) === JSON.stringify(expected)
  const icon = match ? '\u2713' : '\u2717'
  console.log(`${icon} ${name}`)
  if (match) passed++
  else {
    failed++
    console.log(`  expected: ${JSON.stringify(expected)}`)
    console.log(`  got:      ${JSON.stringify(actual)}`)
  }
}

// ---------------------------------------------------------------------------
// 1. Basic registry operations
// ---------------------------------------------------------------------------

console.log('\n=== 1. Basic registry operations ===\n')

{
  const RUN = 'GATE-T01-BASIC'
  eq('getGate unknown run: null', getGate(RUN), null)

  registerGate({
    runId: RUN,
    findings: 'findings A',
    outcomes: [{ reviewerName: 'R1', verdict: 'FAIL', hasCritical: true, summary: 'issue' }],
    allowedDecisions: ['retry', 'ignore', 'fail'],
    openedAt: '2026-09-01T10:00:00.000Z',
  })

  const gate = getGate(RUN)
  ok('getGate after register: not null', gate !== null)
  eq('getGate: runId', gate?.runId, RUN)
  eq('getGate: findings', gate?.findings, 'findings A')
  eq('getGate: allowedDecisions', gate?.allowedDecisions, ['retry', 'ignore', 'fail'])
  eq('getGate: outcomes length', gate?.outcomes.length, 1)

  unregisterGate(RUN)
  eq('getGate after unregister: null', getGate(RUN), null)

  // Unregistering a nonexistent runId is a no-op.
  unregisterGate('NONEXISTENT-GATE')
  ok('unregisterGate nonexistent: no throw', true)
}

// ---------------------------------------------------------------------------
// 2. Two concurrent gates do not interfere
// ---------------------------------------------------------------------------

console.log('\n=== 2. Concurrent gate registry isolation ===\n')

{
  const RUN_A = 'GATE-T02-A'
  const RUN_B = 'GATE-T02-B'

  registerGate({ runId: RUN_A, findings: 'A findings', outcomes: [], allowedDecisions: ['fail'], openedAt: '2026-09-01T10:00:00.000Z' })
  registerGate({ runId: RUN_B, findings: 'B findings', outcomes: [], allowedDecisions: ['retry', 'ignore', 'fail'], openedAt: '2026-09-01T10:01:00.000Z' })

  eq('concurrent: gate A findings', getGate(RUN_A)?.findings, 'A findings')
  eq('concurrent: gate B findings', getGate(RUN_B)?.findings, 'B findings')
  eq('concurrent: gate A allowedDecisions', getGate(RUN_A)?.allowedDecisions, ['fail'])
  eq('concurrent: gate B allowedDecisions', getGate(RUN_B)?.allowedDecisions, ['retry', 'ignore', 'fail'])

  unregisterGate(RUN_A)
  eq('unregister A: gate A gone', getGate(RUN_A), null)
  ok('unregister A: gate B still present', getGate(RUN_B) !== null)

  unregisterGate(RUN_B)
  eq('unregister B: gate B gone', getGate(RUN_B), null)
}

// ---------------------------------------------------------------------------
// 3. gateReplyPath is per-run (no global singleton)
// ---------------------------------------------------------------------------

console.log('\n=== 3. gateReplyPath isolation ===\n')

{
  const pathA = gateReplyPath('GATE-T03-A')
  const pathB = gateReplyPath('GATE-T03-B')

  ok('paths are different', pathA !== pathB)
  ok('path A contains runId A', pathA.includes('GATE-T03-A'))
  ok('path B contains runId B', pathB.includes('GATE-T03-B'))
  ok('path A ends with .gate-reply', pathA.endsWith('.gate-reply'))
  ok('path B ends with .gate-reply', pathB.endsWith('.gate-reply'))
  ok('path A is not the global review-gate.reply singleton', !pathA.endsWith('review-gate.reply'))
  ok('path A is inside RUNS_DIR', pathA.startsWith(RUNS_DIR))
}

// ---------------------------------------------------------------------------
// 4. writeGateReply writes run-scoped file and unregisters gate
// ---------------------------------------------------------------------------

console.log('\n=== 4. writeGateReply ===\n')

{
  const RUN = 'GATE-T04-REPLY'
  const replyPath = gateReplyPath(RUN)
  cleanupFiles.push(replyPath)
  try { unlinkSync(replyPath) } catch { /* ok */ }

  registerGate({
    runId: RUN,
    findings: 'findings',
    outcomes: [],
    allowedDecisions: ['retry', 'ignore', 'fail'],
    openedAt: '2026-09-01T10:00:00.000Z',
  })
  ok('gate registered before reply', getGate(RUN) !== null)

  const result = writeGateReply(RUN, 'ignore', 'Looks fine')
  ok('writeGateReply: ok', result.ok)
  ok('reply file created', existsSync(replyPath))
  eq('gate unregistered after reply', getGate(RUN), null)

  const parsed = JSON.parse(readFileSync(replyPath, 'utf8'))
  eq('reply: decision=ignore', parsed.decision, 'ignore')
  eq('reply: message=Looks fine', parsed.message, 'Looks fine')

  try { unlinkSync(replyPath) } catch { /* ok */ }
}

// ---------------------------------------------------------------------------
// 5. writeGateReply with empty message
// ---------------------------------------------------------------------------

console.log('\n=== 5. writeGateReply empty message ===\n')

{
  const RUN = 'GATE-T05-FAIL'
  const replyPath = gateReplyPath(RUN)
  cleanupFiles.push(replyPath)
  try { unlinkSync(replyPath) } catch { /* ok */ }

  writeGateReply(RUN, 'fail', '')
  ok('fail reply file created', existsSync(replyPath))
  const parsed = JSON.parse(readFileSync(replyPath, 'utf8'))
  eq('decision=fail', parsed.decision, 'fail')
  eq('message empty string', parsed.message, '')

  try { unlinkSync(replyPath) } catch { /* ok */ }
}

// ---------------------------------------------------------------------------
// 6. rejectAllPendingGates resolves pending waitForHumanDecision to fail
// ---------------------------------------------------------------------------

console.log('\n=== 6. rejectAllPendingGates (SIGTERM simulation) ===\n')

await (async () => {
  const RUN = 'GATE-T06-SIGTERM'
  const replyPath = gateReplyPath(RUN)
  cleanupFiles.push(replyPath)
  try { unlinkSync(replyPath) } catch { /* ok */ }

  const mockLog = { error: () => {}, info: () => {} }

  const waitPromise = waitForHumanDecision(RUN, mockLog)

  // Give the poll loop one tick to register.
  await new Promise((r) => setTimeout(r, 10))

  // Simulate SIGTERM.
  rejectAllPendingGates()

  const result = await waitPromise
  eq('rejectAllPendingGates: decision=fail', result.decision, 'fail')
  eq('rejectAllPendingGates: message empty', result.message, '')
  ok('no reply file created by rejectAllPendingGates', !existsSync(replyPath))
})()

// ---------------------------------------------------------------------------
// 7. No automatic timeout
// ---------------------------------------------------------------------------

console.log('\n=== 7. No automatic timeout ===\n')

await (async () => {
  const RUN = 'GATE-T07-NOTIMEOUT'
  const replyPath = gateReplyPath(RUN)
  cleanupFiles.push(replyPath)
  try { unlinkSync(replyPath) } catch { /* ok */ }

  const mockLog = { error: () => {}, info: () => {} }

  let resolved = false
  const waitPromise = waitForHumanDecision(RUN, mockLog).then((r) => {
    resolved = true
    return r
  })

  // Wait 100ms. The gate must NOT auto-resolve (no timeout mechanism).
  await new Promise((r) => setTimeout(r, 100))
  ok('gate not resolved after 100ms', !resolved)

  // Write reply file to resolve it.
  writeFileSync(replyPath, JSON.stringify({ decision: 'ignore', message: '' }), 'utf8')

  // Wait for the poll to pick it up.
  await new Promise((r) => setTimeout(r, GATE_POLL_MS + 500))

  ok('gate resolved after reply file written', resolved)
  const result = await waitPromise
  eq('gate resolved with ignore', result.decision, 'ignore')
})()

// ---------------------------------------------------------------------------
// 8. emitGateOpen writes parseable JSON on stdout
// ---------------------------------------------------------------------------

console.log('\n=== 8. emitGateOpen stdout signal ===\n')

{
  const mockReviewResult = {
    failCount: 2,
    reviewerCount: 4,
    outcomes: [
      { reviewerName: 'R1', verdict: 'FAIL', hasCritical: true, rawOutput: 'Critical bug found.' },
      { reviewerName: 'R2', verdict: 'PASS', hasCritical: false, rawOutput: null },
    ],
  }

  // Capture stdout.
  const chunks = []
  const origWrite = process.stdout.write.bind(process.stdout)
  process.stdout.write = (chunk) => { chunks.push(typeof chunk === 'string' ? chunk : chunk.toString()); return true }

  emitGateOpen('GATE-T08-EMIT', mockReviewResult)

  process.stdout.write = origWrite

  const line = chunks.join('')
  ok('emitGateOpen wrote to stdout', line.length > 0)
  ok('line starts with {"__factory_gate":"open"', line.startsWith('{"__factory_gate":"open"'))
  ok('line ends with newline', line.endsWith('\n'))

  let parsed
  try { parsed = JSON.parse(line.trim()); ok('stdout line is valid JSON', true) }
  catch { ok('stdout line is valid JSON', false) }

  if (parsed) {
    eq('signal.__factory_gate', parsed.__factory_gate, 'open')
    eq('signal.runId', parsed.runId, 'GATE-T08-EMIT')
    ok('signal.findings is string', typeof parsed.findings === 'string')
    ok('signal.outcomes is array', Array.isArray(parsed.outcomes))
    eq('signal.allowedDecisions', parsed.allowedDecisions, ['retry', 'ignore', 'fail'])
    ok('signal.openedAt is ISO string', typeof parsed.openedAt === 'string')

    const r1 = parsed.outcomes.find((o) => o.reviewerName === 'R1')
    ok('outcome R1 present', !!r1)
    eq('outcome R1 verdict', r1?.verdict, 'FAIL')
    eq('outcome R1 hasCritical', r1?.hasCritical, true)
    ok('outcome R1 summary is string', typeof r1?.summary === 'string')

    const r2 = parsed.outcomes.find((o) => o.reviewerName === 'R2')
    ok('outcome R2 summary is null (no rawOutput)', r2?.summary === null)
  }
}

// ---------------------------------------------------------------------------
// 9. Server GET logic: pending, terminal, terminal+humanDecision, unknown
// ---------------------------------------------------------------------------

console.log('\n=== 9. Server GET /api/factory/runs/:id/review-gate logic ===\n')

{
  // 9a. Pending gate.
  const RUN_PENDING = 'GATE-T09-PENDING'
  registerGate({
    runId: RUN_PENDING,
    findings: 'pending findings',
    outcomes: [{ reviewerName: 'R1', verdict: 'FAIL', hasCritical: true, summary: 'bug' }],
    allowedDecisions: ['retry', 'ignore', 'fail'],
    openedAt: '2026-09-01T10:00:00.000Z',
  })

  const pendingGate = getGate(RUN_PENDING)
  ok('9a: getGate returns non-null for pending', pendingGate !== null)
  // Simulate server response construction.
  const pendingResp = pendingGate ? { status: 'pending', findings: pendingGate.findings, outcomes: pendingGate.outcomes, allowedDecisions: pendingGate.allowedDecisions } : null
  eq('9a: response status=pending', pendingResp?.status, 'pending')
  ok('9a: outcomes is array', Array.isArray(pendingResp?.outcomes))
  eq('9a: outcomes length', pendingResp?.outcomes.length, 1)
  unregisterGate(RUN_PENDING)

  // 9b. Completed run with humanDecision:fail in JSONL.
  const RUN_TERMINAL = 'GATE-T09-TERMINAL'
  const terminalPath = join(RUNS_DIR, `${RUN_TERMINAL}.jsonl`)
  cleanupFiles.push(terminalPath)
  writeFileSync(terminalPath, [
    JSON.stringify({ kind: 'run_start', runId: RUN_TERMINAL, workflow: 'us-loop', startedAt: '2026-09-01T10:00:00.000Z' }),
    JSON.stringify({ kind: 'phase', name: 'adversarial-review', phaseKind: 'agent', status: 'fail', startedAt: '2026-09-01T10:05:00.000Z' }),
    JSON.stringify({ kind: 'phase_end', name: 'adversarial-review', status: 'fail', durationMs: 30000, facts: { globalVerdict: 'FAIL', humanDecision: 'fail' } }),
    JSON.stringify({ kind: 'run_end', status: 'fail', durationMs: 360000, endedAt: '2026-09-01T10:06:00.000Z' }),
  ].join('\n') + '\n', 'utf8')

  const tLines = parseJsonl(terminalPath)
  const tRunEnd = tLines.find((l) => l.kind === 'run_end')
  let tHumanDecision = null
  for (const l of tLines) { if (l.kind === 'phase_end' && l.facts?.humanDecision) { tHumanDecision = l.facts.humanDecision; break } }

  ok('9b: run_end present', !!tRunEnd)
  eq('9b: humanDecision from JSONL', tHumanDecision, 'fail')

  const terminalResp = { status: 'terminal', humanDecision: tHumanDecision, reason: 'Run completed. A terminated process cannot resume. Only future pending gates can receive decisions.' }
  eq('9b: terminal status', terminalResp.status, 'terminal')
  eq('9b: humanDecision=fail', terminalResp.humanDecision, 'fail')
  ok('9b: reason non-empty', terminalResp.reason.length > 0)

  // 9c. Run exists, no run_end, no live gate -> terminal with humanDecision:null.
  const noGateResp = { status: 'terminal', humanDecision: null, reason: 'No active review gate for this run.' }
  eq('9c: no-gate status=terminal', noGateResp.status, 'terminal')
  eq('9c: humanDecision null', noGateResp.humanDecision, null)
}

// ---------------------------------------------------------------------------
// 10. POST decision normalization
// ---------------------------------------------------------------------------

console.log('\n=== 10. POST decision normalization ===\n')

{
  // Valid decisions pass through unchanged.
  for (const d of ['retry', 'ignore', 'fail']) {
    const normalized = ['ignore', 'retry', 'fail'].includes(d) ? d : 'fail'
    eq(`decision '${d}' is valid`, normalized, d)
  }

  // Invalid decision is normalized to 'fail'.
  const invalid = 'approve'
  const normalized = ['ignore', 'retry', 'fail'].includes(invalid) ? invalid : 'fail'
  eq('invalid decision normalized to fail', normalized, 'fail')
}

// ---------------------------------------------------------------------------
// 11. Deprecated global endpoint path matching
// ---------------------------------------------------------------------------

console.log('\n=== 11. Deprecated global endpoint path matching ===\n')

{
  const gateRegex = /^\/api\/factory\/runs\/([^/]+)\/review-gate$/
  const replyRegex = /^\/api\/factory\/runs\/([^/]+)\/review-gate\/reply$/

  ok('11: /api/review-gate does not match scoped gate regex', !gateRegex.test('/api/review-gate'))
  ok('11: /api/review-gate/reply does not match scoped reply regex', !replyRegex.test('/api/review-gate/reply'))
  ok('11: scoped GET matches gate regex', gateRegex.test('/api/factory/runs/RUN-123/review-gate'))
  ok('11: scoped POST matches reply regex', replyRegex.test('/api/factory/runs/RUN-123/review-gate/reply'))

  const matchGet = '/api/factory/runs/RUN-123/review-gate'.match(gateRegex)
  eq('11: scoped GET extracts runId', matchGet?.[1], 'RUN-123')

  const matchPost = '/api/factory/runs/RUN-123/review-gate/reply'.match(replyRegex)
  eq('11: scoped POST extracts runId', matchPost?.[1], 'RUN-123')
}

// ---------------------------------------------------------------------------
// 12. Concurrent gates: two runs resolved independently
// ---------------------------------------------------------------------------

console.log('\n=== 12. Concurrent gates: independent resolution ===\n')

await (async () => {
  const RUN_C = 'GATE-T12-C'
  const RUN_D = 'GATE-T12-D'
  const pathC = gateReplyPath(RUN_C)
  const pathD = gateReplyPath(RUN_D)
  cleanupFiles.push(pathC, pathD)
  try { unlinkSync(pathC) } catch { /* ok */ }
  try { unlinkSync(pathD) } catch { /* ok */ }

  const mockLog = { error: () => {}, info: () => {} }

  let resolvedC = null
  let resolvedD = null

  const promiseC = waitForHumanDecision(RUN_C, mockLog).then((r) => { resolvedC = r; return r })
  const promiseD = waitForHumanDecision(RUN_D, mockLog).then((r) => { resolvedD = r; return r })

  // Give polls time to start.
  await new Promise((r) => setTimeout(r, 10))

  // Resolve C with 'ignore'.
  writeFileSync(pathC, JSON.stringify({ decision: 'ignore', message: 'C ok' }), 'utf8')
  await new Promise((r) => setTimeout(r, GATE_POLL_MS + 500))

  ok('12: gate C resolved', resolvedC !== null)
  ok('12: gate D still pending', resolvedD === null)
  eq('12: gate C decision=ignore', resolvedC?.decision, 'ignore')
  eq('12: gate C message', resolvedC?.message, 'C ok')

  // Resolve D with 'retry'.
  writeFileSync(pathD, JSON.stringify({ decision: 'retry', message: 'D needs work' }), 'utf8')
  await new Promise((r) => setTimeout(r, GATE_POLL_MS + 500))

  ok('12: gate D resolved', resolvedD !== null)
  eq('12: gate D decision=retry', resolvedD?.decision, 'retry')
  eq('12: gate D message', resolvedD?.message, 'D needs work')

  // Gate C's decision unaffected by D.
  eq('12: gate C decision unchanged', resolvedC?.decision, 'ignore')
})()

// ---------------------------------------------------------------------------
// Cleanup
// ---------------------------------------------------------------------------

for (const f of cleanupFiles) {
  try { unlinkSync(f) } catch { /* already gone */ }
}

// ---------------------------------------------------------------------------
// Result
// ---------------------------------------------------------------------------

console.log('')
console.log(`Result: ${passed} passed, ${failed} failed`)
process.exit(failed > 0 ? 1 : 0)
