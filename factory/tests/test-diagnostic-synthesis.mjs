import {
  buildDiagnosticPacket,
  shouldSynthesizeDiagnostics,
  parseDiagnosticSynthesis,
  runDiagnosticSynthesis,
  routeDiagnosticSynthesis,
  writeDiagnosticSynthesisArtifact,
} from '../lib/diagnostic-synthesis.mjs'
import { existsSync, readFileSync } from 'node:fs'

let failed = 0
function expect(name, actual, expected) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected)
  console.log(`${ok ? '\u2713' : '\u2717'} ${name}`)
  if (!ok) { console.log(' expected:', expected, 'actual:', actual); failed++ }
}
function expectTrue(name, actual) {
  const ok = Boolean(actual)
  console.log(`${ok ? '\u2713' : '\u2717'} ${name}`)
  if (!ok) { console.log(' actual:', actual); failed++ }
}
function expectFalse(name, actual) {
  const ok = !actual
  console.log(`${ok ? '\u2713' : '\u2717'} ${name}`)
  if (!ok) { console.log(' actual:', actual); failed++ }
}

// ---------------------------------------------------------------------------
// 1. buildDiagnosticPacket — bounds and sanitization
// ---------------------------------------------------------------------------
console.log('\n--- buildDiagnosticPacket ---')

const classification = {
  classification: 'PRODUCT_REGRESSION', postEditIdentities: ['TS:TS2345:src/a.ts:4:2'],
  newDiagnostics: ['TS:TS2345:src/a.ts:4:2'], preExistingDiagnostics: [],
  newDiagnosticLines: ['src/a.ts(4,2): error TS2345: bad value'],
}
const packet = buildDiagnosticPacket({
  oracle: { name: 'types', command: 'pnpm nx type-check', cwd: '/repo' },
  baseline: { exitCode: 0, timedOut: false, emptySuccess: false, durationMs: 12, diagnosticIdentities: [] },
  postEdit: { exitCode: 1, durationMs: 42, timedOut: false, emptySuccess: false, tasks: { executed: 2, fromCache: 1 } },
  classificationResult: classification, plannedFiles: ['src/a.ts'], changedFiles: ['src/a.ts'],
})
expect('packet keeps command and deterministic verdict facts', packet.oracle.exitCode, 1)
expectFalse('packet has no raw stdout', Object.hasOwn(packet, 'stdout'))
expect('packet includes baseline/post-edit identities', packet.postEdit.newDiagnostics, classification.newDiagnostics)

// Oversized oracle name is truncated
const longNamePacket = buildDiagnosticPacket({
  oracle: { name: 'x'.repeat(300), command: 'cmd', cwd: '/r' },
  baseline: null,
  postEdit: { exitCode: 1, durationMs: 0, timedOut: false, emptySuccess: false, tasks: {} },
  classificationResult: { classification: 'PRODUCT_REGRESSION', newDiagnosticLines: [], newDiagnostics: [], preExistingDiagnostics: [], postEditIdentities: [] },
  plannedFiles: [], changedFiles: [],
})
expect('oracle name truncated to MAX_ORACLE_NAME_LENGTH', longNamePacket.oracle.name.length, 128)

// Oversized diagnostic line is truncated
const longLineClassification = {
  classification: 'PRODUCT_REGRESSION',
  newDiagnosticLines: ['x'.repeat(2000)],
  newDiagnostics: [], preExistingDiagnostics: [], postEditIdentities: [],
}
const longLinePacket = buildDiagnosticPacket({
  oracle: { name: 'types', command: 'cmd', cwd: '/r' },
  baseline: null,
  postEdit: { exitCode: 1, durationMs: 0, timedOut: false, emptySuccess: false, tasks: {} },
  classificationResult: longLineClassification,
  plannedFiles: [], changedFiles: [],
})
expectTrue('diagnostic excerpt truncated to MAX_DIAGNOSTIC_LINE_LENGTH', longLinePacket.postEdit.diagnosticExcerpts.every(l => l.length <= 1000))

// NaN/Infinity numeric fields clamped to 0
const nanPacket = buildDiagnosticPacket({
  oracle: { name: 'types', command: 'cmd', cwd: '/r' },
  baseline: null,
  postEdit: { exitCode: NaN, durationMs: Infinity, timedOut: false, emptySuccess: false, tasks: { executed: NaN } },
  classificationResult: { classification: 'PRODUCT_REGRESSION', newDiagnosticLines: [], newDiagnostics: [], preExistingDiagnostics: [], postEditIdentities: [] },
  plannedFiles: [], changedFiles: [],
})
expect('NaN exitCode clamped to 0', nanPacket.oracle.exitCode, 0)
expect('Infinity durationMs clamped to 0', nanPacket.oracle.durationMs, 0)
expect('NaN tasks.executed clamped to 0', nanPacket.oracle.tasks.executed, 0)

// More than MAX_FILES files are truncated
const manyFiles = Array.from({ length: 50 }, (_, i) => `src/file${i}.ts`)
const manyFilesPacket = buildDiagnosticPacket({
  oracle: { name: 'types', command: 'cmd', cwd: '/r' },
  baseline: null,
  postEdit: { exitCode: 1, durationMs: 0, timedOut: false, emptySuccess: false, tasks: {} },
  classificationResult: { classification: 'PRODUCT_REGRESSION', newDiagnosticLines: [], newDiagnostics: [], preExistingDiagnostics: [], postEditIdentities: [] },
  plannedFiles: manyFiles, changedFiles: manyFiles,
})
expect('planned files truncated to MAX_FILES', manyFilesPacket.files.planned.length, 20)
expect('changed files truncated to MAX_FILES', manyFilesPacket.files.modified.length, 20)

// File path longer than MAX_FILE_PATH_LENGTH is truncated
const longPath = 'src/' + 'a'.repeat(400) + '.ts'
const longPathPacket = buildDiagnosticPacket({
  oracle: { name: 'types', command: 'cmd', cwd: '/r' },
  baseline: null,
  postEdit: { exitCode: 1, durationMs: 0, timedOut: false, emptySuccess: false, tasks: {} },
  classificationResult: { classification: 'PRODUCT_REGRESSION', newDiagnosticLines: [], newDiagnostics: [], preExistingDiagnostics: [], postEditIdentities: [] },
  plannedFiles: [longPath], changedFiles: [],
})
expectTrue('long file path truncated', longPathPacket.files.planned[0].length <= 300)

// ---------------------------------------------------------------------------
// 2. shouldSynthesizeDiagnostics
// ---------------------------------------------------------------------------
console.log('\n--- shouldSynthesizeDiagnostics ---')

expect('do not synthesize when deterministic diagnostic line is actionable', shouldSynthesizeDiagnostics({ classificationResult: classification, postEdit: {} }), false)
expect('synthesize PRODUCT_REGRESSION with no diagnostic lines', shouldSynthesizeDiagnostics({ classificationResult: { ...classification, newDiagnosticLines: [] }, postEdit: {} }), true)
expect('synthesize INDETERMINATE_OUT_OF_SCOPE', shouldSynthesizeDiagnostics({ classificationResult: { ...classification, classification: 'INDETERMINATE_OUT_OF_SCOPE' }, postEdit: {} }), true)
expect('never synthesize timeout', shouldSynthesizeDiagnostics({ classificationResult: { ...classification, classification: 'INDETERMINATE_OUT_OF_SCOPE' }, postEdit: { timedOut: true } }), false)
expect('never synthesize emptySuccess', shouldSynthesizeDiagnostics({ classificationResult: { ...classification, classification: 'INDETERMINATE_OUT_OF_SCOPE' }, postEdit: { emptySuccess: true } }), false)

// ---------------------------------------------------------------------------
// 3. routeDiagnosticSynthesis
// ---------------------------------------------------------------------------
console.log('\n--- routeDiagnosticSynthesis ---')

expect('actionable routes to editor', routeDiagnosticSynthesis({ status: 'actionable' }), 'editor')
expect('ambiguous routes to human gate', routeDiagnosticSynthesis({ status: 'ambiguous' }), 'human-gate')
expect('insufficient-evidence routes to human gate', routeDiagnosticSynthesis({ status: 'insufficient-evidence' }), 'human-gate')
expect('null routes to none', routeDiagnosticSynthesis(null), 'none')
expect('undefined routes to none', routeDiagnosticSynthesis(undefined), 'none')

// ---------------------------------------------------------------------------
// 4. parseDiagnosticSynthesis — schema enforcement and actionable semantics
// ---------------------------------------------------------------------------
console.log('\n--- parseDiagnosticSynthesis ---')

const valid = parseDiagnosticSynthesis('```json\n{"status":"actionable","summary":"Fix the narrowed type mismatch.","diagnostics":[{"diagnostic":"TS2345","evidence":"src/a.ts(4,2)","provenance":"post-edit"}],"files":["src/a.ts"]}\n```')
expect('accepts bounded valid synthesis JSON', valid.ok, true)
expect('rejects unknown status', parseDiagnosticSynthesis('{"status":"pass","summary":"x"}').ok, false)
expect('rejects missing JSON', parseDiagnosticSynthesis('narrative only').ok, false)
expect('rejects oversized summary', parseDiagnosticSynthesis(JSON.stringify({ status: 'actionable', summary: 'x'.repeat(601) })).ok, false)
expect('rejects oversized reason', parseDiagnosticSynthesis(JSON.stringify({ status: 'ambiguous', summary: 'ok', reason: 'x'.repeat(601) })).ok, false)

// actionable with no diagnostics and no files is rejected
const emptyActionable = parseDiagnosticSynthesis(JSON.stringify({ status: 'actionable', summary: 'ok', diagnostics: [], files: [] }))
expect('actionable with empty diagnostics and files rejected (SYNTHESIS_INSUFFICIENT_EVIDENCE)', emptyActionable.errorCode, 'SYNTHESIS_INSUFFICIENT_EVIDENCE')

// actionable with empty summary is rejected
const emptySummaryActionable = parseDiagnosticSynthesis(JSON.stringify({ status: 'actionable', summary: '', diagnostics: [{ diagnostic: 'TS1', evidence: 'e', provenance: 'post-edit' }], files: [] }))
expect('actionable with empty summary rejected', emptySummaryActionable.ok, false)

// ambiguous with empty diagnostics/files is accepted
const emptyAmbiguous = parseDiagnosticSynthesis(JSON.stringify({ status: 'ambiguous', summary: 'Two causes.', diagnostics: [], files: [] }))
expect('ambiguous with empty diagnostics accepted', emptyAmbiguous.ok, true)

// ---------------------------------------------------------------------------
// 5. preflightReadOnlyAgent — allow-list enforcement
// ---------------------------------------------------------------------------
console.log('\n--- preflightReadOnlyAgent (via runDiagnosticSynthesis) ---')

// Helper factory for integration-specific ops
function makeOps(integrations, agentIntegrations = null) {
  const agentIntKeys = agentIntegrations ?? Object.fromEntries(integrations.map(i => [i.name, {}]))
  return {
    async preflightAgent() { return { ok: true, reason: null, agent: { integrations: agentIntKeys, subAgents: [] } } },
    async listIntegrations() { return integrations },
    async createCase() { return { id: 'case-1' } },
    async runAgentTurn() { return { status: 'finished', message: JSON.stringify({ status: 'ambiguous', summary: 'Two causes remain.', diagnostics: [], files: [] }), killedByBudget: false } },
  }
}

// FILE_ACCESS readOnly: true — allowed
const readOnlyOps = makeOps([{ name: 'files', integrationType: 'FILE_ACCESS', parameters: { readOnly: true } }])
const result = await runDiagnosticSynthesis({ namespaceId: 'ns', agentName: 'synth', packet, agentOps: readOnlyOps })
expect('FILE_ACCESS readOnly allowed — synthesis runs', result.synthesis?.status, 'ambiguous')

// FILE_ACCESS without readOnly: true — rejected
const nonReadOnlyOps = makeOps([{ name: 'files', integrationType: 'FILE_ACCESS', parameters: { readOnly: false } }])
const nonReadOnly = await runDiagnosticSynthesis({ namespaceId: 'ns', agentName: 'synth', packet, agentOps: nonReadOnlyOps })
expect('FILE_ACCESS without readOnly rejected', nonReadOnly.errorCode, 'SYNTHESIS_PREFLIGHT_FAILED')

// BASH — rejected (not in allow-list)
const bashOps = makeOps([{ name: 'shell', integrationType: 'BASH', parameters: {} }])
const blocked = await runDiagnosticSynthesis({ namespaceId: 'ns', agentName: 'synth', packet, agentOps: bashOps })
expect('BASH mutating capability fails closed', blocked.errorCode, 'SYNTHESIS_PREFLIGHT_FAILED')

// WEBHOOK — rejected
const webhookOps = makeOps([{ name: 'hook', integrationType: 'WEBHOOK', parameters: {} }])
const blockedWebhook = await runDiagnosticSynthesis({ namespaceId: 'ns', agentName: 'synth', packet, agentOps: webhookOps })
expect('WEBHOOK rejected', blockedWebhook.errorCode, 'SYNTHESIS_PREFLIGHT_FAILED')

// CASE_FILE_EXCHANGE — rejected (not in allow-list)
const exchangeOps = makeOps([{ name: 'exchange', integrationType: 'CASE_FILE_EXCHANGE', parameters: {} }])
const blockedExchange = await runDiagnosticSynthesis({ namespaceId: 'ns', agentName: 'synth', packet, agentOps: exchangeOps })
expect('CASE_FILE_EXCHANGE rejected (not in allow-list)', blockedExchange.errorCode, 'SYNTHESIS_PREFLIGHT_FAILED')

// NAMESPACE_FILE_EXCHANGE — rejected
const nsExchangeOps = makeOps([{ name: 'nsex', integrationType: 'NAMESPACE_FILE_EXCHANGE', parameters: {} }])
const blockedNsExchange = await runDiagnosticSynthesis({ namespaceId: 'ns', agentName: 'synth', packet, agentOps: nsExchangeOps })
expect('NAMESPACE_FILE_EXCHANGE rejected (not in allow-list)', blockedNsExchange.errorCode, 'SYNTHESIS_PREFLIGHT_FAILED')

// Unknown integration type — rejected (fail-closed)
const unknownOps = makeOps([{ name: 'custom', integrationType: 'CUSTOM_UNKNOWN', parameters: {} }])
const blockedUnknown = await runDiagnosticSynthesis({ namespaceId: 'ns', agentName: 'synth', packet, agentOps: unknownOps })
expect('Unknown integration type rejected (fail-closed)', blockedUnknown.errorCode, 'SYNTHESIS_PREFLIGHT_FAILED')

// AI integration — allowed
const aiOps = makeOps([{ name: 'ai', integrationType: 'AI', parameters: {} }])
const aiResult = await runDiagnosticSynthesis({ namespaceId: 'ns', agentName: 'synth', packet, agentOps: aiOps })
expect('AI integration allowed', aiResult.ok, true)

// MEMORY without readOnly: true — rejected (exposes curate/edit/delete by default)
const memoryMutatingOps = makeOps([{ name: 'mem', integrationType: 'MEMORY', parameters: {} }])
const blockedMemory = await runDiagnosticSynthesis({ namespaceId: 'ns', agentName: 'synth', packet, agentOps: memoryMutatingOps })
expect('MEMORY without readOnly rejected (may expose curate/edit/delete)', blockedMemory.errorCode, 'SYNTHESIS_PREFLIGHT_FAILED')

// MEMORY with readOnly: true — allowed
const memoryReadOnlyOps = makeOps([{ name: 'mem', integrationType: 'MEMORY', parameters: { readOnly: true } }])
const allowedMemory = await runDiagnosticSynthesis({ namespaceId: 'ns', agentName: 'synth', packet, agentOps: memoryReadOnlyOps })
expect('MEMORY with readOnly: true allowed', allowedMemory.ok, true)

// ATLASSIAN with empty tools array — rejected (fail-closed: default may grant writes)
const atlassianEmptyOps = makeOps([{ name: 'jira', integrationType: 'ATLASSIAN', parameters: { tools: [] } }])
const blockedAtlassianEmpty = await runDiagnosticSynthesis({ namespaceId: 'ns', agentName: 'synth', packet, agentOps: atlassianEmptyOps })
expect('ATLASSIAN with empty tools rejected (fail-closed)', blockedAtlassianEmpty.errorCode, 'SYNTHESIS_PREFLIGHT_FAILED')

// ATLASSIAN with absent tools — rejected
const atlassianNoToolsOps = makeOps([{ name: 'jira', integrationType: 'ATLASSIAN', parameters: {} }])
const blockedAtlassianNoTools = await runDiagnosticSynthesis({ namespaceId: 'ns', agentName: 'synth', packet, agentOps: atlassianNoToolsOps })
expect('ATLASSIAN with absent tools rejected (fail-closed)', blockedAtlassianNoTools.errorCode, 'SYNTHESIS_PREFLIGHT_FAILED')

// ATLASSIAN with explicit read-only tools — allowed
const atlassianReadOnlyOps = makeOps([{ name: 'jira', integrationType: 'ATLASSIAN', parameters: { tools: ['getIssue', 'searchIssues'] } }])
const allowedAtlassian = await runDiagnosticSynthesis({ namespaceId: 'ns', agentName: 'synth', packet, agentOps: atlassianReadOnlyOps })
expect('ATLASSIAN with explicit read-only tools allowed', allowedAtlassian.ok, true)

// GITHUB with absent tools — rejected
const githubNoToolsOps = makeOps([{ name: 'gh', integrationType: 'GITHUB', parameters: {} }])
const blockedGithubNoTools = await runDiagnosticSynthesis({ namespaceId: 'ns', agentName: 'synth', packet, agentOps: githubNoToolsOps })
expect('GITHUB with absent tools rejected (fail-closed)', blockedGithubNoTools.errorCode, 'SYNTHESIS_PREFLIGHT_FAILED')

// GITHUB with explicit read-only tools — allowed
const githubReadOnlyOps = makeOps([{ name: 'gh', integrationType: 'GITHUB', parameters: { tools: ['getIssue', 'listPullRequests'] } }])
const allowedGithub = await runDiagnosticSynthesis({ namespaceId: 'ns', agentName: 'synth', packet, agentOps: githubReadOnlyOps })
expect('GITHUB with explicit read-only tools allowed', allowedGithub.ok, true)

// ---------------------------------------------------------------------------
// 6. runDiagnosticSynthesis — lifecycle facts and error paths
// ---------------------------------------------------------------------------
console.log('\n--- runDiagnosticSynthesis lifecycle ---')

// preflightAgent throws — returns structured error (not unhandled rejection)
const preflightThrowOps = {
  async preflightAgent() { throw new Error('Network timeout during preflight') },
  async listIntegrations() { return [] },
  async createCase() { return { id: 'unreachable' } },
  async runAgentTurn() { return { status: 'finished', message: '{}', killedByBudget: false } },
}
const preflightThrowResult = await runDiagnosticSynthesis({ namespaceId: 'ns', agentName: 'synth', packet, agentOps: preflightThrowOps })
expect('preflightAgent throws — SYNTHESIS_PREFLIGHT_ERROR (structured result)', preflightThrowResult.errorCode, 'SYNTHESIS_PREFLIGHT_ERROR')
expect('preflightAgent throws — ok false', preflightThrowResult.ok, false)
expect('preflightAgent throws — caseId null', preflightThrowResult.caseId, null)
expect('preflightAgent throws — killedByBudget false', preflightThrowResult.killedByBudget, false)
expect('preflightAgent throws — agentIdentity preserved', preflightThrowResult.agentIdentity, 'synth')

// listIntegrations throws — also returns structured error
const listIntegrationsThrowOps = {
  async preflightAgent() { return { ok: true, reason: null, agent: { integrations: { ai: {} }, subAgents: [] } } },
  async listIntegrations() { throw new Error('DB connection failed') },
  async createCase() { return { id: 'unreachable' } },
  async runAgentTurn() { return { status: 'finished', message: '{}', killedByBudget: false } },
}
const listIntegrationsThrowResult = await runDiagnosticSynthesis({ namespaceId: 'ns', agentName: 'synth', packet, agentOps: listIntegrationsThrowOps })
expect('listIntegrations throws — SYNTHESIS_PREFLIGHT_ERROR (structured result)', listIntegrationsThrowResult.errorCode, 'SYNTHESIS_PREFLIGHT_ERROR')
expect('listIntegrations throws — caseId null', listIntegrationsThrowResult.caseId, null)

// createCase throws — returns structured error
const createCaseThrowOps = {
  async preflightAgent() { return { ok: true, reason: null, agent: { integrations: {}, subAgents: [] } } },
  async listIntegrations() { return [] },
  async createCase() { throw new Error('Network error') },
}
const createCaseFail = await runDiagnosticSynthesis({ namespaceId: 'ns', agentName: 'synth', packet, agentOps: createCaseThrowOps })
expect('createCase throws — SYNTHESIS_AGENT_ERROR', createCaseFail.errorCode, 'SYNTHESIS_AGENT_ERROR')
expect('createCase throws — caseId null', createCaseFail.caseId, null)
expect('createCase throws — killedByBudget false', createCaseFail.killedByBudget, false)

// runAgentTurn throws — returns structured error with caseId
const turnThrowOps = {
  async preflightAgent() { return { ok: true, reason: null, agent: { integrations: {}, subAgents: [] } } },
  async listIntegrations() { return [] },
  async createCase() { return { id: 'case-throw-1' } },
  async runAgentTurn() { throw new Error('Turn error') },
}
const turnFail = await runDiagnosticSynthesis({ namespaceId: 'ns', agentName: 'synth', packet, agentOps: turnThrowOps })
expect('runAgentTurn throws — SYNTHESIS_AGENT_ERROR', turnFail.errorCode, 'SYNTHESIS_AGENT_ERROR')
expect('runAgentTurn throws — caseId preserved', turnFail.caseId, 'case-throw-1')

// timeout turn status
const timeoutOps = {
  async preflightAgent() { return { ok: true, reason: null, agent: { integrations: {}, subAgents: [] } } },
  async listIntegrations() { return [] },
  async createCase() { return { id: 'case-timeout-1' } },
  async runAgentTurn() { return { status: 'work_timeout', message: null, killedByBudget: true } },
}
const timeoutResult = await runDiagnosticSynthesis({ namespaceId: 'ns', agentName: 'synth', packet, agentOps: timeoutOps })
expect('timeout — SYNTHESIS_AGENT_WORK_TIMEOUT', timeoutResult.errorCode, 'SYNTHESIS_AGENT_WORK_TIMEOUT')
expect('timeout — caseId preserved', timeoutResult.caseId, 'case-timeout-1')
expect('timeout — turnStatus preserved', timeoutResult.turnStatus, 'work_timeout')
expect('timeout — killedByBudget preserved', timeoutResult.killedByBudget, true)

// malformed output (no JSON)
const malformedOps = {
  async preflightAgent() { return { ok: true, reason: null, agent: { integrations: {}, subAgents: [] } } },
  async listIntegrations() { return [] },
  async createCase() { return { id: 'case-malformed-1' } },
  async runAgentTurn() { return { status: 'finished', message: 'Sorry I cannot help with that.', killedByBudget: false } },
}
const malformedResult = await runDiagnosticSynthesis({ namespaceId: 'ns', agentName: 'synth', packet, agentOps: malformedOps })
expect('malformed output — SYNTHESIS_NO_JSON', malformedResult.errorCode, 'SYNTHESIS_NO_JSON')
expect('malformed output — caseId preserved', malformedResult.caseId, 'case-malformed-1')
expect('malformed output — agentIdentity preserved', malformedResult.agentIdentity, 'synth')

// Successful run returns lifecycle facts
const successOps = {
  async preflightAgent() { return { ok: true, reason: null, agent: { integrations: {}, subAgents: [] } } },
  async listIntegrations() { return [] },
  async createCase() { return { id: 'case-success-1' } },
  async runAgentTurn() { return { status: 'finished', message: JSON.stringify({ status: 'ambiguous', summary: 'Two causes.', diagnostics: [], files: [] }), killedByBudget: false } },
}
const successResult = await runDiagnosticSynthesis({ namespaceId: 'ns', agentName: 'synth', packet, agentOps: successOps })
expect('success — ok true', successResult.ok, true)
expect('success — caseId preserved', successResult.caseId, 'case-success-1')
expect('success — agentIdentity preserved', successResult.agentIdentity, 'synth')
expect('success — turnStatus finished', successResult.turnStatus, 'finished')
expect('success — killedByBudget false', successResult.killedByBudget, false)
expect('success — rawOutput not null', typeof successResult.rawOutput, 'string')

// ---------------------------------------------------------------------------
// 7. writeDiagnosticSynthesisArtifact — path safety and exclusivity
// ---------------------------------------------------------------------------
console.log('\n--- writeDiagnosticSynthesisArtifact ---')

// Unsafe runId rejected
const unsafeRunId = writeDiagnosticSynthesisArtifact('../traversal', 'types', 1, 1, 'output')
expect('traversal in runId rejected', unsafeRunId.error, 'UNSAFE_ARTIFACT_COMPONENT:runId')

// Unsafe oracleName rejected
const unsafeOracle = writeDiagnosticSynthesisArtifact('run-safe', 'type/check', 1, 1, 'output')
expect('slash in oracleName rejected', unsafeOracle.error, 'UNSAFE_ARTIFACT_COMPONENT:oracleName')

// Null byte in runId rejected
const nullRunId = writeDiagnosticSynthesisArtifact('run\x00id', 'types', 1, 1, 'output')
expect('null byte in runId rejected', nullRunId.error, 'UNSAFE_ARTIFACT_COMPONENT:runId')

// Valid artifact creation succeeds
const safeRunId = 'test-run-' + Date.now() + '-' + Math.random().toString(36).slice(2)
const artifactResult = writeDiagnosticSynthesisArtifact(safeRunId, 'types', 1, 1, 'synthesis output')
expectTrue('valid artifact written — artifactPath present', typeof artifactResult.artifactPath === 'string')
expectTrue('valid artifact written — artifactHash sha256', /^[0-9a-f]{64}$/.test(artifactResult.artifactHash ?? ''))
expectTrue('valid artifact written — artifactRef present', typeof artifactResult.artifactRef === 'string')

// Duplicate artifact rejected with ARTIFACT_COLLISION
const collision = writeDiagnosticSynthesisArtifact(safeRunId, 'types', 1, 1, 'different output')
expect('duplicate artifact rejected — ARTIFACT_COLLISION', collision.error, 'ARTIFACT_COLLISION')

// Verify the artifact is a structured JSON envelope
if (artifactResult.artifactPath) {
  try {
    const { join: pathJoin, dirname: pathDirname } = await import('node:path')
    const { fileURLToPath: fu } = await import('node:url')
    const __d = pathDirname(fu(import.meta.url))
    const artifactFullPath = pathJoin(__d, '..', artifactResult.artifactPath)
    const envelope = JSON.parse(readFileSync(artifactFullPath, 'utf8'))
    expect('artifact envelope has schemaVersion', envelope.schemaVersion, 1)
    expect('artifact envelope has kind', envelope.kind, 'diagnostic-synthesis')
    expectTrue('artifact envelope has rawOutput string', typeof envelope.rawOutput === 'string')
    expectTrue('artifact envelope has sha256', typeof envelope.sha256 === 'string')
  } catch (err) {
    console.log('  (artifact filesystem check skipped: ' + err.message + ')')
  }
}

console.log(`\nResultat: ${failed === 0 ? 'OK' : `${failed} failure(s)`}`)
process.exit(failed ? 1 : 0)
