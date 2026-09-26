// B4-T2 persistence authority switching, shadow reads and rollback tests.
//
// Offline, Docker-free: a temporary filesystem root plus the in-memory SQL
// client stand in for PostgreSQL. Covers:
//   1. default state (`FACTORY_PERSISTENCE` defaults to `fs`, zero regression);
//   2. shadow-read discrepancy detection without altering the served result;
//   3. shadow-read error containment (a failing PostgreSQL never fails a read);
//   4. SQL writer-unique mode (reads/writes served by PostgreSQL, 0 filesystem writes);
//   5. rollback to `fs` restores filesystem authority.
//
// Usage: node factory/tests/test-persistence-shadow-and-switch.mjs
import assert from 'node:assert/strict'
import { mkdtemp, readdir, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { createStores, loadConfig } from '../dashboard/composition-root.mjs'
import { runOneShotImport } from '../runtime/factory-operational.mjs'
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

async function withTempRoot(fn) {
  const root = await mkdtemp(join(tmpdir(), 'factory-persistence-switch-'))
  try {
    return await fn(root)
  } finally {
    await rm(root, { recursive: true, force: true })
  }
}

const NAMESPACE_ID = '11111111-1111-4111-8111-111111111111'
const WORKFLOW_ID = 'wf-persistence-1'
const DEFINITION_INPUT = {
  workflowType: 'demo',
  version: '1.0.0',
  definitionHash: 'a'.repeat(64),
  steps: [{ id: 'build', name: 'Build', responsibility: { kind: 'agent', name: 'worker' }, dependsOn: [] }],
}
const CONTROLLER_EXECUTION = { kind: 'factory', runtimeId: 'runtime-1', agentId: 'worker', caseId: 'case-1' }

function captureLogger() {
  const warnings = []
  return {
    warnings,
    logger: { ...console, warn: (...args) => warnings.push(args) },
  }
}

async function listFiles(root) {
  return readdir(root).catch((error) => {
    if (error?.code === 'ENOENT') return []
    throw error
  })
}

async function seedFilesystemProjection(root) {
  const config = loadConfig({ FACTORY_DATA_ROOT: root, FACTORY_BIND_HOST: '127.0.0.1' })
  const stores = createStores(config)
  await stores.workflowProjectionStore.initialize()
  const started = await stores.workflowProjectionStore.start(
    NAMESPACE_ID,
    { workflowId: WORKFLOW_ID, workflowType: 'demo', title: 'Persistence switch run' },
    DEFINITION_INPUT,
    CONTROLLER_EXECUTION
  )
  assert.equal(started.ok, true)
  assert.equal(started.created, true)
}

try {
  await scenario('FACTORY_PERSISTENCE defaults to fs with shadow disabled', async () => {
    const config = loadConfig({ FACTORY_BIND_HOST: '127.0.0.1' })
    assert.equal(config.persistenceMode, 'fs')
    assert.equal(config.shadowReadEnabled, false)
    assert.deepEqual(config.persistence, {
      mode: 'fs',
      shadowRead: false,
      organizationId: 'default',
      workstreamId: 'default',
    })
    // An unknown value must fall back to the safe filesystem mode.
    const bogus = loadConfig({ FACTORY_PERSISTENCE: 'oracle', FACTORY_BIND_HOST: '127.0.0.1' })
    assert.equal(bogus.persistenceMode, 'fs')
    // Shadow only applies while the filesystem remains the authority.
    const sqlShadow = loadConfig({
      FACTORY_PERSISTENCE: 'sql',
      FACTORY_PERSISTENCE_SHADOW: 'true',
      FACTORY_BIND_HOST: '127.0.0.1',
    })
    assert.equal(sqlShadow.persistenceMode, 'sql')
    assert.equal(sqlShadow.shadowReadEnabled, false)
  })

  await scenario('default fs mode serves and persists through the filesystem', async () => {
    await withTempRoot(async (root) => {
      await seedFilesystemProjection(root)
      const config = loadConfig({ FACTORY_DATA_ROOT: root, FACTORY_BIND_HOST: '127.0.0.1' })
      const stores = createStores(config)
      assert.equal(stores.workflowProjectionStore.dataRoot, root)
      const snapshot = await stores.workflowProjectionStore.read(NAMESPACE_ID, WORKFLOW_ID)
      assert.equal(snapshot.projection.workflowId, WORKFLOW_ID)
      assert.ok((await listFiles(root)).includes('workflows'))
    })
  })

  await scenario('shadow mode logs a PostgreSQL discrepancy and still serves filesystem data', async () => {
    await withTempRoot(async (root) => {
      await seedFilesystemProjection(root)
      const client = createInMemorySqlClient()
      const imported = await runOneShotImport({ dataRoot: root, sqlClient: client })
      assert.equal(imported.contexts['workflow-instance'].ok, true)

      const config = loadConfig({
        FACTORY_DATA_ROOT: root,
        FACTORY_PERSISTENCE: 'fs',
        FACTORY_PERSISTENCE_SHADOW: 'true',
        FACTORY_BIND_HOST: '127.0.0.1',
      })
      const { warnings, logger } = captureLogger()
      const stores = createStores(config, { sqlClient: client, logger })

      // Faithful copies: no discrepancy must be reported.
      const faithful = await stores.workflowProjectionStore.read(NAMESPACE_ID, WORKFLOW_ID)
      assert.equal(faithful.projection.workflowId, WORKFLOW_ID)
      assert.equal(warnings.length, 0, `unexpected shadow diagnostics: ${JSON.stringify(warnings)}`)

      // Inject a divergent PostgreSQL payload, then read again.
      const { rows } = await client.query(
        'SELECT instance_json FROM workflow_instances WHERE namespace_id = $1 AND workflow_id = $2',
        [NAMESPACE_ID, WORKFLOW_ID]
      )
      const instance =
        typeof rows[0].instance_json === 'string' ? JSON.parse(rows[0].instance_json) : rows[0].instance_json
      instance.revision = 99
      await client.query(
        'UPDATE workflow_instances SET instance_json = $1 WHERE namespace_id = $2 AND workflow_id = $3',
        [JSON.stringify(instance), NAMESPACE_ID, WORKFLOW_ID]
      )

      warnings.length = 0
      const served = await stores.workflowProjectionStore.read(NAMESPACE_ID, WORKFLOW_ID)
      // The served value is always the filesystem snapshot.
      assert.equal(served.instance.revision, 1)
      assert.equal(served.projection.workflowId, WORKFLOW_ID)
      assert.equal(warnings.length, 1, 'a discrepancy must be logged once')
      assert.equal(warnings[0][0], '[SHADOW_READ_DISCREPANCY]')
      assert.equal(warnings[0][1].store, 'workflowProjectionStore')
      assert.equal(warnings[0][1].method, 'read')
      assert.notEqual(warnings[0][1].filesystemHash, warnings[0][1].sqlHash)
    })
  })

  await scenario('shadow mode contains PostgreSQL errors and returns the filesystem result', async () => {
    await withTempRoot(async (root) => {
      await seedFilesystemProjection(root)
      const config = loadConfig({
        FACTORY_DATA_ROOT: root,
        FACTORY_PERSISTENCE_SHADOW: 'true',
        FACTORY_BIND_HOST: '127.0.0.1',
      })
      const { warnings, logger } = captureLogger()
      const failingClient = {
        query: async () => {
          throw new Error('PG_UNAVAILABLE')
        },
      }
      const stores = createStores(config, { sqlClient: failingClient, logger })
      const served = await stores.workflowProjectionStore.read(NAMESPACE_ID, WORKFLOW_ID)
      assert.equal(served.projection.workflowId, WORKFLOW_ID)
      assert.equal(warnings.length, 1)
      assert.equal(warnings[0][0], '[SHADOW_READ_ERROR]')
      assert.equal(warnings[0][1].message, 'PG_UNAVAILABLE')
    })
  })

  await scenario('FACTORY_PERSISTENCE=sql serves reads/writes from PostgreSQL and 0 filesystem writes', async () => {
    await withTempRoot(async (root) => {
      const client = createInMemorySqlClient()
      const config = loadConfig({
        FACTORY_PERSISTENCE: 'sql',
        FACTORY_DATA_ROOT: root,
        FACTORY_ORGANIZATION_ID: 'default',
        FACTORY_WORKSTREAM_ID: 'default',
        FACTORY_BIND_HOST: '127.0.0.1',
      })
      assert.equal(config.persistenceMode, 'sql')
      assert.equal(config.shadowReadEnabled, false)

      const stores = createStores(config, { sqlClient: client })
      assert.equal(stores.__persistenceAuthority, 'sql')

      const now = new Date().toISOString()
      const reserved = await stores.workUnitEnvironmentStore.reserve({
        schemaVersion: '1',
        environmentId: 'env-sql-1',
        workUnitId: 'unit-sql-1',
        namespaceId: NAMESPACE_ID,
        repoRoot: '/repo',
        integrationBranch: 'main',
        branch: 'feature/sql',
        worktreePath: '/worktrees/sql',
        baseCommit: 'c'.repeat(40),
        createdAt: now,
        createdBy: 'factory',
        lifecycleState: 'provisioning',
      })
      assert.equal(reserved.ok, true)

      // The write landed in PostgreSQL...
      const { rows } = await client.query('SELECT environment_id FROM work_environments')
      assert.equal(rows.length, 1)
      assert.equal(rows[0].environment_id, 'env-sql-1')
      // ...and is read back from PostgreSQL.
      const snapshot = await stores.workUnitEnvironmentStore.read(NAMESPACE_ID, 'env-sql-1')
      assert.equal(snapshot.environment.environmentId, 'env-sql-1')

      // No filesystem directory was created or written.
      assert.deepEqual(await listFiles(root), [])

      // Unmigrated operations fail closed instead of silently writing to disk.
      await assert.rejects(
        async () => stores.deliveryEvidenceStore.record(NAMESPACE_ID, 'storage', {}, {}),
        (error) => error instanceof TypeError
      )
      assert.deepEqual(await listFiles(root), [])
    })
  })

  await scenario('rollback to FACTORY_PERSISTENCE=fs restores filesystem authority', async () => {
    await withTempRoot(async (root) => {
      const sqlConfig = loadConfig({
        FACTORY_PERSISTENCE: 'sql',
        FACTORY_DATA_ROOT: root,
        FACTORY_BIND_HOST: '127.0.0.1',
      })
      const sqlStores = createStores(sqlConfig, { sqlClient: createInMemorySqlClient() })
      assert.equal(sqlStores.__persistenceAuthority, 'sql')

      // Roll back: unset / switch the environment back to fs.
      const fsConfig = loadConfig({ FACTORY_DATA_ROOT: root, FACTORY_BIND_HOST: '127.0.0.1' })
      const fsStores = createStores(fsConfig)
      assert.equal(fsStores.__persistenceAuthority, undefined)
      assert.equal(fsStores.workflowProjectionStore.dataRoot, root)

      await fsStores.workflowProjectionStore.initialize()
      const started = await fsStores.workflowProjectionStore.start(
        NAMESPACE_ID,
        { workflowId: WORKFLOW_ID, workflowType: 'demo', title: 'Rollback run' },
        DEFINITION_INPUT,
        CONTROLLER_EXECUTION
      )
      assert.equal(started.ok, true)
      const snapshot = await fsStores.workflowProjectionStore.read(NAMESPACE_ID, WORKFLOW_ID)
      assert.equal(snapshot.projection.workflowId, WORKFLOW_ID)
      assert.ok((await listFiles(root)).includes('workflows'))
    })
  })
} finally {
  // nothing global to clean up
}

console.log(`\nResult: ${passed} passed, ${failed} failed`)
process.exit(failed ? 1 : 0)
