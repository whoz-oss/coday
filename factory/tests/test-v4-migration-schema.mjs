/**
 * Validation hors-ligne de la migration Flyway V4 (support tables: outbox &
 * idempotency).
 *
 * Le fichier `factory/infra/migrations/V4__outbox_and_idempotency.sql` ajoute les
 * deux tables d'infrastructure transverses du Jalon B2 (Tâche B2-T3). Ce test lit
 * **V1 + V2 + V3 + V4**, les parse sans dépendance externe (aucun PostgreSQL,
 * Docker ou driver `pg` requis) et vérifie l'état cumulé du schéma :
 *
 *   Bloc A — propreté syntaxique du SQL (parenthèses équilibrées, terminaux,
 *            dollar-quoting de la fonction trigger).
 *   Bloc B — tables créées par V4 et colonnes attendues (type + NOT NULL).
 *   Bloc C — clés primaires composites tenant-scoped et garantie d'unicité de la
 *            clé d'idempotence.
 *   Bloc D — index de drain de l'outbox couvrant `(status, created_at)`.
 *   Bloc E — contraintes CHECK (status, attempts >= 0), valeurs par défaut et
 *            trigger `updated_at` de `idempotency_records`.
 *   Bloc F — isolation tenant (`organization_id NOT NULL DEFAULT 'default'`).
 *
 * Usage : node factory/tests/test-v4-migration-schema.mjs
 * Code de sortie : 0 = tous les cas passent, 1 = au moins un échec.
 */

import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const MIGRATIONS = join(here, '..', 'infra', 'migrations')
const SQL_FILES = [
  'V1__init_workflow_pilot_schema.sql',
  'V2__tenant_and_membership.sql',
  'V3__workflow_core.sql',
  'V4__outbox_and_idempotency.sql',
]
const V4_FILE = 'V4__outbox_and_idempotency.sql'

// ---------------------------------------------------------------------------
// Runner minimal
// ---------------------------------------------------------------------------

let passed = 0
let failed = 0

function check(name, fn) {
  try {
    fn()
    console.log(`\u2713 ${name}`)
    passed++
  } catch (error) {
    console.log(`\u2717 ${name}`)
    console.log(`  ${error.message}`)
    failed++
  }
}

function equal(actual, expected, label) {
  const a = JSON.stringify(actual)
  const e = JSON.stringify(expected)
  if (a !== e) throw new Error(`${label ? `${label} : ` : ''}attendu ${e}, obtenu ${a}`)
}

// ---------------------------------------------------------------------------
// Lecteur SQL : retrait des commentaires, découpage en instructions, parsing
// ---------------------------------------------------------------------------

/** Remplace les commentaires `--` et `/* ... *​/` par des espaces. */
function stripComments(sql) {
  let out = ''
  let i = 0
  const n = sql.length
  while (i < n) {
    const c = sql[i]
    const next = sql[i + 1]
    if (c === '-' && next === '-') {
      while (i < n && sql[i] !== '\n') i++
      continue
    }
    if (c === '/' && next === '*') {
      i += 2
      while (i < n && !(sql[i] === '*' && sql[i + 1] === '/')) i++
      i += 2
      continue
    }
    if (c === "'") {
      out += c
      i++
      while (i < n) {
        out += sql[i]
        if (sql[i] === "'") {
          if (sql[i + 1] === "'") {
            out += sql[i + 1]
            i += 2
            continue
          }
          i++
          break
        }
        i++
      }
      continue
    }
    if (c === '$') {
      const match = /^\$[A-Za-z0-9_]*\$/.exec(sql.slice(i))
      if (match) {
        const tag = match[0]
        const end = sql.indexOf(tag, i + tag.length)
        const stop = end === -1 ? n : end + tag.length
        out += sql.slice(i, stop)
        i = stop
        continue
      }
    }
    out += c
    i++
  }
  return out
}

/** Découpe un texte SQL en instructions terminées par `;` (hors strings/$$). */
function splitStatements(sql) {
  const statements = []
  let buffer = ''
  let i = 0
  const n = sql.length
  while (i < n) {
    const c = sql[i]
    if (c === "'") {
      let j = i + 1
      buffer += c
      while (j < n) {
        buffer += sql[j]
        if (sql[j] === "'") {
          if (sql[j + 1] === "'") {
            buffer += sql[j + 1]
            j += 2
            continue
          }
          j++
          break
        }
        j++
      }
      i = j
      continue
    }
    if (c === '$') {
      const match = /^\$[A-Za-z0-9_]*\$/.exec(sql.slice(i))
      if (match) {
        const tag = match[0]
        const end = sql.indexOf(tag, i + tag.length)
        const stop = end === -1 ? n : end + tag.length
        buffer += sql.slice(i, stop)
        i = stop
        continue
      }
    }
    if (c === ';') {
      if (buffer.trim()) statements.push(buffer.trim())
      buffer = ''
      i++
      continue
    }
    buffer += c
    i++
  }
  if (buffer.trim()) statements.push(buffer.trim())
  return statements
}

/** Découpe un corps de table sur les virgules de premier niveau. */
function splitTopLevel(body) {
  const parts = []
  let depth = 0
  let buffer = ''
  let i = 0
  while (i < body.length) {
    const c = body[i]
    if (c === "'") {
      let j = i + 1
      buffer += c
      while (j < body.length) {
        buffer += body[j]
        if (body[j] === "'") {
          if (body[j + 1] === "'") {
            buffer += body[j + 1]
            j += 2
            continue
          }
          j++
          break
        }
        j++
      }
      i = j
      continue
    }
    if (c === '(') depth++
    else if (c === ')') depth--
    if (c === ',' && depth === 0) {
      parts.push(buffer.trim())
      buffer = ''
      i++
      continue
    }
    buffer += c
    i++
  }
  if (buffer.trim()) parts.push(buffer.trim())
  return parts.filter(Boolean)
}

/** Extrait le contenu de chaque `CHECK (...)` d'un fragment. */
function extractChecks(text) {
  const out = []
  const re = /\bCHECK\s*/gi
  let match
  while ((match = re.exec(text))) {
    let i = re.lastIndex
    if (text[i] !== '(') continue
    let depth = 0
    const start = i
    for (; i < text.length; i++) {
      if (text[i] === '(') depth++
      else if (text[i] === ')') {
        depth--
        if (depth === 0) {
          i++
          break
        }
      }
    }
    out.push(text.slice(start + 1, i - 1).trim())
    re.lastIndex = i
  }
  return out
}

/** Parse une colonne `name TYPE [contraintes...]`. */
function parseColumn(part) {
  const match = /^([A-Za-z0-9_]+)\s+([A-Za-z]+(?:\s*\([^)]*\))?)([\s\S]*)$/.exec(part)
  if (!match) throw new Error(`définition de colonne illisible : ${part}`)
  const [, name, rawType, rest] = match
  const defaultMatch = /\bDEFAULT\s+('(?:[^']|'')*'|[A-Za-z0-9_]+(?:\s*\([^)]*\))?)(\s*::[A-Za-z0-9_ ]+)?/i.exec(rest)
  return {
    name,
    type: rawType.replace(/\s+/g, ' ').toUpperCase(),
    notNull: /\bNOT\s+NULL\b/i.test(rest),
    hasDefault: defaultMatch !== null,
    defaultRaw: defaultMatch ? `${defaultMatch[1]}${defaultMatch[2] ?? ''}`.trim() : null,
    checks: extractChecks(rest),
  }
}

const KIND = {
  PRIMARY: 'primary',
  UNIQUE: 'unique',
  FOREIGN: 'foreign',
  CHECK: 'check',
}

/** Classe un élément de corps de table (colonne ou contrainte). */
function classify(part) {
  const named = /^CONSTRAINT\s+([A-Za-z0-9_]+)\s+([\s\S]*)$/i.exec(part)
  const name = named ? named[1] : null
  const body = named ? named[2].trim() : part
  if (/^PRIMARY\s+KEY\b/i.test(body)) return { kind: KIND.PRIMARY, name, body }
  if (/^UNIQUE\b/i.test(body)) return { kind: KIND.UNIQUE, name, body }
  if (/^FOREIGN\s+KEY\b/i.test(body)) return { kind: KIND.FOREIGN, name, body }
  if (/^CHECK\b/i.test(body)) return { kind: KIND.CHECK, name, body }
  return { kind: 'column', name: null, column: parseColumn(part) }
}

function columnList(raw) {
  return raw
    .split(',')
    .map((entry) => entry.trim())
    .filter(Boolean)
}

/** Construit le modèle de schéma cumulé V1 + V2 + V3 + V4. */
function parseSchema(sql) {
  const statements = splitStatements(stripComments(sql))
  const tables = new Map()
  const functions = new Set()
  const triggers = []
  const indexes = []

  const applyConstraint = (table, part) => {
    const item = classify(part)
    if (item.kind === KIND.PRIMARY) {
      const m = /^PRIMARY\s+KEY\s*\(([^)]*)\)/i.exec(item.body)
      table.pk = { name: item.name, columns: columnList(m[1]) }
      return
    }
    if (item.kind === KIND.UNIQUE) {
      const m = /^UNIQUE\s*\(([^)]*)\)/i.exec(item.body)
      table.uniques.push({ name: item.name, columns: columnList(m[1]) })
      return
    }
    if (item.kind === KIND.FOREIGN) {
      const m = /^FOREIGN\s+KEY\s*\(([^)]*)\)\s+REFERENCES\s+([A-Za-z0-9_]+)\s*\(([^)]*)\)([\s\S]*)$/i.exec(item.body)
      if (!m) throw new Error(`clé étrangère illisible dans ${table.name} : ${item.body}`)
      const onDelete = /ON\s+DELETE\s+([A-Z ]+?)(?:\s|$)/i.exec(m[4])
      table.fks.push({
        name: item.name,
        columns: columnList(m[1]),
        refTable: m[2].toLowerCase(),
        refColumns: columnList(m[3]),
        onDelete: onDelete ? onDelete[1].trim().toUpperCase() : null,
      })
      return
    }
    if (item.kind === KIND.CHECK) {
      table.checks.push(item.body.replace(/^CHECK\s*/i, '').trim())
    }
  }

  for (const statement of statements) {
    const fnMatch = /^CREATE\s+(?:OR\s+REPLACE\s+)?FUNCTION\s+([A-Za-z0-9_]+)\s*\(/i.exec(statement)
    if (fnMatch) {
      functions.add(fnMatch[1].toLowerCase())
      continue
    }

    const tableMatch = /^CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([A-Za-z0-9_]+)\s*\(([\s\S]*)\)\s*$/i.exec(statement)
    if (tableMatch) {
      const [, name, body] = tableMatch
      const table = { name, columns: new Map(), pk: null, uniques: [], fks: [], checks: [] }
      for (const part of splitTopLevel(body)) {
        const item = classify(part)
        if (item.kind === 'column') {
          table.columns.set(item.column.name, item.column)
          table.checks.push(...item.column.checks)
          continue
        }
        applyConstraint(table, part)
      }
      tables.set(name.toLowerCase(), table)
      continue
    }

    // V3/V4 may extend an existing table via ALTER TABLE ADD COLUMN / ADD CONSTRAINT.
    const addColumnMatch = /^ALTER\s+TABLE\s+([A-Za-z0-9_]+)\s+ADD\s+COLUMN\s+(?:IF\s+NOT\s+EXISTS\s+)?([\s\S]+)$/i.exec(statement)
    if (addColumnMatch) {
      const table = tables.get(addColumnMatch[1].toLowerCase())
      if (table) {
        const column = parseColumn(addColumnMatch[2].trim())
        table.columns.set(column.name, column)
        table.checks.push(...column.checks)
      }
      continue
    }

    const addConstraintMatch = /^ALTER\s+TABLE\s+([A-Za-z0-9_]+)\s+ADD\s+CONSTRAINT\s+([A-Za-z0-9_]+)\s+([\s\S]+)$/i.exec(statement)
    if (addConstraintMatch) {
      const table = tables.get(addConstraintMatch[1].toLowerCase())
      if (table) applyConstraint(table, `CONSTRAINT ${addConstraintMatch[2]} ${addConstraintMatch[3]}`)
      continue
    }

    const indexMatch = /^CREATE\s+(UNIQUE\s+)?INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?([A-Za-z0-9_]+)[\s\S]*?\bON\s+([A-Za-z0-9_]+)\s*\(([^)]*)\)/i.exec(
      statement
    )
    if (indexMatch) {
      indexes.push({
        name: indexMatch[2].toLowerCase(),
        unique: Boolean(indexMatch[1]),
        table: indexMatch[3].toLowerCase(),
        columns: columnList(indexMatch[4]),
      })
      continue
    }

    const triggerMatch = /^CREATE\s+TRIGGER\s+([A-Za-z0-9_]+)[\s\S]*?\bBEFORE\s+UPDATE\s+ON\s+([A-Za-z0-9_]+)[\s\S]*?EXECUTE\s+FUNCTION\s+([A-Za-z0-9_]+)\s*\(/i.exec(
      statement
    )
    if (triggerMatch) {
      triggers.push({
        name: triggerMatch[1].toLowerCase(),
        table: triggerMatch[2].toLowerCase(),
        fn: triggerMatch[3].toLowerCase(),
      })
    }
  }

  return { statements, tables, functions, triggers, indexes }
}

// ---------------------------------------------------------------------------
// Spécification attendue pour V4 (état cumulé V1 + V2 + V3 + V4)
// ---------------------------------------------------------------------------

const NEW_TABLES = ['outbox_events', 'idempotency_records']

const EXPECTED_COLUMNS = {
  outbox_events: {
    organization_id: { type: 'VARCHAR(255)', notNull: true },
    id: { type: 'VARCHAR(255)', notNull: true },
    workstream_id: { type: 'VARCHAR(255)', notNull: false },
    event_type: { type: 'VARCHAR(255)', notNull: true },
    payload: { type: 'JSONB', notNull: true },
    status: { type: 'VARCHAR(64)', notNull: true },
    attempts: { type: 'INTEGER', notNull: true },
    created_at: { type: 'TIMESTAMPTZ', notNull: true },
    dispatched_at: { type: 'TIMESTAMPTZ', notNull: false },
  },
  idempotency_records: {
    organization_id: { type: 'VARCHAR(255)', notNull: true },
    idempotency_key: { type: 'VARCHAR(255)', notNull: true },
    workstream_id: { type: 'VARCHAR(255)', notNull: true },
    request_hash: { type: 'VARCHAR(64)', notNull: true },
    resource_ref: { type: 'VARCHAR(255)', notNull: false },
    status: { type: 'VARCHAR(64)', notNull: true },
    response_payload: { type: 'JSONB', notNull: true },
    created_at: { type: 'TIMESTAMPTZ', notNull: true },
    updated_at: { type: 'TIMESTAMPTZ', notNull: true },
  },
}

const EXPECTED_PKS = {
  outbox_events: ['organization_id', 'id'],
  idempotency_records: ['organization_id', 'idempotency_key'],
}

const TENANT_SCOPED_TABLES = ['outbox_events', 'idempotency_records']

const UPDATED_AT_TABLES = ['idempotency_records']

// ---------------------------------------------------------------------------
// Chargement + parsing cumulé
// ---------------------------------------------------------------------------

const rawFiles = new Map(SQL_FILES.map((file) => [file, readFileSync(join(MIGRATIONS, file), 'utf8')]))
const rawSql = SQL_FILES.map((file) => rawFiles.get(file)).join('\n')
const cleanSql = stripComments(rawSql)
const schema = parseSchema(rawSql)
const v4Statements = splitStatements(stripComments(rawFiles.get(V4_FILE)))

// ---------------------------------------------------------------------------
// Bloc A — propreté syntaxique
// ---------------------------------------------------------------------------

check('A1 — les fichiers V1, V2, V3 et V4 existent, sont non vides et terminent par un point-virgule', () => {
  for (const file of SQL_FILES) {
    const raw = rawFiles.get(file)
    assert.ok(raw.trim().length > 0, `${file} : fichier vide`)
    assert.ok(stripComments(raw).trim().endsWith(';'), `${file} : dernière instruction sans ;`)
  }
})

check('A2 — aucune instruction vide dans le SQL cumulé', () => {
  for (const statement of schema.statements) {
    assert.ok(statement.replace(/[\s;]/g, '').length > 0, 'instruction vide détectée')
  }
  // V1 + V2 + V3 + V4 (2 tables + 2 index + 1 DROP TRIGGER + 1 trigger).
  assert.ok(schema.statements.length >= 17 + 3 + 11, `nombre d'instructions trop faible : ${schema.statements.length}`)
  assert.ok(v4Statements.length >= 6, `V4 : nombre d'instructions trop faible : ${v4Statements.length}`)
})

check('A3 — parenthèses équilibrées sur tout le fichier nettoyé', () => {
  let depth = 0
  for (const char of cleanSql) {
    if (char === '(') depth++
    else if (char === ')') depth--
    assert.ok(depth >= 0, 'parenthèse fermante surnuméraire')
  }
  equal(depth, 0, 'profondeur finale')
})

check('A4 — la V4 réutilise le dollar-quoting / la fonction set_updated_at de V2', () => {
  const dollars = [...rawSql.matchAll(/\$\$/g)].length
  assert.ok(dollars > 0 && dollars % 2 === 0, `nombre de délimiteurs $$ impair : ${dollars}`)
  assert.match(rawSql, /CREATE\s+OR\s+REPLACE\s+FUNCTION\s+set_updated_at\s*\(\s*\)/i)
  assert.match(rawSql, /RETURNS\s+TRIGGER/i)
  assert.match(rawSql, /LANGUAGE\s+plpgsql/i)
  const v4 = rawFiles.get(V4_FILE)
  assert.match(v4, /EXECUTE\s+FUNCTION\s+set_updated_at\s*\(\)/i, 'V4 doit brancher set_updated_at()')
})

// ---------------------------------------------------------------------------
// Bloc B — tables et colonnes
// ---------------------------------------------------------------------------

check('B1 — les 2 nouvelles tables de support sont créées', () => {
  for (const table of NEW_TABLES) {
    assert.ok(schema.tables.has(table), `table manquante : ${table}`)
  }
  equal(NEW_TABLES.length, 2, 'nombre de nouvelles tables')
})

check('B2 — chaque table exposée a exactement les colonnes attendues (type + NOT NULL)', () => {
  for (const [table, columns] of Object.entries(EXPECTED_COLUMNS)) {
    const parsed = schema.tables.get(table)
    assert.ok(parsed, `table absente : ${table}`)
    equal([...parsed.columns.keys()].sort(), Object.keys(columns).sort(), `colonnes de ${table}`)
    for (const [column, spec] of Object.entries(columns)) {
      const parsedColumn = parsed.columns.get(column)
      equal(parsedColumn.type, spec.type, `${table}.${column} type`)
      equal(parsedColumn.notNull, spec.notNull, `${table}.${column} NOT NULL`)
    }
  }
})

// ---------------------------------------------------------------------------
// Bloc C — clés primaires et unicité de la clé d'idempotence
// ---------------------------------------------------------------------------

check('C1 — chaque table de support possède la clé primaire composite tenant-scoped attendue', () => {
  for (const [table, columns] of Object.entries(EXPECTED_PKS)) {
    const parsed = schema.tables.get(table)
    assert.ok(parsed, `table absente : ${table}`)
    assert.ok(parsed.pk, `${table} : PRIMARY KEY absente`)
    equal(parsed.pk.columns, columns, `PRIMARY KEY de ${table}`)
  }
})

check("C2 — l'unicité de la clé d'idempotence par tenant est garantie (PK ou UNIQUE)", () => {
  const idem = schema.tables.get('idempotency_records')
  const key = ['organization_id', 'idempotency_key']
  const coveredByUnique = idem.uniques.some((u) => JSON.stringify(u.columns) === JSON.stringify(key))
  const coveredByPk = idem.pk && JSON.stringify(idem.pk.columns) === JSON.stringify(key)
  assert.ok(coveredByUnique || coveredByPk, 'idempotency_records : aucune unicité sur (organization_id, idempotency_key)')
})

check("C3 — l'unicité de la clé d'idempotence n'est pas scindée par workstream (collision inter-workstream détectée)", () => {
  const idem = schema.tables.get('idempotency_records')
  const key = ['organization_id', 'idempotency_key']
  const uniqueSets = [...(idem.pk ? [idem.pk.columns] : []), ...idem.uniques.map((u) => u.columns)]
  assert.ok(
    uniqueSets.some((cols) => JSON.stringify(cols) === JSON.stringify(key)),
    'idempotency_records : la clé unique doit être exactement (organization_id, idempotency_key)'
  )
})

// ---------------------------------------------------------------------------
// Bloc D — index de drain
// ---------------------------------------------------------------------------

check('D1 — idx_outbox_events_drain existe sur outbox_events et couvre (status, created_at)', () => {
  const index = schema.indexes.find((candidate) => candidate.name === 'idx_outbox_events_drain')
  assert.ok(index, 'index idx_outbox_events_drain absent')
  equal(index.table, 'outbox_events', 'table de idx_outbox_events_drain')
  assert.ok(index.columns.includes('status'), 'idx_outbox_events_drain doit couvrir status')
  assert.ok(index.columns.includes('created_at'), 'idx_outbox_events_drain doit couvrir created_at')
  equal(index.unique, false, 'idx_outbox_events_drain ne doit pas être unique')
})

check('D2 — l\'index de drain est tenant-scopé (organization_id en tête)', () => {
  const index = schema.indexes.find((candidate) => candidate.name === 'idx_outbox_events_drain')
  equal(index.columns[0], 'organization_id', 'première colonne de idx_outbox_events_drain')
  assert.ok(
    index.columns.indexOf('status') < index.columns.indexOf('created_at'),
    'idx_outbox_events_drain : status doit précéder created_at'
  )
})

// ---------------------------------------------------------------------------
// Bloc E — contraintes CHECK, defaults et trigger
// ---------------------------------------------------------------------------

const checkText = (table) => schema.tables.get(table).checks.join(' | ')

check('E1 — CHECK status IN (pending, dispatched, failed) sur outbox_events', () => {
  assert.match(
    checkText('outbox_events'),
    /status\s+IN\s*\(\s*'pending'\s*,\s*'dispatched'\s*,\s*'failed'\s*\)/i,
    'outbox_events : CHECK status manquante'
  )
})

check('E2 — CHECK (attempts >= 0) sur outbox_events', () => {
  assert.match(checkText('outbox_events'), /attempts\s*>=\s*0/, 'outbox_events : CHECK attempts >= 0 manquante')
})

check('E3 — CHECK status IN (processing, completed, failed) sur idempotency_records', () => {
  assert.match(
    checkText('idempotency_records'),
    /status\s+IN\s*\(\s*'processing'\s*,\s*'completed'\s*,\s*'failed'\s*\)/i,
    'idempotency_records : CHECK status manquante'
  )
})

check("E4 — valeurs par défaut d'outbox_events (tenant, payload JSONB, status, attempts, created_at)", () => {
  const table = schema.tables.get('outbox_events')
  equal(table.columns.get('organization_id').defaultRaw, "'default'", 'outbox_events.organization_id DEFAULT')
  equal(table.columns.get('payload').defaultRaw, "'{}'::jsonb", 'outbox_events.payload DEFAULT')
  equal(table.columns.get('status').defaultRaw, "'pending'", 'outbox_events.status DEFAULT')
  equal(table.columns.get('attempts').defaultRaw, '0', 'outbox_events.attempts DEFAULT')
  equal(table.columns.get('created_at').defaultRaw, 'CURRENT_TIMESTAMP', 'outbox_events.created_at DEFAULT')
})

check("E5 — valeurs par défaut d'idempotency_records (tenant, workstream, status, response_payload, timestamps)", () => {
  const table = schema.tables.get('idempotency_records')
  equal(table.columns.get('organization_id').defaultRaw, "'default'", 'idempotency_records.organization_id DEFAULT')
  equal(table.columns.get('workstream_id').defaultRaw, "'default'", 'idempotency_records.workstream_id DEFAULT')
  equal(table.columns.get('status').defaultRaw, "'processing'", 'idempotency_records.status DEFAULT')
  equal(table.columns.get('response_payload').defaultRaw, "'{}'::jsonb", 'idempotency_records.response_payload DEFAULT')
  equal(table.columns.get('created_at').defaultRaw, 'CURRENT_TIMESTAMP', 'idempotency_records.created_at DEFAULT')
  equal(table.columns.get('updated_at').defaultRaw, 'CURRENT_TIMESTAMP', 'idempotency_records.updated_at DEFAULT')
})

check('E6 — un trigger BEFORE UPDATE set_updated_at sur idempotency_records', () => {
  const triggers = schema.triggers.filter((trigger) => trigger.table === 'idempotency_records')
  equal(triggers.length, 1, 'nombre de triggers sur idempotency_records')
  equal(triggers[0].fn, 'set_updated_at', `fonction du trigger ${triggers[0].name}`)
  assert.match(triggers[0].name, /updated_at$/, `nom du trigger ${triggers[0].name}`)
})

check("E7 — outbox_events est un log append-only sans updated_at ni trigger", () => {
  const table = schema.tables.get('outbox_events')
  assert.ok(!table.columns.has('updated_at'), 'outbox_events ne doit pas porter de colonne updated_at')
  assert.ok(
    !schema.triggers.some((trigger) => trigger.table === 'outbox_events'),
    'outbox_events ne doit pas porter de trigger'
  )
})

check('E8 — les noms de triggers restent uniques dans le schéma cumulé', () => {
  const names = schema.triggers.map((trigger) => trigger.name)
  equal(new Set(names).size, names.length, 'noms de triggers uniques')
})

// ---------------------------------------------------------------------------
// Bloc F — isolation tenant
// ---------------------------------------------------------------------------

check("F1 — organization_id est NOT NULL avec DEFAULT 'default' sur les tables de support", () => {
  for (const table of TENANT_SCOPED_TABLES) {
    const column = schema.tables.get(table).columns.get('organization_id')
    assert.ok(column, `${table} : organization_id absente`)
    assert.ok(column.notNull, `${table} : organization_id doit être NOT NULL`)
    equal(column.defaultRaw, "'default'", `${table} : DEFAULT 'default' attendu`)
  }
})

check('F2 — les clés primaires incluent organization_id (pas de collision inter-tenant)', () => {
  for (const table of TENANT_SCOPED_TABLES) {
    const pk = schema.tables.get(table).pk
    assert.ok(pk, `${table} : PRIMARY KEY absente`)
    equal(pk.columns[0], 'organization_id', `${table} : la PK doit commencer par organization_id`)
  }
})

// ---------------------------------------------------------------------------
// Résultat
// ---------------------------------------------------------------------------

console.log(`\nRésultat : ${passed} passé(s), ${failed} échoué(s)`)
process.exit(failed === 0 ? 0 : 1)
