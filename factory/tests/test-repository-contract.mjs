// Shared repository contract for the pilot aggregates.
//
// The exact same assertion suite is executed against the filesystem adapters
// and the SQL adapters so both implementations are held to one behavioural
// specification. The suite is a library: `test-sql-repository-ports-adapters.mjs`
// decides which implementations to run it against.
//
// Usage: node factory/tests/test-sql-repository-ports-adapters.mjs
import assert from 'node:assert/strict'

import { WORKFLOW_DEFINITION_REPOSITORY_ERROR_CODES } from '../runtime/factory-operational.mjs'

export const CONTRACT_WORKFLOW_TYPE = 'demo'
export const CONTRACT_WORKFLOW_VERSION = '1.0.0'
export const CONTRACT_NAMESPACE_ID = '11111111-1111-4111-8111-111111111111'

export const CONTRACT_DEFINITION_INPUT = {
  workflowType: CONTRACT_WORKFLOW_TYPE,
  version: CONTRACT_WORKFLOW_VERSION,
  definitionHash: 'a'.repeat(64),
  steps: [{ id: 'build', name: 'Build', responsibility: { kind: 'agent', name: 'worker' }, dependsOn: [] }],
}

export const CONTRACT_CONTROLLER_EXECUTION = {
  runtimeId: 'runtime-contract',
  kind: 'factory',
  agentId: 'worker',
  caseId: 'case-contract',
  actorId: 'actor-contract',
}

/** Runs an ordered list of `[name, fn]` scenarios, reporting each outcome. */
export async function executeContractSuite({ suiteName, scenarios }) {
  let passed = 0
  let failed = 0
  for (const [name, scenario] of scenarios) {
    try {
      await scenario()
      passed++
      console.log(`✓ ${suiteName}: ${name}`)
    } catch (error) {
      failed++
      console.log(`✗ ${suiteName}: ${name}`)
      console.log(`  ${error?.stack ?? error}`)
    }
  }
  return { passed, failed }
}

/** Behavioural contract of `WorkflowDefinitionRepository` over a seeded store. */
export function definitionRepositoryScenarios({ repository }) {
  return [
    [
      'list returns the seeded definition with a canonical hash',
      async () => {
        const list = await repository.list()
        assert.equal(list.length, 1)
        assert.equal(list[0].workflowType, CONTRACT_WORKFLOW_TYPE)
        assert.equal(list[0].version, CONTRACT_WORKFLOW_VERSION)
        assert.match(list[0].definitionHash, /^[0-9a-f]{64}$/)
      },
    ],
    [
      'get resolves an exact version and returns null otherwise',
      async () => {
        const found = await repository.get(CONTRACT_WORKFLOW_TYPE, CONTRACT_WORKFLOW_VERSION)
        assert.ok(found)
        assert.match(found.definitionHash, /^[0-9a-f]{64}$/)
        assert.equal(await repository.get(CONTRACT_WORKFLOW_TYPE, '9.9.9'), null)
        assert.equal(await repository.get('missing', CONTRACT_WORKFLOW_VERSION), null)
      },
    ],
    [
      'resolveUnique returns the highest version and fails closed when unknown',
      async () => {
        const unique = await repository.resolveUnique(CONTRACT_WORKFLOW_TYPE)
        assert.equal(unique.version, CONTRACT_WORKFLOW_VERSION)
        await assert.rejects(
          () => repository.resolveUnique('missing'),
          (error) => error.code === WORKFLOW_DEFINITION_REPOSITORY_ERROR_CODES.WORKFLOW_DEFINITION_NOT_FOUND
        )
      },
    ],
  ]
}

/** Behavioural contract of `WorkflowInstanceRepository` over an empty store. */
export function instanceRepositoryScenarios({ repository, namespaceId }) {
  const workflowId = 'wf-contract-1'
  const command = { workflowId, workflowType: CONTRACT_WORKFLOW_TYPE, title: 'Contract run' }
  const transitionRequest = (overrides = {}) => ({
    requestId: 'req-contract',
    workflowId,
    stepId: 'build',
    expectedRevision: 1,
    requestedStatus: 'running',
    evidenceIds: [],
    ...overrides,
  })
  const execution = { kind: 'factory', runtimeId: 'runtime-contract', agentId: 'worker', caseId: 'case-contract' }

  return [
    [
      'create materializes revision 1 and replays idempotently',
      async () => {
        const created = await repository.create(
          namespaceId,
          command,
          CONTRACT_DEFINITION_INPUT,
          CONTRACT_CONTROLLER_EXECUTION
        )
        assert.equal(created.instance.workflowId, workflowId)
        assert.equal(created.instance.revision, 1)
        assert.equal(created.projection.schemaVersion, '2')
        assert.equal(created.projection.workflowId, workflowId)
        const replay = await repository.create(
          namespaceId,
          command,
          CONTRACT_DEFINITION_INPUT,
          CONTRACT_CONTROLLER_EXECUTION
        )
        assert.equal(replay.instance.revision, 1)
      },
    ],
    [
      'create rejects a divergent command for the same workflow id',
      async () => {
        await assert.rejects(
          () =>
            repository.create(
              namespaceId,
              { ...command, title: 'A different run' },
              CONTRACT_DEFINITION_INPUT,
              CONTRACT_CONTROLLER_EXECUTION
            ),
          (error) => error.code === 'WORKFLOW_IDENTITY_CONFLICT'
        )
      },
    ],
    [
      'get and list expose live projections only',
      async () => {
        const read = await repository.get(namespaceId, workflowId)
        assert.ok(read)
        assert.equal(read.instance.revision, 1)
        assert.equal(await repository.get(namespaceId, 'missing'), null)
        const projections = await repository.list(namespaceId)
        assert.deepEqual(
          projections.map((projection) => projection.workflowId),
          [workflowId]
        )
      },
    ],
    [
      'transition applies the shared state machine and bumps the revision',
      async () => {
        const transitioned = await repository.transition(namespaceId, workflowId, {
          request: transitionRequest(),
          definition: CONTRACT_DEFINITION_INPUT,
          evidence: [],
          execution,
        })
        assert.equal(transitioned.instance.revision, 2)
        assert.equal(transitioned.instance.steps.find((step) => step.id === 'build').status, 'running')
        assert.equal(transitioned.projection.steps.find((step) => step.id === 'build').status, 'running')
      },
    ],
    [
      'transition rejects a stale revision with REVISION_CONFLICT',
      async () => {
        await assert.rejects(
          () =>
            repository.transition(namespaceId, workflowId, {
              request: transitionRequest({
                requestId: 'req-stale',
                expectedRevision: 99,
                requestedStatus: 'completed',
              }),
              definition: CONTRACT_DEFINITION_INPUT,
              evidence: [],
              execution,
            }),
          (error) => error.code === 'REVISION_CONFLICT'
        )
      },
    ],
    [
      'transition surfaces an injected policy denial',
      async () => {
        await assert.rejects(
          () =>
            repository.transition(namespaceId, workflowId, {
              request: transitionRequest({ requestId: 'req-denied', expectedRevision: 2 }),
              definition: CONTRACT_DEFINITION_INPUT,
              evidence: [],
              execution,
              policy: () => ({ allowed: false, code: 'DENIED_BY_CONTRACT', reason: 'contract' }),
            }),
          (error) => error.code === 'DENIED_BY_CONTRACT'
        )
      },
    ],
    [
      'remove, restore and purge drive the lifecycle',
      async () => {
        await repository.remove(namespaceId, workflowId)
        assert.equal(await repository.get(namespaceId, workflowId), null)
        assert.deepEqual(await repository.list(namespaceId), [])
        await repository.restore(namespaceId, workflowId)
        assert.ok(await repository.get(namespaceId, workflowId))
        await repository.remove(namespaceId, workflowId)
        await repository.purge(namespaceId, workflowId)
        assert.equal(await repository.get(namespaceId, workflowId), null)
        assert.deepEqual(await repository.list(namespaceId), [])
      },
    ],
  ]
}
