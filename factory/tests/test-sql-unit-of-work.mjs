/**
 * Transactional unit of work tests (Milestone B3, task T0).
 *
 * Exercises the shared `withTransaction` primitive against the in-memory
 * `SqlClient` implementation, offline and Docker-free:
 *
 *   1. A successful transaction commits every write performed inside the
 *      callback (INSERT + UPDATE survive after the primitive resolves).
 *   2. A failing transaction rolls back every write performed inside the
 *      callback (INSERT + UPDATE + DELETE are undone) and re-throws the exact
 *      error raised by the callback.
 *   3. Nested transactions restore the right snapshot on rollback.
 *
 * Usage : node factory/tests/test-sql-unit-of-work.mjs
 * Code de sortie : 0 = tous les cas passent, 1 = au moins un échec.
 */

import assert from 'node:assert/strict'

import { withTransaction } from '../src/adapters/persistence/sql/unit-of-work.ts'
import { createInMemorySqlClient } from './support/in-memory-sql-client.mjs'

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

let passed = 0
let failed = 0

async function test(name, fn) {
  try {
    await fn()
    console.log(`✓ ${name}`)
    passed++
  } catch (error) {
    console.error(`✗ ${name}\n   ${error?.stack ?? error}`)
    failed++
  }
}

const selectAll = async (client, table) => (await client.query(`SELECT * FROM ${table}`)).rows

// ---------------------------------------------------------------------------
// Cases
// ---------------------------------------------------------------------------

// a. Success (COMMIT): writes inside the callback are persisted afterwards.
await test('withTransaction commits writes made inside the callback', async () => {
  const client = createInMemorySqlClient()

  const result = await withTransaction(client, async (tx) => {
    await tx.query('INSERT INTO accounts (id, balance) VALUES ($1, $2)', ['a1', 100])
    await tx.query('INSERT INTO accounts (id, balance) VALUES ($1, $2)', ['a2', 50])
    await tx.query('UPDATE accounts SET balance = $1 WHERE id = $2', [150, 'a1'])
    return 'committed'
  })

  assert.equal(result, 'committed', 'the callback result must be returned')

  const rows = await selectAll(client, 'accounts')
  assert.deepEqual(
    rows,
    [
      { id: 'a1', balance: 150 },
      { id: 'a2', balance: 50 },
    ],
    'every write must survive the commit'
  )
})

// The in-memory client must recognise the transaction control statements.
await test('in-memory client acknowledges BEGIN / COMMIT / ROLLBACK', async () => {
  const client = createInMemorySqlClient()
  const begin = await client.query('BEGIN')
  const commit = await client.query('COMMIT')
  const beginAgain = await client.query('BEGIN')
  const rollback = await client.query('ROLLBACK')
  for (const response of [begin, commit, beginAgain, rollback]) {
    assert.deepEqual(response, { rows: [], rowCount: 0 })
  }
})

// b. Failure (ROLLBACK): every write is undone and the original error is kept.
await test('withTransaction rolls back writes and re-throws when the callback fails', async () => {
  const client = createInMemorySqlClient()
  await client.query('INSERT INTO accounts (id, balance) VALUES ($1, $2)', ['a1', 100])

  const failure = new Error('simulated failure')

  await assert.rejects(
    () =>
      withTransaction(client, async (tx) => {
        await tx.query('INSERT INTO accounts (id, balance) VALUES ($1, $2)', ['a2', 50])
        await tx.query('UPDATE accounts SET balance = $1 WHERE id = $2', [999, 'a1'])
        await tx.query('DELETE FROM accounts WHERE id = $1', ['a1'])
        throw failure
      }),
    (error) => error === failure
  )

  const rows = await selectAll(client, 'accounts')
  assert.deepEqual(rows, [{ id: 'a1', balance: 100 }], 'the pre-transaction state must be restored')
})

// Re-entrance: an inner rollback only undoes the inner writes.
await test('withTransaction supports nested transactions with independent rollback', async () => {
  const client = createInMemorySqlClient()
  await client.query('INSERT INTO accounts (id, balance) VALUES ($1, $2)', ['a1', 100])

  await withTransaction(client, async (tx) => {
    await tx.query('UPDATE accounts SET balance = $1 WHERE id = $2', [200, 'a1'])

    await assert.rejects(() =>
      withTransaction(tx, async () => {
        await tx.query('INSERT INTO accounts (id, balance) VALUES ($1, $2)', ['a2', 50])
        throw new Error('inner failure')
      })
    )

    // The inner insert is gone, the outer update is still pending.
    assert.deepEqual(await selectAll(client, 'accounts'), [{ id: 'a1', balance: 200 }])
  })

  assert.deepEqual(await selectAll(client, 'accounts'), [{ id: 'a1', balance: 200 }])
})

// ---------------------------------------------------------------------------
// Summary
// ---------------------------------------------------------------------------

console.log(`\nResult: ${passed} passed, ${failed} failed`)
process.exit(failed ? 1 : 0)
