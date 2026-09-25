/**
 * Transactional unit of work shared by the SQL persistence adapters.
 *
 * Adapters depend on the structural {@link SqlClient} port rather than on the
 * `pg` driver directly, so the same primitive drives a live PostgreSQL
 * connection and the offline in-memory client used by the contract tests. The
 * driver is never imported here: `BEGIN` / `COMMIT` / `ROLLBACK` are issued
 * through the port, exactly like the repository statements.
 */

import type { SqlClient } from './db.js'

/**
 * Work executed atomically inside {@link withTransaction}. The callback
 * receives the transaction-scoped client so it can issue its reads and writes
 * against the same unit of work.
 */
export type TransactionalWork<Result> = (tx: SqlClient) => Promise<Result>

/**
 * A {@link SqlClient} able to open a transaction. Both a dedicated `pg.Client`
 * and a `pg.Pool` (or the in-memory test client) satisfy this shape, which is
 * what lets the repositories stay database-agnostic.
 */
export type TransactionalSqlClient = SqlClient

/**
 * Runs `work` inside a database transaction on `client`.
 *
 * `BEGIN` is issued first, then the callback runs; on success the transaction
 * is committed with `COMMIT` and the callback result is returned. If the
 * callback throws (or its promise rejects), the transaction is rolled back with
 * `ROLLBACK` and the original error is re-thrown so callers keep their failure
 * semantics.
 */
export async function withTransaction<Result>(client: SqlClient, work: TransactionalWork<Result>): Promise<Result> {
  await client.query('BEGIN')
  try {
    const result = await work(client)
    await client.query('COMMIT')
    return result
  } catch (error) {
    await client.query('ROLLBACK')
    throw error
  }
}
