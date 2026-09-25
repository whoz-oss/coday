/**
 * SQL connection primitives shared by the persistence adapters.
 *
 * The Factory runtime artifact must stay autonomous: its only external imports
 * are `node:*` (see `DEPENDENCY_MATRIX.md`). The PostgreSQL driver is therefore
 * never imported statically. Repositories depend on the small structural
 * `SqlClient` port below (a `pg.Pool` / `pg.Client` satisfies it as-is), and
 * `createPgPoolClient` loads the driver lazily from the operator's environment
 * when a live connection is actually needed.
 */

/** Default tenant used when no organization scoping is provided. */
export const DEFAULT_ORGANIZATION_ID = 'default'

/** Default workstream used when no workstream scoping is provided. */
export const DEFAULT_WORKSTREAM_ID = 'default'

/** Minimal result shape returned by {@link SqlClient.query}. */
export interface SqlQueryResult<Row = Record<string, unknown>> {
  rows: Row[]
  rowCount: number | null
}

/**
 * Structural database port. `pg.Pool` and `pg.Client` both satisfy it, which is
 * what lets the same repositories run against a real PostgreSQL and against the
 * in-memory client used by the contract tests.
 */
export interface SqlClient {
  query<Row = Record<string, unknown>>(text: string, params?: readonly unknown[]): Promise<SqlQueryResult<Row>>
}

/** Resolved connection settings for a PostgreSQL client. */
export interface SqlDatabaseConfig {
  host: string
  port: number
  database: string
  user: string
  password: string
  maxConnections: number
  ssl: boolean
}

/** Reads the standard `PG*` environment variables with local-dev defaults. */
export function resolveSqlDatabaseConfig(env: NodeJS.ProcessEnv = process.env): SqlDatabaseConfig {
  const port = Number.parseInt(env.PGPORT ?? '', 10)
  const maxConnections = Number.parseInt(env.PGPOOL_MAX ?? '', 10)
  return {
    host: env.PGHOST ?? 'localhost',
    port: Number.isFinite(port) ? port : 5432,
    database: env.PGDATABASE ?? 'coday_factory',
    user: env.PGUSER ?? 'factory',
    password: env.PGPASSWORD ?? 'factory_dev_pass',
    maxConnections: Number.isFinite(maxConnections) && maxConnections > 0 ? maxConnections : 10,
    ssl: env.PGSSL === 'true',
  }
}

interface PgPoolLike {
  query(text: string, params?: readonly unknown[]): Promise<SqlQueryResult>
  end?(): Promise<void>
}

interface PgModuleLike {
  Pool: new (config: Record<string, unknown>) => PgPoolLike
}

/**
 * Loads a driver by name through a non-literal dynamic import so bundlers keep
 * the specifier unresolved; the runtime artifact never hard-depends on
 * `node_modules`.
 */
async function loadDriver(specifier: string): Promise<PgModuleLike> {
  const imported = (await import(specifier)) as PgModuleLike & { default?: PgModuleLike }
  const module = imported.default ?? imported
  if (typeof module?.Pool !== 'function') throw new Error(`SQL_DRIVER_INVALID: ${specifier}`)
  return module
}

/**
 * Creates a `pg`-backed {@link SqlClient}. Callers must provide `pg` in their
 * runtime environment (it is deliberately not bundled); connection settings
 * default to {@link resolveSqlDatabaseConfig}.
 */
export async function createPgPoolClient(
  config: SqlDatabaseConfig = resolveSqlDatabaseConfig(),
  driver = 'pg'
): Promise<SqlClient> {
  const pg = await loadDriver(driver)
  const pool = new pg.Pool({
    host: config.host,
    port: config.port,
    database: config.database,
    user: config.user,
    password: config.password,
    max: config.maxConnections,
    ssl: config.ssl ? { rejectUnauthorized: false } : false,
  })
  return {
    query: <Row = Record<string, unknown>>(text: string, params?: readonly unknown[]) =>
      pool.query(text, params) as Promise<SqlQueryResult<Row>>,
  }
}

/**
 * JSONB columns come back as parsed values from `pg` and as strings from the
 * in-memory client; both are normalized here.
 */
export function parseJsonColumn<Value>(value: unknown): Value {
  if (typeof value === 'string') return JSON.parse(value) as Value
  return value as Value
}
