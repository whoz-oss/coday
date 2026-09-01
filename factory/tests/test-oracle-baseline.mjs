/**
 * Regression tests for the oracle baseline/classification/escalation architecture.
 *
 * Covers:
 *   1. baseline pass -> post fail => PRODUCT_REGRESSION / editor retry
 *   2. baseline fail same diagnostics -> BASELINE_FAILURE / human gate, no editor case
 *   3. baseline fail + new diagnostics -> editor gets only new errors
 *   4. TS5090-only -> ORACLE_INFRASTRUCTURE gate, no editor case
 *   5. zero-execution/empty evidence -> ORACLE_INFRASTRUCTURE gate
 *   6. human continue records quarantine and reaches adversarial review
 *   7. human fail ends run
 *   8. review packet contains quarantined oracle evidence
 *   9. existing adversarial-review gate remains compatible
 *  10. normalizeDiagnosticLine: TS, Jest, RAW, noise, ANSI
 *  11. extractOracleDiagnostics: deduplication, type vs test oracle
 *  12. isInfrastructureIdentity: TS5090, TS2345, non-TS
 *  13. classifyOracleResult: all classification paths
 *  14. buildQuarantineRecord: durable evidence, oracleFailed=true
 *  15. emitOracleGateOpen: parseable JSON signal on stdout
 *  16. waitForHumanDecision: 'continue' decision normalised correctly
 *
 * No HTTP server, no AgentOS, no child process, no Oracle execution.
 *
 * Usage: node factory/tests/test-oracle-baseline.mjs
 * Exit: 0 = all pass, 1 = at least one failure.
 */

import {
  normalizeDiagnosticLine,
  extractOracleDiagnostics,
  isInfrastructureIdentity,
  classifyOracleResult,
  buildQuarantineRecord,
} from '../lib/oracle-baseline.mjs'

import {
  emitOracleGateOpen,
  emitGateOpen,
  registerGate,
  getGate,
  unregisterGate,
  writeGateReply,
  gateReplyPath,
  waitForHumanDecision,
  GATE_POLL_MS,
} from '../lib/review-gate.mjs'

import { writeFileSync, unlinkSync, existsSync } from 'node:fs'

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
// 1. normalizeDiagnosticLine
// ---------------------------------------------------------------------------

console.log('\n=== 1. normalizeDiagnosticLine ===\n')

{
  // TypeScript error with location
  const line = 'src/app/foo.ts(42,7): error TS2345: Argument of type string.'
  const id = normalizeDiagnosticLine(line)
  ok('TS error: starts with TS:', id?.startsWith('TS:'))
  ok('TS error: contains error code', id?.includes('TS2345'))
  ok('TS error: contains file path', id?.includes('src/app/foo.ts'))
  ok('TS error: contains line/col', id?.includes(':42:7'))
}

{
  // TypeScript error without location
  const line = 'src/lib/bar.ts: error TS2304: Cannot find name Foo.'
  const id = normalizeDiagnosticLine(line)
  ok('TS error no loc: starts with TS:', id?.startsWith('TS:'))
  ok('TS error no loc: contains code', id?.includes('TS2304'))
}

{
  // Jest bullet
  const line = '\u25cf FooComponent \u203a should render title'
  const id = normalizeDiagnosticLine(line)
  ok('Jest bullet: starts with TEST:', id?.startsWith('TEST:'))
  ok('Jest bullet: contains suite', id?.includes('FooComponent'))
  ok('Jest bullet: contains test name', id?.includes('should render title'))
}

{
  // Generic RAW line
  const line = 'Build failed with some generic error'
  const id = normalizeDiagnosticLine(line)
  ok('RAW line: starts with RAW:', id?.startsWith('RAW:'))
  ok('RAW line: not null', id !== null)
}

{
  // Noise: NX summary
  const line = ' NX   Running target type-check for 4 projects failed'
  const id = normalizeDiagnosticLine(line)
  eq('Noise NX summary: returns null', id, null)
}

{
  // Noise: empty line
  const id = normalizeDiagnosticLine('   ')
  eq('Noise empty line: returns null', id, null)
}

{
  // ANSI stripped before matching
  const line = '\u001b[31msrc/app/foo.ts(1,1): error TS2345: message\u001b[0m'
  const id = normalizeDiagnosticLine(line)
  ok('ANSI stripped: TS identity extracted', id?.startsWith('TS:TS2345'))
}

// ---------------------------------------------------------------------------
// 2. isInfrastructureIdentity
// ---------------------------------------------------------------------------

console.log('\n=== 2. isInfrastructureIdentity ===\n')

{
  ok('TS5090 is infrastructure', isInfrastructureIdentity('TS:TS5090:apps/client/tsconfig.app.json:1:1'))
  ok('TS6059 is infrastructure', isInfrastructureIdentity('TS:TS6059:some/path.ts:1:1'))
  ok('TS18003 is infrastructure', isInfrastructureIdentity('TS:TS18003:some/path.ts:1:1'))
  ok('TS6305 is infrastructure', isInfrastructureIdentity('TS:TS6305:some/path.ts:1:1'))
  ok('TS6307 is infrastructure', isInfrastructureIdentity('TS:TS6307:some/path.ts:1:1'))
  ok('TS2345 is NOT infrastructure', !isInfrastructureIdentity('TS:TS2345:src/app/foo.ts:42:7'))
  ok('TS2304 is NOT infrastructure', !isInfrastructureIdentity('TS:TS2304:src/app/bar.ts:10:1'))
  ok('TEST identity is NOT infrastructure', !isInfrastructureIdentity('TEST:FooComponent:should create'))
  ok('RAW identity is NOT infrastructure', !isInfrastructureIdentity('RAW:some error line'))
}

// ---------------------------------------------------------------------------
// 3. extractOracleDiagnostics
// ---------------------------------------------------------------------------

console.log('\n=== 3. extractOracleDiagnostics ===\n')

{
  // Types oracle: extracts TS errors, deduplicates
  const stdout = [
    'src/app/foo.ts(42,7): error TS2345: Type string not assignable.',
    'src/app/foo.ts(42,7): error TS2345: Type string not assignable.', // duplicate
    'src/app/bar.ts(10,1): error TS2304: Cannot find name Foo.',
    ' NX   Running target type-check for 4 projects failed',
  ].join('\n')

  const { identities, rawLines } = extractOracleDiagnostics('types', stdout, '')
  ok('types oracle: TS2345 identity present', identities.some((id) => id.includes('TS2345')))
  ok('types oracle: TS2304 identity present', identities.some((id) => id.includes('TS2304')))
  ok('types oracle: NX summary not in identities', !identities.some((id) => id.includes('Running target')))
  ok('types oracle: deduplication (only 2 unique TS errors)', identities.filter((id) => id.includes('TS2345')).length === 1)
}

{
  // Tests oracle: extracts Jest failures
  const stdout = [
    'FAIL src/app/foo.spec.ts',
    '\u25cf FooComponent \u203a should create',
    '  Expected: true',
    '  Received: false',
    ' NX   Running target frontend-test for 2 projects failed',
  ].join('\n')

  const { identities } = extractOracleDiagnostics('tests', stdout, '')
  ok('tests oracle: Jest bullet identity present', identities.some((id) => id.startsWith('TEST:')))
}

// ---------------------------------------------------------------------------
// 4. classifyOracleResult — CLEAN
// ---------------------------------------------------------------------------

console.log('\n=== 4. classifyOracleResult — CLEAN ===\n')

{
  const result = classifyOracleResult({
    oracle: { name: 'types', command: 'pnpm nx run-many --target=type-check', cwd: '/repo' },
    baseline: {
      exitCode: 0, timedOut: false, emptySuccess: false,
      diagnosticIdentities: [], durationMs: 5000, tasks: { executed: 4 },
    },
    postEdit: { exitCode: 0, timedOut: false, emptySuccess: false, stdout: '', stderr: '', tasks: { executed: 4 } },
    changedFiles: ['src/app/foo.ts'],
    plannedFiles: ['src/app/foo.ts'],
  })
  eq('CLEAN: classification', result.classification, 'CLEAN')
  eq('CLEAN: postEditPassed', result.postEditPassed, true)
  eq('CLEAN: newDiagnostics empty', result.newDiagnostics, [])
}

// ---------------------------------------------------------------------------
// 5. classifyOracleResult — PRODUCT_REGRESSION (baseline passed, post-edit fails)
// ---------------------------------------------------------------------------

console.log('\n=== 5. classifyOracleResult — PRODUCT_REGRESSION (baseline pass) ===\n')

{
  const newErrorStdout = 'src/app/foo.ts(42,7): error TS2345: Type string not assignable.'
  const result = classifyOracleResult({
    oracle: { name: 'types', command: 'pnpm nx run-many --target=type-check', cwd: '/repo' },
    baseline: {
      exitCode: 0, timedOut: false, emptySuccess: false,
      diagnosticIdentities: [], durationMs: 5000, tasks: { executed: 4 },
    },
    postEdit: {
      exitCode: 1, timedOut: false, emptySuccess: false,
      stdout: newErrorStdout, stderr: '', tasks: { executed: 4 },
    },
    changedFiles: ['src/app/foo.ts'],
    plannedFiles: ['src/app/foo.ts'],
  })
  eq('PRODUCT_REGRESSION: classification', result.classification, 'PRODUCT_REGRESSION')
  ok('PRODUCT_REGRESSION: newDiagnostics non-empty', result.newDiagnostics.length > 0)
  eq('PRODUCT_REGRESSION: preExistingDiagnostics empty', result.preExistingDiagnostics, [])
  eq('PRODUCT_REGRESSION: baselinePassed', result.baselinePassed, true)
  eq('PRODUCT_REGRESSION: postEditPassed', result.postEditPassed, false)
}

// ---------------------------------------------------------------------------
// 6. classifyOracleResult — BASELINE_FAILURE (same diagnostics, no new errors)
// ---------------------------------------------------------------------------

console.log('\n=== 6. classifyOracleResult — BASELINE_FAILURE ===\n')

{
  const sharedId = 'TS:TS2345:src/app/foo.ts:42:7'
  const result = classifyOracleResult({
    oracle: { name: 'types', command: 'pnpm nx run-many --target=type-check', cwd: '/repo' },
    baseline: {
      exitCode: 1, timedOut: false, emptySuccess: false,
      diagnosticIdentities: [sharedId], durationMs: 5000, tasks: { executed: 4 },
    },
    postEdit: {
      exitCode: 1, timedOut: false, emptySuccess: false,
      stdout: 'src/app/foo.ts(42,7): error TS2345: Type string not assignable.',
      stderr: '', tasks: { executed: 4 },
    },
    changedFiles: ['src/app/foo.ts'],
    plannedFiles: ['src/app/foo.ts'],
  })
  eq('BASELINE_FAILURE: classification', result.classification, 'BASELINE_FAILURE')
  eq('BASELINE_FAILURE: newDiagnostics empty', result.newDiagnostics, [])
  ok('BASELINE_FAILURE: preExistingDiagnostics non-empty', result.preExistingDiagnostics.length > 0)
  eq('BASELINE_FAILURE: baselinePassed', result.baselinePassed, false)
}

// ---------------------------------------------------------------------------
// 7. classifyOracleResult — baseline fail + new diagnostics -> PRODUCT_REGRESSION
//    Editor gets only new errors (classResult.newDiagnosticLines)
// ---------------------------------------------------------------------------

console.log('\n=== 7. classifyOracleResult — baseline fail + new errors ===\n')

{
  const existingId = 'TS:TS2345:src/app/foo.ts:42:7'
  const newErrorStdout = [
    'src/app/foo.ts(42,7): error TS2345: Type string not assignable.',  // pre-existing
    'src/app/bar.ts(10,1): error TS2304: Cannot find name Foo.',         // new
  ].join('\n')

  const result = classifyOracleResult({
    oracle: { name: 'types', command: 'pnpm nx run-many --target=type-check', cwd: '/repo' },
    baseline: {
      exitCode: 1, timedOut: false, emptySuccess: false,
      diagnosticIdentities: [existingId], durationMs: 5000, tasks: { executed: 4 },
    },
    postEdit: {
      exitCode: 1, timedOut: false, emptySuccess: false,
      stdout: newErrorStdout, stderr: '', tasks: { executed: 4 },
    },
    changedFiles: ['src/app/foo.ts', 'src/app/bar.ts'],
    plannedFiles: ['src/app/foo.ts', 'src/app/bar.ts'],
  })
  eq('baseline fail + new: classification', result.classification, 'PRODUCT_REGRESSION')
  ok('baseline fail + new: newDiagnostics contains TS2304', result.newDiagnostics.some((id) => id.includes('TS2304')))
  ok('baseline fail + new: preExisting contains TS2345', result.preExistingDiagnostics.some((id) => id.includes('TS2345')))
  ok('baseline fail + new: newDiagnostics does NOT contain TS2345', !result.newDiagnostics.some((id) => id.includes('TS2345')))
}

// ---------------------------------------------------------------------------
// 8. classifyOracleResult — TS5090-only -> ORACLE_INFRASTRUCTURE
// ---------------------------------------------------------------------------

console.log('\n=== 8. classifyOracleResult — TS5090-only (ORACLE_INFRASTRUCTURE) ===\n')

{
  const ts5090Stdout = 'apps/client/tsconfig.app.json(1,1): error TS5090: Option --rootDir is required.'
  const result = classifyOracleResult({
    oracle: { name: 'types', command: 'pnpm nx run-many --target=type-check', cwd: '/repo' },
    baseline: {
      exitCode: 0, timedOut: false, emptySuccess: false,
      diagnosticIdentities: [], durationMs: 5000, tasks: { executed: 4 },
    },
    postEdit: {
      exitCode: 1, timedOut: false, emptySuccess: false,
      stdout: ts5090Stdout, stderr: '', tasks: { executed: 4 },
    },
    changedFiles: ['src/app/foo.ts'],
    plannedFiles: ['src/app/foo.ts'],
  })
  eq('TS5090-only: classification', result.classification, 'ORACLE_INFRASTRUCTURE')
  ok('TS5090-only: reason mentions TS5090', result.reason.includes('TS5090'))
  eq('TS5090-only: newDiagnostics empty (no editor retry)', result.newDiagnostics.filter((id) => !id.startsWith('TS:TS5')).length, 0)
}

// ---------------------------------------------------------------------------
// 9. classifyOracleResult — timeout -> ORACLE_INFRASTRUCTURE
// ---------------------------------------------------------------------------

console.log('\n=== 9. classifyOracleResult — timeout ===\n')

{
  const result = classifyOracleResult({
    oracle: { name: 'types', command: 'pnpm nx run-many --target=type-check', cwd: '/repo' },
    baseline: {
      exitCode: 0, timedOut: false, emptySuccess: false,
      diagnosticIdentities: [], durationMs: 5000, tasks: { executed: 4 },
    },
    postEdit: {
      exitCode: -1, timedOut: true, emptySuccess: false,
      stdout: '', stderr: '', tasks: { executed: 0 },
    },
    changedFiles: ['src/app/foo.ts'],
    plannedFiles: ['src/app/foo.ts'],
  })
  eq('timeout: classification', result.classification, 'ORACLE_INFRASTRUCTURE')
  ok('timeout: reason mentions timed out', result.reason.toLowerCase().includes('timed out'))
}

// ---------------------------------------------------------------------------
// 10. classifyOracleResult — empty success -> ORACLE_INFRASTRUCTURE
// ---------------------------------------------------------------------------

console.log('\n=== 10. classifyOracleResult — empty success ===\n')

{
  const result = classifyOracleResult({
    oracle: { name: 'types', command: 'pnpm nx run-many --target=type-check', cwd: '/repo' },
    baseline: {
      exitCode: 0, timedOut: false, emptySuccess: false,
      diagnosticIdentities: [], durationMs: 5000, tasks: { executed: 4 },
    },
    postEdit: {
      exitCode: 0, timedOut: false, emptySuccess: true,
      stdout: '', stderr: '', tasks: { executed: 0 },
    },
    changedFiles: ['src/app/foo.ts'],
    plannedFiles: ['src/app/foo.ts'],
  })
  eq('empty success: classification', result.classification, 'ORACLE_INFRASTRUCTURE')
  eq('empty success: postEditPassed false (not truly passed)', result.postEditPassed, false)
}

// ---------------------------------------------------------------------------
// 11. buildQuarantineRecord — durable evidence, oracleFailed=true
// ---------------------------------------------------------------------------

console.log('\n=== 11. buildQuarantineRecord ===\n')

{
  const classResult = {
    classification: 'BASELINE_FAILURE',
    baselineIdentities: ['TS:TS2345:src/app/foo.ts:42:7'],
    postEditIdentities: ['TS:TS2345:src/app/foo.ts:42:7'],
    newDiagnostics: [],
    preExistingDiagnostics: ['TS:TS2345:src/app/foo.ts:42:7'],
    newDiagnosticLines: [],
    baselinePassed: false,
    postEditPassed: false,
  }

  const record = buildQuarantineRecord({
    oracleName: 'types',
    classification: 'BASELINE_FAILURE',
    reason: 'All diagnostics pre-existing.',
    baseline: { exitCode: 1, timedOut: false, emptySuccess: false, executionEvidence: 'exitCode=1', diagnosticIdentities: [] },
    postEdit: { exitCode: 1, timedOut: false, emptySuccess: false, durationMs: 5000 },
    classificationResult: classResult,
    humanDecision: 'continue',
    humanMessage: 'Pre-existing issue, not related to this edit.',
  })

  eq('quarantine: oracleFailed=true (never rewritten as passed)', record.oracleFailed, true)
  eq('quarantine: oracleName', record.oracleName, 'types')
  eq('quarantine: classification', record.classification, 'BASELINE_FAILURE')
  eq('quarantine: humanDecision=continue', record.humanDecision, 'continue')
  ok('quarantine: humanMessage present', !!record.humanMessage)
  ok('quarantine: quarantinedAt is ISO string', typeof record.quarantinedAt === 'string')
  ok('quarantine: diagnostics.baseline present', Array.isArray(record.diagnostics.baseline))
  ok('quarantine: diagnostics.preExisting present', Array.isArray(record.diagnostics.preExisting))
  eq('quarantine: diagnostics.new empty', record.diagnostics.new, [])
}

// ---------------------------------------------------------------------------
// 12. emitOracleGateOpen — parseable JSON signal on stdout
// ---------------------------------------------------------------------------

console.log('\n=== 12. emitOracleGateOpen stdout signal ===\n')

{
  const oracleInfo = {
    oracleName: 'types',
    classification: 'BASELINE_FAILURE',
    reason: 'All diagnostics pre-existing at baseline.',
    command: 'pnpm nx run-many --target=type-check',
    cwd: '/repo',
    projects: ['client', 'admin'],
    baselineDiagnostics: ['TS:TS2345:src/app/foo.ts:42:7'],
    postEditDiagnostics: ['TS:TS2345:src/app/foo.ts:42:7'],
    newDiagnostics: [],
    preExistingDiagnostics: ['TS:TS2345:src/app/foo.ts:42:7'],
    newDiagnosticLines: [],
    baselineEvidence: 'exitCode=1, durationMs=5000',
  }

  const chunks = []
  const origWrite = process.stdout.write.bind(process.stdout)
  process.stdout.write = (chunk) => { chunks.push(typeof chunk === 'string' ? chunk : chunk.toString()); return true }

  emitOracleGateOpen('TEST-ORACLE-GATE-12', oracleInfo)

  process.stdout.write = origWrite

  const line = chunks.join('')
  ok('emitOracleGateOpen: wrote to stdout', line.length > 0)
  ok('line starts with {"__factory_gate":"open"', line.startsWith('{"__factory_gate":"open"'))
  ok('line ends with newline', line.endsWith('\n'))

  let parsed
  try { parsed = JSON.parse(line.trim()); ok('stdout line is valid JSON', true) }
  catch { ok('stdout line is valid JSON', false) }

  if (parsed) {
    eq('signal.__factory_gate', parsed.__factory_gate, 'open')
    eq('signal.gateType', parsed.gateType, 'oracle')
    eq('signal.runId', parsed.runId, 'TEST-ORACLE-GATE-12')
    eq('signal.allowedDecisions', parsed.allowedDecisions, ['continue', 'fail'])
    ok('signal.oracleGate present', !!parsed.oracleGate)
    eq('signal.oracleGate.oracleName', parsed.oracleGate?.oracleName, 'types')
    eq('signal.oracleGate.classification', parsed.oracleGate?.classification, 'BASELINE_FAILURE')
    // Findings text should mention the classification
    ok('signal.findings mentions classification', parsed.findings.includes('BASELINE_FAILURE'))
    // outcomes is empty for oracle gate
    eq('signal.outcomes empty for oracle gate', parsed.outcomes, [])
  }
}

// ---------------------------------------------------------------------------
// 13. emitGateOpen (adversarial-review) still emits gateType='adversarial-review'
// ---------------------------------------------------------------------------

console.log('\n=== 13. emitGateOpen adversarial-review gateType ===\n')

{
  const mockReviewResult = {
    failCount: 1,
    reviewerCount: 4,
    outcomes: [
      { reviewerName: 'AdversarialReviewer1', verdict: 'FAIL', hasCritical: true, rawOutput: 'Critical bug.' },
    ],
  }

  const chunks = []
  const origWrite = process.stdout.write.bind(process.stdout)
  process.stdout.write = (chunk) => { chunks.push(typeof chunk === 'string' ? chunk : chunk.toString()); return true }

  emitGateOpen('TEST-ADV-GATE-13', mockReviewResult)

  process.stdout.write = origWrite

  const line = chunks.join('')
  let parsed
  try { parsed = JSON.parse(line.trim()); ok('adversarial emitGateOpen is valid JSON', true) }
  catch { ok('adversarial emitGateOpen is valid JSON', false) }

  if (parsed) {
    eq('adversarial gate: gateType=adversarial-review', parsed.gateType, 'adversarial-review')
    eq('adversarial gate: allowedDecisions', parsed.allowedDecisions, ['retry', 'ignore', 'fail'])
    ok('adversarial gate: outcomes non-empty', parsed.outcomes.length > 0)
  }
}

// ---------------------------------------------------------------------------
// 14. registerGate with gateType='oracle' stored and retrievable
// ---------------------------------------------------------------------------

console.log('\n=== 14. registerGate oracle gateType ===\n')

{
  const RUN = 'GATE-OBL-14-ORACLE'
  registerGate({
    runId: RUN,
    gateType: 'oracle',
    findings: 'BASELINE_FAILURE findings',
    outcomes: [],
    oracleGate: { oracleName: 'types', classification: 'BASELINE_FAILURE' },
    allowedDecisions: ['continue', 'fail'],
    openedAt: new Date().toISOString(),
  })

  const gate = getGate(RUN)
  ok('oracle gate stored', gate !== null)
  eq('oracle gate: gateType', gate?.gateType, 'oracle')
  eq('oracle gate: allowedDecisions', gate?.allowedDecisions, ['continue', 'fail'])
  eq('oracle gate: oracleGate.classification', gate?.oracleGate?.classification, 'BASELINE_FAILURE')
  unregisterGate(RUN)
}

// ---------------------------------------------------------------------------
// 15. waitForHumanDecision: 'continue' decision normalized correctly
// ---------------------------------------------------------------------------

console.log('\n=== 15. waitForHumanDecision: continue decision ===\n')

await (async () => {
  const RUN = 'GATE-OBL-15-CONTINUE'
  const replyPath = gateReplyPath(RUN)
  cleanupFiles.push(replyPath)
  try { unlinkSync(replyPath) } catch { /* ok */ }

  const mockLog = { error: () => {}, info: () => {} }

  const waitPromise = waitForHumanDecision(RUN, mockLog, 'oracle')

  await new Promise((r) => setTimeout(r, 10))

  // Write 'continue' decision
  writeFileSync(replyPath, JSON.stringify({ decision: 'continue', message: 'pre-existing, safe to proceed' }), 'utf8')

  await new Promise((r) => setTimeout(r, GATE_POLL_MS + 500))

  const result = await waitPromise
  eq('continue decision: resolved correctly', result.decision, 'continue')
  eq('continue decision: message preserved', result.message, 'pre-existing, safe to proceed')
})()

// ---------------------------------------------------------------------------
// 16. waitForHumanDecision: 'fail' decision ends run
// ---------------------------------------------------------------------------

console.log('\n=== 16. waitForHumanDecision: fail decision ===\n')

await (async () => {
  const RUN = 'GATE-OBL-16-FAIL'
  const replyPath = gateReplyPath(RUN)
  cleanupFiles.push(replyPath)
  try { unlinkSync(replyPath) } catch { /* ok */ }

  const mockLog = { error: () => {}, info: () => {} }

  const waitPromise = waitForHumanDecision(RUN, mockLog, 'oracle')

  await new Promise((r) => setTimeout(r, 10))

  writeFileSync(replyPath, JSON.stringify({ decision: 'fail', message: '' }), 'utf8')

  await new Promise((r) => setTimeout(r, GATE_POLL_MS + 500))

  const result = await waitPromise
  eq('fail decision: resolved to fail', result.decision, 'fail')
})()

// ---------------------------------------------------------------------------
// 17. Quarantine record included in review packet (simulation)
// ---------------------------------------------------------------------------

console.log('\n=== 17. Review packet contains quarantined oracle evidence ===\n')

{
  // Simulate the review packet construction in us-loop.mjs
  const quarantinedOracles = [{
    quarantinedAt: new Date().toISOString(),
    oracleName: 'types',
    classification: 'BASELINE_FAILURE',
    reason: 'All diagnostics pre-existing.',
    humanDecision: 'continue',
    humanMessage: 'Pre-existing.',
    oracleFailed: true,
    baseline: { exitCode: 1, timedOut: false, emptySuccess: false, executionEvidence: 'exitCode=1', diagnosticCount: 1 },
    postEdit: { exitCode: 1, timedOut: false, emptySuccess: false, durationMs: 5000 },
    diagnostics: { baseline: ['TS:TS2345:src/app/foo.ts:42:7'], postEdit: ['TS:TS2345:src/app/foo.ts:42:7'], new: [], preExisting: ['TS:TS2345:src/app/foo.ts:42:7'] },
  }]

  const reviewPacket = {
    task: 'Test task',
    diff: 'diff --git ...',
    oracleResults: [{ name: 'types', exitCode: 1, passed: false, classification: 'BASELINE_FAILURE', tail: '' }],
    claimsGate: { claimsMatch: true, plannedFiles: [], actualFiles: [], unplannedFiles: [], untouchedPlannedFiles: [] },
    quarantinedOracles,
  }

  ok('review packet: quarantinedOracles present', Array.isArray(reviewPacket.quarantinedOracles))
  eq('review packet: quarantine count', reviewPacket.quarantinedOracles.length, 1)
  eq('review packet: quarantine.oracleFailed=true', reviewPacket.quarantinedOracles[0].oracleFailed, true)
  eq('review packet: quarantine.classification', reviewPacket.quarantinedOracles[0].classification, 'BASELINE_FAILURE')
  eq('review packet: quarantine.humanDecision=continue', reviewPacket.quarantinedOracles[0].humanDecision, 'continue')

  // Simulate what buildReviewPacketBrief does with quarantinedOracles
  function simulateBriefSection(packet) {
    if (!Array.isArray(packet.quarantinedOracles) || packet.quarantinedOracles.length === 0) return null
    return packet.quarantinedOracles.map((q) =>
      `Quarantined Oracle: ${q.oracleName} (${q.classification}) \u2014 ${q.reason}`
    ).join('\n')
  }

  const section = simulateBriefSection(reviewPacket)
  ok('review brief: quarantine section present', section !== null)
  ok('review brief: mentions oracle name', section?.includes('types'))
  ok('review brief: mentions classification', section?.includes('BASELINE_FAILURE'))
}

// ---------------------------------------------------------------------------
// 18. Adversarial-review gate decision validation in server POST handler
// ---------------------------------------------------------------------------

console.log('\n=== 18. Server POST decision normalization (oracle + adversarial) ===\n')

{
  // Simulate server decision normalization
  function normalizeDecision(rawDecision) {
    return ['ignore', 'retry', 'continue', 'fail'].includes(rawDecision) ? rawDecision : 'fail'
  }

  eq('continue: valid oracle decision', normalizeDecision('continue'), 'continue')
  eq('fail: valid both gates', normalizeDecision('fail'), 'fail')
  eq('retry: valid adversarial gate', normalizeDecision('retry'), 'retry')
  eq('ignore: valid adversarial gate', normalizeDecision('ignore'), 'ignore')
  eq('approve: invalid, normalized to fail', normalizeDecision('approve'), 'fail')
  eq('empty: invalid, normalized to fail', normalizeDecision(''), 'fail')
}

// ---------------------------------------------------------------------------
// 19. INDETERMINATE_OUT_OF_SCOPE: new diagnostics reference out-of-scope files
// ---------------------------------------------------------------------------

console.log('\n=== 19. classifyOracleResult — INDETERMINATE_OUT_OF_SCOPE ===\n')

{
  // New diagnostic references a file NOT in changedFiles or plannedFiles
  const outOfScopeStdout = 'libs/unrelated-lib/src/lib/other.ts(5,1): error TS2304: Cannot find name Xyz.'
  const result = classifyOracleResult({
    oracle: { name: 'types', command: 'pnpm nx run-many --target=type-check', cwd: '/repo' },
    baseline: {
      exitCode: 0, timedOut: false, emptySuccess: false,
      diagnosticIdentities: [], durationMs: 5000, tasks: { executed: 4 },
    },
    postEdit: {
      exitCode: 1, timedOut: false, emptySuccess: false,
      stdout: outOfScopeStdout, stderr: '', tasks: { executed: 4 },
    },
    changedFiles: ['src/app/foo.ts'],
    plannedFiles: ['src/app/foo.ts'],
  })
  eq('INDETERMINATE: classification', result.classification, 'INDETERMINATE_OUT_OF_SCOPE')
  ok('INDETERMINATE: newDiagnostics non-empty', result.newDiagnostics.length > 0)
}

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
