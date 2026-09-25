// Repository ports & filesystem adapter integration tests.
//
// Offline, no framework: exits 0 when every case passes, 1 otherwise.
// Usage: node factory/tests/test-repository-ports-adapters.mjs
import assert from 'node:assert/strict'
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import {
  FilesystemWorkflowDefinitionRepository,
  FilesystemWorkflowEvidenceRepository,
  FilesystemWorkflowHumanInteractionRepository,
  FilesystemWorkflowInstanceRepository,
  WORKFLOW_DEFINITION_REPOSITORY_ERROR_CODES,
  WorkflowInstanceRepositoryError,
} from '../runtime/factory-operational.mjs'
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
} finally {
  await rm(root, { recursive: true, force: true })
}

console.log(`\nResult: ${passed} passed, ${failed} failed`)
process.exit(failed ? 1 : 0)
