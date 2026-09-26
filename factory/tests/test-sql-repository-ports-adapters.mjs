// Shared repository contract executions for the pilot aggregates.
//
// The exact same assertion suite runs against the filesystem adapters and the
// SQL adapters, with an in-memory SQL client so the run needs neither a
// PostgreSQL server nor Docker. Use `factory/infra/docker-compose.yml` when a
// live database is wanted; these contract tests are the offline gate.
//
// Usage: node factory/tests/test-sql-repository-ports-adapters.mjs
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
  SqlAgentStepAttemptRepository,
  SqlAgentStepResultRepository,
  SqlDeliveryRepository,
  SqlLeaseRepository,
  SqlOracleExecutionRepository,
  SqlWorkEnvironmentRepository,
  SqlWorkUnitRepository,
  SqlWorkerRepository,
  SqlWorkflowDefinitionRepository,
  SqlWorkflowEvidenceRepository,
  SqlWorkflowHumanInteractionRepository,
  SqlWorkflowInstanceRepository,
  createSqlAgentStepAttemptRepository,
  createSqlAgentStepResultRepository,
  createSqlDeliveryRepository,
  createSqlLeaseRepository,
  createSqlOracleExecutionRepository,
  createSqlWorkEnvironmentRepository,
  createSqlWorkUnitRepository,
  createSqlWorkerRepository,
  createSqlWorkflowDefinitionRepository,
  createSqlWorkflowEvidenceRepository,
  createSqlWorkflowHumanInteractionRepository,
  createSqlWorkflowInstanceRepository,
} from '../runtime/factory-operational.mjs'
import { createWorkflowDefinitionRepository } from '../lib/workflow-definition-registry.mjs'
import { createWorkflowInstanceRepository } from '../lib/workflow-projection-store.mjs'
import { createInMemorySqlClient } from './support/in-memory-sql-client.mjs'
import {
  CONTRACT_CONTROLLER_EXECUTION,
  CONTRACT_DEFINITION_INPUT,
  CONTRACT_NAMESPACE_ID,
  CONTRACT_WORKFLOW_TYPE,
  CONTRACT_WORKFLOW_VERSION,
  definitionRepositoryScenarios,
  executeContractSuite,
  instanceRepositoryScenarios,
} from './test-repository-contract.mjs'

/** Public method names declared on a repository prototype (`constructor` excluded). */
function prototypeMethods(repositoryClass) {
  return Object.getOwnPropertyNames(repositoryClass.prototype)
    .filter((name) => name !== 'constructor')
    .sort()
}

/**
 * The twelve persistence ports, each paired with its SQL adapter and the
 * `createSql*Repository` factory exported by the operational bundle. The nine
 * pilot aggregates also have a filesystem adapter; the scheduling/liveness/lease
 * ports (`work-unit`, `worker`, `lease`) are SQL-only in this tier.
 */
const REPOSITORY_PORTS = [
  {
    port: 'workflow-definition',
    filesystem: FilesystemWorkflowDefinitionRepository,
    sql: SqlWorkflowDefinitionRepository,
    createSql: createSqlWorkflowDefinitionRepository,
  },
  {
    port: 'workflow-instance',
    filesystem: FilesystemWorkflowInstanceRepository,
    sql: SqlWorkflowInstanceRepository,
    createSql: createSqlWorkflowInstanceRepository,
  },
  {
    port: 'workflow-evidence',
    filesystem: FilesystemWorkflowEvidenceRepository,
    sql: SqlWorkflowEvidenceRepository,
    createSql: createSqlWorkflowEvidenceRepository,
  },
  {
    port: 'workflow-human-interaction',
    filesystem: FilesystemWorkflowHumanInteractionRepository,
    sql: SqlWorkflowHumanInteractionRepository,
    createSql: createSqlWorkflowHumanInteractionRepository,
  },
  {
    port: 'agent-step-attempt',
    filesystem: FilesystemAgentStepAttemptRepository,
    sql: SqlAgentStepAttemptRepository,
    createSql: createSqlAgentStepAttemptRepository,
  },
  {
    port: 'agent-step-result',
    filesystem: FilesystemAgentStepResultRepository,
    sql: SqlAgentStepResultRepository,
    createSql: createSqlAgentStepResultRepository,
  },
  {
    port: 'oracle-execution',
    filesystem: FilesystemOracleExecutionRepository,
    sql: SqlOracleExecutionRepository,
    createSql: createSqlOracleExecutionRepository,
  },
  {
    port: 'work-environment',
    filesystem: FilesystemWorkEnvironmentRepository,
    sql: SqlWorkEnvironmentRepository,
    createSql: createSqlWorkEnvironmentRepository,
  },
  {
    port: 'delivery',
    filesystem: FilesystemDeliveryRepository,
    sql: SqlDeliveryRepository,
    createSql: createSqlDeliveryRepository,
  },
  {
    port: 'work-unit',
    sql: SqlWorkUnitRepository,
    createSql: createSqlWorkUnitRepository,
  },
  {
    port: 'worker',
    sql: SqlWorkerRepository,
    createSql: createSqlWorkerRepository,
  },
  {
    port: 'lease',
    sql: SqlLeaseRepository,
    createSql: createSqlLeaseRepository,
  },
]

const definitionFile = {
  schemaVersion: '1',
  workflowType: CONTRACT_WORKFLOW_TYPE,
  version: CONTRACT_WORKFLOW_VERSION,
  title: 'Contract definition',
  steps: CONTRACT_DEFINITION_INPUT.steps,
}

const root = await mkdtemp(join(tmpdir(), 'factory-sql-contract-'))
let passed = 0
let failed = 0
try {
  // ------------------------------------------------------------------------
  // Filesystem adapters (reference implementation)
  // ------------------------------------------------------------------------
  const definitionsRoot = join(root, 'definitions')
  await mkdir(join(definitionsRoot, CONTRACT_WORKFLOW_TYPE), { recursive: true })
  await writeFile(
    join(definitionsRoot, CONTRACT_WORKFLOW_TYPE, `${CONTRACT_WORKFLOW_VERSION}.json`),
    `${JSON.stringify(definitionFile)}\n`
  )
  const filesystemDefinitionRepository = createWorkflowDefinitionRepository(definitionsRoot)
  const filesystemInstanceRepository = createWorkflowInstanceRepository(join(root, 'instances'))
  assert.ok(filesystemDefinitionRepository instanceof FilesystemWorkflowDefinitionRepository)
  assert.ok(filesystemInstanceRepository instanceof FilesystemWorkflowInstanceRepository)

  // ------------------------------------------------------------------------
  // SQL adapters (skeleton under test, backed by the in-memory client)
  // ------------------------------------------------------------------------
  const sqlDefinitionClient = createInMemorySqlClient()
  await sqlDefinitionClient.query(
    `INSERT INTO workflow_definitions
       (organization_id, workstream_id, workflow_type, version, definition_hash, definition_json)
     VALUES ($1, $2, $3, $4, $5, $6::jsonb)`,
    ['default', null, CONTRACT_WORKFLOW_TYPE, CONTRACT_WORKFLOW_VERSION, 'a'.repeat(64), JSON.stringify(definitionFile)]
  )
  const sqlDefinitionRepository = new SqlWorkflowDefinitionRepository(sqlDefinitionClient)
  const sqlInstanceRepository = new SqlWorkflowInstanceRepository(createInMemorySqlClient())
  assert.ok(sqlDefinitionRepository instanceof SqlWorkflowDefinitionRepository)
  assert.ok(sqlInstanceRepository instanceof SqlWorkflowInstanceRepository)

  // ------------------------------------------------------------------------
  // Same contract, every implementation
  // ------------------------------------------------------------------------
  const suites = [
    {
      suiteName: 'definition/filesystem',
      scenarios: definitionRepositoryScenarios({ repository: filesystemDefinitionRepository }),
    },
    {
      suiteName: 'definition/sql',
      scenarios: definitionRepositoryScenarios({ repository: sqlDefinitionRepository }),
    },
    {
      suiteName: 'instance/filesystem',
      scenarios: instanceRepositoryScenarios({
        repository: filesystemInstanceRepository,
        namespaceId: CONTRACT_NAMESPACE_ID,
      }),
    },
    {
      suiteName: 'instance/sql',
      scenarios: instanceRepositoryScenarios({
        repository: sqlInstanceRepository,
        namespaceId: CONTRACT_NAMESPACE_ID,
      }),
    },
  ]

  for (const suite of suites) {
    const result = await executeContractSuite(suite)
    passed += result.passed
    failed += result.failed
  }

  // ------------------------------------------------------------------------
  // SQL-only: the optimistic-locking UPDATE guard. The shared contract cannot
  // assert this branch because the filesystem store serializes writers instead
  // of locking a revision column.
  // ------------------------------------------------------------------------
  const lockRepository = new SqlWorkflowInstanceRepository(createInMemorySqlClient())
  const lockWorkflowId = 'wf-lock-contract'
  await lockRepository.create(
    CONTRACT_NAMESPACE_ID,
    { workflowId: lockWorkflowId, workflowType: CONTRACT_WORKFLOW_TYPE, title: 'Lock contract' },
    CONTRACT_DEFINITION_INPUT,
    { ...CONTRACT_CONTROLLER_EXECUTION, caseId: 'case-lock' }
  )
  const lockSuite = await executeContractSuite({
    suiteName: 'instance/sql',
    scenarios: [
      [
        'optimistic lock rejects a stale writer even when the policy allows',
        async () => {
          await assert.rejects(
            () =>
              lockRepository.transition(CONTRACT_NAMESPACE_ID, lockWorkflowId, {
                request: {
                  requestId: 'req-lock',
                  workflowId: lockWorkflowId,
                  stepId: 'build',
                  expectedRevision: 99,
                  requestedStatus: 'running',
                  evidenceIds: [],
                },
                definition: CONTRACT_DEFINITION_INPUT,
                evidence: [],
                execution: {
                  kind: 'factory',
                  runtimeId: 'runtime-contract',
                  agentId: 'worker',
                  caseId: 'case-lock',
                },
                policy: () => ({ allowed: true }),
              }),
            (error) => error.code === 'REVISION_CONFLICT'
          )
        },
      ],
    ],
  })
  passed += lockSuite.passed
  failed += lockSuite.failed

  // ------------------------------------------------------------------------
  // Port wiring parity: every persistence port is reachable from the
  // operational bundle through a filesystem adapter, a SQL adapter and a
  // `createSql*Repository` factory. The SQL class must be instantiable both
  // through its constructor and its factory, and its public surface must cover
  // the filesystem adapter's one (SQL-only methods are allowed).
  // ------------------------------------------------------------------------
  const wiringSuite = await executeContractSuite({
    suiteName: 'ports/wiring',
    scenarios: REPOSITORY_PORTS.flatMap((descriptor) => [
      [
        `${descriptor.port}: filesystem and SQL adapters are exported constructors`,
        () => {
          if (descriptor.filesystem) {
            assert.equal(
              typeof descriptor.filesystem,
              'function',
              `${descriptor.port}: filesystem adapter is not an exported constructor`
            )
          }
          assert.equal(
            typeof descriptor.sql,
            'function',
            `${descriptor.port}: SQL adapter is not an exported constructor`
          )
          assert.equal(
            typeof descriptor.createSql,
            'function',
            `${descriptor.port}: createSql*Repository factory is not exported`
          )
          assert.ok(
            prototypeMethods(descriptor.sql).length > 0,
            `${descriptor.port}: SQL adapter exposes no public method`
          )
        },
      ],
      [
        `${descriptor.port}: SQL adapter instantiates via constructor and factory`,
        () => {
          const viaConstructor = new descriptor.sql(createInMemorySqlClient())
          assert.ok(
            viaConstructor instanceof descriptor.sql,
            `${descriptor.port}: constructor did not return a ${descriptor.sql.name}`
          )
          const viaFactory = descriptor.createSql(createInMemorySqlClient())
          assert.ok(
            viaFactory instanceof descriptor.sql,
            `${descriptor.port}: factory did not return a ${descriptor.sql.name}`
          )
        },
      ],
      [
        `${descriptor.port}: SQL surface covers the filesystem surface`,
        () => {
          if (!descriptor.filesystem) return
          const sqlMethods = prototypeMethods(descriptor.sql)
          for (const method of prototypeMethods(descriptor.filesystem)) {
            assert.ok(sqlMethods.includes(method), `${descriptor.port}: SQL adapter is missing the "${method}" method`)
          }
        },
      ],
    ]),
  })
  passed += wiringSuite.passed
  failed += wiringSuite.failed
} finally {
  await rm(root, { recursive: true, force: true })
}

console.log(`\nResult: ${passed} passed, ${failed} failed`)
process.exit(failed ? 1 : 0)
