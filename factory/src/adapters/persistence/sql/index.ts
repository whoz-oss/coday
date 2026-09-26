/**
 * SQL persistence adapters barrel.
 *
 * Skeleton of the PostgreSQL implementations of the pilot repository ports
 * (`WorkflowDefinitionRepository`, `WorkflowInstanceRepository`). They are
 * driver-agnostic: they depend on the structural `SqlClient` port, so the same
 * code runs against a real `pg` pool or the in-memory client used by the shared
 * contract tests.
 */

export {
  DEFAULT_ORGANIZATION_ID,
  DEFAULT_WORKSTREAM_ID,
  createPgPoolClient,
  parseJsonColumn,
  resolveSqlDatabaseConfig,
  type SqlClient,
  type SqlDatabaseConfig,
  type SqlQueryResult,
} from './db.js'

export { withTransaction, type TransactionalSqlClient, type TransactionalWork } from './unit-of-work.js'

export {
  SqlWorkflowDefinitionRepository,
  createSqlWorkflowDefinitionRepository,
  type SqlWorkflowDefinitionRepositoryOptions,
} from './sql-workflow-definition-repository.js'

export {
  SqlWorkflowInstanceRepository,
  createSqlWorkflowInstanceRepository,
  type SqlWorkflowInstanceRepositoryOptions,
} from './sql-workflow-instance-repository.js'

export {
  SqlWorkflowEvidenceRepository,
  createSqlWorkflowEvidenceRepository,
  type SqlWorkflowEvidenceRepositoryOptions,
} from './sql-workflow-evidence-repository.js'

export {
  SqlWorkflowHumanInteractionRepository,
  createSqlWorkflowHumanInteractionRepository,
  type SqlWorkflowHumanInteractionRepositoryOptions,
} from './sql-workflow-human-interaction-repository.js'

export {
  SqlAgentStepAttemptRepository,
  createSqlAgentStepAttemptRepository,
  type SqlAgentStepAttemptRepositoryOptions,
} from './sql-agent-step-attempt-repository.js'

export {
  SqlAgentStepResultRepository,
  createSqlAgentStepResultRepository,
  type SqlAgentStepResultRepositoryOptions,
} from './sql-agent-step-result-repository.js'

export {
  SqlOracleExecutionRepository,
  createSqlOracleExecutionRepository,
  type SqlOracleExecutionRepositoryOptions,
} from './sql-oracle-execution-repository.js'

export {
  SqlWorkEnvironmentRepository,
  createSqlWorkEnvironmentRepository,
  type SqlWorkEnvironmentRepositoryOptions,
} from './sql-work-environment-repository.js'

export {
  SqlDeliveryRepository,
  createSqlDeliveryRepository,
  type SqlDeliveryRepositoryOptions,
} from './sql-delivery-repository.js'

export {
  SqlWorkUnitRepository,
  createSqlWorkUnitRepository,
  SqlWorkUnitRepositoryError,
  type SqlWorkUnitRepositoryOptions,
} from './sql-work-unit-repository.js'

export {
  SqlWorkerRepository,
  createSqlWorkerRepository,
  SqlWorkerRepositoryError,
  type SqlWorkerRepositoryOptions,
} from './sql-worker-repository.js'

export { SqlLeaseRepository, createSqlLeaseRepository, type SqlLeaseRepositoryOptions } from './sql-lease-repository.js'
