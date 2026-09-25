// Repository ports & filesystem adapter integration tests.
//
// Offline, no framework: exits 0 when every case passes, 1 otherwise.
// Usage: node factory/tests/test-repository-ports-adapters.mjs
import assert from 'node:assert/strict'
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import {
  FilesystemAgentStepAttemptRepository,
  FilesystemAgentStepResultRepository,
  FilesystemDeliveryRepository,
  FilesystemOracleExecutionRepository,
  FilesystemWorkEnvironmentRepository,
  FilesystemWorkflowDefinitionRepository,
  FilesystemWorkflowEvidenceRepository,
  FilesystemWorkflowHumanInteractionRepository,
  FilesystemWorkflowInstanceRepository,
  WORKFLOW_DEFINITION_REPOSITORY_ERROR_CODES,
  WorkflowInstanceRepositoryError,
} from '../runtime/factory-operational.mjs'
import { createAgentStepAttemptRepository } from '../lib/agent-step-attempt-store.mjs'
import { createAgentStepResultRepository } from '../lib/agent-step-result-store.mjs'
import { createDeliveryRepository } from '../lib/delivery-store.mjs'
import { createOracleDefinitionRepository } from '../lib/oracle-definition.mjs'
import { createWorkEnvironmentRepository } from '../lib/work-unit-environment-store.mjs'
import { createWorkflowDefinitionRepository } from '../lib/workflow-definition-registry.mjs'
import { createWorkflowEvidenceRepository } from '../lib/workflow-evidence-store.mjs'
import { createWorkflowHumanInteractionRepository } from '../lib/workflow-human-interaction-store.mjs'
import { createWorkflowInstanceRepository, workflowProjectionStorageId } from '../lib/workflow-projection-store.mjs'

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

const root = await mkdtemp(join(tmpdir(), 'factory-repos-'))
const namespaceId = '11111111-1111-4111-8111-111111111111'
const definitionInput = {
  workflowType: 'demo',
  version: '1.0.0',
  definitionHash: 'a'.repeat(64),
  steps: [{ id: 'build', name: 'Build', responsibility: { kind: 'agent', name: 'worker' }, dependsOn: [] }],
}

try {
  await scenario('definition adapter implements the port over a registry', async () => {
    const definitionsRoot = join(root, 'definitions')
    await mkdir(join(definitionsRoot, 'demo'), { recursive: true })
    await writeFile(
      join(definitionsRoot, 'demo', '1.0.0.json'),
      `${JSON.stringify({
        schemaVersion: '1',
        workflowType: 'demo',
        version: '1.0.0',
        title: 'Demo',
        steps: [{ id: 'build', name: 'Build', responsibility: { kind: 'agent', name: 'worker' }, dependsOn: [] }],
      })}\n`
    )
    const repository = createWorkflowDefinitionRepository(definitionsRoot)
    assert.ok(repository instanceof FilesystemWorkflowDefinitionRepository)
    const list = await repository.list()
    assert.equal(list.length, 1)
    assert.equal(list[0].workflowType, 'demo')
    assert.match(list[0].definitionHash, /^[0-9a-f]{64}$/)
    const found = await repository.get('demo', '1.0.0')
    assert.equal(found.definitionHash, list[0].definitionHash)
    assert.equal(await repository.get('demo', '9.9.9'), null)
    const unique = await repository.resolveUnique('demo')
    assert.equal(unique.version, '1.0.0')
    await assert.rejects(
      () => repository.resolveUnique('missing'),
      (error) => error.code === WORKFLOW_DEFINITION_REPOSITORY_ERROR_CODES.WORKFLOW_DEFINITION_NOT_FOUND
    )
  })

  await scenario('instance adapter creates, reads and lists snapshots', async () => {
    const repository = createWorkflowInstanceRepository(root)
    assert.ok(repository instanceof FilesystemWorkflowInstanceRepository)
    const snapshot = await repository.create(
      namespaceId,
      { workflowId: 'wf-1', workflowType: 'demo', title: 'Demo run' },
      definitionInput,
      { runtimeId: 'runtime-1', kind: 'factory', agentId: 'worker', caseId: 'case-1', actorId: 'user-1' }
    )
    assert.equal(snapshot.instance.workflowId, 'wf-1')
    assert.equal(snapshot.instance.revision, 1)
    assert.equal(snapshot.projection.workflowId, 'wf-1')
    const read = await repository.get(namespaceId, 'wf-1')
    assert.equal(read.instance.revision, 1)
    assert.equal(await repository.get(namespaceId, 'missing'), null)
    const projections = await repository.list(namespaceId)
    assert.deepEqual(
      projections.map((projection) => projection.workflowId),
      ['wf-1']
    )
  })

  await scenario('instance adapter maps transition results and lifecycle errors', async () => {
    const repository = createWorkflowInstanceRepository(root)
    const definition = {
      workflowType: 'demo',
      version: '1.0.0',
      definitionHash: 'a'.repeat(64),
      steps: [{ id: 'build', dependsOn: [], responsibility: { kind: 'agent', name: 'worker' } }],
    }
    const request = {
      requestId: 'req-1',
      workflowId: 'wf-1',
      stepId: 'build',
      expectedRevision: 1,
      requestedStatus: 'running',
      evidenceIds: [],
    }
    const transitioned = await repository.transition(namespaceId, 'wf-1', {
      request,
      definition,
      evidence: [],
      execution: { kind: 'factory', runtimeId: 'runtime-1', agentId: 'worker', caseId: 'case-1' },
      policy: () => ({ allowed: true }),
    })
    assert.equal(transitioned.instance.revision, 2)
    assert.equal(transitioned.instance.steps.find((step) => step.id === 'build').status, 'running')

    const rejected = repository.transition(namespaceId, 'wf-1', {
      request: { ...request, requestId: 'req-2', expectedRevision: 2, requestedStatus: 'completed' },
      definition,
      evidence: [],
      execution: { kind: 'factory', runtimeId: 'runtime-1', agentId: 'worker', caseId: 'case-1' },
      policy: () => ({ allowed: false, code: 'DENIED_BY_TEST', reason: 'test' }),
    })
    await assert.rejects(
      rejected,
      (error) => error instanceof WorkflowInstanceRepositoryError && error.code === 'DENIED_BY_TEST'
    )

    await repository.remove(namespaceId, 'wf-1')
    assert.equal(await repository.get(namespaceId, 'wf-1'), null)
    await repository.restore(namespaceId, 'wf-1')
    assert.ok(await repository.get(namespaceId, 'wf-1'))
    await repository.remove(namespaceId, 'wf-1')
    await repository.purge(namespaceId, 'wf-1')
  })

  await scenario('evidence adapter records idempotently and filters by step', async () => {
    const repository = createWorkflowEvidenceRepository(root)
    assert.ok(repository instanceof FilesystemWorkflowEvidenceRepository)
    const storageId = workflowProjectionStorageId(namespaceId, 'wf-evidence')
    const input = {
      workflowId: 'wf-evidence',
      stepId: 'build',
      kind: 'agent-result',
      outcome: 'pass',
      facts: { attempt: 1 },
      idempotencyKey: 'idem-1',
    }
    const source = { kind: 'factory', runtimeId: 'runtime-1', agentId: 'worker' }
    const first = await repository.record(namespaceId, storageId, input, source)
    assert.equal(first.created, true)
    assert.equal(first.idempotent, false)
    const replay = await repository.record(namespaceId, storageId, input, source)
    assert.equal(replay.created, false)
    assert.equal(replay.idempotent, true)
    assert.equal((await repository.list(namespaceId, storageId)).length, 1)
    assert.equal((await repository.list(namespaceId, storageId, { stepId: 'other' })).length, 0)
  })

  await scenario('human-interaction adapter opens and replies through injected transitions', async () => {
    const repository = createWorkflowHumanInteractionRepository(root)
    assert.ok(repository instanceof FilesystemWorkflowHumanInteractionRepository)
    const storageId = workflowProjectionStorageId(namespaceId, 'wf-human')
    const input = {
      workflowId: 'wf-human',
      stepId: 'build',
      expectedRevision: 1,
      kind: 'approval',
      prompt: 'Approve build?',
      idempotencyKey: 'open-1',
      actions: [
        { id: 'approve', label: 'Approve', requestedStatus: 'completed' },
        { id: 'reject', label: 'Reject', requestedStatus: 'failed' },
      ],
    }
    const opened = await repository.recordOpen(namespaceId, storageId, input, {
      transition: async () => ({
        ok: true,
        changed: true,
        idempotent: false,
        snapshot: { revision: 2 },
        requestId: 'open-req',
      }),
    })
    assert.equal(opened.created, true)
    assert.equal(opened.idempotent, false)
    assert.equal(opened.interaction.status, 'open')
    assert.equal((await repository.list(namespaceId, storageId, { openOnly: true })).length, 1)
    assert.equal((await repository.events(namespaceId, storageId)).length, 2)

    await assert.rejects(
      () => repository.recordOpen(namespaceId, storageId, input),
      (error) => error.code === 'HUMAN_INTERACTION_TRANSITION_REQUIRED'
    )

    const replied = await repository.recordTransition(
      namespaceId,
      storageId,
      opened.interaction.interactionId,
      { actionId: 'approve' },
      'actor-1',
      'evidence-1',
      'reply-req',
      {
        action: async () => ({
          reply: { actionId: 'approve' },
          actorId: 'actor-1',
          evidenceId: 'evidence-1',
          transition: { ok: true, requestId: 'reply-req', snapshot: { revision: 3 } },
        }),
      }
    )
    assert.equal(replied.status, 'replied')
    assert.equal((await repository.list(namespaceId, storageId, { openOnly: true })).length, 0)
  })

  await scenario('agent-step-attempt adapter appends transitions and lists the journal', async () => {
    const repository = createAgentStepAttemptRepository(root)
    assert.ok(repository instanceof FilesystemAgentStepAttemptRepository)
    const storageId = 'attempt-storage'
    const base = {
      attemptId: 'attempt-adapter-1',
      workflowId: 'wf-attempt',
      workflowRevisionAtStart: 1,
      stepId: 'build',
      attemptNumber: 1,
      namespaceId,
      runtimeId: 'factory-runner',
      caseId: null,
      agentName: 'Worker',
      briefHash: `sha256:${'a'.repeat(64)}`,
      status: 'starting',
      startedAt: new Date().toISOString(),
      finishedAt: null,
      evidenceId: null,
      failureCode: null,
    }
    assert.deepEqual(await repository.list(namespaceId, storageId), [])
    await repository.append(namespaceId, storageId, base)
    await repository.append(namespaceId, storageId, { ...base, caseId: 'case-1', status: 'running' })
    const succeeded = await repository.append(namespaceId, storageId, {
      ...base,
      caseId: 'case-1',
      status: 'succeeded',
      finishedAt: new Date().toISOString(),
      evidenceId: 'evidence-1',
    })
    assert.equal(succeeded.status, 'succeeded')
    assert.deepEqual(
      (await repository.list(namespaceId, storageId)).map((attempt) => attempt.status),
      ['starting', 'running', 'succeeded']
    )
    await assert.rejects(
      () =>
        repository.append(namespaceId, storageId, {
          ...base,
          caseId: 'case-1',
          agentName: 'Other',
          status: 'succeeded',
          finishedAt: new Date().toISOString(),
        }),
      /AGENT_STEP_ATTEMPT_IDENTITY_CONFLICT/
    )
    await assert.rejects(
      () => repository.append(namespaceId, storageId, { ...base, caseId: 'case-1', status: 'running' }),
      /INVALID_AGENT_STEP_ATTEMPT_TRANSITION/
    )
  })

  await scenario('agent-step-result adapter issues, submits and projects results', async () => {
    const repository = createAgentStepResultRepository(root)
    assert.ok(repository instanceof FilesystemAgentStepResultRepository)
    const storageId = 'result-storage'
    const identity = {
      attemptId: 'attempt-result-1',
      workflowId: 'wf-result',
      stepId: 'build',
      namespaceId,
      caseId: 'case-result',
      agentName: 'Worker',
      briefHash: `sha256:${'b'.repeat(64)}`,
    }
    const observed = { attemptId: identity.attemptId, caseId: identity.caseId, agentName: identity.agentName }
    const business = { status: 'PASS', summary: 'all good', claims: { modifiedFiles: [] } }
    assert.deepEqual(await repository.list(namespaceId, storageId), [])
    const issued = await repository.issue(namespaceId, storageId, identity)
    assert.equal(typeof issued.token, 'string')
    assert.ok(issued.token.length >= 32)
    assert.ok(!Number.isNaN(Date.parse(issued.expiresAt)))
    const submitted = await repository.submit(issued.token, business, observed)
    assert.equal(submitted.ok, true)
    assert.equal(submitted.idempotent, false)
    const replay = await repository.submit(issued.token, business, observed)
    assert.equal(replay.ok, true)
    assert.equal(replay.idempotent, true)
    assert.equal((await repository.getByAttempt(namespaceId, storageId, identity.attemptId))?.summary, 'all good')
    assert.equal(await repository.getByAttempt(namespaceId, storageId, 'missing'), null)
    assert.deepEqual(
      (await repository.list(namespaceId, storageId)).map((event) => event.type),
      ['capability-issued', 'result-submitted']
    )
    assert.deepEqual(await repository.submit('not-a-token', business, observed), {
      ok: false,
      code: 'RESULT_CAPABILITY_INVALID',
    })
  })

  await scenario('oracle-definition adapter lists and resolves definitions', async () => {
    const definitionsRoot = join(root, 'oracle-definitions')
    await mkdir(definitionsRoot, { recursive: true })
    await writeFile(
      join(definitionsRoot, 'adapter-smoke@1.0.0.json'),
      `${JSON.stringify({
        schemaVersion: '1',
        id: 'adapter-smoke',
        version: '1.0.0',
        domain: 'factory',
        argv: ['node', 'fixture.mjs'],
        cwd: 'repo-root',
        timeoutMs: 1000,
        success: { rule: 'exit-code', requireWork: true },
        applicable: { workflowTypes: ['oracle-smoke'], stepIds: ['verify-code'] },
      })}\n`
    )
    const repository = await createOracleDefinitionRepository(definitionsRoot)
    assert.ok(repository instanceof FilesystemOracleExecutionRepository)
    const list = await repository.list()
    assert.deepEqual(
      list.map((definition) => definition.id),
      ['adapter-smoke']
    )
    assert.equal(list[0].version, '1.0.0')
    const found = await repository.get('adapter-smoke')
    assert.equal(found.domain, 'factory')
    assert.equal(await repository.get('missing'), null)
  })

  await scenario('work-environment adapter reserves, reads, lists and transitions', async () => {
    const repository = await createWorkEnvironmentRepository(join(root, 'environment-adapter'))
    assert.ok(repository instanceof FilesystemWorkEnvironmentRepository)
    const caseId = '22222222-2222-4222-8222-222222222222'
    const base = {
      schemaVersion: '1',
      environmentId: 'env-adapter',
      workUnitId: 'unit-adapter',
      namespaceId,
      repoRoot: '/repo',
      integrationBranch: 'main',
      branch: 'feature/adapter',
      worktreePath: '/worktrees/adapter',
      baseCommit: 'a'.repeat(40),
      createdAt: '2025-01-01T00:00:00.000Z',
      createdBy: 'factory',
      lifecycleState: 'provisioning',
    }
    const paths = repository.paths(namespaceId, 'env-adapter')
    assert.ok(paths.snapshot.endsWith('environment.json'))
    assert.ok(!paths.directory.endsWith('/env-adapter'))

    const reserved = await repository.reserve(base)
    assert.equal(reserved.ok, true)
    assert.equal(reserved.snapshot.revision, 1)
    assert.equal((await repository.read(namespaceId, 'env-adapter')).environment.lifecycleState, 'provisioning')
    assert.equal((await repository.list(namespaceId)).length, 1)
    assert.equal((await repository.list(namespaceId, { states: ['active'] })).length, 0)
    assert.equal((await repository.list(namespaceId, { states: ['provisioning'] })).length, 1)

    const rejected = await repository.reserve({ ...base, environmentId: 'bad id!' })
    assert.equal(rejected.ok, false)

    const active = await repository.transition(namespaceId, 'env-adapter', {
      ...base,
      lifecycleState: 'active',
      parentCaseId: caseId,
    })
    assert.equal(active.ok, true)
    assert.equal(active.snapshot.revision, 2)
    assert.equal((await repository.read(namespaceId, 'env-adapter')).environment.lifecycleState, 'active')

    const conflict = await repository.transition(
      namespaceId,
      'env-adapter',
      { ...base, lifecycleState: 'completed', parentCaseId: caseId },
      { expectedRevision: 99 }
    )
    assert.equal(conflict.error.code, 'REVISION_CONFLICT')
    assert.equal(await repository.read(namespaceId, 'missing-env'), null)
  })

  await scenario('delivery adapter creates, promotes and projects operations', async () => {
    const repository = createDeliveryRepository(join(root, 'delivery-adapter'))
    assert.ok(repository instanceof FilesystemDeliveryRepository)
    const deliveryId = 'wf-delivery-adapter'
    const workflowId = 'wf-adapter'
    const runtimeId = 'factory-dashboard'
    const caseId = '22222222-2222-4222-8222-222222222222'
    const environmentId = '33333333-3333-4333-8333-333333333333'
    const sha = 'a'.repeat(40)
    const digest = `sha256:${'b'.repeat(64)}`
    const targetHash = `sha256:${'c'.repeat(64)}`
    const now = new Date().toISOString()
    const snapshot = {
      schemaVersion: '1',
      deliveryId,
      namespaceId,
      workflowId,
      environmentId,
      environmentHash: digest,
      parentCaseId: caseId,
      runtimeId,
      worktreePath: '/repo',
      branch: 'feature/adapter',
      baseCommit: sha,
      headCommit: sha,
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
    }
    const created = await repository.create(snapshot)
    assert.equal(created.ok, true)
    assert.equal((await repository.create(snapshot)).changed, false)
    assert.equal((await repository.read(namespaceId, deliveryId)).stage, 'implementation-ready')

    // Promotion is delegated to the store policy: an absent delivery fails closed.
    const missing = await repository.promote({
      namespaceId,
      request: {
        requestId: 'req-missing',
        deliveryId: 'missing-delivery',
        expectedRevision: 1,
        requestedStage: 'artifact-ready',
        evidenceIds: [],
        idempotencyKey: 'promote-missing',
      },
      definition: {
        schemaVersion: '1',
        deliveryType: 'delivery',
        version: '1.0.0',
        checkpoints: [],
        artifactPolicy: {},
        promotionPolicy: {},
        deploymentPolicy: {},
        retentionPolicy: {},
        definitionHash: digest,
      },
      evidence: [],
      execution: { kind: 'factory-control-plane', namespaceId, workflowId, caseId, runtimeId },
    })
    assert.equal(missing.error.code, 'DELIVERY_NOT_FOUND')

    const operationArgs = {
      namespaceId,
      workflowId,
      deliveryId,
      caseId,
      runtimeId,
      request: {
        kind: 'deployment',
        expectedRevision: 1,
        idempotencyKey: 'deploy-once',
        targetId: 'prod',
        artifactRef: {
          digest,
          mediaType: 'application/zip',
          producerRef: 'build',
          buildRef: 'build-1',
          sourceCommit: sha,
        },
        releaseRef: {
          releaseId: 'release-1',
          artifactDigest: digest,
          sourceCommit: sha,
          approvedEvidenceId: 'approval',
        },
      },
      targetRef: { targetId: 'prod', targetHash, adapterId: 'test', adapterTargetRef: 'trusted' },
      execution: { kind: 'factory-control-plane', actorId: 'factory' },
    }
    const operation = await repository.createDeliveryOperation(operationArgs)
    assert.equal(operation.operation.state, 'pending')
    assert.equal((await repository.createDeliveryOperation(operationArgs)).changed, false)
    const started = await repository.startDeliveryOperation(
      namespaceId,
      deliveryId,
      operation.operation.operationId,
      'adapter-1'
    )
    assert.equal(started.operation.attempt, 1)
    const indeterminate = await repository.recordDeliveryOperation(
      namespaceId,
      deliveryId,
      operation.operation.operationId,
      { state: 'indeterminate', error: { code: 'LOST_RESPONSE' } }
    )
    assert.equal(indeterminate.ok, true)
    assert.equal(await repository.hasIndeterminateOperation(namespaceId, deliveryId), true)
    const reconciled = await repository.reconcileDeliveryOperation(
      namespaceId,
      deliveryId,
      operation.operation.operationId,
      { state: 'succeeded', result: { deploymentId: 'd1' } }
    )
    assert.equal(reconciled.ok, true)
    assert.equal(await repository.hasIndeterminateOperation(namespaceId, deliveryId), false)
    const projection = await repository.inspectDeliveryOperations(namespaceId, deliveryId)
    assert.equal(projection.operations[0].state, 'succeeded')
    const withOperations = await repository.readWithOperations(namespaceId, deliveryId)
    assert.equal(withOperations.revision, 1)
    assert.equal(withOperations.deliveryOperations[0].state, 'succeeded')

    const patched = await repository.updateSnapshot(
      namespaceId,
      deliveryId,
      { git: { checkpoint: sha } },
      {
        kind: 'checkpoint',
        idempotencyKey: 'checkpoint-1',
        facts: { headCommit: sha },
      }
    )
    assert.equal(patched.ok, true)
    assert.equal((await repository.read(namespaceId, deliveryId)).git.checkpoint, sha)
  })
} finally {
  await rm(root, { recursive: true, force: true })
}

console.log(`\nResult: ${passed} passed, ${failed} failed`)
process.exit(failed ? 1 : 0)
