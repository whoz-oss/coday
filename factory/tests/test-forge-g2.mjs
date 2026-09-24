import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, symlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { resolveForgeRoots } from '../lib/forge-roots.mjs'
import { createEpicRun, parseForgeLedger, projectForgeRun } from '../lib/forge-ledger.mjs'
import { computeG1EvidenceSetHash, G1_POLICY_VERSION, recordHumanDecision } from '../lib/forge-human-decision.mjs'
import { evaluateG2 } from '../lib/forge-g2.mjs'

const root = mkdtempSync(join(tmpdir(), 'forge-g2-')); const factory = join(root, 'factory'); const repo = join(root, 'repo'); const store = join(factory, 'runs'); const specs = join(repo, 'forge', 'specs')
mkdirSync(specs, { recursive: true }); mkdirSync(factory)
const roots = resolveForgeRoots({ orchestratorRoot: factory, runStoreRoot: store, repoRoot: repo, forgeRoot: join(repo, 'forge') })
const valid = `---\nschemaVersion: 1\nworkItem:\n  id: WZ-20\n  kind: Epic\nscope:\n  allow:\n    - apps/*\n  create:\n    - libs/new/**\n  deny:\n    - secrets/**\noracles:\n  - front.build\n  - front.tests\n---\n# Spec\n`
const spec = join(specs, 'WZ-20.md'); writeFileSync(spec, valid)
const run = createEpicRun({ roots, runId: 'epic_g2', epic: { id: 'WZ-20', kind: 'Epic' }, stories: [{ id: 'WZ-21', kind: 'Story' }] })
assert.equal(evaluateG2({ roots, runId: run.runId, specPath: spec }).event.code, 'G2_G1_NOT_APPROVED')
let events = parseForgeLedger(run.filePath); const hash = computeG1EvidenceSetHash(events, run.runId)
await recordHumanDecision({ roots, runId: run.runId, decision: { gate: 'G1', attempt: 1, policyVersion: G1_POLICY_VERSION, evidenceSetHash: hash, outcome: 'approved', reasonCode: 'intent_confirmed' }, identityPort: { actorId: async () => 'human', authorize: async () => ({ authorityId: 'owner' }) } })
const passed = evaluateG2({ roots, runId: run.runId, specPath: spec })
assert.equal(passed.event.status, 'passed', `G2 expected passed, got ${passed.event.status} (${passed.event.code})`)
assert.equal(evaluateG2({ roots, runId: run.runId, specPath: spec }).status, 'idempotent')
assert.equal(projectForgeRun(parseForgeLedger(run.filePath)).gates.find((gate) => gate.gate === 'G2').status, 'passed')
writeFileSync(spec, valid.replace('front.tests', 'back.build'))
assert.equal(evaluateG2({ roots, runId: run.runId, specPath: spec }).code, 'G2_SPEC_HASH_CHANGED')
const invalid = join(specs, 'invalid.md'); writeFileSync(invalid, valid.replace('front.tests', 'rm -rf /'))
const second = createEpicRun({ roots, runId: 'epic_g2_invalid', epic: { id: 'WZ-20', kind: 'Epic' }, stories: [{ id: 'WZ-22', kind: 'Story' }] })
assert.equal(evaluateG2({ roots, runId: second.runId, specPath: invalid }).event.code, 'G2_ORACLE_UNKNOWN')
const traversal = join(specs, 'traversal.md'); writeFileSync(traversal, valid.replace('apps/*', '../escape'))
assert.equal(evaluateG2({ roots, runId: second.runId, specPath: traversal }).event.code, 'G2_SCOPE_PATTERN_INVALID')
const outside = join(root, 'outside.md'); writeFileSync(outside, valid); const linked = join(specs, 'linked.md'); symlinkSync(outside, linked)
assert.equal(evaluateG2({ roots, runId: second.runId, specPath: linked }).event.code, 'G2_SPEC_OUTSIDE_ROOT')
console.log('forge G2: ok')
