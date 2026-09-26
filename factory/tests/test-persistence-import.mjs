// B4-T1 one-shot import integration test (offline, Docker-free).
//
// Seeds a filesystem dataset across every migrated persistence context, runs
// the one-shot import into the in-memory SQL client, asserts count + canonical
// hash fidelity, re-runs the import to assert idempotence, then injects a
// discrepancy and asserts the verification flags it.
//
// Usage: node factory/tests/test-persistence-import.mjs
import assert from 'node:assert/strict'
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { runOneShotImport, verifyImport } from '../runtime/factory-operational.mjs'
import { createAgentStepAttemptRepository } from '../lib/agent-step-attempt-store.mjs'
import { createAgentStepResultRepository } from '../lib/agent-step-result-store.mjs'
import { createDeliveryRepository } from '../lib/delivery-store.mjs'
import { createWorkEnvironmentRepository } from '../lib/work-unit-environment-store.mjs'
import { createWorkflowEvidenceRepository } from '../lib/workflow-evidence-store.mjs'
import { createWorkflowHumanInteractionRepository } from '../lib/workflow-human-interaction-store.mjs'
import { createWorkflowInstanceRepository, workflowProjectionStorageId } from '../lib/workflow-projection-store.mjs'
import { createInMemorySqlClient } from './support/in-memory-sql-client.mjs'

let passed = 0
let failed = 0

async function scenario(name, fn) {
  try {
    await fn()
    passed++
    console.log(`✓ ${name}`)
  } catch (error) {
    failed++
    console.log(`✗ ${name}`)
    console.log(`  ${error?.stack ?? error}`)
  }
}

const CONTEXTS = [
  'workflow-definition',
  'workflow-instance',
  'workflow-evidence',
  'workflow-human-interaction',
  'agent-step-attempt',
  'agent-step-result',
  'oracle-execution',
  'work-environment',
  'delivery',
]

const NAMESPACE_ID = '11111111-1111-4111-8111-111111111111'
const WORKFLOW_ID = 'wf-import-1'
const ATTEMPT_ID = 'attempt-import-1'
const now = new Date().toISOString()

const root = await mkdtemp(join(tmpdir(), 'factory-one-shot-import-'))
const definitionsRoot = join(root, 'definitions')
const oraclesRoot = join(root, 'oracles')
const dataRoot = root
const organizationId = 'default'
const workstreamId = 'default'

async function seedFilesystem() {
  // workflow-definition ----------------------------------------------------
  await mkdir(join(definitionsRoot, 'demo'), { recursive: true })
  await writeFile(
    join(definitionsRoot, 'demo', '1.0.0.json'),
    `${JSON.stringify({
      schemaVersion: '1',
      workflowType: 'demo',
      version: '1.0.0',
      title: 'Demo definition',
      steps: [{ id: 'build', name: 'Build', responsibility: { kind: 'agent', name: 'worker' }, dependsOn: [] }],
    })}\n`
  )

  // oracle-execution -------------------------------------------------------
  await mkdir(oraclesRoot, { recursive: true })
  await writeFile(
    join(oraclesRoot, 'oracle-import@1.0.0.json'),
    `${JSON.stringify({
      schemaVersion: '1',
      id: 'oracle-import',
      version: '1.0.0',
      domain: 'factory',
      argv: ['node', 'fixture.mjs'],
      cwd: 'repo-root',
      timeoutMs: 1000,
      success: { rule: 'exit-code', requireWork: true },
      applicable: { workflowTypes: ['demo'], stepIds: ['build'] },
    })}\n`
  )

  // workflow-instance ------------------------------------------------------
  const definitionInput = {
    workflowType: 'demo',
    version: '1.0.0',
    definitionHash: 'a'.repeat(64),
    steps: [{ id: 'build', name: 'Build', responsibility: { kind: 'agent', name: 'worker' }, dependsOn: [] }],
  }
  const instanceRepository = createWorkflowInstanceRepository(dataRoot)
  await instanceRepository.create(
    NAMESPACE_ID,
    { workflowId: WORKFLOW_ID, workflowType: 'demo', title: 'Import run' },
    definitionInput,
    { kind: 'factory', runtimeId: 'runtime-import', agentId: 'worker', caseId: 'case-import', actorId: 'user-1' }
  )
  const storageId = workflowProjectionStorageId(NAMESPACE_ID, WORKFLOW_ID)

  // workflow-evidence ------------------------------------------------------
  const evidenceRepository = createWorkflowEvidenceRepository(dataRoot)
  await evidenceRepository.record(
    NAMESPACE_ID,
    storageId,
    {
      workflowId: WORKFLOW_ID,
      stepId: 'build',
      kind: 'agent-result',
      outcome: 'pass',
      facts: { attempt: 1 },
      idempotencyKey: 'evidence-import-1',
    },
    { kind: 'factory', runtimeId: 'runtime-import', agentId: 'worker' }
  )

  // workflow-human-interaction --------------------------------------------
  const interactionRepository = createWorkflowHumanInteractionRepository(dataRoot)
  await interactionRepository.recordOpen(
    NAMESPACE_ID,
    storageId,
    {
      workflowId: WORKFLOW_ID,
      stepId: 'build',
      expectedRevision: 1,
      kind: 'approval',
      prompt: 'Approve import?',
      idempotencyKey: 'interaction-import-1',
      actions: [
        { id: 'approve', label: 'Approve', requestedStatus: 'completed' },
        { id: 'reject', label: 'Reject', requestedStatus: 'failed' },
      ],
    },
    {
      transition: async () => ({
        ok: true,
        changed: true,
        idempotent: false,
        snapshot: { revision: 2 },
        requestId: 'interaction-open-request',
      }),
    }
  )

  // agent-step-attempt -----------------------------------------------------
  const attemptRepository = createAgentStepAttemptRepository(dataRoot)
  await attemptRepository.append(NAMESPACE_ID, storageId, {
    attemptId: ATTEMPT_ID,
    workflowId: WORKFLOW_ID,
    workflowRevisionAtStart: 1,
    stepId: 'build',
    attemptNumber: 1,
    namespaceId: NAMESPACE_ID,
    runtimeId: 'runtime-import',
    caseId: null,
    agentName: 'Worker',
    briefHash: `sha256:${'a'.repeat(64)}`,
    status: 'starting',
    startedAt: now,
    finishedAt: null,
    evidenceId: null,
    failureCode: null,
  })

  // agent-step-result ------------------------------------------------------
  const resultRepository = createAgentStepResultRepository(dataRoot)
  const issued = await resultRepository.issue(NAMESPACE_ID, storageId, {
    attemptId: ATTEMPT_ID,
    workflowId: WORKFLOW_ID,
    stepId: 'build',
    namespaceId: NAMESPACE_ID,
    caseId: 'case-import',
    agentName: 'Worker',
    briefHash: `sha256:${'b'.repeat(64)}`,
  })
  const submitted = await resultRepository.submit(
    issued.token,
    { status: 'PASS', summary: 'import ok', claims: { modifiedFiles: [] } },
    { attemptId: ATTEMPT_ID, caseId: 'case-import', agentName: 'Worker' }
  )
  assert.equal(submitted.ok, true)

  // work-environment -------------------------------------------------------
  const environmentRepository = await createWorkEnvironmentRepository(dataRoot)
  await environmentRepository.reserve({
    schemaVersion: '1',
    environmentId: 'env-import-1',
    workUnitId: 'unit-import-1',
    namespaceId: NAMESPACE_ID,
    repoRoot: '/repo',
    integrationBranch: 'main',
    branch: 'feature/import',
    worktreePath: '/worktrees/import',
    baseCommit: 'c'.repeat(40),
    createdAt: now,
    createdBy: 'factory',
    lifecycleState: 'provisioning',
  })

  // delivery ---------------------------------------------------------------
  const deliveryRepository = createDeliveryRepository(dataRoot)
  const digest = `sha256:${'b'.repeat(64)}`
  await deliveryRepository.create({
    schemaVersion: '1',
    deliveryId: 'del-import-1',
    namespaceId: NAMESPACE_ID,
    workflowId: WORKFLOW_ID,
    environmentId: '33333333-3333-4333-8333-333333333333',
    environmentHash: digest,
    parentCaseId: '22222222-2222-4222-8222-222222222222',
    runtimeId: 'runtime-import',
    worktreePath: '/repo',
    branch: 'feature/import',
    baseCommit: 'c'.repeat(40),
    headCommit: 'c'.repeat(40),
    definitionType: 'factory-delivery',
    definitionVersion: '1.0.0',
    definitionHash: digest,
    stage: 'implementation-ready',
    revision: 1,
    evidenceIds: [],
    createdAt: now,
    updatedAt: now,
    git: {},
    artifact: {},
    release: {},
    deployment: {},
    verification: {},
    blockers: [],
  })

  return { storageId }
}

async function sqlCount(client, table, where = '') {
  const { rows } = await client.query(`SELECT * FROM ${table} ${where}`)
  return rows.length
}

try {
  const { storageId } = await seedFilesystem()
  const importOptions = { dataRoot, definitionsRoot, oraclesRoot, organizationId, workstreamId }

  await scenario('first import reports ok with matching counts and hashes', async () => {
    const client = createInMemorySqlClient()
    const report = await runOneShotImport({ ...importOptions, sqlClient: client })
    assert.equal(report.ok, true, JSON.stringify(report.contexts))
    for (const context of CONTEXTS) {
      assert.ok(report.contexts[context], `missing context ${context}`)
      assert.equal(report.contexts[context].ok, true, `context ${context} not ok`)
      assert.equal(report.contexts[context].filesystemCount, 1, `context ${context} filesystem count`)
      assert.equal(report.contexts[context].sqlCount, 1, `context ${context} sql count`)
      assert.deepEqual(report.contexts[context].discrepancies, [])
    }
    // Raw table parity for the identity-keyed contexts.
    assert.equal(await sqlCount(client, 'workflow_definitions'), 1)
    assert.equal(await sqlCount(client, 'workflow_instances'), 1)
    assert.equal(await sqlCount(client, 'workflow_evidence'), 1)
    assert.equal(await sqlCount(client, 'human_interactions'), 1)
    assert.equal(await sqlCount(client, 'agent_step_attempts'), 1)
    assert.equal(await sqlCount(client, 'agent_step_results'), 1)
    assert.equal(await sqlCount(client, 'oracle_executions'), 1)
    assert.equal(await sqlCount(client, 'work_environments'), 1)
    assert.equal(await sqlCount(client, 'deliveries'), 1)
    // Re-runnable verification (no writes) agrees with the import.
    const verification = await verifyImport({ ...importOptions, sqlClient: client })
    assert.equal(verification.ok, true)
  })

  await scenario('re-running the import is idempotent (no duplicates, no error)', async () => {
    const client = createInMemorySqlClient()
    const first = await runOneShotImport({ ...importOptions, sqlClient: client })
    assert.equal(first.ok, true)
    const second = await runOneShotImport({ ...importOptions, sqlClient: client })
    assert.equal(second.ok, true)
    for (const context of CONTEXTS) assert.equal(second.contexts[context].sqlCount, 1, `duplicated ${context}`)
    assert.equal(await sqlCount(client, 'workflow_evidence'), 1)
    assert.equal(await sqlCount(client, 'agent_step_results'), 1)
  })

  await scenario('verification flags a deleted SQL aggregate as MISSING_IN_SQL', async () => {
    const client = createInMemorySqlClient()
    const imported = await runOneShotImport({ ...importOptions, sqlClient: client })
    assert.equal(imported.ok, true)
    await client.query('DELETE FROM workflow_evidence WHERE namespace_id = $1 AND workflow_id = $2', [
      NAMESPACE_ID,
      WORKFLOW_ID,
    ])
    const report = await verifyImport({ ...importOptions, sqlClient: client })
    assert.equal(report.ok, false)
    const context = report.contexts['workflow-evidence']
    assert.equal(context.ok, false)
    assert.equal(context.filesystemCount, 1)
    assert.equal(context.sqlCount, 0)
    assert.equal(context.discrepancies.length, 1)
    assert.equal(context.discrepancies[0].reason, 'MISSING_IN_SQL')
    assert.ok(context.discrepancies[0].key.startsWith(`${NAMESPACE_ID}/`))
    assert.equal(typeof context.discrepancies[0].filesystemHash, 'string')
  })

  await scenario('verification flags an altered SQL payload as HASH_MISMATCH', async () => {
    const client = createInMemorySqlClient()
    const imported = await runOneShotImport({ ...importOptions, sqlClient: client })
    assert.equal(imported.ok, true)
    const { rows } = await client.query(
      'SELECT payload FROM agent_step_attempts WHERE namespace_id = $1 AND attempt_id = $2',
      [NAMESPACE_ID, ATTEMPT_ID]
    )
    const payload = JSON.parse(rows[0].payload)
    const tampered = JSON.stringify({ ...payload, status: 'tampered' })
    await client.query('UPDATE agent_step_attempts SET payload = $1 WHERE namespace_id = $2 AND attempt_id = $3', [
      tampered,
      NAMESPACE_ID,
      ATTEMPT_ID,
    ])
    const report = await verifyImport({ ...importOptions, sqlClient: client })
    assert.equal(report.ok, false)
    const context = report.contexts['agent-step-attempt']
    assert.equal(context.ok, false)
    assert.equal(context.discrepancies.length, 1)
    assert.equal(context.discrepancies[0].reason, 'HASH_MISMATCH')
    assert.notEqual(context.discrepancies[0].filesystemHash, context.discrepancies[0].sqlHash)
  })
} finally {
  await rm(root, { recursive: true, force: true })
}

console.log(`\nResult: ${passed} passed, ${failed} failed`)
process.exit(failed ? 1 : 0)
