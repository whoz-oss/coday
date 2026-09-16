/**
 * Tests for the run-scoped human review gate.
 *
 * Verifies gate instance identity, single-use semantics, IPC signal validation,
 * sequential gates in same run, stale/duplicate/wrong-decision replies,
 * crafted unauthenticated stdout rejection, and concurrent gate independence.
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
  gateInstanceReplyPath,
  gateReplyPath,
  generateGateInstanceId,
  rejectAllPendingGates,
  waitForHumanDecision,
  emitGateOpen,
  emitOracleGateOpen,
  validateGateSignal,
  GATE_POLL_MS,
} from '../lib/review-gate.mjs'

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
// 1. generateGateInstanceId
// ---------------------------------------------------------------------------

console.log('\n=== 1. generateGateInstanceId ===')

{
  const id1 = generateGateInstanceId()
  const id2 = generateGateInstanceId()
  ok('id is string', typeof id1 === 'string')
  ok('id is non-empty', id1.length > 0)
  ok('id is hex (safe token)', /^[0-9a-f]+$/.test(id1))
  ok('two ids are different', id1 !== id2)
}

// ---------------------------------------------------------------------------
// 2. registerGate — validation and registry
// ---------------------------------------------------------------------------

console.log('\n=== 2. registerGate validation ===')

{
  const RUN = 'GATE-T02-BASIC'
  const instanceId = generateGateInstanceId()

  // Unknown gate type rejected
  const badType = registerGate({ runId: RUN, gateInstanceId: instanceId, gateType: 'unknown-type', findings: '', outcomes: [], allowedDecisions: [] })
  eq('unknown gate type rejected', badType.ok, false)

  // Invalid gateInstanceId rejected
  const badId = registerGate({ runId: RUN, gateInstanceId: '../traversal', gateType: 'adversarial-review', findings: '', outcomes: [], allowedDecisions: [] })
  eq('invalid gateInstanceId rejected', badId.ok, false)

  // Valid adversarial-review gate
  const good = registerGate({ runId: RUN, gateInstanceId: instanceId, gateType: 'adversarial-review', findings: 'some findings', outcomes: [], allowedDecisions: ['retry', 'ignore', 'fail'] })
  eq('valid adversarial-review gate registered', good.ok, true)

  const gate = getGate(RUN)
  ok('getGate returns gate', gate !== null)
  eq('gate.gateInstanceId', gate?.gateInstanceId, instanceId)
  eq('gate.gateType', gate?.gateType, 'adversarial-review')
  // allowedDecisions is from GATE_ALLOWED_DECISIONS (canonical), not from input
  ok('gate.allowedDecisions is array', Array.isArray(gate?.allowedDecisions))

  unregisterGate(RUN)
  eq('getGate after unregister: null', getGate(RUN), null)
}

// ---------------------------------------------------------------------------
// 3. writeGateReply — single-use, instance-scoped
// ---------------------------------------------------------------------------

console.log('\n=== 3. writeGateReply single-use and instance-scoped ===')

{
  const RUN = 'GATE-T03-REPLY'
  const instanceId = generateGateInstanceId()
  const replyPath = gateInstanceReplyPath(RUN, instanceId)
  cleanupFiles.push(replyPath)
  try { unlinkSync(replyPath) } catch { /* ok */ }

  // No pending gate — writeGateReply returns 404
  const noPending = writeGateReply(RUN, instanceId, 'ignore', 'msg')
  eq('no pending gate — status 404', noPending.status, 404)
  eq('no pending gate — ok false', noPending.ok, false)

  // Register gate
  registerGate({ runId: RUN, gateInstanceId: instanceId, gateType: 'adversarial-review', findings: 'f', outcomes: [], allowedDecisions: [] })

  // Wrong gateInstanceId — 409 conflict
  const wrongId = writeGateReply(RUN, 'wrong-instance-id', 'ignore', 'msg')
  eq('wrong gateInstanceId — status 409', wrongId.status, 409)
  eq('wrong gateInstanceId — ok false', wrongId.ok, false)
  ok('gate still registered after wrong id attempt', getGate(RUN) !== null)

  // Wrong decision for gate type — 400
  const wrongDecision = writeGateReply(RUN, instanceId, 'continue', 'msg') // 'continue' is for oracle, not adversarial-review
  eq('wrong decision for gate type — status 400', wrongDecision.status, 400)
  eq('wrong decision for gate type — ok false', wrongDecision.ok, false)
  ok('gate still registered after wrong decision', getGate(RUN) !== null)

  // Valid reply — succeeds and consumes gate
  const valid = writeGateReply(RUN, instanceId, 'ignore', 'Looks fine')
  eq('valid reply — ok true', valid.ok, true)
  ok('reply file created', existsSync(replyPath))
  eq('gate consumed (unregistered)', getGate(RUN), null)

  const parsed = JSON.parse(readFileSync(replyPath, 'utf8'))
  eq('reply.decision', parsed.decision, 'ignore')
  eq('reply.message', parsed.message, 'Looks fine')
  eq('reply.gateInstanceId', parsed.gateInstanceId, instanceId)

  // Duplicate POST after consumption — no pending gate (404)
  const duplicate = writeGateReply(RUN, instanceId, 'retry', 'again')
  eq('duplicate POST after consumption — status 404', duplicate.status, 404)

  try { unlinkSync(replyPath) } catch { /* ok */ }
}

// ---------------------------------------------------------------------------
// 4. Sequential gates in same run — stale reply cannot be consumed
// ---------------------------------------------------------------------------

console.log('\n=== 4. Sequential gates in same run ===')

await (async () => {
  const RUN = 'GATE-T04-SEQUENTIAL'
  const instanceId1 = generateGateInstanceId()
  const instanceId2 = generateGateInstanceId()
  const replyPath1 = gateInstanceReplyPath(RUN, instanceId1)
  const replyPath2 = gateInstanceReplyPath(RUN, instanceId2)
  cleanupFiles.push(replyPath1, replyPath2)
  try { unlinkSync(replyPath1) } catch { /* ok */ }
  try { unlinkSync(replyPath2) } catch { /* ok */ }

  // Open gate 1
  registerGate({ runId: RUN, gateInstanceId: instanceId1, gateType: 'adversarial-review', findings: 'f1', outcomes: [], allowedDecisions: [] })

  // Consume gate 1
  const reply1 = writeGateReply(RUN, instanceId1, 'retry', 'round 1')
  eq('gate 1 consumed', reply1.ok, true)
  eq('gate 1 unregistered', getGate(RUN), null)

  // Open gate 2 (same run, new instance)
  registerGate({ runId: RUN, gateInstanceId: instanceId2, gateType: 'oracle', findings: 'f2', outcomes: [], allowedDecisions: [] })

  // Stale reply for gate 1 cannot be accepted by gate 2
  const stale = writeGateReply(RUN, instanceId1, 'continue', 'stale')
  eq('stale gate 1 id rejected by gate 2 — status 409', stale.status, 409)
  ok('gate 2 still registered', getGate(RUN) !== null)

  // Correct reply for gate 2
  const reply2 = writeGateReply(RUN, instanceId2, 'continue', 'proceed')
  eq('gate 2 consumed with correct id', reply2.ok, true)
  eq('gate 2 unregistered', getGate(RUN), null)

  try { unlinkSync(replyPath1) } catch { /* ok */ }
  try { unlinkSync(replyPath2) } catch { /* ok */ }
})()

// ---------------------------------------------------------------------------
// 5. waitForHumanDecision — validates gateInstanceId in reply file
// ---------------------------------------------------------------------------

console.log('\n=== 5. waitForHumanDecision validates gateInstanceId ===')

await (async () => {
  const RUN = 'GATE-T05-WAIT'
  const instanceId = generateGateInstanceId()
  const wrongInstanceId = generateGateInstanceId()
  const replyPath = gateInstanceReplyPath(RUN, instanceId)
  cleanupFiles.push(replyPath)
  try { unlinkSync(replyPath) } catch { /* ok */ }

  const mockLog = { error: () => {}, info: () => {} }

  const waitPromise = waitForHumanDecision(RUN, instanceId, mockLog, 'adversarial-review')

  await new Promise((r) => setTimeout(r, 10))

  // Write reply with wrong gateInstanceId — should fail closed to 'fail'
  writeFileSync(replyPath, JSON.stringify({ decision: 'ignore', message: 'stale', gateInstanceId: wrongInstanceId }), 'utf8')

  await new Promise((r) => setTimeout(r, GATE_POLL_MS + 500))

  const result = await waitPromise
  eq('wrong gateInstanceId in reply file — decision fail-closed', result.decision, 'fail')

  try { unlinkSync(replyPath) } catch { /* ok */ }
})()

// ---------------------------------------------------------------------------
// 6. waitForHumanDecision — correct gateInstanceId resolves correctly
// ---------------------------------------------------------------------------

console.log('\n=== 6. waitForHumanDecision resolves with correct id ===')

await (async () => {
  const RUN = 'GATE-T06-CORRECT'
  const instanceId = generateGateInstanceId()
  const replyPath = gateInstanceReplyPath(RUN, instanceId)
  cleanupFiles.push(replyPath)
  try { unlinkSync(replyPath) } catch { /* ok */ }

  const mockLog = { error: () => {}, info: () => {} }

  const waitPromise = waitForHumanDecision(RUN, instanceId, mockLog, 'oracle')
  await new Promise((r) => setTimeout(r, 10))

  writeFileSync(replyPath, JSON.stringify({ decision: 'continue', message: 'proceed', gateInstanceId: instanceId }), 'utf8')
  await new Promise((r) => setTimeout(r, GATE_POLL_MS + 500))

  const result = await waitPromise
  eq('correct id — decision=continue', result.decision, 'continue')
  eq('correct id — message', result.message, 'proceed')

  try { unlinkSync(replyPath) } catch { /* ok */ }
})()

// ---------------------------------------------------------------------------
// 7. rejectAllPendingGates resolves all pending to fail
// ---------------------------------------------------------------------------

console.log('\n=== 7. rejectAllPendingGates ===')

await (async () => {
  const RUN = 'GATE-T07-SIGTERM'
  const instanceId = generateGateInstanceId()
  const replyPath = gateInstanceReplyPath(RUN, instanceId)
  cleanupFiles.push(replyPath)
  try { unlinkSync(replyPath) } catch { /* ok */ }

  const mockLog = { error: () => {}, info: () => {} }
  const waitPromise = waitForHumanDecision(RUN, instanceId, mockLog)
  await new Promise((r) => setTimeout(r, 10))
  rejectAllPendingGates()

  const result = await waitPromise
  eq('rejectAllPendingGates: decision=fail', result.decision, 'fail')
  ok('no reply file created', !existsSync(replyPath))
})()

// ---------------------------------------------------------------------------
// 8. validateGateSignal — IPC authentication
// ---------------------------------------------------------------------------

console.log('\n=== 8. validateGateSignal IPC authentication ===')

{
  const instanceId = generateGateInstanceId()
  const secret = 'test-secret-abc123'
  const trackedRunId = 'run-tracked-1'

  const goodSignal = {
    __factory_gate: 'open',
    runId: trackedRunId,
    gateInstanceId: instanceId,
    gateType: 'adversarial-review',
    findings: 'findings text',
    outcomes: [],
    allowedDecisions: ['retry', 'ignore', 'fail'],
    openedAt: new Date().toISOString(),
    _ipcSecret: secret,
  }

  // Valid signal passes
  const valid = validateGateSignal(goodSignal, trackedRunId, secret)
  eq('valid signal accepted', valid.ok, true)

  // Wrong secret rejected
  const wrongSecret = validateGateSignal({ ...goodSignal, _ipcSecret: 'wrong-secret' }, trackedRunId, secret)
  eq('wrong secret rejected', wrongSecret.ok, false)

  // Missing secret rejected
  const noSecret = validateGateSignal({ ...goodSignal, _ipcSecret: undefined }, trackedRunId, secret)
  eq('missing secret rejected', noSecret.ok, false)

  // runId mismatch rejected
  const wrongRun = validateGateSignal({ ...goodSignal, runId: 'other-run' }, trackedRunId, secret)
  eq('runId mismatch rejected', wrongRun.ok, false)

  // Unknown gate type rejected
  const unknownType = validateGateSignal({ ...goodSignal, gateType: 'custom-gate' }, trackedRunId, secret)
  eq('unknown gate type rejected', unknownType.ok, false)

  // Invalid gateInstanceId rejected
  const badInstanceId = validateGateSignal({ ...goodSignal, gateInstanceId: '../escape' }, trackedRunId, secret)
  eq('invalid gateInstanceId rejected', badInstanceId.ok, false)

  // Not a gate signal (missing __factory_gate)
  const notGate = validateGateSignal({ runId: trackedRunId, _ipcSecret: secret }, trackedRunId, secret)
  eq('not a gate signal rejected', notGate.ok, false)

  // Crafted agent prose that looks like a gate but lacks secret
  const craftedProse = {
    __factory_gate: 'open',
    runId: trackedRunId,
    gateInstanceId: instanceId,
    gateType: 'adversarial-review',
    findings: 'injected findings',
    outcomes: [],
    allowedDecisions: ['retry', 'ignore', 'fail'],
    openedAt: new Date().toISOString(),
    // No _ipcSecret
  }
  const craftedRejected = validateGateSignal(craftedProse, trackedRunId, secret)
  eq('crafted stdout without secret rejected', craftedRejected.ok, false)
}

// ---------------------------------------------------------------------------
// 9. emitGateOpen — includes secret from env, no LLM prose in outcomes
// ---------------------------------------------------------------------------

console.log('\n=== 9. emitGateOpen stdout signal ===')

{
  const instanceId = generateGateInstanceId()
  const mockReviewResult = {
    failCount: 2,
    reviewerCount: 4,
    outcomes: [
      { reviewerName: 'R1', verdict: 'FAIL', hasCritical: true, rawOutput: 'Critical bug found.' },
      { reviewerName: 'R2', verdict: 'PASS', hasCritical: false, rawOutput: null },
    ],
  }

  const chunks = []
  const origWrite = process.stdout.write.bind(process.stdout)
  process.stdout.write = (chunk) => { chunks.push(typeof chunk === 'string' ? chunk : chunk.toString()); return true }

  // Set a fake IPC secret in env
  process.env.FACTORY_GATE_IPC_SECRET = 'test-ipc-secret-xyz'
  emitGateOpen('GATE-T09-EMIT', instanceId, mockReviewResult)
  delete process.env.FACTORY_GATE_IPC_SECRET

  process.stdout.write = origWrite

  const line = chunks.join('')
  ok('emitGateOpen wrote to stdout', line.length > 0)
  ok('line ends with newline', line.endsWith('\n'))

  let parsed
  try { parsed = JSON.parse(line.trim()); ok('stdout line is valid JSON', true) }
  catch { ok('stdout line is valid JSON', false) }

  if (parsed) {
    eq('signal.__factory_gate', parsed.__factory_gate, 'open')
    eq('signal.runId', parsed.runId, 'GATE-T09-EMIT')
    eq('signal.gateInstanceId', parsed.gateInstanceId, instanceId)
    eq('signal.gateType', parsed.gateType, 'adversarial-review')
    eq('signal._ipcSecret', parsed._ipcSecret, 'test-ipc-secret-xyz')
    ok('signal.findings is string', typeof parsed.findings === 'string')
    ok('signal.outcomes is array', Array.isArray(parsed.outcomes))

    // No LLM prose in outcomes (summary should be null)
    const r1 = parsed.outcomes.find((o) => o.reviewerName === 'R1')
    ok('outcome R1 present', !!r1)
    eq('outcome R1 verdict', r1?.verdict, 'FAIL')
    eq('outcome R1 summary null (no prose in IPC)', r1?.summary, null)
  }
}

// ---------------------------------------------------------------------------
// 10. emitOracleGateOpen — structured facts only, no prose
// ---------------------------------------------------------------------------

console.log('\n=== 10. emitOracleGateOpen — no prose in IPC ===')

{
  const instanceId = generateGateInstanceId()
  const oracleInfo = {
    oracleName: 'types',
    classification: 'INDETERMINATE_OUT_OF_SCOPE',
    exitCode: 1,
    artifactRef: 'run-abc.ds-types-2-1',
    artifactHash: 'a'.repeat(64),
    newDiagnosticCount: 3,
    synthesisStatus: 'ambiguous',
    // prose fields that must NOT appear in IPC
    reason: 'Some LLM-generated reason',
    summary: 'Some LLM summary',
  }

  const chunks = []
  const origWrite = process.stdout.write.bind(process.stdout)
  process.stdout.write = (chunk) => { chunks.push(typeof chunk === 'string' ? chunk : chunk.toString()); return true }

  process.env.FACTORY_GATE_IPC_SECRET = 'test-ipc-secret-oracle'
  emitOracleGateOpen('GATE-T10-ORACLE', instanceId, oracleInfo)
  delete process.env.FACTORY_GATE_IPC_SECRET

  process.stdout.write = origWrite

  const line = chunks.join('')
  let parsed
  try { parsed = JSON.parse(line.trim()); ok('oracle gate signal is valid JSON', true) }
  catch { ok('oracle gate signal is valid JSON', false) }

  if (parsed) {
    eq('oracle gate type', parsed.gateType, 'oracle')
    eq('oracle gateInstanceId', parsed.gateInstanceId, instanceId)
    ok('oracle oracleGate present', !!parsed.oracleGate)
    eq('oracle oracleGate.oracleName', parsed.oracleGate?.oracleName, 'types')
    eq('oracle oracleGate.classification', parsed.oracleGate?.classification, 'INDETERMINATE_OUT_OF_SCOPE')
    eq('oracle oracleGate.synthesisStatus', parsed.oracleGate?.synthesisStatus, 'ambiguous')
    // prose fields must not appear in IPC oracleGate
    ok('reason not in oracleGate', !('reason' in (parsed.oracleGate ?? {})))
    ok('summary not in oracleGate', !('summary' in (parsed.oracleGate ?? {})))
    // artifactHash must be validated (64 hex chars)
    eq('artifactHash present in oracleGate', parsed.oracleGate?.artifactHash, 'a'.repeat(64))
  }
}

// ---------------------------------------------------------------------------
// 11b. registerGate rejects duplicate registration for same run
// ---------------------------------------------------------------------------

console.log('\n=== 11b. registerGate rejects duplicate for same run ===')

{
  const RUN = 'GATE-T11B-DUP'
  const instanceId1 = generateGateInstanceId()
  const instanceId2 = generateGateInstanceId()

  // First registration succeeds
  const first = registerGate({ runId: RUN, gateInstanceId: instanceId1, gateType: 'adversarial-review', findings: 'f1', outcomes: [], allowedDecisions: [] })
  eq('first registration succeeds', first.ok, true)
  eq('first gate is pending', getGate(RUN)?.gateInstanceId, instanceId1)

  // Second registration for same run is rejected (gate still pending)
  const second = registerGate({ runId: RUN, gateInstanceId: instanceId2, gateType: 'oracle', findings: 'f2', outcomes: [], allowedDecisions: [] })
  eq('second registration rejected while first pending', second.ok, false)
  ok('second rejection has error message', typeof second.error === 'string')

  // First gate is still the active one
  eq('first gate still active after rejected duplicate', getGate(RUN)?.gateInstanceId, instanceId1)

  // After consuming the first gate, a new registration is accepted
  unregisterGate(RUN)
  const third = registerGate({ runId: RUN, gateInstanceId: instanceId2, gateType: 'oracle', findings: 'f2', outcomes: [], allowedDecisions: [] })
  eq('third registration succeeds after first consumed', third.ok, true)
  eq('third gate is now active', getGate(RUN)?.gateInstanceId, instanceId2)

  unregisterGate(RUN)
}

// ---------------------------------------------------------------------------
// 11c. gateInstanceReplyPath vs deprecated gateReplyPath
// ---------------------------------------------------------------------------

console.log('\n=== 11c. Reply path isolation ===')

{
  const runId = 'GATE-T11-PATHS'
  const instanceId = generateGateInstanceId()

  const instancePath = gateInstanceReplyPath(runId, instanceId)
  const deprecatedPath = gateReplyPath(runId)

  ok('instance path contains gateInstanceId', instancePath.includes(instanceId))
  ok('deprecated path does not contain instanceId', !deprecatedPath.includes(instanceId))
  ok('paths are different', instancePath !== deprecatedPath)
  ok('instance path is inside RUNS_DIR', instancePath.startsWith(RUNS_DIR))
}

// ---------------------------------------------------------------------------
// 12. Concurrent gates: two runs resolved independently
// ---------------------------------------------------------------------------

console.log('\n=== 12. Concurrent gates: independent resolution ===')

await (async () => {
  const RUN_C = 'GATE-T12-C'
  const RUN_D = 'GATE-T12-D'
  const idC = generateGateInstanceId()
  const idD = generateGateInstanceId()
  const pathC = gateInstanceReplyPath(RUN_C, idC)
  const pathD = gateInstanceReplyPath(RUN_D, idD)
  cleanupFiles.push(pathC, pathD)
  try { unlinkSync(pathC) } catch { /* ok */ }
  try { unlinkSync(pathD) } catch { /* ok */ }

  const mockLog = { error: () => {}, info: () => {} }

  let resolvedC = null
  let resolvedD = null

  const promiseC = waitForHumanDecision(RUN_C, idC, mockLog, 'adversarial-review').then((r) => { resolvedC = r; return r })
  const promiseD = waitForHumanDecision(RUN_D, idD, mockLog, 'oracle').then((r) => { resolvedD = r; return r })

  await new Promise((r) => setTimeout(r, 10))

  // Resolve C with 'ignore'
  writeFileSync(pathC, JSON.stringify({ decision: 'ignore', message: 'C ok', gateInstanceId: idC }), 'utf8')
  await new Promise((r) => setTimeout(r, GATE_POLL_MS + 500))

  ok('12: gate C resolved', resolvedC !== null)
  ok('12: gate D still pending', resolvedD === null)
  eq('12: gate C decision=ignore', resolvedC?.decision, 'ignore')

  // Resolve D with 'continue'
  writeFileSync(pathD, JSON.stringify({ decision: 'continue', message: 'D proceed', gateInstanceId: idD }), 'utf8')
  await new Promise((r) => setTimeout(r, GATE_POLL_MS + 500))

  ok('12: gate D resolved', resolvedD !== null)
  eq('12: gate D decision=continue', resolvedD?.decision, 'continue')
  eq('12: gate C decision unchanged after D resolved', resolvedC?.decision, 'ignore')
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
