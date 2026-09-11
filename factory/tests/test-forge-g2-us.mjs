import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { resolveForgeRoots } from '../lib/forge-roots.mjs'
import { createEpicRun, parseForgeLedger, projectForgeRun } from '../lib/forge-ledger.mjs'
import { computeG1EvidenceSetHash, G1_POLICY_VERSION, recordHumanDecision } from '../lib/forge-human-decision.mjs'
import { evaluateG2, evaluateG2US } from '../lib/forge-g2.mjs'

// ---------------------------------------------------------------------------
// Shared test environment setup
// ---------------------------------------------------------------------------
const root = mkdtempSync(join(tmpdir(), 'forge-g2-us-'))
const factory = join(root, 'factory')
const repo = join(root, 'repo')
const store = join(factory, 'runs')
const specs = join(repo, 'forge', 'specs')
mkdirSync(specs, { recursive: true })
mkdirSync(factory)

const roots = resolveForgeRoots({
  orchestratorRoot: factory,
  runStoreRoot: store,
  repoRoot: repo,
  forgeRoot: join(repo, 'forge'),
})

// ---------------------------------------------------------------------------
// Spec fixtures
// ---------------------------------------------------------------------------
const EPIC_SPEC = [
  '---',
  'schemaVersion: 1',
  'workItem:',
  '  id: EPIC-1',
  '  kind: Epic',
  'scope:',
  '  allow:',
  '    - apps/**',
  '    - libs/**',
  '  create:',
  '    - libs/new/**',
  '  deny:',
  '    - secrets/**',
  'oracles:',
  '  - front.build',
  '  - front.tests',
  '---',
  '# Epic spec',
  '',
].join('\n')

// Valid Story spec — inherits correctly from EPIC-1
const STORY_SPEC_VALID = [
  '---',
  'schemaVersion: 1',
  'workItem:',
  '  id: WZ-42',
  '  kind: Story',
  '  parentId: EPIC-1',
  'scope:',
  '  allow:',
  '    - apps/**',
  '  create:',
  '    - libs/new/**',
  '  deny:',
  '    - secrets/**',
  'oracles:',
  '  - front.tests',
  '---',
  '# Story spec',
  '',
].join('\n')

// Story spec with allow exceeding Epic (extra pattern not in Epic allow)
const STORY_SPEC_ALLOW_EXCEEDS = [
  '---',
  'schemaVersion: 1',
  'workItem:',
  '  id: WZ-42',
  '  kind: Story',
  '  parentId: EPIC-1',
  'scope:',
  '  allow:',
  '    - apps/**',
  '    - outside/**',
  '  create:',
  '    - libs/new/**',
  '  deny:',
  '    - secrets/**',
  'oracles:',
  '  - front.tests',
  '---',
  '# Story spec allow exceeds',
  '',
].join('\n')

// Story spec with deny weaker than Epic (missing secrets/**)
const STORY_SPEC_DENY_WEAKER = [
  '---',
  'schemaVersion: 1',
  'workItem:',
  '  id: WZ-42',
  '  kind: Story',
  '  parentId: EPIC-1',
  'scope:',
  '  allow:',
  '    - apps/**',
  '  create:',
  '    - libs/new/**',
  '  deny:',
  '    - other/**',
  'oracles:',
  '  - front.tests',
  '---',
  '# Story spec deny weaker',
  '',
].join('\n')

// Story spec with wrong workItem.id (WZ-99 instead of WZ-42)
const STORY_SPEC_WRONG_ID = [
  '---',
  'schemaVersion: 1',
  'workItem:',
  '  id: WZ-99',
  '  kind: Story',
  '  parentId: EPIC-1',
  'scope:',
  '  allow:',
  '    - apps/**',
  '  create:',
  '    - libs/new/**',
  '  deny:',
  '    - secrets/**',
  'oracles:',
  '  - front.tests',
  '---',
  '# Story spec wrong id',
  '',
].join('\n')

// Write spec files
const epicSpecPath = join(specs, 'EPIC-1.md')
const storySpecPath = join(specs, 'WZ-42.md')
const storySpecAllowExceedsPath = join(specs, 'WZ-42-allow-exceeds.md')
const storySpecDenyWeakerPath = join(specs, 'WZ-42-deny-weaker.md')
const storySpecWrongIdPath = join(specs, 'WZ-42-wrong-id.md')

writeFileSync(epicSpecPath, EPIC_SPEC)
writeFileSync(storySpecPath, STORY_SPEC_VALID)
writeFileSync(storySpecAllowExceedsPath, STORY_SPEC_ALLOW_EXCEEDS)
writeFileSync(storySpecDenyWeakerPath, STORY_SPEC_DENY_WEAKER)
writeFileSync(storySpecWrongIdPath, STORY_SPEC_WRONG_ID)

// ---------------------------------------------------------------------------
// Helper: build an EpicRun with G1 approved + G2 Epic passed
// ---------------------------------------------------------------------------
async function buildApprovedRun({ runId, epicId = 'EPIC-1', storyId = 'WZ-42' }) {
  const run = createEpicRun({
    roots,
    runId,
    epic: { id: epicId, kind: 'Epic' },
    stories: [{ id: storyId, kind: 'Story' }],
  })
  // G1 approval
  const events = parseForgeLedger(run.filePath)
  const evidenceSetHash = computeG1EvidenceSetHash(events, run.runId)
  await recordHumanDecision({
    roots,
    runId: run.runId,
    decision: { gate: 'G1', attempt: 1, policyVersion: G1_POLICY_VERSION, evidenceSetHash, outcome: 'approved', reasonCode: 'intent_confirmed' },
    identityPort: { actorId: async () => 'human', authorize: async () => ({ authorityId: 'owner' }) },
  })
  // G2 Epic
  const g2Result = evaluateG2({ roots, runId: run.runId, specPath: epicSpecPath })
  assert.equal(g2Result.event.status, 'passed', `G2 setup failed: ${g2Result.event.code}`)
  return run
}

// ---------------------------------------------------------------------------
// Test 1: G2_US_G1_NOT_APPROVED — G1 not yet approved
// ---------------------------------------------------------------------------
{
  const run = createEpicRun({
    roots,
    runId: 'epic_us_no_g1',
    epic: { id: 'EPIC-1', kind: 'Epic' },
    stories: [{ id: 'WZ-42', kind: 'Story' }],
  })
  const storyRunId = run.storyRuns[0].runId
  const result = evaluateG2US({ roots, epicRunId: run.runId, storyRunId, storySpecPath })
  assert.equal(result.status, 'recorded', 'test 1: expected recorded')
  assert.equal(result.event.code, 'G2_US_G1_NOT_APPROVED', `test 1: got ${result.event.code}`)
  assert.equal(result.event.status, 'blocked', 'test 1: expected blocked')
  console.log('  [1/12] G2_US_G1_NOT_APPROVED: ok')
}

// ---------------------------------------------------------------------------
// Test 2: G2_US_G2_NOT_PASSED — G1 approved but G2 Epic not evaluated
// ---------------------------------------------------------------------------
{
  const run = createEpicRun({
    roots,
    runId: 'epic_us_no_g2',
    epic: { id: 'EPIC-1', kind: 'Epic' },
    stories: [{ id: 'WZ-42', kind: 'Story' }],
  })
  // Approve G1 only
  const events = parseForgeLedger(run.filePath)
  const evidenceSetHash = computeG1EvidenceSetHash(events, run.runId)
  await recordHumanDecision({
    roots,
    runId: run.runId,
    decision: { gate: 'G1', attempt: 1, policyVersion: G1_POLICY_VERSION, evidenceSetHash, outcome: 'approved', reasonCode: 'intent_confirmed' },
    identityPort: { actorId: async () => 'human', authorize: async () => ({ authorityId: 'owner' }) },
  })
  const storyRunId = run.storyRuns[0].runId
  const result = evaluateG2US({ roots, epicRunId: run.runId, storyRunId, storySpecPath })
  assert.equal(result.event.code, 'G2_US_G2_NOT_PASSED', `test 2: got ${result.event.code}`)
  console.log('  [2/12] G2_US_G2_NOT_PASSED: ok')
}

// ---------------------------------------------------------------------------
// Test 3: G2_US_STORY_RUN_NOT_FOUND — unknown storyRunId
// ---------------------------------------------------------------------------
{
  const run = await buildApprovedRun({ runId: 'epic_us_no_story' })
  const result = evaluateG2US({ roots, epicRunId: run.runId, storyRunId: 'story_nonexistent', storySpecPath })
  assert.equal(result.event.code, 'G2_US_STORY_RUN_NOT_FOUND', `test 3: got ${result.event.code}`)
  console.log('  [3/12] G2_US_STORY_RUN_NOT_FOUND: ok')
}

// ---------------------------------------------------------------------------
// Test 4: Spec invalide — path incorrect → G2_US_SPEC_PATH_INVALID
// ---------------------------------------------------------------------------
{
  const run = await buildApprovedRun({ runId: 'epic_us_bad_spec' })
  const storyRunId = run.storyRuns[0].runId
  const result = evaluateG2US({ roots, epicRunId: run.runId, storyRunId, storySpecPath: '/nonexistent/path/spec.md' })
  assert.equal(result.status, 'recorded', `test 4: expected recorded, got ${result.status}`)
  const validSpecCodes = ['G2_US_SPEC_PATH_INVALID', 'G2_US_SPEC_INVALID', 'G2_US_SPEC_OUTSIDE_ROOT']
  assert.ok(validSpecCodes.includes(result.event.code), `test 4: unexpected code ${result.event.code}`)
  console.log(`  [4/12] spec invalide (${result.event.code}): ok`)
}

// ---------------------------------------------------------------------------
// Test 5: G2_US_WORK_ITEM_MISMATCH — spec dit WZ-99, ledger dit WZ-42
// ---------------------------------------------------------------------------
{
  const run = await buildApprovedRun({ runId: 'epic_us_mismatch' })
  const storyRunId = run.storyRuns[0].runId
  const result = evaluateG2US({ roots, epicRunId: run.runId, storyRunId, storySpecPath: storySpecWrongIdPath })
  assert.equal(result.event.code, 'G2_US_WORK_ITEM_MISMATCH', `test 5: got ${result.event.code}`)
  console.log('  [5/12] G2_US_WORK_ITEM_MISMATCH: ok')
}

// ---------------------------------------------------------------------------
// Test 6: G2_US_INHERITANCE_VIOLATION — allow exceeds Epic
// ---------------------------------------------------------------------------
{
  const run = await buildApprovedRun({ runId: 'epic_us_allow_exceeds' })
  const storyRunId = run.storyRuns[0].runId
  const result = evaluateG2US({ roots, epicRunId: run.runId, storyRunId, storySpecPath: storySpecAllowExceedsPath })
  assert.equal(result.event.code, 'G2_US_INHERITANCE_VIOLATION', `test 6: got ${result.event.code}`)
  assert.ok(Array.isArray(result.event.violations), 'test 6: violations must be array')
  assert.ok(result.event.violations.some((v) => v.code === 'G2_US_ALLOW_EXCEEDS_EPIC'), `test 6: expected G2_US_ALLOW_EXCEEDS_EPIC in violations, got: ${JSON.stringify(result.event.violations)}`)
  console.log('  [6/12] G2_US_ALLOW_EXCEEDS_EPIC violation: ok')
}

// ---------------------------------------------------------------------------
// Test 7: G2_US_INHERITANCE_VIOLATION — deny weaker than Epic
// ---------------------------------------------------------------------------
{
  const run = await buildApprovedRun({ runId: 'epic_us_deny_weaker' })
  const storyRunId = run.storyRuns[0].runId
  const result = evaluateG2US({ roots, epicRunId: run.runId, storyRunId, storySpecPath: storySpecDenyWeakerPath })
  assert.equal(result.event.code, 'G2_US_INHERITANCE_VIOLATION', `test 7: got ${result.event.code}`)
  assert.ok(result.event.violations.some((v) => v.code === 'G2_US_DENY_WEAKER_THAN_EPIC'), `test 7: expected G2_US_DENY_WEAKER_THAN_EPIC, got: ${JSON.stringify(result.event.violations)}`)
  console.log('  [7/12] G2_US_DENY_WEAKER_THAN_EPIC violation: ok')
}

// ---------------------------------------------------------------------------
// Test 8: Cas valide — passed, G2_US_SPEC_VALID, storySpec.sha256 présent
// ---------------------------------------------------------------------------
let passedRun, passedStoryRunId, passedHash
{
  const run = await buildApprovedRun({ runId: 'epic_us_valid' })
  passedRun = run
  passedStoryRunId = run.storyRuns[0].runId
  const result = evaluateG2US({ roots, epicRunId: run.runId, storyRunId: passedStoryRunId, storySpecPath })
  assert.equal(result.status, 'recorded', `test 8: expected recorded, got ${result.status}`)
  assert.equal(result.event.status, 'passed', `test 8: expected passed, got ${result.event.status}`)
  assert.equal(result.event.code, 'G2_US_SPEC_VALID', `test 8: got ${result.event.code}`)
  assert.ok(typeof result.event.storySpec?.sha256 === 'string' && result.event.storySpec.sha256.startsWith('sha256:'), `test 8: storySpec.sha256 missing or invalid`)
  passedHash = result.event.storySpec.sha256
  console.log('  [8/12] cas valide passed: ok')
}

// ---------------------------------------------------------------------------
// Test 9: Idempotence — second appel identique → status: 'idempotent'
// ---------------------------------------------------------------------------
{
  const result = evaluateG2US({ roots, epicRunId: passedRun.runId, storyRunId: passedStoryRunId, storySpecPath })
  assert.equal(result.status, 'idempotent', `test 9: expected idempotent, got ${result.status}`)
  assert.equal(result.event.code, 'G2_US_SPEC_VALID', `test 9: expected G2_US_SPEC_VALID on idempotent event`)
  console.log('  [9/12] idempotence: ok')
}

// ---------------------------------------------------------------------------
// Test 10: Conflit de hash — spec modifiée après un passed
// ---------------------------------------------------------------------------
{
  // Modify the spec file content (add a trailing comment to change hash)
  writeFileSync(storySpecPath, STORY_SPEC_VALID + '<!-- changed -->')
  const result = evaluateG2US({ roots, epicRunId: passedRun.runId, storyRunId: passedStoryRunId, storySpecPath })
  assert.equal(result.status, 'conflict', `test 10: expected conflict, got ${result.status}`)
  assert.equal(result.code, 'G2_US_SPEC_HASH_CHANGED', `test 10: expected G2_US_SPEC_HASH_CHANGED, got ${result.code}`)
  // Restore for subsequent tests
  writeFileSync(storySpecPath, STORY_SPEC_VALID)
  console.log('  [10/12] conflit de hash: ok')
}

// ---------------------------------------------------------------------------
// Test 11: Projection projectForgeRun expose storyG2
// ---------------------------------------------------------------------------
{
  const events = parseForgeLedger(join(store, `${passedRun.runId}.jsonl`))
  const projection = projectForgeRun(events)
  assert.ok(projection, 'test 11: projection must exist')
  const story = projection.stories.find((s) => s.runId === passedStoryRunId)
  assert.ok(story, 'test 11: story must be in projection')
  assert.ok(story.storyG2, `test 11: storyG2 must be present, got: ${JSON.stringify(story.storyG2)}`)
  assert.equal(story.storyG2.gate, 'G2-US', `test 11: gate must be G2-US, got ${story.storyG2.gate}`)
  assert.equal(story.storyG2.status, 'passed', `test 11: status must be passed, got ${story.storyG2.status}`)
  assert.equal(story.storyG2.storySpec?.sha256, passedHash, 'test 11: storySpec.sha256 must match')
  console.log('  [11/12] projection storyG2: ok')
}

// ---------------------------------------------------------------------------
// Test 12: G2_US_G1_NOT_APPROVED est réévaluable (non terminal)
//          Après approbation G1, le même storyRunId peut passer
// ---------------------------------------------------------------------------
{
  // Create a fresh run WITHOUT approving G1 yet
  const run = createEpicRun({
    roots,
    runId: 'epic_us_reval',
    epic: { id: 'EPIC-1', kind: 'Epic' },
    stories: [{ id: 'WZ-42', kind: 'Story' }],
  })
  const storyRunId = run.storyRuns[0].runId

  // First call: G1 not approved
  const first = evaluateG2US({ roots, epicRunId: run.runId, storyRunId, storySpecPath })
  assert.equal(first.event.code, 'G2_US_G1_NOT_APPROVED', `test 12: first call: got ${first.event.code}`)
  assert.equal(first.event.attempt, 1, 'test 12: first attempt must be 1')

  // Now approve G1
  const events = parseForgeLedger(run.filePath)
  const evidenceSetHash = computeG1EvidenceSetHash(events, run.runId)
  await recordHumanDecision({
    roots,
    runId: run.runId,
    decision: { gate: 'G1', attempt: 1, policyVersion: G1_POLICY_VERSION, evidenceSetHash, outcome: 'approved', reasonCode: 'intent_confirmed' },
    identityPort: { actorId: async () => 'human', authorize: async () => ({ authorityId: 'owner' }) },
  })

  // Evaluate G2 Epic so the G2-US precondition is met
  const g2Result = evaluateG2({ roots, runId: run.runId, specPath: epicSpecPath })
  assert.equal(g2Result.event.status, 'passed', `test 12: G2 Epic setup failed: ${g2Result.event.code}`)

  // Second call: should now pass
  const second = evaluateG2US({ roots, epicRunId: run.runId, storyRunId, storySpecPath })
  assert.equal(second.status, 'recorded', `test 12: second call: expected recorded, got ${second.status}`)
  assert.equal(second.event.status, 'passed', `test 12: second call: expected passed, got ${second.event.status}`)
  assert.equal(second.event.attempt, 2, `test 12: second attempt must be 2, got ${second.event.attempt}`)
  console.log('  [12/12] G2_US_G1_NOT_APPROVED réévaluable: ok')
}

console.log('forge G2-US: ok')
