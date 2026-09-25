// Minimal in-memory implementation of the `SqlClient` port used by the shared
// repository contract tests.
//
// It is intentionally *not* a SQL engine: it recognises the constrained subset
// of statements the SQL adapters emit (single-table INSERT … [ON CONFLICT DO
// NOTHING] / SELECT … [WHERE eq AND …] / UPDATE … SET … WHERE … / DELETE FROM …
// WHERE …) plus the transaction control statements `BEGIN` / `COMMIT` /
// `ROLLBACK`. Transactions are simulated by snapshotting the table state on
// `BEGIN` and restoring it on `ROLLBACK`, which lets the unit-of-work tests
// assert atomicity offline. This keeps the contract tests offline and
// Docker-free while still exercising the adapters' query construction, row
// mapping and revision logic.

const PRIMARY_KEYS = {
  workflow_definitions: ['organization_id', 'workflow_type', 'version'],
  workflow_instances: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id'],
}

function stripCast(token) {
  return token.replace(/::[\w[\]]+$/, '').trim()
}

function resolveValue(token, params) {
  const value = stripCast(token)
  const placeholder = value.match(/^\$(\d+)$/)
  if (placeholder) return params[Number(placeholder[1]) - 1]
  const literal = value.match(/^'([^']*)'$/)
  if (literal) return literal[1]
  if (/^null$/i.test(value)) return null
  const numeric = Number(value)
  if (value !== '' && Number.isFinite(numeric)) return numeric
  return value
}

export function createInMemorySqlClient() {
  const tables = new Map()

  // Stack of table-state snapshots, one per open transaction. It supports
  // nested / re-entrant `BEGIN` (the inner snapshot is restored by the inner
  // `ROLLBACK`) so the client behaves like a connection with savepoints.
  const transactionSnapshots = []

  // Deep copy of the table state. Rows only hold JSON-scalar values in these
  // adapters, but `structuredClone` keeps the snapshot correct even if a row
  // ever carries a nested object.
  const snapshotTables = () => new Map([...tables.entries()].map(([table, rows]) => [table, structuredClone(rows)]))

  const restoreTables = (snapshot) => {
    tables.clear()
    for (const [table, rows] of snapshot.entries()) tables.set(table, rows)
  }

  const rowsFor = (table) => {
    if (!tables.has(table)) tables.set(table, [])
    return tables.get(table)
  }

  const matches = (row, where, params) => {
    if (!where) return true
    return where.split(/\s+AND\s+/i).every((condition) => {
      const equality = condition.trim().match(/^(\w+)\s*(=|<>)\s*(.+)$/)
      if (!equality) throw new Error(`UNSUPPORTED_CONDITION: ${condition}`)
      const [, column, operator, rawValue] = equality
      const expected = resolveValue(rawValue, params)
      return operator === '=' ? row[column] === expected : row[column] !== expected
    })
  }

  const insert = (table, columns, values, params) => {
    const row = {}
    columns.forEach((column, index) => {
      row[column] = resolveValue(values[index], params)
    })
    const primaryKey = PRIMARY_KEYS[table]
    if (primaryKey && rowsFor(table).some((existing) => primaryKey.every((key) => existing[key] === row[key]))) {
      return { rows: [], rowCount: 0 }
    }
    rowsFor(table).push(row)
    return { rows: [], rowCount: 1 }
  }

  const select = (table, columns, where, params) => {
    const projected = rowsFor(table)
      .filter((row) => matches(row, where, params))
      .map((row) => {
        if (!columns) return { ...row }
        const output = {}
        for (const column of columns) if (column in row) output[column] = row[column]
        return output
      })
    return { rows: projected, rowCount: projected.length }
  }

  const update = (table, setClause, where, params) => {
    const assignments = setClause.split(',').map((assignment) => {
      const separator = assignment.indexOf('=')
      const column = assignment.slice(0, separator).trim()
      const value = resolveValue(assignment.slice(separator + 1), params)
      return [column, value]
    })
    let rowCount = 0
    for (const row of rowsFor(table)) {
      if (!matches(row, where, params)) continue
      for (const [column, value] of assignments) row[column] = value
      rowCount++
    }
    return { rows: [], rowCount }
  }

  const deleteRows = (table, where, params) => {
    const remaining = rowsFor(table).filter((row) => !matches(row, where, params))
    const rowCount = rowsFor(table).length - remaining.length
    tables.set(table, remaining)
    return { rows: [], rowCount }
  }

  return {
    async query(text, params = []) {
      const sql = text.replace(/\s+/g, ' ').trim()
      const beginMatch = sql.match(/^BEGIN(?: TRANSACTION)?;?$/i)
      if (beginMatch) {
        transactionSnapshots.push(snapshotTables())
        return { rows: [], rowCount: 0 }
      }
      const commitMatch = sql.match(/^COMMIT;?$/i)
      if (commitMatch) {
        // Committing discards the snapshot: the writes stay in the tables.
        if (transactionSnapshots.length > 0) transactionSnapshots.pop()
        return { rows: [], rowCount: 0 }
      }
      const rollbackMatch = sql.match(/^ROLLBACK;?$/i)
      if (rollbackMatch) {
        const snapshot = transactionSnapshots.pop()
        if (snapshot) restoreTables(snapshot)
        return { rows: [], rowCount: 0 }
      }
      const insertMatch = sql.match(/^INSERT INTO (\w+) \(([^)]+)\) VALUES \(([^)]+)\)/i)
      if (insertMatch) {
        const [, table, columns, values] = insertMatch
        return insert(
          table,
          columns.split(',').map((column) => column.trim()),
          values.split(',').map((value) => value.trim()),
          params
        )
      }
      const selectMatch = sql.match(/^SELECT (.+?) FROM (\w+)(?: WHERE (.*))?$/i)
      if (selectMatch) {
        const [, projection, table, where] = selectMatch
        const columns = projection.trim() === '*' ? null : projection.split(',').map((column) => column.trim())
        return select(table, columns, where, params)
      }
      const updateMatch = sql.match(/^UPDATE (\w+) SET (.+?) WHERE (.+)$/i)
      if (updateMatch) {
        const [, table, setClause, where] = updateMatch
        return update(table, setClause, where, params)
      }
      const deleteMatch = sql.match(/^DELETE FROM (\w+)(?: WHERE (.*))?$/i)
      if (deleteMatch) {
        const [, table, where] = deleteMatch
        return deleteRows(table, where, params)
      }
      throw new Error(`UNSUPPORTED_SQL: ${sql}`)
    },
  }
}
