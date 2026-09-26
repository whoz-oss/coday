// Minimal in-memory implementation of the `SqlClient` port used by the shared
// repository contract tests.
//
// It is intentionally *not* a SQL engine: it recognises the constrained subset
// of statements the SQL adapters emit (single-table INSERT … [ON CONFLICT DO
// NOTHING] / SELECT … [WHERE …][ ORDER BY …][ LIMIT n][ FOR UPDATE [SKIP
// LOCKED]] / UPDATE … SET … WHERE … / DELETE FROM … WHERE …) plus the
// transaction control statements `BEGIN` / `COMMIT` / `ROLLBACK` and the
// PostgreSQL sequence call `SELECT nextval('…')`. Transactions are simulated by
// snapshotting the table state on `BEGIN` and restoring it on `ROLLBACK`, which
// lets the unit-of-work tests assert atomicity offline. This keeps the contract
// tests offline and Docker-free while still exercising the adapters' query
// construction, row mapping, revision and lease/fencing logic.
//
// `FOR UPDATE [SKIP LOCKED]` is a no-op here: the client is single-threaded, so
// the strict concurrency guarantee is only exercised against a real PostgreSQL
// in `test-lease-protocol.mjs`.

const PRIMARY_KEYS = {
  workflow_definitions: ['organization_id', 'workflow_type', 'version'],
  workflow_instances: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id'],
  workflow_evidence: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id', 'evidence_id'],
  human_interactions: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id', 'interaction_id'],
  agent_step_attempts: [
    'organization_id',
    'workstream_id',
    'namespace_id',
    'workflow_id',
    'step_id',
    'attempt_id',
  ],
  agent_step_results: [
    'organization_id',
    'workstream_id',
    'namespace_id',
    'workflow_id',
    'step_id',
    'attempt_id',
    'result_id',
  ],
  oracle_executions: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id', 'execution_id'],
  work_environments: ['organization_id', 'workstream_id', 'environment_id'],
  deliveries: ['organization_id', 'workstream_id', 'namespace_id', 'delivery_id'],
  artifacts: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id', 'artifact_id'],
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

// Splits `expression` on a top-level keyword (`AND` / `OR`) or on a top-level
// comma, respecting parenthesis nesting and single-quoted string literals so a
// clause like `status IN ('a', 'b') AND (x IS NULL OR x <= $1)` is split at the
// right boundaries only.
function splitTopLevel(expression, separator) {
  const parts = []
  let current = ''
  let depth = 0
  let inString = false
  let index = 0
  while (index < expression.length) {
    const char = expression[index]
    if (char === "'") {
      inString = !inString
      current += char
      index++
      continue
    }
    if (!inString) {
      if (char === '(') depth++
      else if (char === ')') depth--
      if (depth === 0) {
        const rest = expression.slice(index)
        const match = rest.match(separator)
        if (match && match.index === 0) {
          parts.push(current)
          current = ''
          index += match[0].length
          continue
        }
      }
    }
    current += char
    index++
  }
  parts.push(current)
  return parts.map((part) => part.trim()).filter((part) => part.length > 0)
}

const AND_SEPARATOR = /^\s+AND\s+/i
const OR_SEPARATOR = /^\s+OR\s+/i
const COMMA_SEPARATOR = /^\s*,\s*/

// True when the first `(` of `text` matches its last `)` (no earlier top-level
// closing paren), i.e. the whole expression is wrapped in one redundant pair.
function hasBalancedOuterParens(text) {
  let depth = 0
  let inString = false
  for (let index = 0; index < text.length; index++) {
    const char = text[index]
    if (char === "'") {
      inString = !inString
      continue
    }
    if (inString) continue
    if (char === '(') depth++
    else if (char === ')') {
      depth--
      if (depth === 0 && index !== text.length - 1) return false
    }
  }
  return depth === 0
}

function stripOuterParens(expression) {
  let result = expression.trim()
  while (result.startsWith('(') && result.endsWith(')') && hasBalancedOuterParens(result)) {
    result = result.slice(1, -1).trim()
  }
  return result
}

const AND_NULL = /^(\w+)\s+IS\s+NULL$/i
const NOT_NULL = /^(\w+)\s+IS\s+NOT\s+NULL$/i
const IN_LIST = /^(\w+)\s+IN\s*\((.+)\)$/i
const COMPARISON = /^(\w+)\s*(=|<>|!=|<=|>=|<|>)\s*(.+)$/

function evaluateCondition(row, condition, params) {
  const isNull = condition.match(AND_NULL)
  if (isNull) return row[isNull[1]] === null || row[isNull[1]] === undefined

  const isNotNull = condition.match(NOT_NULL)
  if (isNotNull) return !(row[isNotNull[1]] === null || row[isNotNull[1]] === undefined)

  const inList = condition.match(IN_LIST)
  if (inList) {
    const expected = splitTopLevel(inList[2], COMMA_SEPARATOR).map((token) => resolveValue(token, params))
    return expected.includes(row[inList[1]])
  }

  const comparison = condition.match(COMPARISON)
  if (comparison) {
    const [, column, operator, rawValue] = comparison
    const actual = row[column]
    const expected = resolveValue(rawValue, params)
    switch (operator) {
      case '=':
        return actual === expected
      case '<>':
      case '!=':
        return actual !== expected
      case '<':
        return actual !== null && actual !== undefined && actual < expected
      case '<=':
        return actual !== null && actual !== undefined && actual <= expected
      case '>':
        return actual !== null && actual !== undefined && actual > expected
      case '>=':
        return actual !== null && actual !== undefined && actual >= expected
      default:
        break
    }
  }
  throw new Error(`UNSUPPORTED_CONDITION: ${condition}`)
}

function evaluateExpression(row, expression, params) {
  const trimmed = expression.trim()
  const andParts = splitTopLevel(trimmed, AND_SEPARATOR)
  if (andParts.length > 1) return andParts.every((part) => evaluateExpression(row, part, params))

  const orParts = splitTopLevel(trimmed, OR_SEPARATOR)
  if (orParts.length > 1) return orParts.some((part) => evaluateExpression(row, part, params))

  const stripped = stripOuterParens(trimmed)
  if (stripped !== trimmed) return evaluateExpression(row, stripped, params)

  return evaluateCondition(row, stripped, params)
}

function compareValues(left, right) {
  if (left === right) return 0
  if (left === null || left === undefined) return -1
  if (right === null || right === undefined) return 1
  if (typeof left === 'number' && typeof right === 'number') return left - right
  return String(left) < String(right) ? -1 : 1
}

function applyOrderBy(rows, orderBy) {
  const terms = splitTopLevel(orderBy, COMMA_SEPARATOR).map((term) => {
    const match = term.match(/^(\w+)(?:\s+(ASC|DESC))?$/i)
    if (!match) throw new Error(`UNSUPPORTED_ORDER_BY: ${term}`)
    return { column: match[1], descending: (match[2] ?? 'ASC').toUpperCase() === 'DESC' }
  })
  return [...rows].sort((left, right) => {
    for (const { column, descending } of terms) {
      const ordering = compareValues(left[column], right[column])
      if (ordering !== 0) return descending ? -ordering : ordering
    }
    return 0
  })
}

// Parses the clause tail of a SELECT (everything after `FROM <table>`):
// `WHERE …` / `ORDER BY …` / `LIMIT n` / `FOR UPDATE [SKIP LOCKED]` in the
// canonical order. Mutates nothing; returns the extracted parts.
function parseSelectClauses(tail) {
  let rest = (tail ?? '').trim()
  const forUpdate = /FOR\s+UPDATE(?:\s+SKIP\s+LOCKED)?\s*$/i.test(rest)
  if (forUpdate) rest = rest.replace(/FOR\s+UPDATE(?:\s+SKIP\s+LOCKED)?\s*$/i, '').trim()

  const take = (regex) => {
    const match = rest.match(regex)
    if (!match) return null
    const value = match[1]
    rest = rest.slice(0, match.index).trim()
    return value
  }

  const limit = take(/\bLIMIT\s+(\d+)\s*$/i)
  const orderBy = take(/\bORDER\s+BY\s+(.+)$/i)
  const where = take(/\bWHERE\s+(.+)$/i)
  return { where: where ?? null, orderBy, limit: limit === null ? null : Number(limit) }
}

// Evaluates a SET right-hand side: either an arithmetic update of the row's own
// current value (`revision + 1`, `attempt_count + 1`) or a plain value.
function resolveAssignmentValue(row, expression, params) {
  const addition = expression.match(/^(\w+)\s*\+\s*(.+)$/)
  if (addition) {
    const current = row[addition[1]]
    return Number(current ?? 0) + Number(resolveValue(addition[2], params))
  }
  const subtraction = expression.match(/^(\w+)\s*-\s*(.+)$/)
  if (subtraction) {
    const current = row[subtraction[1]]
    return Number(current ?? 0) - Number(resolveValue(subtraction[2], params))
  }
  return resolveValue(expression, params)
}

export function createInMemorySqlClient() {
  const tables = new Map()
  const sequences = new Map()

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
    return evaluateExpression(row, where.trim(), params)
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

  const select = (table, columns, where, params, clauses = {}) => {
    let matched = rowsFor(table).filter((row) => matches(row, where, params))
    if (clauses.orderBy) matched = applyOrderBy(matched, clauses.orderBy)
    if (clauses.limit !== null && clauses.limit !== undefined) matched = matched.slice(0, clauses.limit)
    const projected = matched.map((row) => {
      if (!columns) return { ...row }
      const output = {}
      for (const column of columns) if (column in row) output[column] = row[column]
      return output
    })
    return { rows: projected, rowCount: projected.length }
  }

  const update = (table, setClause, where, params) => {
    const assignments = splitTopLevel(setClause, COMMA_SEPARATOR).map((assignment) => {
      const separator = assignment.indexOf('=')
      const column = assignment.slice(0, separator).trim()
      const value = assignment.slice(separator + 1).trim()
      return [column, value]
    })
    let rowCount = 0
    for (const row of rowsFor(table)) {
      if (!matches(row, where, params)) continue
      for (const [column, expression] of assignments) row[column] = resolveAssignmentValue(row, expression, params)
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
      const sql = text.replace(/\s+/g, ' ').trim().replace(/;$/, '')
      const beginMatch = sql.match(/^BEGIN(?: TRANSACTION)?$/i)
      if (beginMatch) {
        transactionSnapshots.push(snapshotTables())
        return { rows: [], rowCount: 0 }
      }
      const commitMatch = sql.match(/^COMMIT$/i)
      if (commitMatch) {
        // Committing discards the snapshot: the writes stay in the tables.
        if (transactionSnapshots.length > 0) transactionSnapshots.pop()
        return { rows: [], rowCount: 0 }
      }
      const rollbackMatch = sql.match(/^ROLLBACK$/i)
      if (rollbackMatch) {
        const snapshot = transactionSnapshots.pop()
        if (snapshot) restoreTables(snapshot)
        return { rows: [], rowCount: 0 }
      }
      // PostgreSQL sequence call used for the fence token.
      const sequenceMatch = sql.match(/^SELECT nextval\('([^']+)'\)(?:\s+AS\s+(\w+))?$/i)
      if (sequenceMatch) {
        const [, name, alias] = sequenceMatch
        const next = (sequences.get(name) ?? 0) + 1
        sequences.set(name, next)
        return { rows: [{ [alias ?? 'nextval']: next }], rowCount: 1 }
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
      const selectMatch = sql.match(/^SELECT (.+?) FROM (\w+)(?:\s+(.*))?$/i)
      if (selectMatch) {
        const [, projection, table, tail = ''] = selectMatch
        const columns = projection.trim() === '*' ? null : projection.split(',').map((column) => column.trim())
        const clauses = parseSelectClauses(tail)
        return select(table, columns, clauses.where, params, clauses)
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
