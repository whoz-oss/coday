/**
 * Forge Ledger → generic workflow read-only projection tests (Step 1 of the
 * Forge Ledger convergence).
 *
 * Offline, framework-free suite. Exit code: 0 = every case passed, 1 = at least
 * one failure. Run with plain `node`:
 *
 *   node factory/tests/test-forge-ledger-projection.mjs
 *
 * Coverage:
 *   1. Real `createEpicRun` ledger (tmp dir) parsed by `parseForgeLedger` and
 *      projected: epic/story `ready`, G1 `waiting_human`, a valid approval
 *      interaction opening, and the unmappable G1 fields reported.
 *   2. A synthetic full lifecycle (`run_started` … `story_g3_evaluated`):
 *      evidences, interactions, transitions and the exhaustive list of
 *      non-whitelisted Forge fields in `unmappedEvents`.
 *   3. Every projected `WorkflowEvidenceInput` is re-validated through the
 *      public `validateWorkflowEvidenceInput` (TS source and runtime bundle must
 *      agree).
 *   4. An invalid artifact hash yields `isValid: false` with a validation error
 *      (never silently dropped).
 *   5. An unrecognized event is reported with reason `unmapped_event_type`.
 *   6. Domain purity: the projection source imports no I/O and the projection
 *      never mutates its input.
 *
 * Usage : node factory/tests/test-forge-ledger-projection.mjs
 */

import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync } from 'node:fs'
import { register } from 'node:module'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

// Register the `.js` → `.ts` resolver before importing the TypeScript domain
// module directly (the runtime bundle is generated and must not be edited).
register(new URL('./support/node-ts-resolve-hook.mjs', import.meta.url))

const { projectForgeLedgerToGeneric, FORGE_FACT_KEY_WHITELIST } = await import(
  '../src/domain/forge-bmad/forge-ledger-projection.ts'
)
const { validateWorkflowEvidenceInput } = await import('../lib/workflow-evidence.mjs')
const { resolveForgeRoots } = await import('../lib/forge-roots.mjs')
const { createEpicRun, parseForgeLedger } = await import('../lib/forge-ledger.mjs')

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

let passed = 0
let failed = 0

function test(name, fn) {
  try {
    fn()
    console.log(`\u2713 ${name}`)
    passed++
  } catch (error) {
    console.error(`\u2717 ${name}\n   ${error?.stack ?? error}`)
    failed++
  }
}

const sha256 = (char) => `sha256:${char.repeat(64)}`

// ---------------------------------------------------------------------------
// 1. Real `createEpicRun` ledger
// ---------------------------------------------------------------------------

test('createEpicRun ledger projects epic/story readiness and a valid G1 opening', () => {
  const root = mkdtempSync(join(tmpdir(), 'factory-forge-projection-'))
  const orchestratorRoot = join(root, 'central-factory')
  const repoRoot = join(root, 'target-repository')
  mkdirSync(orchestratorRoot, { recursive: true })
  mkdirSync(repoRoot)
  const roots = resolveForgeRoots({ orchestratorRoot, runStoreRoot: join(orchestratorRoot, 'runs'), repoRoot })
  const run = createEpicRun({
    roots,
    runId: 'epic_fixture',
    epic: { id: 'WZ-812', kind: 'Epic' },
    stories: [{ id: 'WZ-813', kind: 'Story' }],
  })
  const events = parseForgeLedger(run.filePath)
  const projection = projectForgeLedgerToGeneric(events)

  assert.deepEqual(
    projection.transitions.map((transition) => `${transition.workflowId}/${transition.stepId}=${transition.status}`),
    ['epic_fixture/epic-run=ready', `${run.storyRuns[0].runId}/story-run=ready`, 'epic_fixture/G1=waiting_human']
  )

  const opens = projection.interactions.filter((interaction) => interaction.type === 'open')
  assert.equal(opens.length, 1)
  assert.equal(opens[0].isValid, true)
  assert.equal(opens[0].openInput.kind, 'approval')
  assert.equal(opens[0].openInput.workflowId, 'epic_fixture')
  assert.equal(opens[0].openInput.stepId, 'G1')
  assert.deepEqual(
    opens[0].openInput.actions.map((action) => action.id),
    ['approve', 'reject']
  )

  // `requiredDecision` and `policyVersion` have no generic home and are flagged.
  const flaggedFields = new Set(projection.unmappedEvents.map((unmapped) => unmapped.field))
  assert.ok(flaggedFields.has('requiredDecision'))
  assert.ok(flaggedFields.has('policyVersion'))
  assert.ok(
    projection.unmappedEvents.every((unmapped) => !FORGE_FACT_KEY_WHITELIST.has(unmapped.field)),
    'flagged fields must be outside the FACT_KEYS whitelist'
  )
})

// ---------------------------------------------------------------------------
// 2. Synthetic full lifecycle
// ---------------------------------------------------------------------------

const EPIC_RUN_ID = 'epic_fixture'
const STORY_RUN_ID = 'story_fixture'

const LIFECYCLE = Object.freeze([
  {
    schemaVersion: 1,
    event: 'run_started',
    runId: EPIC_RUN_ID,
    runType: 'EpicRun',
    workflow: 'forge-epic-v1',
    workItem: { id: 'WZ-812', kind: 'Epic' },
    roots: { repoRoot: '/tmp/repo' },
    at: '2026-01-01T00:00:00.000Z',
  },
  {
    schemaVersion: 1,
    event: 'story_run_created',
    runId: STORY_RUN_ID,
    parentRunId: EPIC_RUN_ID,
    runType: 'StoryRun',
    ordinal: 1,
    workItem: { id: 'WZ-813', kind: 'Story' },
    at: '2026-01-01T00:00:01.000Z',
  },
  {
    schemaVersion: 1,
    event: 'gate_started',
    runId: EPIC_RUN_ID,
    gate: 'G1',
    attempt: 1,
    status: 'waiting_human',
    requiredDecision: 'intent-approval',
    policyVersion: 'forge-g1-human-v1',
    at: '2026-01-01T00:00:02.000Z',
  },
  {
    schemaVersion: 1,
    event: 'human_decision_recorded',
    decisionId: 'decision_fixture',
    runId: EPIC_RUN_ID,
    gate: 'G1',
    attempt: 1,
    policyVersion: 'forge-g1-human-v1',
    evidenceSetHash: sha256('f'),
    decision: {
      actorId: 'user-1',
      authorityId: 'authority-1',
      outcome: 'approved',
      reasonCode: 'intent_confirmed',
    },
    idempotencyKey: 'g1-fingerprint',
    at: '2026-01-01T00:00:03.000Z',
  },
  {
    schemaVersion: 1,
    event: 'g2_evaluated',
    runId: EPIC_RUN_ID,
    gate: 'G2',
    attempt: 1,
    status: 'passed',
    code: 'G2_SPEC_VALID',
    policyVersion: 'forge-g2-deterministic-v1',
    spec: { path: 'specs/epic.md', sha256: sha256('b'), schemaVersion: 1 },
    at: '2026-01-01T00:00:04.000Z',
  },
  {
    schemaVersion: 1,
    event: 'g2_us_evaluated',
    runId: EPIC_RUN_ID,
    storyRunId: STORY_RUN_ID,
    gate: 'G2-US',
    attempt: 1,
    status: 'passed',
    code: 'G2_US_SPEC_VALID',
    policyVersion: 'forge-g2-us-deterministic-v1',
    storySpec: { path: 'specs/story.md', sha256: sha256('c'), schemaVersion: 1 },
    at: '2026-01-01T00:00:05.000Z',
  },
  {
    schemaVersion: 1,
    event: 'agent_execution_finished',
    runId: EPIC_RUN_ID,
    parentRunId: EPIC_RUN_ID,
    executionId: 'exec_fixture',
    caseId: 'case_fixture',
    storyRunId: STORY_RUN_ID,
    role: 'analyst',
    agentName: 'ProductEngineer',
    namespaceId: 'namespace-1',
    observedAt: '2026-01-01T00:00:06.000Z',
    status: 'finished',
    policyVersion: 'forge-story-analysis-v1',
    outcome: 'finished',
    caseStatus: 'idle',
    killedByBudget: false,
    artifact: { path: 'artifacts/epic_fixture/exec_fixture.md', sha256: sha256('a'), schemaVersion: 1 },
    at: '2026-01-01T00:00:06.000Z',
  },
  {
    schemaVersion: 1,
    event: 'story_analysis_plan_validated',
    runId: EPIC_RUN_ID,
    storyRunId: STORY_RUN_ID,
    executionId: 'exec_fixture',
    planSchemaVersion: 1,
    status: 'valid',
    code: 'STORY_ANALYSIS_PLAN_VALID',
    artifact: { path: 'artifacts/epic_fixture/exec_fixture.md', sha256: sha256('a'), schemaVersion: 1 },
    at: '2026-01-01T00:00:07.000Z',
  },
  {
    schemaVersion: 1,
    event: 'story_edit_finished',
    runId: EPIC_RUN_ID,
    storyRunId: STORY_RUN_ID,
    editId: 'edit_fixture',
    caseId: 'case_edit',
    status: 'finished',
    outcome: 'finished',
    caseStatus: 'idle',
    killedByBudget: false,
    filesModified: ['apps/a.ts'],
    filesCreated: ['apps/b.ts'],
    diffValidation: { status: 'valid', code: 'STORY_EDIT_DIFF_VALID', invalidFiles: [] },
    at: '2026-01-01T00:00:08.000Z',
  },
  {
    schemaVersion: 1,
    event: 'story_oracle_finished',
    campaignId: 'campaign_fixture',
    runId: EPIC_RUN_ID,
    storyRunId: STORY_RUN_ID,
    editId: 'edit_fixture',
    name: 'front.tests',
    ownerProjects: ['app'],
    ownersWithTestTarget: ['app'],
    ownersWithoutTestTarget: [],
    buildHosts: [],
    target: 'frontend-test',
    configuration: null,
    status: 'passed',
    code: 'ORACLE_PASS',
    exitCode: 0,
    durationMs: 42,
    commandHash: sha256('d'),
    at: '2026-01-01T00:00:09.000Z',
  },
  {
    schemaVersion: 1,
    event: 'story_g3_evaluated',
    campaignId: 'campaign_fixture',
    runId: EPIC_RUN_ID,
    storyRunId: STORY_RUN_ID,
    editId: 'edit_fixture',
    attempt: 1,
    status: 'passed',
    specHash: sha256('e'),
    policyVersion: 'forge-story-oracles-v1',
    at: '2026-01-01T00:00:10.000Z',
  },
])

test('full lifecycle maps evidences for every generic kind', () => {
  const projection = projectForgeLedgerToGeneric(LIFECYCLE)
  assert.ok(projection.evidences.length >= 10)
  const kinds = new Set(projection.evidences.map((evidence) => evidence.input.kind))
  assert.deepEqual([...kinds].sort(), ['agent-result', 'artifact', 'human-decision', 'oracle-result'])
})

test('every projected evidence input is valid and re-validates through the public validator', () => {
  const projection = projectForgeLedgerToGeneric(LIFECYCLE)
  for (const evidence of projection.evidences) {
    assert.equal(evidence.isValid, true, `${evidence.input.stepId}/${evidence.input.kind}: ${evidence.validationError}`)
    const revalidated = validateWorkflowEvidenceInput(evidence.input, evidence.input.workflowId)
    assert.equal(revalidated.ok, true, `${evidence.input.stepId}/${evidence.input.kind} re-validation failed`)
  }
  // Non-whitelisted facts are never smuggled into a valid evidence fact bag.
  for (const evidence of projection.evidences) {
    for (const key of Object.keys(evidence.input.facts ?? {})) {
      assert.ok(FORGE_FACT_KEY_WHITELIST.has(key), `unexpected fact key ${key}`)
    }
  }
})

test('human interaction open and reply are derived from G1', () => {
  const projection = projectForgeLedgerToGeneric(LIFECYCLE)
  const opens = projection.interactions.filter((interaction) => interaction.type === 'open')
  const replies = projection.interactions.filter((interaction) => interaction.type === 'reply')
  assert.equal(opens.length, 1)
  assert.equal(opens[0].isValid, true)
  assert.equal(replies.length, 1)
  assert.equal(replies[0].replyData.outcome, 'approved')
  assert.equal(replies[0].replyData.reasonCode, 'intent_confirmed')
  assert.equal(replies[0].replyData.actorId, 'user-1')
  // The reply targets the same interaction the gate opened.
  assert.equal(replies[0].replyData.interactionId, opens[0].openInput.interactionId)
})

test('workflow transitions follow the forge state machine', () => {
  const projection = projectForgeLedgerToGeneric(LIFECYCLE)
  const statuses = new Map(
    projection.transitions.map((transition) => [`${transition.workflowId}/${transition.stepId}`, transition.status])
  )
  assert.equal(statuses.get(`${EPIC_RUN_ID}/epic-run`), 'ready')
  assert.equal(statuses.get(`${STORY_RUN_ID}/story-run`), 'ready')
  assert.equal(statuses.get(`${EPIC_RUN_ID}/G1`), 'completed')
  assert.equal(statuses.get(`${EPIC_RUN_ID}/G2`), 'completed')
  assert.equal(statuses.get(`${STORY_RUN_ID}/G2-US`), 'completed')
  assert.equal(statuses.get(`${STORY_RUN_ID}/analysis`), 'completed')
  assert.equal(statuses.get(`${STORY_RUN_ID}/analysis-plan`), 'completed')
  assert.equal(statuses.get(`${STORY_RUN_ID}/edit`), 'completed')
  assert.equal(statuses.get(`${STORY_RUN_ID}/oracle.front.tests`), 'completed')
  assert.equal(statuses.get(`${STORY_RUN_ID}/G3`), 'completed')
  // Every projected transition status belongs to the generic vocabulary.
  const allowed = new Set([
    'pending',
    'ready',
    'running',
    'waiting_human',
    'blocked',
    'completed',
    'failed',
    'cancelled',
  ])
  for (const transition of projection.transitions) assert.ok(allowed.has(transition.status))
})

test('non-whitelisted forge fields are explicitly reported', () => {
  const projection = projectForgeLedgerToGeneric(LIFECYCLE)
  const flagged = new Set(projection.unmappedEvents.map((unmapped) => unmapped.field))
  for (const field of [
    'policyVersion',
    'evidenceSetHash',
    'requiredDecision',
    'caseStatus',
    'killedByBudget',
    'planSchemaVersion',
    'filesModified',
    'filesCreated',
    'diffValidation',
    'ownerProjects',
    'buildHosts',
    'ownersWithTestTarget',
    'ownersWithoutTestTarget',
    'commandHash',
    'specHash',
  ]) {
    assert.ok(flagged.has(field), `expected ${field} to be reported as unmapped`)
  }
  for (const unmapped of projection.unmappedEvents) {
    assert.ok(['not_in_fact_whitelist', 'unmapped_event_type', 'unsupported_structure'].includes(unmapped.reason))
    assert.equal(typeof unmapped.event, 'string')
    assert.equal(typeof unmapped.field, 'string')
  }
  // The exhaustive list contains only fields outside the current whitelist.
  const factWhitelistFlags = projection.unmappedEvents.filter(
    (unmapped) => unmapped.reason === 'not_in_fact_whitelist'
  )
  assert.ok(factWhitelistFlags.every((unmapped) => !FORGE_FACT_KEY_WHITELIST.has(unmapped.field)))
})

// ---------------------------------------------------------------------------
// 3. Degenerate cases
// ---------------------------------------------------------------------------

test('an invalid artifact hash is kept but flagged invalid', () => {
  const projection = projectForgeLedgerToGeneric([
    {
      schemaVersion: 1,
      event: 'g2_evaluated',
      runId: EPIC_RUN_ID,
      gate: 'G2',
      attempt: 1,
      status: 'passed',
      code: 'G2_SPEC_VALID',
      spec: { path: 'specs/epic.md', sha256: 'sha256:not-a-valid-hash', schemaVersion: 1 },
    },
  ])
  const artifact = projection.evidences.find((evidence) => evidence.input.kind === 'artifact')
  assert.ok(artifact)
  assert.equal(artifact.isValid, false)
  assert.match(artifact.validationError, /artifactHash/)
})

test('an unrecognized event is reported as unmapped_event_type', () => {
  const projection = projectForgeLedgerToGeneric([{ schemaVersion: 1, event: 'totally_unknown', runId: EPIC_RUN_ID }])
  assert.equal(projection.transitions.length, 0)
  assert.deepEqual(projection.unmappedEvents, [
    {
      event: 'totally_unknown',
      runId: EPIC_RUN_ID,
      field: 'event',
      value: 'totally_unknown',
      reason: 'unmapped_event_type',
    },
  ])
})

test('rejected decision transitions to failed and produces fail/human-decision evidence', () => {
  const projection = projectForgeLedgerToGeneric([
    {
      schemaVersion: 1,
      event: 'human_decision_recorded',
      runId: EPIC_RUN_ID,
      gate: 'G1',
      attempt: 1,
      decision: { actorId: 'user-2', authorityId: 'authority-1', outcome: 'rejected', reasonCode: 'intent_rejected' },
      at: '2026-01-01T00:00:03.000Z',
    },
  ])
  const transition = projection.transitions.find((item) => item.stepId === 'G1')
  assert.equal(transition.status, 'failed')
  const evidence = projection.evidences.find((item) => item.input.kind === 'human-decision')
  assert.equal(evidence.input.outcome, 'fail')
  const reply = projection.interactions.find((item) => item.type === 'reply')
  assert.equal(reply.replyData.outcome, 'rejected')
})

// ---------------------------------------------------------------------------
// 4. Purity and immutability
// ---------------------------------------------------------------------------

test('the projection module imports no I/O', () => {
  const source = readFileSync(new URL('../src/domain/forge-bmad/forge-ledger-projection.ts', import.meta.url), 'utf8')
  const specifiers = [...source.matchAll(/from\s+'([^']+)'/g)].map((match) => match[1])
  assert.ok(specifiers.length > 0, 'expected import specifiers')
  for (const specifier of specifiers) {
    assert.ok(
      specifier.startsWith('./') || specifier.startsWith('../'),
      `projection must only import sibling domain modules, found ${specifier}`
    )
    assert.equal(/\b(fs|fs\/promises|child_process|http|https|net|dns|worker_threads)\b/.test(specifier), false)
  }
  assert.equal(/\brequire\s*\(/.test(source), false, 'projection must not use require()')
})

test('the projection never mutates its input events', () => {
  const input = JSON.parse(JSON.stringify(LIFECYCLE))
  const snapshot = JSON.parse(JSON.stringify(input))
  projectForgeLedgerToGeneric(input)
  assert.deepEqual(input, snapshot)
})

// ---------------------------------------------------------------------------

if (failed > 0) {
  console.error(`forge ledger projection: ${failed} failure(s), ${passed} passed`)
  process.exit(1)
}
console.log(`forge ledger projection: ${passed} passed`)
