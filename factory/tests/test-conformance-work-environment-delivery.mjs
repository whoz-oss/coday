/**
 * Cross-adapter conformance suite for the work-environment and delivery SQL
 * adapters (Milestone B3, wave W3).
 *
 * The exact same behavioural suite runs against the reference filesystem
 * adapters (delegating to the runtime `WorkUnitEnvironmentStore` and
 * `DeliveryStore`) and against the SQL adapters driven by the offline in-memory
 * `SqlClient`. It asserts:
 *
 *   1. work-environment reserve / idempotency / read / list / transition, the
 *      shared error codes (`INVALID_NAMESPACE`, `INVALID_ENVIRONMENT`,
 *      `INVALID_TRANSITION`, `REVISION_CONFLICT`, `NOT_FOUND`) and the
 *      validation failures returned for an invalid descriptor;
 *   2. delivery create / read / promote (idempotent replay and
 *      `IDEMPOTENCY_KEY_COLLISION`), operation lifecycle (create, start,
 *      record, reconcile, indeterminate blocking), rollback request /
 *      approval lifecycle and `updateSnapshot`;
 *   3. explicit observation parity between the filesystem and SQL adapters:
 *      for every scenario both backends must return the same state, revision,
 *      flags and machine error codes.
 *
 * The SQL adapter sources are TypeScript with `.js` import specifiers (the
 * factory convention) and cannot be loaded by `node` directly, so they are
 * bundled in-memory with the already-required `esbuild` toolchain dependency
 * and imported from a data URL — no file is written, no bundle is regenerated.
 *
 * Usage: node factory/tests/test-conformance-work-environment-delivery.mjs
 * Exit code: 0 when every case passes, 1 otherwise.
 */
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { build } from 'esbuild'

import {
  DeliveryStore,
  FilesystemDeliveryRepository,
  FilesystemWorkEnvironmentRepository,
  WorkUnitEnvironmentStore,
  defaultDeliveryDefinition,
  hashDeliveryDefinition,
  validateDeliveryDefinition,
  validateWorkUnitEnvironment,
} from '../runtime/factory-operational.mjs'
import { createInMemorySqlClient } from './support/in-memory-sql-client.mjs'

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

let passed = 0
let failed = 0

async function test(name, fn) {
  try {
    await fn()
    passed++
    console.log(`\u2713 ${name}`)
  } catch (error) {
    failed++
    console.error(`\u2717 ${name}\n   ${error?.stack ?? error}`)
  }
}

/** Runs `fn` and returns the thrown machine code (or its message). */
async function thrownCode(fn) {
  try {
    await fn()
    return 'NO_ERROR'
  } catch (error) {
    return error?.code ?? error?.message ?? String(error)
  }
}

/**
 * Stable projection of a write result: only the deterministic fields are kept
 * (`updatedAt` / `timestamp` are wall-clock and cannot be compared across
 * backends).
 */
function describeWrite(result) {
  if (!result || result.ok !== true) {
    return { ok: false, error: result?.error?.code ?? null }
  }
  return {
    ok: true,
    changed: result.changed ?? null,
    idempotent: result.idempotent ?? null,
    revision: result.snapshot?.revision ?? null,
    stage: result.snapshot?.stage ?? null,
    operationId: result.operation?.operationId ?? null,
    operationState: result.operation?.state ?? null,
    operationAttempt: result.operation?.attempt ?? null,
    requestStatus: result.request?.status ?? null,
  }
}

// ---------------------------------------------------------------------------
// SQL adapter sources, bundled in-memory (no generated bundle is touched)
// ---------------------------------------------------------------------------

const ADAPTER_ENTRY_POINTS = [
  '../src/adapters/persistence/sql/sql-work-environment-repository.ts',
  '../src/adapters/persistence/sql/sql-delivery-repository.ts',
]

const adapterEntry = ADAPTER_ENTRY_POINTS.map((entryPoint) => `export * from ${JSON.stringify(entryPoint)}`).join('\n')
const compiled = await build({
  stdin: {
    contents: adapterEntry,
    resolveDir: import.meta.dirname,
    loader: 'ts',
    sourcefile: 'conformance-work-environment-delivery-adapters.ts',
  },
  bundle: true,
  format: 'esm',
  platform: 'node',
  target: ['node22'],
  packages: 'bundle',
  external: ['node:*'],
  write: false,
  logLevel: 'silent',
})
const sqlModule = await import(
  `data:text/javascript;base64,${Buffer.from(compiled.outputFiles[0].text, 'utf8').toString('base64')}`
)

// ---------------------------------------------------------------------------
// Shared fixtures
// ---------------------------------------------------------------------------

const NAMESPACE_ID = '123e4567-e89b-42d3-a456-426614174000'
const OTHER_NAMESPACE_ID = '223e4567-e89b-42d3-a456-426614174000'
const CASE_ID = '22222222-2222-4222-8222-222222222222'
const ENVIRONMENT_ID = '33333333-3333-4333-8333-333333333333'
const COMMIT = 'a'.repeat(40)
const ENV_HASH = `sha256:${'b'.repeat(64)}`
const TARGET_HASH = `sha256:${'c'.repeat(64)}`
const ARTIFACT_DIGEST = `sha256:${'d'.repeat(64)}`
const SCOPE_HASH = `sha256:${'e'.repeat(64)}`
const SEMANTIC_HASH = `sha256:${'f'.repeat(64)}`

const validatedDefinition = validateDeliveryDefinition(defaultDeliveryDefinition())
const definition = {
  ...validatedDefinition.definition,
  definitionHash: hashDeliveryDefinition(validatedDefinition.definition),
}

// Work-environment descriptor: `baseCommit` is set at reservation so the
// `provisioning -> active` transition keeps every immutable field stable.
function environmentDescriptor(overrides = {}) {
  return {
    schemaVersion: '1',
    environmentId: 'env-1',
    workUnitId: 'unit-1',
    namespaceId: NAMESPACE_ID,
    repoRoot: '/repo',
    integrationBranch: 'main',
    branch: 'feature/x',
    worktreePath: '/worktrees/x',
    baseCommit: COMMIT,
    createdAt: '2025-01-01T00:00:00.000Z',
    createdBy: 'factory',
    lifecycleState: 'provisioning',
    ...overrides,
  }
}

/** Canonical descriptor (stable key order) as persisted by both adapters. */
function canonicalEnvironment(descriptor) {
  const result = validateWorkUnitEnvironment(descriptor)
  assert.equal(result.ok, true)
  return result.environment
}

function deliverySnapshotInput(overrides = {}) {
  return {
    schemaVersion: '1',
    deliveryId: 'delivery',
    namespaceId: NAMESPACE_ID,
    workflowId: 'workflow',
    environmentId: ENVIRONMENT_ID,
    environmentHash: ENV_HASH,
    parentCaseId: CASE_ID,
    runtimeId: 'factory-dashboard',
    worktreePath: '/tmp/w',
    branch: 'feature/test',
    baseCommit: COMMIT,
    headCommit: COMMIT,
    definitionType: 'factory-delivery',
    definitionVersion: '1.0.0',
    definitionHash: definition.definitionHash,
    stage: 'implementation-ready',
    revision: 1,
    evidenceIds: [],
    createdAt: '2025-01-01T00:00:00.000Z',
    updatedAt: '2025-01-01T00:00:00.000Z',
    git: {},
    artifact: {},
    release: {},
    deployment: {},
    verification: {},
    blockers: [],
    ...overrides,
  }
}

function deliveryEvidence() {
  const base = {
    namespaceId: NAMESPACE_ID,
    workflowId: 'workflow',
    deliveryId: 'delivery',
    environmentHash: ENV_HASH,
    caseId: CASE_ID,
    headCommit: COMMIT,
    source: { kind: 'factory' },
  }
  return [
    { evidenceId: 'ev-artifact', kind: 'artifact', outcome: 'pass', ...base },
    { evidenceId: 'ev-oracle', kind: 'oracle-result', outcome: 'pass', ...base },
  ]
}

function controlPlaneExecution() {
  return {
    kind: 'factory-control-plane',
    namespaceId: NAMESPACE_ID,
    workflowId: 'workflow',
    caseId: CASE_ID,
    runtimeId: 'factory-dashboard',
  }
}

function promotionRequest(overrides = {}) {
  return {
    requestId: 'req-promote',
    deliveryId: 'delivery',
    expectedRevision: 1,
    requestedStage: 'artifact-ready',
    evidenceIds: ['ev-artifact', 'ev-oracle'],
    idempotencyKey: 'promote-1',
    ...overrides,
  }
}

const DEPLOYMENT_REQUEST = Object.freeze({
  kind: 'deployment',
  expectedRevision: 3,
  idempotencyKey: 'deploy-once',
  targetId: 'prod',
  artifactRef: Object.freeze({
    digest: ARTIFACT_DIGEST,
    mediaType: 'application/zip',
    producerRef: 'build',
    buildRef: 'build-1',
    sourceCommit: COMMIT,
  }),
  releaseRef: Object.freeze({
    releaseId: 'release-1',
    artifactDigest: ARTIFACT_DIGEST,
    sourceCommit: COMMIT,
    approvedEvidenceId: 'approval',
  }),
})

function deliveryOperationArgs() {
  return {
    namespaceId: NAMESPACE_ID,
    workflowId: 'workflow',
    deliveryId: 'delivery-ops',
    caseId: CASE_ID,
    runtimeId: 'factory-dashboard',
    request: DEPLOYMENT_REQUEST,
    targetRef: { targetId: 'prod', targetHash: TARGET_HASH, adapterId: 'test', adapterTargetRef: 'trusted' },
    execution: { kind: 'factory-control-plane', actorId: 'factory' },
  }
}

const ROLLBACK_REQUEST = Object.freeze({
  rollbackRequestId: 'rrq_123',
  expectedRevision: 1,
  idempotencyKey: 'rollback-1',
  targetId: 'prod',
  targetHash: TARGET_HASH,
  deploymentRef: { operationId: 'dop_1' },
  priorArtifactRef: { digest: ARTIFACT_DIGEST },
  priorReleaseRef: { releaseId: 'old' },
  reasonCode: 'bad-release',
  reason: 'bounded explanation',
  scopeHash: SCOPE_HASH,
  semanticHash: SEMANTIC_HASH,
})

function rollbackRequestArgs() {
  return {
    namespaceId: NAMESPACE_ID,
    deliveryId: 'delivery-rollback',
    workflowId: 'workflow',
    caseId: CASE_ID,
    runtimeId: 'factory-dashboard',
    request: ROLLBACK_REQUEST,
    execution: {
      kind: 'factory-control-plane',
      namespaceId: NAMESPACE_ID,
      workflowId: 'workflow',
      caseId: CASE_ID,
      runtimeId: 'factory-dashboard',
      actorId: 'alice',
    },
  }
}

// ---------------------------------------------------------------------------
// Shared behavioural suite: work environment
// ---------------------------------------------------------------------------

async function runWorkEnvironmentSuite(repository) {
  const observation = {}

  // a. valid descriptor reserve creates revision 1
  const reserved = await repository.reserve(environmentDescriptor())
  assert.equal(reserved.ok, true)
  assert.equal(reserved.changed, true)
  assert.equal(reserved.snapshot.revision, 1)
  assert.equal(reserved.snapshot.environment.lifecycleState, 'provisioning')
  observation.reserve = describeWrite(reserved)

  // re-reserving an identical descriptor is a no-op
  const reserveReplay = await repository.reserve(environmentDescriptor())
  assert.equal(reserveReplay.ok, true)
  assert.equal(reserveReplay.changed, false)
  assert.equal(reserveReplay.snapshot.revision, 1)
  observation.reserveReplay = describeWrite(reserveReplay)

  // a divergent descriptor fails with INVALID_TRANSITION
  const reserveDivergent = await repository.reserve(environmentDescriptor({ branch: 'feature/other' }))
  assert.equal(reserveDivergent.ok, false)
  assert.equal(reserveDivergent.error.code, 'INVALID_TRANSITION')
  observation.reserveDivergent = describeWrite(reserveDivergent)

  // an invalid descriptor is rejected with the domain validation code
  const reserveInvalid = await repository.reserve({
    ...environmentDescriptor({ environmentId: 'env-invalid' }),
    repoRoot: 'relative',
  })
  assert.equal(reserveInvalid.ok, false)
  assert.equal(reserveInvalid.error.code, 'INVALID_PATH')
  observation.reserveInvalid = describeWrite(reserveInvalid)

  // b. read returns the stored snapshot or null
  const read = await repository.read(NAMESPACE_ID, 'env-1')
  assert.equal(read.revision, 1)
  assert.equal(read.environment.environmentId, 'env-1')
  observation.read = {
    revision: read.revision,
    environmentId: read.environment.environmentId,
    state: read.environment.lifecycleState,
  }
  const readMissing = await repository.read(NAMESPACE_ID, 'env-absent')
  assert.equal(readMissing, null)
  observation.readMissing = readMissing

  // c. list returns environments filtered by state or all active
  await repository.reserve(
    environmentDescriptor({ environmentId: 'env-2', branch: 'feature/y', worktreePath: '/worktrees/y' })
  )
  const listAll = await repository.list(NAMESPACE_ID)
  assert.deepEqual(listAll.map((snapshot) => snapshot.environment.environmentId).sort(), ['env-1', 'env-2'])
  observation.listAll = listAll.map((snapshot) => snapshot.environment.environmentId).sort()

  const listProvisioning = await repository.list(NAMESPACE_ID, { states: ['provisioning'] })
  observation.listProvisioning = listProvisioning.map((snapshot) => snapshot.environment.environmentId).sort()

  const listActive = await repository.list(NAMESPACE_ID, { states: ['active'] })
  assert.deepEqual(listActive, [])
  observation.listActive = listActive.length

  const listOtherNamespace = await repository.list(OTHER_NAMESPACE_ID)
  assert.deepEqual(listOtherNamespace, [])
  observation.listOtherNamespace = listOtherNamespace.length

  // d. valid transition (provisioning -> active) increments the revision
  const activeDescriptor = canonicalEnvironment({
    ...environmentDescriptor(),
    lifecycleState: 'active',
    parentCaseId: CASE_ID,
  })
  const transitioned = await repository.transition(NAMESPACE_ID, 'env-1', activeDescriptor)
  assert.equal(transitioned.ok, true)
  assert.equal(transitioned.changed, true)
  assert.equal(transitioned.snapshot.revision, 2)
  assert.equal(transitioned.snapshot.environment.lifecycleState, 'active')
  observation.transition = describeWrite(transitioned)

  const transitionReplay = await repository.transition(NAMESPACE_ID, 'env-1', activeDescriptor)
  assert.equal(transitionReplay.ok, true)
  assert.equal(transitionReplay.changed, false)
  assert.equal(transitionReplay.snapshot.revision, 2)
  observation.transitionReplay = describeWrite(transitionReplay)

  const transitionInvalid = await repository.transition(
    NAMESPACE_ID,
    'env-1',
    canonicalEnvironment(environmentDescriptor())
  )
  assert.equal(transitionInvalid.ok, false)
  assert.equal(transitionInvalid.error.code, 'INVALID_TRANSITION')
  observation.transitionInvalid = describeWrite(transitionInvalid)

  const transitionRevisionConflict = await repository.transition(NAMESPACE_ID, 'env-1', activeDescriptor, {
    expectedRevision: 99,
  })
  assert.equal(transitionRevisionConflict.ok, false)
  assert.equal(transitionRevisionConflict.error.code, 'REVISION_CONFLICT')
  observation.transitionRevisionConflict = describeWrite(transitionRevisionConflict)

  const transitionNotFound = await repository.transition(
    NAMESPACE_ID,
    'env-absent',
    canonicalEnvironment(environmentDescriptor({ environmentId: 'env-absent' }))
  )
  assert.equal(transitionNotFound.ok, false)
  assert.equal(transitionNotFound.error.code, 'NOT_FOUND')
  observation.transitionNotFound = describeWrite(transitionNotFound)

  // e. error codes on invalid inputs
  observation.invalidNamespace = await thrownCode(() => repository.read('not-a-uuid', 'env-1'))
  assert.equal(observation.invalidNamespace, 'INVALID_NAMESPACE')
  observation.invalidEnvironment = await thrownCode(() => repository.read(NAMESPACE_ID, 'bad id!'))
  assert.equal(observation.invalidEnvironment, 'INVALID_ENVIRONMENT')

  return observation
}

// ---------------------------------------------------------------------------
// Shared behavioural suite: delivery
// ---------------------------------------------------------------------------

async function runDeliverySuite(repository) {
  const observation = {}

  // a. create (revision 1), idempotent replay, identity conflict
  const created = await repository.create(deliverySnapshotInput())
  assert.equal(created.ok, true)
  assert.equal(created.changed, true)
  assert.equal(created.snapshot.revision, 1)
  observation.create = describeWrite(created)

  const createReplay = await repository.create(deliverySnapshotInput())
  assert.equal(createReplay.ok, true)
  assert.equal(createReplay.changed, false)
  assert.equal(createReplay.snapshot.revision, 1)
  observation.createReplay = describeWrite(createReplay)

  const createConflict = await repository.create(deliverySnapshotInput({ stage: 'artifact-ready' }))
  assert.equal(createConflict.ok, false)
  assert.equal(createConflict.error.code, 'DELIVERY_IDENTITY_CONFLICT')
  observation.createConflict = describeWrite(createConflict)

  const createInvalidSnapshot = await repository.create(
    deliverySnapshotInput({ deliveryId: 'delivery-invalid', environmentHash: 'not-a-hash' })
  )
  assert.equal(createInvalidSnapshot.ok, false)
  assert.equal(createInvalidSnapshot.error.code, 'INVALID_DELIVERY_SNAPSHOT')
  observation.createInvalidSnapshot = describeWrite(createInvalidSnapshot)

  observation.createInvalidScope = await thrownCode(() =>
    repository.create(deliverySnapshotInput({ deliveryId: 'bad id!' }))
  )
  assert.equal(observation.createInvalidScope, 'INVALID_DELIVERY_SCOPE')

  // b. read
  const read = await repository.read(NAMESPACE_ID, 'delivery')
  assert.equal(read.revision, 1)
  assert.equal(read.stage, 'implementation-ready')
  observation.read = { deliveryId: read.deliveryId, stage: read.stage, revision: read.revision }
  const readMissing = await repository.read(NAMESPACE_ID, 'delivery-absent')
  assert.equal(readMissing, null)
  observation.readMissing = readMissing

  // c. promote against policy / definition, idempotent replay, collision
  const promoted = await repository.promote({
    namespaceId: NAMESPACE_ID,
    request: promotionRequest(),
    definition,
    evidence: deliveryEvidence(),
    execution: controlPlaneExecution(),
  })
  assert.equal(promoted.ok, true)
  assert.equal(promoted.changed, true)
  assert.equal(promoted.idempotent, false)
  assert.equal(promoted.snapshot.stage, 'artifact-ready')
  assert.equal(promoted.snapshot.revision, 2)
  observation.promote = describeWrite(promoted)

  const promoteReplay = await repository.promote({
    namespaceId: NAMESPACE_ID,
    request: promotionRequest(),
    definition,
    evidence: deliveryEvidence(),
    execution: controlPlaneExecution(),
  })
  assert.equal(promoteReplay.ok, true)
  assert.equal(promoteReplay.changed, false)
  assert.equal(promoteReplay.idempotent, true)
  observation.promoteReplay = describeWrite(promoteReplay)

  const promoteCollision = await repository.promote({
    namespaceId: NAMESPACE_ID,
    request: { ...promotionRequest(), requestedStage: 'release-approved', evidenceIds: [] },
    definition,
    evidence: deliveryEvidence(),
    execution: controlPlaneExecution(),
  })
  assert.equal(promoteCollision.ok, false)
  assert.equal(promoteCollision.error.code, 'IDEMPOTENCY_KEY_COLLISION')
  observation.promoteCollision = describeWrite(promoteCollision)

  const promoteIllegal = await repository.promote({
    namespaceId: NAMESPACE_ID,
    request: promotionRequest({ idempotencyKey: 'promote-illegal', expectedRevision: 2, requestedStage: 'deployed' }),
    definition,
    evidence: deliveryEvidence(),
    execution: controlPlaneExecution(),
  })
  assert.equal(promoteIllegal.ok, false)
  assert.equal(promoteIllegal.error.code, 'ILLEGAL_PROMOTION')
  observation.promoteIllegal = describeWrite(promoteIllegal)

  // d. readWithOperations
  const withOperations = await repository.readWithOperations(NAMESPACE_ID, 'delivery')
  assert.equal(withOperations.revision, 2)
  observation.readWithOperations = {
    revision: withOperations.revision,
    stage: withOperations.stage,
    deliveryOperations: withOperations.deliveryOperations.map((operation) => operation.state),
    rollbackRequests: withOperations.rollbackRequests.map((request) => request.status),
  }
  const withOperationsMissing = await repository.readWithOperations(NAMESPACE_ID, 'delivery-absent')
  assert.equal(withOperationsMissing, null)
  observation.readWithOperationsMissing = withOperationsMissing

  // e. delivery-operation lifecycle
  await repository.create(
    deliverySnapshotInput({ deliveryId: 'delivery-ops', stage: 'release-approved', revision: 3 })
  )

  const createdOperation = await repository.createDeliveryOperation(deliveryOperationArgs())
  assert.equal(createdOperation.ok, true)
  assert.equal(createdOperation.changed, true)
  assert.equal(createdOperation.operation.state, 'pending')
  observation.operationCreate = describeWrite(createdOperation)

  const replayedOperation = await repository.createDeliveryOperation(deliveryOperationArgs())
  assert.equal(replayedOperation.ok, true)
  assert.equal(replayedOperation.changed, false)
  assert.equal(replayedOperation.idempotent, true)
  observation.operationReplay = describeWrite(replayedOperation)

  const collidedOperation = await repository.createDeliveryOperation({
    ...deliveryOperationArgs(),
    request: {
      ...DEPLOYMENT_REQUEST,
      artifactRef: { ...DEPLOYMENT_REQUEST.artifactRef, buildRef: 'build-2' },
    },
  })
  assert.equal(collidedOperation.ok, false)
  assert.equal(collidedOperation.error.code, 'IDEMPOTENCY_KEY_COLLISION')
  observation.operationCollision = describeWrite(collidedOperation)

  const staleOperation = await repository.createDeliveryOperation({
    ...deliveryOperationArgs(),
    request: { ...DEPLOYMENT_REQUEST, idempotencyKey: 'deploy-stale', expectedRevision: 2 },
  })
  assert.equal(staleOperation.ok, false)
  assert.equal(staleOperation.error.code, 'REVISION_CONFLICT')
  observation.operationRevisionConflict = describeWrite(staleOperation)

  const invalidOperation = await repository.createDeliveryOperation({
    ...deliveryOperationArgs(),
    request: { kind: 'unknown-kind', expectedRevision: 3, idempotencyKey: 'deploy-invalid', targetId: 'prod' },
  })
  assert.equal(invalidOperation.ok, false)
  assert.equal(invalidOperation.error.code, 'INVALID_DELIVERY_OPERATION_REQUEST')
  observation.operationInvalid = describeWrite(invalidOperation)

  const operationId = createdOperation.operation.operationId
  const started = await repository.startDeliveryOperation(
    NAMESPACE_ID,
    'delivery-ops',
    operationId,
    'adapter-correlation'
  )
  assert.equal(started.ok, true)
  assert.equal(started.operation.state, 'running')
  assert.equal(started.operation.attempt, 1)
  observation.operationStart = describeWrite(started)

  const restarted = await repository.startDeliveryOperation(
    NAMESPACE_ID,
    'delivery-ops',
    operationId,
    'adapter-correlation'
  )
  assert.equal(restarted.ok, false)
  assert.equal(restarted.error.code, 'INVALID_DELIVERY_OPERATION_TRANSITION')
  observation.operationRestart = describeWrite(restarted)

  const indeterminate = await repository.recordDeliveryOperation(NAMESPACE_ID, 'delivery-ops', operationId, {
    state: 'indeterminate',
    error: { code: 'LOST_RESPONSE' },
  })
  assert.equal(indeterminate.ok, true)
  assert.equal(indeterminate.operation.state, 'indeterminate')
  observation.operationIndeterminate = describeWrite(indeterminate)

  observation.operationHasIndeterminate = await repository.hasIndeterminateOperation(NAMESPACE_ID, 'delivery-ops')
  assert.equal(observation.operationHasIndeterminate, true)

  const blockedOperation = await repository.createDeliveryOperation({
    ...deliveryOperationArgs(),
    request: { ...DEPLOYMENT_REQUEST, idempotencyKey: 'deploy-blocked' },
  })
  assert.equal(blockedOperation.ok, false)
  assert.equal(blockedOperation.error.code, 'DELIVERY_OPERATION_INDETERMINATE')
  observation.operationBlocked = describeWrite(blockedOperation)

  const reconciled = await repository.reconcileDeliveryOperation(NAMESPACE_ID, 'delivery-ops', operationId, {
    state: 'succeeded',
    result: { deploymentId: 'd1' },
  })
  assert.equal(reconciled.ok, true)
  assert.equal(reconciled.operation.state, 'succeeded')
  observation.operationReconcile = describeWrite(reconciled)

  observation.operationHasIndeterminateAfter = await repository.hasIndeterminateOperation(
    NAMESPACE_ID,
    'delivery-ops'
  )
  assert.equal(observation.operationHasIndeterminateAfter, false)

  const missingOperation = await repository.recordDeliveryOperation(NAMESPACE_ID, 'delivery-ops', 'dop_missing', {
    state: 'running',
  })
  assert.equal(missingOperation.ok, false)
  assert.equal(missingOperation.error.code, 'DELIVERY_OPERATION_NOT_FOUND')
  observation.operationMissing = describeWrite(missingOperation)

  const wrongOperationTransition = await repository.recordDeliveryOperation(
    NAMESPACE_ID,
    'delivery-ops',
    operationId,
    { state: 'running' }
  )
  assert.equal(wrongOperationTransition.ok, false)
  assert.equal(wrongOperationTransition.error.code, 'INVALID_DELIVERY_OPERATION_TRANSITION')
  observation.operationWrongTransition = describeWrite(wrongOperationTransition)

  // f. rollback request / approval lifecycle
  await repository.create(
    deliverySnapshotInput({ deliveryId: 'delivery-rollback', stage: 'deployed', revision: 1 })
  )

  const rollbackRequested = await repository.createRollbackRequest(rollbackRequestArgs())
  assert.equal(rollbackRequested.ok, true)
  assert.equal(rollbackRequested.changed, true)
  assert.equal(rollbackRequested.request.status, 'requested')
  observation.rollbackCreate = describeWrite(rollbackRequested)

  const rollbackReplay = await repository.createRollbackRequest(rollbackRequestArgs())
  assert.equal(rollbackReplay.ok, true)
  assert.equal(rollbackReplay.idempotent, true)
  observation.rollbackReplay = describeWrite(rollbackReplay)

  const rollbackCollision = await repository.createRollbackRequest({
    ...rollbackRequestArgs(),
    request: { ...ROLLBACK_REQUEST, semanticHash: SCOPE_HASH },
  })
  assert.equal(rollbackCollision.ok, false)
  assert.equal(rollbackCollision.error.code, 'IDEMPOTENCY_KEY_COLLISION')
  observation.rollbackCollision = describeWrite(rollbackCollision)

  const rollbackApproveMissing = await repository.approveRollbackRequest(
    NAMESPACE_ID,
    'delivery-rollback',
    'rrq_missing',
    { expectedRevision: 1, idempotencyKey: 'approve-missing', execution: { actorId: 'alice' } }
  )
  assert.equal(rollbackApproveMissing.ok, false)
  assert.equal(rollbackApproveMissing.error.code, 'ROLLBACK_REQUEST_NOT_FOUND')
  observation.rollbackApproveMissing = describeWrite(rollbackApproveMissing)

  const rollbackApproveRevisionConflict = await repository.approveRollbackRequest(
    NAMESPACE_ID,
    'delivery-rollback',
    'rrq_123',
    { expectedRevision: 99, idempotencyKey: 'approve-stale', execution: { actorId: 'alice' } }
  )
  assert.equal(rollbackApproveRevisionConflict.ok, false)
  assert.equal(rollbackApproveRevisionConflict.error.code, 'REVISION_CONFLICT')
  observation.rollbackApproveRevisionConflict = describeWrite(rollbackApproveRevisionConflict)

  const rollbackApproved = await repository.approveRollbackRequest(NAMESPACE_ID, 'delivery-rollback', 'rrq_123', {
    expectedRevision: 1,
    idempotencyKey: 'approve-1',
    execution: { actorId: 'alice' },
  })
  assert.equal(rollbackApproved.ok, true)
  assert.equal(rollbackApproved.request.status, 'approved')
  observation.rollbackApprove = describeWrite(rollbackApproved)
  observation.rollbackApprovedActor = rollbackApproved.request.approvedBy.actorId
  assert.equal(observation.rollbackApprovedActor, 'alice')

  const rollbackApproveReplay = await repository.approveRollbackRequest(
    NAMESPACE_ID,
    'delivery-rollback',
    'rrq_123',
    { expectedRevision: 1, idempotencyKey: 'approve-1', execution: { actorId: 'alice' } }
  )
  assert.equal(rollbackApproveReplay.ok, true)
  assert.equal(rollbackApproveReplay.idempotent, true)
  observation.rollbackApproveReplay = describeWrite(rollbackApproveReplay)

  const rollbackAlreadyDecided = await repository.approveRollbackRequest(
    NAMESPACE_ID,
    'delivery-rollback',
    'rrq_123',
    { expectedRevision: 1, idempotencyKey: 'approve-2', execution: { actorId: 'alice' } }
  )
  assert.equal(rollbackAlreadyDecided.ok, false)
  assert.equal(rollbackAlreadyDecided.error.code, 'ROLLBACK_REQUEST_ALREADY_DECIDED')
  observation.rollbackAlreadyDecided = describeWrite(rollbackAlreadyDecided)

  observation.hasIndeterminateNone = await repository.hasIndeterminateOperation(NAMESPACE_ID, 'delivery-rollback')
  assert.equal(observation.hasIndeterminateNone, false)

  // g. updateSnapshot patches fields and records a journal operation
  await repository.create(deliverySnapshotInput({ deliveryId: 'delivery-patch' }))
  const patched = await repository.updateSnapshot(
    NAMESPACE_ID,
    'delivery-patch',
    { 'git.checkpoint': 'cp-1', stage: 'artifact-ready' },
    { kind: 'git-push', idempotencyKey: 'push-1' }
  )
  assert.equal(patched.ok, true)
  assert.equal(patched.changed, true)
  // `updateSnapshot` re-writes the snapshot with the patch applied; neither
  // adapter bumps the snapshot revision on a patch (only journal operations do).
  assert.equal(patched.snapshot.revision, 1)
  assert.equal(patched.snapshot.stage, 'artifact-ready')
  assert.equal(patched.snapshot.git.checkpoint, 'cp-1')
  observation.updateSnapshot = describeWrite(patched)

  const patchedRead = await repository.read(NAMESPACE_ID, 'delivery-patch')
  assert.equal(patchedRead.git.checkpoint, 'cp-1')
  observation.updateSnapshotRead = {
    revision: patchedRead.revision,
    stage: patchedRead.stage,
    checkpoint: patchedRead.git.checkpoint,
  }

  const updateSnapshotMissing = await repository.updateSnapshot(
    NAMESPACE_ID,
    'delivery-absent',
    { stage: 'x' },
    { kind: 'noop', idempotencyKey: 'noop-1' }
  )
  assert.equal(updateSnapshotMissing.ok, false)
  assert.equal(updateSnapshotMissing.error.code, 'DELIVERY_NOT_FOUND')
  observation.updateSnapshotMissing = describeWrite(updateSnapshotMissing)

  return observation
}

// ---------------------------------------------------------------------------
// Adapter wiring
// ---------------------------------------------------------------------------

function createFilesystemAdapters(root) {
  const environmentStore = new WorkUnitEnvironmentStore(join(root, 'environment'))
  const deliveryStore = new DeliveryStore(join(root, 'delivery'))
  return {
    environment: async () => {
      await environmentStore.initialize()
      return new FilesystemWorkEnvironmentRepository(environmentStore)
    },
    delivery: async () => {
      await deliveryStore.initialize()
      return new FilesystemDeliveryRepository(deliveryStore)
    },
  }
}

function createSqlAdapters() {
  const environmentClient = createInMemorySqlClient()
  const deliveryClient = createInMemorySqlClient()
  return {
    environment: async () => new sqlModule.SqlWorkEnvironmentRepository(environmentClient),
    delivery: async () => new sqlModule.SqlDeliveryRepository(deliveryClient),
  }
}

// ---------------------------------------------------------------------------
// Run
// ---------------------------------------------------------------------------

const root = await mkdtemp(join(tmpdir(), 'factory-conformance-work-env-delivery-'))

try {
  const filesystem = createFilesystemAdapters(root)
  const sql = createSqlAdapters()

  let filesystemEnvironmentObservation
  let sqlEnvironmentObservation
  let filesystemDeliveryObservation
  let sqlDeliveryObservation

  await test('[filesystem] work-environment scenario suite', async () => {
    filesystemEnvironmentObservation = await runWorkEnvironmentSuite(await filesystem.environment())
  })
  await test('[sql] work-environment scenario suite', async () => {
    sqlEnvironmentObservation = await runWorkEnvironmentSuite(await sql.environment())
  })
  await test('work-environment parity: identical observations and error codes', () => {
    assert.ok(filesystemEnvironmentObservation && sqlEnvironmentObservation, 'both scenario suites must run')
    assert.ok(
      Object.keys(filesystemEnvironmentObservation).length >= 15,
      'work-environment suite must cover every required scenario'
    )
    assert.deepEqual(sqlEnvironmentObservation, filesystemEnvironmentObservation)
  })

  await test('[filesystem] delivery scenario suite', async () => {
    filesystemDeliveryObservation = await runDeliverySuite(await filesystem.delivery())
  })
  await test('[sql] delivery scenario suite', async () => {
    sqlDeliveryObservation = await runDeliverySuite(await sql.delivery())
  })
  await test('delivery parity: identical observations and error codes', () => {
    assert.ok(filesystemDeliveryObservation && sqlDeliveryObservation, 'both scenario suites must run')
    assert.ok(
      Object.keys(filesystemDeliveryObservation).length >= 35,
      'delivery suite must cover every required scenario'
    )
    assert.deepEqual(sqlDeliveryObservation, filesystemDeliveryObservation)
  })
} finally {
  await rm(root, { recursive: true, force: true })
}

console.log(`\nResult: ${passed} passed, ${failed} failed`)
process.exit(failed ? 1 : 0)
