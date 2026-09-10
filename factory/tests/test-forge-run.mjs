import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, readFileSync, realpathSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { resolveForgeRoots } from '../lib/forge-roots.mjs'
import { createEpicRun, parseForgeLedger, projectForgeRun } from '../lib/forge-ledger.mjs'

const root = mkdtempSync(join(tmpdir(), 'factory-forge-'))
const orchestratorRoot = join(root, 'central-factory')
const runStoreRoot = join(orchestratorRoot, 'runs')
const repoRoot = join(root, 'target-repository')
// The orchestrator parent must exist; Factory creates its own store later.
mkdirSync(orchestratorRoot, { recursive: true })
mkdirSync(repoRoot)

const roots = resolveForgeRoots({ orchestratorRoot, runStoreRoot, repoRoot })
assert.equal(roots.repoRoot, realpathSync(repoRoot))
assert.equal(roots.orchestratorRoot, realpathSync(orchestratorRoot))
assert.equal(roots.runStoreRoot, realpathSync(orchestratorRoot) + '/runs')
assert.throws(() => resolveForgeRoots({ orchestratorRoot, runStoreRoot, repoRoot: '.' }), /absolute path/)

let tick = 0
const result = createEpicRun({
  roots,
  runId: 'epic_fixture',
  epic: { id: 'WZ-812', kind: 'Epic', source: 'fixture' },
  stories: [{ id: 'WZ-813', kind: 'Story', source: 'fixture' }, { id: 'WZ-814', kind: 'Story', source: 'fixture' }],
  now: () => `2026-01-01T00:00:0${tick++}.000Z`,
})
const events = parseForgeLedger(result.filePath)
assert.deepEqual(events.map((event) => event.event), ['run_started', 'story_run_created', 'story_run_created', 'gate_started'])
assert.equal(events[0].roots.repoRoot, realpathSync(repoRoot))
assert.equal(events[1].parentRunId, 'epic_fixture')
assert.equal(events[3].status, 'waiting_human')
assert.equal(events[3].requiredDecision, 'intent-approval')
const projection = projectForgeRun(events)
assert.equal(projection.status, 'waiting_human')
assert.equal(projection.stories.length, 2)
assert.equal(projection.stories[0].status, 'not_started')

// The dashboard consumes this projection only: prove that structured evidence is
// retained without replaying artifacts, commands, stdout, stderr, or LLM prose.
const enriched = projectForgeRun([
  ...events,
  { schemaVersion: 1, event: 'agent_execution_finished', storyRunId: result.storyRuns[0].runId, executionId: 'exec_fixture', caseId: 'case_fixture', status: 'finished', outcome: 'finished', artifact: { path: 'artifacts/epic_fixture/exec_fixture.md', sha256: 'sha256:artifact' } },
  { schemaVersion: 1, event: 'story_analysis_plan_validated', executionId: 'exec_fixture', planSchemaVersion: 1, status: 'valid', code: 'STORY_ANALYSIS_PLAN_VALID' },
  { schemaVersion: 1, event: 'story_edit_finished', storyRunId: result.storyRuns[0].runId, editId: 'edit_fixture', status: 'finished', outcome: 'finished', filesModified: ['apps/a.ts'], filesCreated: ['apps/b.ts'], diffValidation: { status: 'valid', code: 'STORY_EDIT_DIFF_VALID' } },
  { schemaVersion: 1, event: 'story_oracle_finished', campaignId: 'campaign_fixture', storyRunId: result.storyRuns[0].runId, name: 'front.tests', status: 'passed', code: 'ORACLE_PASS', ownerProjects: ['app'], target: 'frontend-test', buildHosts: [], ownersWithTestTarget: ['app'], ownersWithoutTestTarget: [], exitCode: 0, durationMs: 42, commandHash: 'sha256:command' },
  { schemaVersion: 1, event: 'story_g3_evaluated', campaignId: 'campaign_fixture', storyRunId: result.storyRuns[0].runId, editId: 'edit_fixture', status: 'passed', specHash: 'sha256:spec', policyVersion: 'forge-story-oracles-v1' },
])
assert.equal(enriched.stories[0].status, 'passed')
assert.deepEqual(enriched.stories[0].oracleCampaigns[0].results[0], { name: 'front.tests', status: 'passed', code: 'ORACLE_PASS', ownerProjects: ['app'], target: 'frontend-test', buildHosts: [], ownersWithTestTarget: ['app'], ownersWithoutTestTarget: [], exitCode: 0, durationMs: 42, commandHash: 'sha256:command' })
assert.deepEqual(projectForgeRun(parseForgeLedger(result.filePath)), projection)
assert.match(readFileSync(result.filePath, 'utf8'), /"schemaVersion":1/)
console.log('forge run: ok')
