import assert from 'node:assert/strict'
import { existsSync, mkdtempSync, mkdirSync, realpathSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { resolveForgeRoots } from '../lib/forge-roots.mjs'
import { createEpicRun, parseForgeLedger, projectForgeRun } from '../lib/forge-ledger.mjs'
import { computeG1EvidenceSetHash, G1_POLICY_VERSION, recordHumanDecision } from '../lib/forge-human-decision.mjs'

const root = mkdtempSync(join(tmpdir(), 'forge-g1-'))
const orchestratorRoot = join(root, 'factory')
const repoRoot = join(root, 'repo')
mkdirSync(orchestratorRoot); mkdirSync(repoRoot)
const roots = resolveForgeRoots({ orchestratorRoot, repoRoot, runStoreRoot: join(orchestratorRoot, 'new-store') })
assert.equal(roots.orchestratorRoot, realpathSync(orchestratorRoot))
assert.equal(roots.repoRoot, realpathSync(repoRoot))
assert.equal(roots.runStoreRoot, join(realpathSync(orchestratorRoot), 'new-store'))
assert.equal(existsSync(roots.runStoreRoot), false)
const externalParent = join(root, 'external-parent'); mkdirSync(externalParent)
assert.equal(resolveForgeRoots({ orchestratorRoot, repoRoot, runStoreRoot: join(externalParent, 'store'), runStorePolicy: 'external_allowed' }).runStorePolicy, 'external_allowed')
assert.throws(() => resolveForgeRoots({ orchestratorRoot, repoRoot, runStoreRoot: join(externalParent, 'store') }), /unless runStorePolicy/)
// Confinement is path-segment based: a sibling sharing the text prefix is not
// a child of the orchestrator root.
const prefixSibling = `${orchestratorRoot}-other`
mkdirSync(prefixSibling)
assert.throws(() => resolveForgeRoots({ orchestratorRoot, repoRoot, runStoreRoot: join(prefixSibling, 'store') }), /unless runStorePolicy/)

const created = createEpicRun({ roots, runId: 'epic_g1', epic: { id: 'WZ-1', kind: 'Epic' }, stories: [{ id: 'WZ-2', kind: 'Story' }] })
assert.equal(existsSync(roots.runStoreRoot), true)
const events = parseForgeLedger(created.filePath)
const hash = computeG1EvidenceSetHash(events, 'epic_g1')
const good = { gate: 'G1', attempt: 1, policyVersion: G1_POLICY_VERSION, evidenceSetHash: hash, outcome: 'approved', reasonCode: 'intent_confirmed' }
const authorized = { actorId: async () => 'human-1', authorize: async () => ({ authorityId: 'product-owner' }) }
const recorded = await recordHumanDecision({ roots, runId: 'epic_g1', decision: good, identityPort: authorized })
assert.equal(recorded.status, 'recorded')
assert.equal(projectForgeRun(parseForgeLedger(created.filePath)).status, 'approved')
assert.equal((await recordHumanDecision({ roots, runId: 'epic_g1', decision: good, identityPort: authorized })).status, 'idempotent')
await assert.rejects(() => recordHumanDecision({ roots, runId: 'epic_g1', decision: { ...good, outcome: 'rejected', reasonCode: 'intent_rejected' }, identityPort: authorized }), /conflicting/)

const second = createEpicRun({ roots, runId: 'epic_g1_bad', epic: { id: 'WZ-3', kind: 'Epic' }, stories: [{ id: 'WZ-4', kind: 'Story' }] })
const secondEvents = parseForgeLedger(second.filePath)
const secondGood = { ...good, evidenceSetHash: computeG1EvidenceSetHash(secondEvents, 'epic_g1_bad') }
await assert.rejects(() => recordHumanDecision({ roots, runId: 'epic_g1_bad', decision: { ...secondGood, evidenceSetHash: 'sha256:wrong' }, identityPort: authorized }), /stale/)
await assert.rejects(() => recordHumanDecision({ roots, runId: 'epic_g1_bad', decision: { ...secondGood, attempt: 2 }, identityPort: authorized }), /active G1 attempt/)
await assert.rejects(() => recordHumanDecision({ roots, runId: 'epic_g1_bad', decision: secondGood, identityPort: { actorId: async () => 'human-2', authorize: async () => null } }), /not authorized/)
await assert.rejects(() => recordHumanDecision({ roots, runId: 'epic_g1_bad', decision: { ...secondGood, actorId: 'forged' }, identityPort: authorized }), /must not be declared/)
assert.equal(projectForgeRun(parseForgeLedger(second.filePath)).status, 'waiting_human')
console.log('forge G1: ok')
