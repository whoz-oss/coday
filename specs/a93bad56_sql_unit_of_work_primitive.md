# Implementation Plan: Task B3-T0 Transactional Unit of Work Primitive for SQL Persistence Adapters

## Overview
This task establishes the foundational transactional primitive (`withTransaction`) for SQL persistence adapters in `factory/src/adapters/persistence/sql/`, extends the in-memory SQL client (`factory/tests/support/in-memory-sql-client.mjs`) to support transaction state snapshotting and restoration (`BEGIN`, `COMMIT`, `ROLLBACK`), and adds a dedicated standalone test suite (`factory/tests/test-sql-unit-of-work.mjs`) to validate transaction atomicity and rollback behavior.

---

## Allowed Scope & Strict Constraints
### Strict Scope (Files allowed to be created or modified)
1. `factory/src/adapters/persistence/sql/db.ts` (or `factory/src/adapters/persistence/sql/unit-of-work.ts` re-exported by `factory/src/adapters/persistence/sql/index.ts`)
2. `factory/src/adapters/persistence/sql/index.ts`
3. `factory/tests/support/in-memory-sql-client.mjs`
4. `factory/tests/test-sql-unit-of-work.mjs`

### Strict Prohibitions
- DO NOT write business adapters (evidence, interaction, agent-step, oracle, work-environment, delivery).
- DO NOT touch migrations.
- DO NOT regenerate or commit the runtime bundle `factory/runtime/factory-operational.mjs`.
- DO NOT touch `agentos/**`.
- DO NOT introduce static imports of driver `pg` (`pg` must remain dynamic or compatible with `SqlClient`).

---

## Proposed Changes & Strategy

### 1. SQL Transactional Primitive (`factory/src/adapters/persistence/sql/unit-of-work.ts` or `db.ts`)
- Implement `withTransaction<T>(client: SqlClient, callback: (tx: SqlClient) => Promise<T>): Promise<T>` in `factory/src/adapters/persistence/sql/unit-of-work.ts` (or `db.ts`).
- Function logic:
  1. Call `await client.query('BEGIN')`.
  2. Execute `const result = await callback(client)`.
  3. Call `await client.query('COMMIT')`.
  4. Return `result`.
  5. In a `catch` block on error/rejection:
     - Call `await client.query('ROLLBACK')`.
     - Re-throw the original error/exception (`throw err`).
- Re-export `withTransaction` in `factory/src/adapters/persistence/sql/index.ts`.
- Ensure all existing exports of `db.ts` (`SqlClient`, `createPgPoolClient`, `parseJsonColumn`, `resolveSqlDatabaseConfig`, `DEFAULT_ORGANIZATION_ID`, `DEFAULT_WORKSTREAM_ID`, `SqlQueryResult`, `SqlDatabaseConfig`) are strictly preserved and re-exported without regression.

### 2. In-Memory Transactional Simulation (`factory/tests/support/in-memory-sql-client.mjs`)
- Extend `createInMemorySqlClient` in `factory/tests/support/in-memory-sql-client.mjs`.
- Add transaction state tracking:
  - `snapshotStack` (array of snapshot objects or Map backups to support nested/re-entrant BEGIN/COMMIT/ROLLBACK or single transaction snapshot).
- SQL command parsing in `query(text, params)`:
  - Match `/^BEGIN$/i`:
    - Deep copy the internal `tables` state (e.g. clone each table name -> array of cloned row objects).
    - Push snapshot to `snapshotStack`.
    - Return `{ rows: [], rowCount: 0 }`.
  - Match `/^COMMIT$/i`:
    - Pop the latest snapshot from `snapshotStack` (discarding the old state snapshot as changes are committed).
    - Return `{ rows: [], rowCount: 0 }`.
  - Match `/^ROLLBACK$/i`:
    - Pop the latest snapshot from `snapshotStack`.
    - Restore `tables` from the popped snapshot (restoring table Map entries and row arrays).
    - Return `{ rows: [], rowCount: 0 }`.
- Ensure non-transaction queries (INSERT, SELECT, UPDATE, DELETE) continue to operate normally on the active `tables` state during a transaction.

### 3. Dedicated Test Suite (`factory/tests/test-sql-unit-of-work.mjs`)
- Create standard ES Module script executable with Node.js (`node factory/tests/test-sql-unit-of-work.mjs`).
- Use `node:assert/strict` for assertions.
- Test Cases:
  1. **Success Scenario (COMMIT)**:
     - Execute write queries (INSERT, UPDATE) within `withTransaction(client, async (tx) => { ... })`.
     - Assert that the transaction callback resolves successfully.
     - Query the client outside `withTransaction` (or post-commit) to verify that all inserted/updated rows persist in the database tables.
  2. **Failure Scenario (ROLLBACK)**:
     - Seed initial table state.
     - Execute write queries (INSERT, UPDATE, DELETE) inside `withTransaction(client, async (tx) => { throw new Error('Simulated Failure') })`.
     - Assert that the call to `withTransaction` rejects with the exact thrown error.
     - Query the client post-rollback to verify that all modifications inside the callback were completely undone and tables are restored to their pre-transaction state.
  3. **Exit Code**: Log summary and exit with `0` on success, `1` on error.

---

## Verification Plan

1. **Existing SQL Contract Tests**:
   - Run `node factory/tests/test-sql-repository-ports-adapters.mjs`
   - Ensure all 21 tests continue to pass without issues.

2. **New Unit of Work Test Suite**:
   - Run `node factory/tests/test-sql-unit-of-work.mjs`
   - Confirm output shows 100% test success and process exits with status code `0`.

3. **Scope & Bundle Compliance**:
   - Verify `git status` to ensure only the authorized files were created/modified.
   - Confirm `factory/runtime/factory-operational.mjs` is untouched.
