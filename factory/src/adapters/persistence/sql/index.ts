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
