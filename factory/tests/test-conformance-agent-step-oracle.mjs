/**
 * Cross-adapter conformance suite for the agent-step attempt / result SQL
 * adapters and the oracle-execution SQL adapter (Milestone B, wave W2).
 *
 * The same behavioural suite is executed against the reference filesystem
 * adapters (delegating to the runtime `AgentStepAttemptStore` /
 * `AgentStepResultStore`) and against the SQL adapters driven by the offline
 * in-memory `SqlClient`. It asserts:
 *
 *   1. attempt lifecycle, invalid transition, identity conflict, must-start and
 *      namespace mismatch error codes;
 *   2. capability issuance (single-use, identity conflict, invalid identity);
 *   3. submission idempotency (identical replay), `RESULT_SEMANTIC_COLLISION` on
 *      a divergent payload, schema / capability / identity / expiry errors;
 *   4. SQL atomicity: a failure inside the submission transaction rolls back the
 *      result row, the attempt terminalization and the outbox event together;
 *   5. Amendment 5: oracle terminalization publishes the linked artifact in the
 *      same transaction, with rollback semantics;
 *   6. explicit error-code parity between the filesystem and SQL adapters.
 *
 * The SQL adapter sources are TypeScript with `.js` import specifiers (the
 * factory convention) and cannot be loaded by `node` directly, so they are
 * bundled in-memory with the already-required `esbuild` toolchain dependency
 * and imported from a data URL — no file is written, no bundle is regenerated.
 *
 * Usage: node factory/tests/test-conformance-agent-step-oracle.mjs
 * Exit code: 0 when every case passes, 1 otherwise.
 */
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { build } from 'esbuild'

import {
  AgentStepAttemptStore,
  AgentStepResultStore,
  createFilesystemAgentStepAttemptRepository,
  createFilesystemAgentStepResultRepository,
} from '../runtime/factory-operational.mjs'
import { createInMemorySqlClient } from './support/in-memory-sql-client.mjs'

const NAMESPACE_ID = 'conformance'
const TTL_MS = 15 * 60 * 1000
const BRIEF_HASH = `sha256:${'a'.repeat(64)}`

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

async function thrownCode(fn) {
  try {
    await fn()
    return 'NO_ERROR'
  } catch (error) {
    return error?.message ?? String(error)
  }
}

// ---------------------------------------------------------------------------
// SQL adapter sources, bundled in-memory (no generated bundle is touched)
// ---------------------------------------------------------------------------

const ADAPTER_ENTRY_POINTS = [
  '../src/adapters/persistence/sql/sql-agent-step-attempt-repository.ts',
  '../src/adapters/persistence/sql/sql-agent-step-result-repository.ts',
  '../src/adapters/persistence/sql/sql-oracle-execution-repository.ts',
]

const adapterEntry = ADAPTER_ENTRY_POINTS.map((entryPoint) => `export * from ${JSON.stringify(entryPoint)}`).join('\n')
const compiled = await build({
  stdin: {
    contents: adapterEntry,
    resolveDir: import.meta.dirname,
    loader: 'ts',
    sourcefile: 'conformance-sql-adapters.ts',
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
// Fixtures
// ---------------------------------------------------------------------------

function baseAttempt(overrides = {}) {
  return {
    attemptId: 'attempt-1',
    workflowId: 'wf-cap',
    workflowRevisionAtStart: 1,
    stepId: 'step-1',
    attemptNumber: 1,
    namespaceId: NAMESPACE_ID,
    runtimeId: 'runtime-1',
    caseId: null,
    agentName: 'Agent',
    briefHash: BRIEF_HASH,
    status: 'starting',
    startedAt: '2026-01-01T00:00:00.000Z',
    finishedAt: null,
    evidenceId: null,
    failureCode: null,
    ...overrides,
  }
}

function runningAttempt(overrides = {}) {
  return baseAttempt({ caseId: 'case-1', status: 'running', ...overrides })
}

function succeededAttempt(overrides = {}) {
  return baseAttempt({
    caseId: 'case-1',
    status: 'succeeded',
    finishedAt: '2026-01-01T00:00:01.000Z',
    evidenceId: 'evidence-1',
    ...overrides,
  })
}

function capabilityIdentity(overrides = {}) {
  return {
    attemptId: 'attempt-cap',
    workflowId: 'wf-cap',
    stepId: 'step-cap',
    namespaceId: NAMESPACE_ID,
    caseId: 'case-cap',
    agentName: 'Agent',
    briefHash: BRIEF_HASH,
    ...overrides,
  }
}

function capabilityObserved(attemptId, overrides = {}) {
  return { attemptId, caseId: 'case-cap', agentName: 'Agent', ...overrides }
}

function validBusiness(overrides = {}) {
  return { status: 'PASS', summary: 'ok', claims: { modifiedFiles: ['libs/a.ts'] }, ...overrides }
}

function mutableClock(initial) {
  let value = initial
  return { now: () => value, set: (next) => void (value = next) }
}

// ---------------------------------------------------------------------------
// Adapter wiring
// ---------------------------------------------------------------------------

function createFilesystemAdapters(root, clock) {
  const attempt = createFilesystemAgentStepAttemptRepository(new AgentStepAttemptStore(root))
  const result = createFilesystemAgentStepResultRepository(
    new AgentStepResultStore(root, { clock: () => clock.now(), ttlMs: TTL_MS })
  )
  return { attempt, result, setNow: (value) => clock.set(value) }
}

function createSqlAdapters(client, clock) {
  const attempt = new sqlModule.SqlAgentStepAttemptRepository(client)
  const result = new sqlModule.SqlAgentStepResultRepository(client, { clock: () => clock.now(), ttlMs: TTL_MS })
  return { attempt, result, client, setNow: (value) => clock.set(value) }
}

// ---------------------------------------------------------------------------
// Shared behavioural suite (filesystem reference and SQL adapter)
// ---------------------------------------------------------------------------

async function runSharedSuite(label, adapters) {
  const { attempt, result, setNow } = adapters

  await test(`[${label}] attempt journal records starting -> running -> succeeded in append order`, async () => {
    const storageId = 'lifecycle'
    await attempt.append(NAMESPACE_ID, storageId, baseAttempt())
    await attempt.append(NAMESPACE_ID, storageId, runningAttempt())
    await attempt.append(NAMESPACE_ID, storageId, succeededAttempt())
    assert.deepEqual(
      (await attempt.list(NAMESPACE_ID, storageId)).map((entry) => entry.status),
      ['starting', 'running', 'succeeded']
    )
  })

  await test(`[${label}] rejects an illegal status transition`, async () => {
    const storageId = 'transition'
    await attempt.append(NAMESPACE_ID, storageId, baseAttempt())
    await attempt.append(NAMESPACE_ID, storageId, runningAttempt())
    assert.equal(
      await thrownCode(() => attempt.append(NAMESPACE_ID, storageId, runningAttempt())),
      'INVALID_AGENT_STEP_ATTEMPT_TRANSITION'
    )
  })

  await test(`[${label}] rejects an immutable-field change`, async () => {
    const storageId = 'identity'
    await attempt.append(NAMESPACE_ID, storageId, baseAttempt())
    assert.equal(
      await thrownCode(() => attempt.append(NAMESPACE_ID, storageId, runningAttempt({ agentName: 'Other' }))),
      'AGENT_STEP_ATTEMPT_IDENTITY_CONFLICT'
    )
  })

  await test(`[${label}] requires 'starting' as the first status`, async () => {
    assert.equal(
      await thrownCode(() => attempt.append(NAMESPACE_ID, 'must-start', runningAttempt())),
      'AGENT_STEP_ATTEMPT_MUST_START'
    )
  })

  await test(`[${label}] rejects a namespace mismatch`, async () => {
    assert.equal(
      await thrownCode(() => attempt.append(NAMESPACE_ID, 'ns', baseAttempt({ namespaceId: 'other' }))),
      'AGENT_STEP_ATTEMPT_NAMESPACE_MISMATCH'
    )
  })

  await test(`[${label}] issues exactly one capability per attempt identity`, async () => {
    const storageId = 'capability'
    const identity = capabilityIdentity({ attemptId: 'cap-issue' })
    const issued = await result.issue(NAMESPACE_ID, storageId, identity)
    assert.equal(typeof issued.token, 'string')
    assert.ok(issued.token.length >= 32)
    assert.ok(!Number.isNaN(Date.parse(issued.expiresAt)))
    assert.equal(
      await thrownCode(() => result.issue(NAMESPACE_ID, storageId, identity)),
      'RESULT_CAPABILITY_ALREADY_ISSUED'
    )
    assert.equal(
      await thrownCode(() => result.issue(NAMESPACE_ID, storageId, { ...identity, agentName: 'Other' })),
      'RESULT_CAPABILITY_IDENTITY_CONFLICT'
    )
    assert.equal(
      await thrownCode(() => result.issue(NAMESPACE_ID, storageId, { ...identity, attemptId: 'bad id!' })),
      'INVALID_RESULT_CAPABILITY_IDENTITY'
    )
    assert.equal(
      await thrownCode(() =>
        result.issue(NAMESPACE_ID, storageId, { ...identity, attemptId: 'bad-brief', briefHash: 'sha256:not-hex' })
      ),
      'INVALID_RESULT_CAPABILITY_IDENTITY'
    )
  })

  await test(`[${label}] submission is idempotent and detects semantic collisions`, async () => {
    setNow(new Date('2026-02-01T00:00:00.000Z'))
    const storageId = 'submit'
    const issued = await result.issue(NAMESPACE_ID, storageId, capabilityIdentity({ attemptId: 'submit-1' }))
    const observed = capabilityObserved('submit-1')
    const first = await result.submit(issued.token, validBusiness(), observed)
    assert.equal(first.ok, true)
    assert.equal(first.idempotent, false)
    assert.equal(first.result.resultHash, first.result.resultHash)

    const replay = await result.submit(issued.token, validBusiness(), observed)
    assert.deepEqual(replay, { ok: true, idempotent: true, result: first.result })

    const collision = await result.submit(issued.token, validBusiness({ summary: 'divergent' }), observed)
    assert.deepEqual(collision, { ok: false, code: 'RESULT_SEMANTIC_COLLISION' })

    assert.deepEqual(await result.getByAttempt(NAMESPACE_ID, storageId, 'submit-1'), first.result)
    assert.deepEqual(
      (await result.list(NAMESPACE_ID, storageId)).map((event) => event.type),
      ['capability-issued', 'result-submitted']
    )
  })

  await test(`[${label}] submission rejects schema, capability and identity violations`, async () => {
    setNow(new Date('2026-03-01T00:00:00.000Z'))
    const storageId = 'submit-errors'
    const issued = await result.issue(NAMESPACE_ID, storageId, capabilityIdentity({ attemptId: 'submit-errors' }))
    const observed = capabilityObserved('submit-errors')
    assert.deepEqual(await result.submit(issued.token, { bad: true }, observed), {
      ok: false,
      code: 'RESULT_SCHEMA_INVALID',
    })
    assert.deepEqual(
      await result.submit('unknown-capability-token-000000000000000000', validBusiness(), observed),
      { ok: false, code: 'RESULT_CAPABILITY_INVALID' }
    )
    assert.deepEqual(await result.submit(issued.token, validBusiness(), { ...observed, caseId: 'wrong' }), {
      ok: false,
      code: 'RESULT_IDENTITY_MISMATCH',
    })
  })

  await test(`[${label}] rejects an expired capability`, async () => {
    const storageId = 'expiry'
    setNow(new Date('2026-04-01T00:00:00.000Z'))
    const issued = await result.issue(NAMESPACE_ID, storageId, capabilityIdentity({ attemptId: 'expiry-1' }))
    setNow(new Date('2026-04-01T00:20:00.000Z'))
    assert.deepEqual(await result.submit(issued.token, validBusiness(), capabilityObserved('expiry-1')), {
      ok: false,
      code: 'RESULT_CAPABILITY_EXPIRED',
    })
  })
}

// ---------------------------------------------------------------------------
// Explicit error-code parity
// ---------------------------------------------------------------------------

async function collectErrorCodes(adapters) {
  const { attempt, result, setNow } = adapters
  const codes = {}
  setNow(new Date('2026-01-01T00:00:00.000Z'))

  codes.AGENT_STEP_ATTEMPT_NAMESPACE_MISMATCH = await thrownCode(() =>
    attempt.append(NAMESPACE_ID, 'parity-ns', baseAttempt({ namespaceId: 'other' }))
  )
  codes.AGENT_STEP_ATTEMPT_MUST_START = await thrownCode(() =>
    attempt.append(NAMESPACE_ID, 'parity-must', runningAttempt())
  )

  await attempt.append(NAMESPACE_ID, 'parity-transition', baseAttempt())
  await attempt.append(NAMESPACE_ID, 'parity-transition', runningAttempt())
  codes.INVALID_AGENT_STEP_ATTEMPT_TRANSITION = await thrownCode(() =>
    attempt.append(NAMESPACE_ID, 'parity-transition', runningAttempt())
  )

  await attempt.append(NAMESPACE_ID, 'parity-identity', baseAttempt())
  codes.AGENT_STEP_ATTEMPT_IDENTITY_CONFLICT = await thrownCode(() =>
    attempt.append(NAMESPACE_ID, 'parity-identity', runningAttempt({ agentName: 'Other' }))
  )

  const identity = capabilityIdentity({ attemptId: 'parity-cap' })
  await result.issue(NAMESPACE_ID, 'parity-cap', identity)
  codes.RESULT_CAPABILITY_ALREADY_ISSUED = await thrownCode(() =>
    result.issue(NAMESPACE_ID, 'parity-cap', identity)
  )
  codes.RESULT_CAPABILITY_IDENTITY_CONFLICT = await thrownCode(() =>
    result.issue(NAMESPACE_ID, 'parity-cap', { ...identity, caseId: 'other-case' })
  )
  codes.INVALID_RESULT_CAPABILITY_IDENTITY = await thrownCode(() =>
    result.issue(NAMESPACE_ID, 'parity-cap', { ...identity, attemptId: 'bad id!' })
  )

  const issued = await result.issue(NAMESPACE_ID, 'parity-submit', capabilityIdentity({ attemptId: 'parity-submit' }))
  const observed = capabilityObserved('parity-submit')
  codes.RESULT_SCHEMA_INVALID = (
    await result.submit(issued.token, { bad: true }, observed)
  ).code
  codes.RESULT_CAPABILITY_INVALID = (
    await result.submit('unknown-capability-token-000000000000000000', validBusiness(), observed)
  ).code
  codes.RESULT_IDENTITY_MISMATCH = (await result.submit(issued.token, validBusiness(), { ...observed, caseId: 'x' }))
    .code
  await result.submit(issued.token, validBusiness(), observed)
  codes.RESULT_SEMANTIC_COLLISION = (await result.submit(issued.token, validBusiness({ summary: 'other' }), observed))
    .code

  const expiring = await result.issue(NAMESPACE_ID, 'parity-expiry', capabilityIdentity({ attemptId: 'parity-expiry' }))
  setNow(new Date('2026-01-01T01:00:00.000Z'))
  codes.RESULT_CAPABILITY_EXPIRED = (
    await result.submit(expiring.token, validBusiness(), capabilityObserved('parity-expiry'))
  ).code

  return codes
}

// ---------------------------------------------------------------------------
// SQL-only: Amendment 4 atomicity and Amendment 5 terminalization
// ---------------------------------------------------------------------------

function failingClient(base, predicate) {
  return {
    query(text, params) {
      if (predicate(text)) return Promise.reject(new Error('ENGINE_FAILURE'))
      return base.query(text, params)
    },
  }
}

async function runSqlSpecificSuite() {
  await test('[sql] submission terminalizes the attempt inside the same transaction', async () => {
    const client = createInMemorySqlClient()
    const adapters = createSqlAdapters(client, mutableClock(new Date('2026-05-01T00:00:00.000Z')))
    const storageId = 'amend-4'
    await adapters.attempt.append(NAMESPACE_ID, storageId, baseAttempt({ attemptId: 'amend-4' }))
    await adapters.attempt.append(NAMESPACE_ID, storageId, runningAttempt({ attemptId: 'amend-4' }))
    const issued = await adapters.result.issue(NAMESPACE_ID, storageId, capabilityIdentity({ attemptId: 'amend-4' }))
    const submitted = await adapters.result.submit(issued.token, validBusiness(), capabilityObserved('amend-4'))
    assert.equal(submitted.ok, true)
    const { rows } = await client.query(
      `SELECT status FROM agent_step_attempts
       WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3 AND step_id = $4 AND attempt_id = $5`,
      ['default', 'default', NAMESPACE_ID, storageId, 'amend-4']
    )
    assert.equal(rows[0].status, 'completed')
  })

  await test('[sql] submission rollback leaves results, outbox and attempt untouched', async () => {
    const base = createInMemorySqlClient()
    const client = failingClient(base, (text) => text.includes('INSERT INTO outbox_events'))
    const adapters = createSqlAdapters(client, mutableClock(new Date('2026-05-01T00:00:00.000Z')))
    const storageId = 'rollback'
    await adapters.attempt.append(NAMESPACE_ID, storageId, baseAttempt({ attemptId: 'rollback' }))
    await adapters.attempt.append(NAMESPACE_ID, storageId, runningAttempt({ attemptId: 'rollback' }))
    const issued = await adapters.result.issue(NAMESPACE_ID, storageId, capabilityIdentity({ attemptId: 'rollback' }))

    await assert.rejects(
      () => adapters.result.submit(issued.token, validBusiness(), capabilityObserved('rollback')),
      /ENGINE_FAILURE/
    )

    assert.equal((await base.query('SELECT * FROM agent_step_results')).rows.length, 0)
    assert.equal((await base.query('SELECT * FROM outbox_events')).rows.length, 0)
    assert.equal((await base.query('SELECT * FROM result_capabilities')).rows.length, 1)
    const { rows } = await base.query(
      `SELECT status FROM agent_step_attempts
       WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3 AND step_id = $4 AND attempt_id = $5`,
      ['default', 'default', NAMESPACE_ID, storageId, 'rollback']
    )
    assert.equal(rows[0].status, 'running')
  })

  const definition = {
    schemaVersion: '1',
    id: 'lint',
    version: '1.0.0',
    domain: 'quality',
    argv: ['pnpm', 'lint'],
    cwd: 'repo-root',
    timeoutMs: 1000,
    success: { rule: 'exit-code', requireWork: true },
    applicable: { workflowTypes: ['us-loop'], stepIds: ['build'] },
  }

  const insertExecution = (client, executionId) =>
    client.query(
      `INSERT INTO oracle_executions
         (organization_id, workstream_id, namespace_id, workflow_id, execution_id, oracle_id, status, revision,
          artifact_id, payload, created_at, updated_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10::jsonb, $11, $12)`,
      [
        'default',
        'default',
        NAMESPACE_ID,
        'wf-oracle',
        executionId,
        'lint',
        'running',
        1,
        'artifact-1',
        JSON.stringify(definition),
        '2026-01-01T00:00:00.000Z',
        '2026-01-01T00:00:00.000Z',
      ]
    )

  const insertArtifact = (client) =>
    client.query(
      `INSERT INTO artifacts
         (organization_id, workstream_id, namespace_id, workflow_id, artifact_id, availability_status,
          content_hash, size, content_type, storage_key, payload, created_at, updated_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11::jsonb, $12, $13)`,
      [
        'default',
        'default',
        NAMESPACE_ID,
        'wf-oracle',
        'artifact-1',
        'pending',
        BRIEF_HASH,
        42,
        'text/plain',
        'objects/artifact-1',
        JSON.stringify({}),
        '2026-01-01T00:00:00.000Z',
        '2026-01-01T00:00:00.000Z',
      ]
    )

  await test('[sql] oracle definitions list and get from persisted execution payloads', async () => {
    const client = createInMemorySqlClient()
    const repository = new sqlModule.SqlOracleExecutionRepository(client)
    await insertExecution(client, 'exec-1')
    const list = await repository.list()
    assert.equal(list.length, 1)
    assert.equal(list[0].id, 'lint')
    assert.equal((await repository.get('lint'))?.id, 'lint')
    assert.equal(await repository.get('missing'), null)
  })

  await test('[sql] terminalization publishes the linked artifact in the same transaction', async () => {
    const client = createInMemorySqlClient()
    const repository = new sqlModule.SqlOracleExecutionRepository(client)
    await insertExecution(client, 'exec-1')
    await insertArtifact(client)
    const outcome = await repository.terminalize({
      namespaceId: NAMESPACE_ID,
      workflowId: 'wf-oracle',
      executionId: 'exec-1',
      status: 'succeeded',
      artifactId: 'artifact-1',
    })
    assert.equal(outcome.revision, 2)
    assert.equal(outcome.artifactId, 'artifact-1')
    const { rows: artifacts } = await client.query(
      `SELECT availability_status FROM artifacts WHERE organization_id = $1 AND workstream_id = $2 AND artifact_id = $3`,
      ['default', 'default', 'artifact-1']
    )
    assert.equal(artifacts[0].availability_status, 'available')
  })

  await test('[sql] terminalization rolls the execution transition back when the artifact commit fails', async () => {
    const base = createInMemorySqlClient()
    await insertExecution(base, 'exec-1')
    await insertArtifact(base)
    const repository = new sqlModule.SqlOracleExecutionRepository(
      failingClient(base, (text) => text.includes('UPDATE artifacts'))
    )
    await assert.rejects(
      () =>
        repository.terminalize({
          namespaceId: NAMESPACE_ID,
          workflowId: 'wf-oracle',
          executionId: 'exec-1',
          status: 'succeeded',
          artifactId: 'artifact-1',
        }),
      /ENGINE_FAILURE/
    )
    const { rows: executions } = await base.query(
      `SELECT status, revision FROM oracle_executions WHERE organization_id = $1 AND workstream_id = $2 AND execution_id = $3`,
      ['default', 'default', 'exec-1']
    )
    assert.equal(executions[0].status, 'running')
    assert.equal(executions[0].revision, 1)
    const { rows: artifacts } = await base.query(
      `SELECT availability_status FROM artifacts WHERE organization_id = $1 AND workstream_id = $2 AND artifact_id = $3`,
      ['default', 'default', 'artifact-1']
    )
    assert.equal(artifacts[0].availability_status, 'pending')
  })
}

// ---------------------------------------------------------------------------
// Run
// ---------------------------------------------------------------------------

const root = await mkdtemp(join(tmpdir(), 'factory-conformance-'))
try {
  const filesystemClock = mutableClock(new Date('2026-01-01T00:00:00.000Z'))
  const filesystem = createFilesystemAdapters(root, filesystemClock)
  const sqlClock = mutableClock(new Date('2026-01-01T00:00:00.000Z'))
  const sql = createSqlAdapters(createInMemorySqlClient(), sqlClock)

  await runSharedSuite('filesystem', filesystem)
  await runSharedSuite('sql', sql)

  const filesystemCodes = await collectErrorCodes(filesystem)
  const sqlCodes = await collectErrorCodes(sql)
  await test('error codes are identical across filesystem and SQL adapters', () => {
    assert.deepEqual(sqlCodes, filesystemCodes)
    for (const [expected, actual] of Object.entries(filesystemCodes))
      assert.equal(actual, expected, `filesystem code mismatch for ${expected}`)
    for (const [expected, actual] of Object.entries(sqlCodes))
      assert.equal(actual, expected, `SQL code mismatch for ${expected}`)
  })

  await runSqlSpecificSuite()
} finally {
  await rm(root, { recursive: true, force: true })
}

console.log(`\nResult: ${passed} passed, ${failed} failed`)
process.exit(failed ? 1 : 0)
