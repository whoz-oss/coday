import { mkdtemp, mkdir, realpath, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { defaultDeliveryDefinition, hashDeliveryDefinition, validateDeliveryDefinition } from '../lib/delivery-definition.mjs'
import { evaluateDeliveryPromotion } from '../lib/delivery-policy.mjs'
import { DeliveryGitControlPlane } from '../lib/delivery-git-control-plane.mjs'
import { DeliveryPullRequestAdapter } from '../lib/delivery-pr-adapter.mjs'
import { DeliveryStore } from '../lib/delivery-store.mjs'

let passed = 0, failed = 0
function expect(name, actual, expected) { const ok = JSON.stringify(actual) === JSON.stringify(expected); console.log(`${ok ? '✓' : '✗'} ${name}`); if (!ok) console.log({ expected, actual }); ok ? passed++ : failed++ }
async function rejects(name, action, code) { try { await action(); expect(name, 'no error', code) } catch (error) { expect(name, error.code, code) } }
const ns = '11111111-1111-4111-8111-111111111111', caseId = '22222222-2222-4222-8222-222222222222', envId = '33333333-3333-4333-8333-333333333333', sha = 'a'.repeat(40)
const definition = defaultDeliveryDefinition(), validated = validateDeliveryDefinition(definition)
expect('versioned delivery definition validates', validated.ok, true)
expect('default definition has correct stage count', validated.ok && validated.definition.checkpoints.length, 5)
expect('definition hash is deterministic', hashDeliveryDefinition(validated.definition) === hashDeliveryDefinition(validated.definition), true)
expect('invalid definition rejected', validateDeliveryDefinition({ schemaVersion: '1', deliveryType: 'x', version: '1.0.0', title: 'T', checkpoints: [], artifactPolicy: {}, promotionPolicy: {}, deploymentPolicy: {}, retentionPolicy: {} }).ok, false)
const immutableDefinition = { ...validated.definition, definitionHash: hashDeliveryDefinition(validated.definition) }
const snapshot = { deliveryId: 'wf-delivery', namespaceId: ns, workflowId: 'wf', parentCaseId: caseId, runtimeId: 'agentos', stage: 'artifact-ready', revision: 2, definitionHash: immutableDefinition.definitionHash, environmentHash: `sha256:${'b'.repeat(64)}`, headCommit: sha }
const evidence = [{ evidenceId: 'approval', namespaceId: ns, deliveryId: 'wf-delivery', workflowId: 'wf', environmentHash: snapshot.environmentHash, caseId, runtimeId: 'agentos', headCommit: sha, kind: 'human-decision', outcome: 'approved', source: { kind: 'factory-human' } }]
// Human promotion requires runtimeId === 'factory-dashboard' (control-plane boundary)
expect('release without human approval refused', evaluateDeliveryPromotion({ request: { deliveryId: 'wf-delivery', expectedRevision: 2, requestedStage: 'release-approved', evidenceIds: [] }, snapshot, definition: immutableDefinition, evidence, execution: { kind: 'factory-human', namespaceId: ns, workflowId: 'wf', caseId, runtimeId: 'factory-dashboard', actorId: 'human' } }).code, 'PASS_EVIDENCE_REQUIRED')
expect('release with scoped human approval accepted', evaluateDeliveryPromotion({ request: { deliveryId: 'wf-delivery', expectedRevision: 2, requestedStage: 'release-approved', evidenceIds: ['approval'] }, snapshot, definition: immutableDefinition, evidence, execution: { kind: 'factory-human', namespaceId: ns, workflowId: 'wf', caseId, runtimeId: 'factory-dashboard', actorId: 'human' } }).allowed, true)
expect('non-dashboard runtime refused for human gate', evaluateDeliveryPromotion({ request: { deliveryId: 'wf-delivery', expectedRevision: 2, requestedStage: 'release-approved', evidenceIds: ['approval'] }, snapshot, definition: immutableDefinition, evidence, execution: { kind: 'factory-human', namespaceId: ns, workflowId: 'wf', caseId, runtimeId: 'agentos', actorId: 'human' } }).code, 'ACTOR_NOT_AUTHORIZED')
// Skipping 2 stages must yield ILLEGAL_PROMOTION (ordered promotion required)
expect('deploy without sequential promotion refused', evaluateDeliveryPromotion({ request: { deliveryId: 'wf-delivery', expectedRevision: 2, requestedStage: 'deployed', evidenceIds: [] }, snapshot: { ...snapshot, stage: 'implementation-ready', revision: 2 }, definition: immutableDefinition, evidence: [], execution: { kind: 'factory-control-plane', namespaceId: ns, workflowId: 'wf', caseId, runtimeId: 'factory-dashboard' } }).code, 'ILLEGAL_PROMOTION')
expect('production verified without smoke PASS refused', evaluateDeliveryPromotion({ request: { deliveryId: 'wf-delivery', expectedRevision: 4, requestedStage: 'production-verified', evidenceIds: [] }, snapshot: { ...snapshot, stage: 'deployed', revision: 4, runtimeId: 'factory-dashboard' }, definition: immutableDefinition, evidence: [], execution: { kind: 'factory-control-plane', namespaceId: ns, workflowId: 'wf', caseId, runtimeId: 'factory-dashboard' } }).code, 'PASS_EVIDENCE_REQUIRED')
expect('namespace/case scoping enforced', evaluateDeliveryPromotion({ request: { deliveryId: 'wf-delivery', expectedRevision: 2, requestedStage: 'release-approved', evidenceIds: ['approval'] }, snapshot, definition: immutableDefinition, evidence, execution: { kind: 'factory-human', namespaceId: ns, workflowId: 'wf', caseId: 'wrong', runtimeId: 'agentos', actorId: 'human' } }).code, 'DELIVERY_SCOPE_MISMATCH')
expect('stale revision refused', evaluateDeliveryPromotion({ request: { deliveryId: 'wf-delivery', expectedRevision: 99, requestedStage: 'release-approved', evidenceIds: [] }, snapshot, definition: immutableDefinition, evidence, execution: { kind: 'factory-human', namespaceId: ns, workflowId: 'wf', caseId, runtimeId: 'factory-dashboard', actorId: 'human' } }).code, 'REVISION_CONFLICT')
expect('definition hash mismatch refused', evaluateDeliveryPromotion({ request: { deliveryId: 'wf-delivery', expectedRevision: 2, requestedStage: 'release-approved', evidenceIds: [] }, snapshot, definition: { ...immutableDefinition, definitionHash: 'wrong' }, evidence, execution: { kind: 'factory-human', namespaceId: ns, workflowId: 'wf', caseId, runtimeId: 'factory-dashboard', actorId: 'human' } }).code, 'DELIVERY_DEFINITION_MISMATCH')
expect('agent-sourced evidence refused for code gate', evaluateDeliveryPromotion({ request: { deliveryId: 'wf-delivery', expectedRevision: 2, requestedStage: 'release-approved', evidenceIds: ['approval'] }, snapshot, definition: immutableDefinition, evidence: [{ ...evidence[0], source: { kind: 'agent' } }], execution: { kind: 'factory-human', namespaceId: ns, workflowId: 'wf', caseId, runtimeId: 'factory-dashboard', actorId: 'human' } }).code, 'PASS_EVIDENCE_REQUIRED')
expect('PR adapter is explicitly blocked when unconfigured', (await new DeliveryPullRequestAdapter().createDraft({})).error.code, 'PULL_REQUEST_NOT_CONFIGURED')
const untrustedPr = new DeliveryPullRequestAdapter({ provider: { createDraft: async () => ({ id: 1, url: 'https://evil.example/pr/1', draft: true }) } })
expect('PR adapter rejects untrusted URLs', (await untrustedPr.createDraft({})).error.code, 'PULL_REQUEST_RESULT_INDETERMINATE')

const { WorkflowProjectionStore } = await import('../lib/workflow-projection-store.mjs')
const root = await mkdtemp(join(tmpdir(), 'factory-delivery-'))
try {
  const repo = join(root, 'repo'); await mkdir(repo); const canonical = await realpath(repo); let status = ' M src/ok.ts\0', head = sha, commitCalls = 0, pushCalls = 0
  const runner = async (_file, args) => { const command = args.join(' ')
    if (command === 'rev-parse --show-toplevel') return { exitCode: 0, stdout: `${canonical}\n` }
    if (command === 'branch --show-current') return { exitCode: 0, stdout: 'feature/unit\n' }
    if (command === 'rev-parse HEAD') return { exitCode: 0, stdout: `${head}\n` }
    if (command.startsWith('status --porcelain')) return { exitCode: 0, stdout: status }
    if (args[0] === 'diff' && args[1] === '--binary') return { exitCode: 0, stdout: 'diff' }
    if (command.startsWith('add --')) return { exitCode: 0, stdout: '' }
    if (command === 'diff --cached --quiet --exit-code') return { exitCode: 1, stdout: '' }
    if (args.includes('commit')) { commitCalls++; head = 'c'.repeat(40); return { exitCode: 0, stdout: '' } }
    if (command === 'show -s --format=%cn%n%ce HEAD') return { exitCode: 0, stdout: 'Factory Service\nfactory@example.test\n' }
    if (args[0] === 'ls-remote') return { exitCode: 0, stdout: pushCalls ? `${head}\trefs/heads/feature/unit\n` : '' }
    if (args[0] === 'push') { pushCalls++; return { exitCode: 0, stdout: '' } }
    return { exitCode: 1, stdout: '' }
  }
  const git = new DeliveryGitControlPlane({ runner, serviceIdentity: { name: 'Factory Service', email: 'factory@example.test' }, configuredRemote: 'origin', allowedPaths: ['src'], protectedPaths: ['src/protected'] })
  const binding = { worktreePath: canonical, branch: 'feature/unit', baseCommit: sha, expectedHead: sha }
  const inspection = await git.inspect(binding)
  expect('real diff hash produced', inspection.diffHash.startsWith('sha256:'), true)
  await rejects('claims mismatch refused', () => git.checkpoint(binding, { message: 'checkpoint', claims: { paths: ['wrong'], diffHash: inspection.diffHash } }), 'CLAIMS_MISMATCH')
  status = ' M src/protected/key.ts\0'; await rejects('protected files refused', () => git.inspect(binding), 'PROTECTED_FILE_CHANGED')
  status = ' M outside.ts\0'; await rejects('scope refused', () => git.inspect(binding), 'SCOPE_VIOLATION')
  status = ' M src/ok.ts\0'; const committed = await git.checkpoint(binding, { message: 'checkpoint', claims: { paths: ['src/ok.ts'], diffHash: inspection.diffHash } })
  expect('service identity commit verified', [committed.changed, commitCalls], [true, 1])
  await rejects('stale HEAD refused', () => git.inspect(binding), 'STALE_HEAD')
  await rejects('branch name with colon refused', () => git.inspect({ ...binding, branch: 'feat:inject', expectedHead: head }), 'INVALID_BRANCH_NAME')
  await rejects('branch name with space refused', () => git.inspect({ ...binding, branch: 'feat inject', expectedHead: head }), 'INVALID_BRANCH_NAME')
  const noRemote = new DeliveryGitControlPlane({ runner, serviceIdentity: { name: 'Factory Service', email: 'factory@example.test' }, configuredRemote: null, allowedPaths: ['src'] })
  expect('remote not configured blocked', (await noRemote.push({ ...binding, expectedHead: head })).error.code, 'REMOTE_NOT_CONFIGURED')

  const workflowData = join(root, 'workflow-data'); const workflowStore = new WorkflowProjectionStore(workflowData); await workflowStore.initialize()
  const definitionHash = 'd'.repeat(64), workflowDefinition = { workflowType: 'test', version: '1.0.0', definitionHash, steps: [] }
  await workflowStore.start(ns, { workflowId: 'wf-binding', workflowType: 'test', title: 'Binding' }, workflowDefinition, { kind: 'agentos', caseId, runtimeId: 'agentos' })
  const deliveryRef = { deliveryId: 'wf-binding-delivery', definitionHash: immutableDefinition.definitionHash }
  const firstBinding = await workflowStore.bindDelivery(ns, 'wf-binding', deliveryRef), replayBinding = await workflowStore.bindDelivery(ns, 'wf-binding', deliveryRef)
  const conflictBinding = await workflowStore.bindDelivery(ns, 'wf-binding', { ...deliveryRef, deliveryId: 'other-delivery' })
  expect('delivery reference is durable and bind-once', [firstBinding.changed, replayBinding.changed, conflictBinding.error.code], [true, false, 'DELIVERY_ALREADY_BOUND'])
  expect('delivery reference survives reload', (await new WorkflowProjectionStore(workflowData).read(ns, 'wf-binding')).instance.deliveryRef, deliveryRef)

  const data = join(root, 'data'); const store = new DeliveryStore(data); await store.initialize()
  const stored = { schemaVersion: '1', deliveryId: 'wf-delivery', namespaceId: ns, workflowId: 'wf', environmentId: envId, environmentHash: `sha256:${'b'.repeat(64)}`, parentCaseId: caseId, runtimeId: 'agentos', worktreePath: canonical, branch: 'feature/unit', baseCommit: sha, headCommit: sha, definitionType: 'factory-delivery', definitionVersion: '1.0.0', definitionHash: immutableDefinition.definitionHash, stage: 'implementation-ready', revision: 1, evidenceIds: [], createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(), git: {}, artifact: {}, release: {}, deployment: {}, verification: {}, blockers: [] }
  expect('delivery creation succeeds', (await store.create(stored)).ok, true)
  expect('journal reload restores snapshot', (await new DeliveryStore(data).read(ns, 'wf-delivery')).deliveryId, 'wf-delivery')
  const one = await store.recordOperation(ns, 'wf-delivery', { kind: 'git-push', state: 'succeeded', idempotencyKey: 'push-1', facts: { headCommit: sha } })
  const two = await store.recordOperation(ns, 'wf-delivery', { kind: 'git-push', state: 'succeeded', idempotencyKey: 'push-1', facts: { headCommit: sha } })
  expect('double push journal is idempotent', [one.changed, two.changed], [true, false])

  // Fault seam: crash after pending.json written but before snapshot — recovery must complete
  const data2 = join(root, 'data2'); const faultStore = new DeliveryStore(data2, { fault: async (point) => { if (point === 'after-pending-journal') throw new Error('SIMULATED_CRASH') } })
  await faultStore.initialize()
  const stored2 = { ...stored, deliveryId: 'wf-delivery2', namespaceId: ns }
  // First call will throw after writing pending+journal but before snapshot
  let threw = false
  try { await faultStore.create(stored2) } catch { threw = true }
  expect('fault seam triggers after pending+journal', threw, true)
  // Now use a clean store on the same data root — recovery should throw DELIVERY_OPERATION_INDETERMINATE
  // because the journal only has 'pending' state (no 'succeeded' was written before the fault).
  // This is correct fail-closed behavior: operator must reconcile.
  const recoveryStore = new DeliveryStore(data2)
  await recoveryStore.initialize()
  await rejects('recovery fail-closed on indeterminate', () => recoveryStore.read(ns, 'wf-delivery2'), 'DELIVERY_OPERATION_INDETERMINATE')
} finally { await rm(root, { recursive: true, force: true }) }
console.log(`\nResult: ${passed} passed, ${failed} failed`); process.exit(failed ? 1 : 0)
