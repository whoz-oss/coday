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
  FilesystemOracleExecutionRepository,
  FilesystemWorkflowDefinitionRepository,
  FilesystemWorkflowEvidenceRepository,
  FilesystemWorkflowHumanInteractionRepository,
  FilesystemWorkflowInstanceRepository,
  WORKFLOW_DEFINITION_REPOSITORY_ERROR_CODES,
  WorkflowInstanceRepositoryError,
} from '../runtime/factory-operational.mjs'
import { createAgentStepAttemptRepository } from '../lib/agent-step-attempt-store.mjs'
import { createAgentStepResultRepository } from '../lib/agent-step-result-store.mjs'
import { createOracleDefinitionRepository } from '../lib/oracle-definition.mjs'
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
} finally {
  await rm(root, { recursive: true, force: true })
}

console.log(`\nResult: ${passed} passed, ${failed} failed`)
process.exit(failed ? 1 : 0)
