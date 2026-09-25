/**
 * Validation hors-ligne de la migration Flyway V2 (tenant & membership).
 *
 * Le fichier `factory/infra/migrations/V2__tenant_and_membership.sql` est le
 * schéma autoritaire du Jalon B2. Ce test le lit, le parse sans dépendance
 * externe (aucun PostgreSQL, Docker ou driver `pg` requis) et vérifie :
 *
 *   Bloc A — propreté syntaxique du SQL (parenthèses équilibrées, terminaux,
 *            aucun commentaire orphelin, dollar-quoting du trigger).
 *   Bloc B — présence des 11 tables attendues avec leurs colonnes/types.
 *   Bloc C — clés primaires et clés uniques explicites.
 *   Bloc D — clés étrangères composites, actions ON DELETE et intégrité :
 *            chaque ensemble de colonnes référencé doit être couvert par une
 *            PK ou une contrainte UNIQUE de la table cible (isolation tenant).
 *   Bloc E — contraintes CHECK (revision >= 1, subject_type).
 *   Bloc F — valeur par défaut `organization_id`, fonctions et triggers
 *            `updated_at`.
 *   Bloc G — scénarios d'intégrité de clés (tenant croisé impossible).
 *
 * Usage : node factory/tests/test-v2-migration-schema.mjs
 * Code de sortie : 0 = tous les cas passent, 1 = au moins un échec.
 */

import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const SQL_PATH = join(here, '..', 'infra', 'migrations', 'V2__tenant_and_membership.sql')

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

/** Construit le modèle de schéma à partir des instructions SQL. */
function parseSchema(sql) {
  const statements = splitStatements(stripComments(sql))
  const tables = new Map()
  const functions = new Set()
  const triggers = []

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
        if (item.kind === KIND.PRIMARY) {
          const m = /^PRIMARY\s+KEY\s*\(([^)]*)\)/i.exec(item.body)
          table.pk = { name: item.name, columns: columnList(m[1]) }
          continue
        }
        if (item.kind === KIND.UNIQUE) {
          const m = /^UNIQUE\s*\(([^)]*)\)/i.exec(item.body)
          table.uniques.push({ name: item.name, columns: columnList(m[1]) })
          continue
        }
        if (item.kind === KIND.FOREIGN) {
          const m = /^FOREIGN\s+KEY\s*\(([^)]*)\)\s+REFERENCES\s+([A-Za-z0-9_]+)\s*\(([^)]*)\)([\s\S]*)$/i.exec(item.body)
          if (!m) throw new Error(`clé étrangère illisible dans ${name} : ${item.body}`)
          const onDelete = /ON\s+DELETE\s+([A-Z ]+?)(?:\s|$)/i.exec(m[4])
          table.fks.push({
            name: item.name,
            columns: columnList(m[1]),
            refTable: m[2].toLowerCase(),
            refColumns: columnList(m[3]),
            onDelete: onDelete ? onDelete[1].trim().toUpperCase() : null,
          })
          continue
        }
        if (item.kind === KIND.CHECK) {
          table.checks.push(item.body.replace(/^CHECK\s*/i, '').trim())
        }
      }
      tables.set(name.toLowerCase(), table)
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

  return { statements, tables, functions, triggers }
}

// ---------------------------------------------------------------------------
// Spécification attendue pour V2
// ---------------------------------------------------------------------------

const EXPECTED_COLUMNS = {
  organizations: {
    organization_id: { type: 'VARCHAR(255)', notNull: true },
    name: { type: 'VARCHAR(255)', notNull: true },
    revision: { type: 'INTEGER', notNull: true },
    payload: { type: 'JSONB', notNull: true },
    created_at: { type: 'TIMESTAMPTZ', notNull: true },
    updated_at: { type: 'TIMESTAMPTZ', notNull: true },
  },
  workstreams: {
    organization_id: { type: 'VARCHAR(255)', notNull: true },
    workstream_id: { type: 'VARCHAR(255)', notNull: true },
    name: { type: 'VARCHAR(255)', notNull: true },
    revision: { type: 'INTEGER', notNull: true },
    payload: { type: 'JSONB', notNull: true },
    created_at: { type: 'TIMESTAMPTZ', notNull: true },
    updated_at: { type: 'TIMESTAMPTZ', notNull: true },
  },
  squads: {
    organization_id: { type: 'VARCHAR(255)', notNull: true },
    workstream_id: { type: 'VARCHAR(255)', notNull: true },
    squad_id: { type: 'VARCHAR(255)', notNull: true },
    name: { type: 'VARCHAR(255)', notNull: true },
    revision: { type: 'INTEGER', notNull: true },
    payload: { type: 'JSONB', notNull: true },
    created_at: { type: 'TIMESTAMPTZ', notNull: true },
    updated_at: { type: 'TIMESTAMPTZ', notNull: true },
  },
  principals: {
    organization_id: { type: 'VARCHAR(255)', notNull: true },
    principal_id: { type: 'VARCHAR(255)', notNull: true },
    email: { type: 'VARCHAR(255)', notNull: false },
    name: { type: 'VARCHAR(255)', notNull: false },
    revision: { type: 'INTEGER', notNull: true },
    payload: { type: 'JSONB', notNull: true },
    created_at: { type: 'TIMESTAMPTZ', notNull: true },
    updated_at: { type: 'TIMESTAMPTZ', notNull: true },
  },
  service_identities: {
    organization_id: { type: 'VARCHAR(255)', notNull: true },
    service_identity_id: { type: 'VARCHAR(255)', notNull: true },
    name: { type: 'VARCHAR(255)', notNull: true },
    revision: { type: 'INTEGER', notNull: true },
    payload: { type: 'JSONB', notNull: true },
    created_at: { type: 'TIMESTAMPTZ', notNull: true },
    updated_at: { type: 'TIMESTAMPTZ', notNull: true },
  },
  roles: {
    organization_id: { type: 'VARCHAR(255)', notNull: true },
    role_id: { type: 'VARCHAR(255)', notNull: true },
    version: { type: 'VARCHAR(64)', notNull: true },
    name: { type: 'VARCHAR(255)', notNull: true },
    permissions: { type: 'JSONB', notNull: true },
    created_at: { type: 'TIMESTAMPTZ', notNull: true },
    updated_at: { type: 'TIMESTAMPTZ', notNull: true },
  },
  organization_memberships: {
    organization_id: { type: 'VARCHAR(255)', notNull: true },
    subject_type: { type: 'VARCHAR(32)', notNull: true },
    subject_id: { type: 'VARCHAR(255)', notNull: true },
    role_id: { type: 'VARCHAR(255)', notNull: true },
    role_version: { type: 'VARCHAR(64)', notNull: true },
    created_at: { type: 'TIMESTAMPTZ', notNull: true },
  },
  workstream_memberships: {
    organization_id: { type: 'VARCHAR(255)', notNull: true },
    workstream_id: { type: 'VARCHAR(255)', notNull: true },
    subject_type: { type: 'VARCHAR(32)', notNull: true },
    subject_id: { type: 'VARCHAR(255)', notNull: true },
    role_id: { type: 'VARCHAR(255)', notNull: true },
    role_version: { type: 'VARCHAR(64)', notNull: true },
    created_at: { type: 'TIMESTAMPTZ', notNull: true },
  },
  squad_memberships: {
    organization_id: { type: 'VARCHAR(255)', notNull: true },
    workstream_id: { type: 'VARCHAR(255)', notNull: true },
    squad_id: { type: 'VARCHAR(255)', notNull: true },
    subject_type: { type: 'VARCHAR(32)', notNull: true },
    subject_id: { type: 'VARCHAR(255)', notNull: true },
    role_id: { type: 'VARCHAR(255)', notNull: true },
    role_version: { type: 'VARCHAR(64)', notNull: true },
    created_at: { type: 'TIMESTAMPTZ', notNull: true },
  },
  repositories: {
    organization_id: { type: 'VARCHAR(255)', notNull: true },
    repository_id: { type: 'VARCHAR(255)', notNull: true },
    name: { type: 'VARCHAR(255)', notNull: true },
    url: { type: 'VARCHAR(1024)', notNull: false },
    revision: { type: 'INTEGER', notNull: true },
    payload: { type: 'JSONB', notNull: true },
    created_at: { type: 'TIMESTAMPTZ', notNull: true },
    updated_at: { type: 'TIMESTAMPTZ', notNull: true },
  },
  workstream_repositories: {
    organization_id: { type: 'VARCHAR(255)', notNull: true },
    workstream_id: { type: 'VARCHAR(255)', notNull: true },
    repository_id: { type: 'VARCHAR(255)', notNull: true },
    created_at: { type: 'TIMESTAMPTZ', notNull: true },
  },
}

const EXPECTED_PKS = {
  organizations: ['organization_id'],
  workstreams: ['organization_id', 'workstream_id'],
  squads: ['organization_id', 'workstream_id', 'squad_id'],
  principals: ['organization_id', 'principal_id'],
  service_identities: ['organization_id', 'service_identity_id'],
  roles: ['organization_id', 'role_id', 'version'],
  organization_memberships: ['organization_id', 'subject_type', 'subject_id', 'role_id'],
  workstream_memberships: ['organization_id', 'workstream_id', 'subject_type', 'subject_id', 'role_id'],
  squad_memberships: ['organization_id', 'workstream_id', 'squad_id', 'subject_type', 'subject_id', 'role_id'],
  repositories: ['organization_id', 'repository_id'],
  workstream_repositories: ['organization_id', 'workstream_id', 'repository_id'],
}

const EXPECTED_UNIQUES = {
  workstreams: [['organization_id', 'workstream_id']],
  squads: [['organization_id', 'workstream_id', 'squad_id']],
  principals: [['organization_id', 'principal_id']],
  service_identities: [['organization_id', 'service_identity_id']],
  repositories: [['organization_id', 'repository_id']],
}

const EXPECTED_FKS = [
  { table: 'workstreams', columns: ['organization_id'], refTable: 'organizations', refColumns: ['organization_id'], onDelete: 'CASCADE' },
  { table: 'squads', columns: ['organization_id', 'workstream_id'], refTable: 'workstreams', refColumns: ['organization_id', 'workstream_id'], onDelete: 'CASCADE' },
  { table: 'principals', columns: ['organization_id'], refTable: 'organizations', refColumns: ['organization_id'], onDelete: 'CASCADE' },
  { table: 'service_identities', columns: ['organization_id'], refTable: 'organizations', refColumns: ['organization_id'], onDelete: 'CASCADE' },
  { table: 'roles', columns: ['organization_id'], refTable: 'organizations', refColumns: ['organization_id'], onDelete: 'CASCADE' },
  { table: 'organization_memberships', columns: ['organization_id'], refTable: 'organizations', refColumns: ['organization_id'], onDelete: 'CASCADE' },
  { table: 'organization_memberships', columns: ['organization_id', 'role_id', 'role_version'], refTable: 'roles', refColumns: ['organization_id', 'role_id', 'version'], onDelete: null },
  { table: 'workstream_memberships', columns: ['organization_id', 'workstream_id'], refTable: 'workstreams', refColumns: ['organization_id', 'workstream_id'], onDelete: 'CASCADE' },
  { table: 'workstream_memberships', columns: ['organization_id', 'role_id', 'role_version'], refTable: 'roles', refColumns: ['organization_id', 'role_id', 'version'], onDelete: null },
  { table: 'squad_memberships', columns: ['organization_id', 'workstream_id', 'squad_id'], refTable: 'squads', refColumns: ['organization_id', 'workstream_id', 'squad_id'], onDelete: 'CASCADE' },
  { table: 'squad_memberships', columns: ['organization_id', 'role_id', 'role_version'], refTable: 'roles', refColumns: ['organization_id', 'role_id', 'version'], onDelete: null },
  { table: 'repositories', columns: ['organization_id'], refTable: 'organizations', refColumns: ['organization_id'], onDelete: 'CASCADE' },
  { table: 'workstream_repositories', columns: ['organization_id', 'workstream_id'], refTable: 'workstreams', refColumns: ['organization_id', 'workstream_id'], onDelete: 'CASCADE' },
  { table: 'workstream_repositories', columns: ['organization_id', 'repository_id'], refTable: 'repositories', refColumns: ['organization_id', 'repository_id'], onDelete: 'CASCADE' },
]

const REVISION_TABLES = [
  'organizations',
  'workstreams',
  'squads',
  'principals',
  'service_identities',
  'repositories',
]

const MEMBERSHIP_TABLES = ['organization_memberships', 'workstream_memberships', 'squad_memberships']

const UPDATED_AT_TABLES = [
  'organizations',
  'workstreams',
  'squads',
  'principals',
  'service_identities',
  'roles',
  'repositories',
]

// ---------------------------------------------------------------------------
// Chargement + parsing
// ---------------------------------------------------------------------------

const rawSql = readFileSync(SQL_PATH, 'utf8')
const cleanSql = stripComments(rawSql)
const schema = parseSchema(rawSql)

// ---------------------------------------------------------------------------
// Bloc A — propreté syntaxique
// ---------------------------------------------------------------------------

check('A1 — le fichier existe, est non vide et se termine par un point-virgule', () => {
  assert.ok(rawSql.trim().length > 0, 'le fichier SQL est vide')
  assert.ok(cleanSql.trim().endsWith(';'), 'la dernière instruction doit finir par ;')
})

check('A2 — aucun point-virgule orphelin ni instruction vide', () => {
  for (const statement of schema.statements) {
    assert.ok(statement.replace(/[\s;]/g, '').length > 0, 'instruction vide détectée')
  }
  assert.ok(schema.statements.length >= 11 + 1 + UPDATED_AT_TABLES.length, 'nombre d\'instructions trop faible')
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

check('A4 — la fonction trigger utilise un dollar-quoting équilibré', () => {
  const dollars = [...rawSql.matchAll(/\$\$/g)].length
  assert.ok(dollars > 0 && dollars % 2 === 0, `nombre de délimiteurs $$ impair : ${dollars}`)
  assert.match(rawSql, /CREATE\s+OR\s+REPLACE\s+FUNCTION\s+set_updated_at\s*\(\s*\)/i)
  assert.match(rawSql, /RETURNS\s+TRIGGER/i)
  assert.match(rawSql, /LANGUAGE\s+plpgsql/i)
})

// ---------------------------------------------------------------------------
// Bloc B — tables et colonnes
// ---------------------------------------------------------------------------

check('B1 — les 11 tables attendues sont créées avec CREATE TABLE', () => {
  const expected = Object.keys(EXPECTED_COLUMNS)
  equal(expected.length, 11, 'nombre de tables attendues')
  for (const table of expected) {
    assert.ok(schema.tables.has(table), `table manquante : ${table}`)
  }
  equal(schema.tables.size, 11, 'nombre de tables créées')
})

check('B2 — chaque table expose exactement les colonnes attendues avec le bon type et NOT NULL', () => {
  for (const [table, columns] of Object.entries(EXPECTED_COLUMNS)) {
    const parsed = schema.tables.get(table)
    equal([...parsed.columns.keys()].sort(), Object.keys(columns).sort(), `colonnes de ${table}`)
    for (const [column, spec] of Object.entries(columns)) {
      const parsedColumn = parsed.columns.get(column)
      equal(parsedColumn.type, spec.type, `${table}.${column} type`)
      equal(parsedColumn.notNull, spec.notNull, `${table}.${column} NOT NULL`)
    }
  }
})

// ---------------------------------------------------------------------------
// Bloc C — clés primaires et uniques
// ---------------------------------------------------------------------------

check('C1 — chaque table possède la clé primaire composite attendue', () => {
  for (const [table, columns] of Object.entries(EXPECTED_PKS)) {
    const parsed = schema.tables.get(table)
    assert.ok(parsed.pk, `${table} : PRIMARY KEY absente`)
    equal(parsed.pk.columns, columns, `PRIMARY KEY de ${table}`)
  }
})

check('C2 — les clés uniques explicites attendues sont déclarées', () => {
  for (const [table, expectedUniques] of Object.entries(EXPECTED_UNIQUES)) {
    const parsed = schema.tables.get(table)
    const actual = parsed.uniques.map((u) => u.columns)
    for (const expected of expectedUniques) {
      assert.ok(
        actual.some((cols) => JSON.stringify(cols) === JSON.stringify(expected)),
        `UNIQUE manquante sur ${table} (${expected.join(', ')})`
      )
    }
  }
})

// ---------------------------------------------------------------------------
// Bloc D — clés étrangères composites + intégrité des références
// ---------------------------------------------------------------------------

/** Colonnes couvertes par une PK ou une UNIQUE (garde-fou des FK composites). */
function uniqueColumnSets(table) {
  const sets = []
  if (table.pk) sets.push(table.pk.columns)
  for (const unique of table.uniques) sets.push(unique.columns)
  return sets
}

check('D1 — toutes les clés étrangères attendues existent (colonnes, cible, ON DELETE)', () => {
  for (const expected of EXPECTED_FKS) {
    const parsed = schema.tables.get(expected.table)
    assert.ok(parsed, `table absente : ${expected.table}`)
    const fk = parsed.fks.find(
      (candidate) =>
        JSON.stringify(candidate.columns) === JSON.stringify(expected.columns) &&
        candidate.refTable === expected.refTable &&
        JSON.stringify(candidate.refColumns) === JSON.stringify(expected.refColumns)
    )
    assert.ok(
      fk,
      `FK manquante : ${expected.table}(${expected.columns.join(', ')}) -> ${expected.refTable}(${expected.refColumns.join(', ')})`
    )
    if (expected.onDelete) equal(fk.onDelete, expected.onDelete, `ON DELETE de ${expected.table}`)
  }
})

check('D2 — aucune clé étrangère inattendue', () => {
  const actual = []
  for (const table of schema.tables.values()) {
    for (const fk of table.fks) {
      actual.push(`${table.name}(${fk.columns.join(',')})->${fk.refTable}(${fk.refColumns.join(',')})`)
    }
  }
  const expected = EXPECTED_FKS.map(
    (fk) => `${fk.table}(${fk.columns.join(',')})->${fk.refTable}(${fk.refColumns.join(',')})`
  )
  equal(actual.sort(), expected.sort(), 'ensemble des clés étrangères')
})

check('D3 — chaque colonne locale d\'une FK existe et chaque cible est unique', () => {
  for (const table of schema.tables.values()) {
    for (const fk of table.fks) {
      for (const column of fk.columns) {
        assert.ok(table.columns.has(column), `${table.name} : colonne FK inconnue ${column}`)
      }
      const ref = schema.tables.get(fk.refTable)
      assert.ok(ref, `${table.name} : table cible inconnue ${fk.refTable}`)
      for (const column of fk.refColumns) {
        assert.ok(ref.columns.has(column), `${table.name} : colonne cible inconnue ${fk.refTable}.${column}`)
      }
      const covered = uniqueColumnSets(ref).some(
        (cols) => JSON.stringify(cols) === JSON.stringify(fk.refColumns)
      )
      assert.ok(
        covered,
        `${table.name} : la cible ${fk.refTable}(${fk.refColumns.join(', ')}) n'est couverte par aucune PK/UNIQUE`
      )
    }
  }
})

// ---------------------------------------------------------------------------
// Bloc E — contraintes CHECK
// ---------------------------------------------------------------------------

const checkText = (table) => schema.tables.get(table).checks.join(' | ')

check('E1 — CHECK (revision >= 1) sur toutes les tables versionnées', () => {
  for (const table of REVISION_TABLES) {
    assert.ok(schema.tables.get(table).columns.has('revision'), `${table} : colonne revision absente`)
    assert.match(checkText(table), /revision\s*>=\s*1/, `${table} : CHECK revision >= 1 manquante`)
  }
})

check('E2 — CHECK subject_type IN (principal, service_identity) sur les memberships', () => {
  for (const table of MEMBERSHIP_TABLES) {
    const checks = checkText(table)
    assert.match(checks, /subject_type\s+IN\s*\(\s*'principal'\s*,\s*'service_identity'\s*\)/i, `${table} : CHECK subject_type manquante`)
  }
})

check('E3 — aucun CHECK revision sur une table sans colonne revision', () => {
  for (const table of schema.tables.values()) {
    if (!table.columns.has('revision')) {
      assert.ok(!/revision\s*>=/.test(table.checks.join(' ')), `${table.name} : CHECK revision inattendu`)
    }
  }
})

// ---------------------------------------------------------------------------
// Bloc F — defaults, triggers, fonction
// ---------------------------------------------------------------------------

check('F1 — organization_id est NOT NULL avec DEFAULT \'default\' sur toutes les tables', () => {
  for (const table of schema.tables.values()) {
    const column = table.columns.get('organization_id')
    assert.ok(column, `${table.name} : organization_id absente`)
    assert.ok(column.notNull, `${table.name} : organization_id doit être NOT NULL`)
    assert.equal(column.defaultRaw, "'default'", `${table.name} : DEFAULT 'default' attendu`)
  }
})

check('F2 — revision DEFAULT 1 et payload DEFAULT adaptés', () => {
  for (const table of REVISION_TABLES) {
    const revision = schema.tables.get(table).columns.get('revision')
    assert.ok(revision.hasDefault, `${table} : revision doit avoir un DEFAULT`)
    equal(revision.defaultRaw, '1', `${table} : DEFAULT revision`)
    const payload = schema.tables.get(table).columns.get('payload')
    assert.ok(payload.hasDefault, `${table} : payload doit avoir un DEFAULT`)
  }
  const roles = schema.tables.get('roles')
  equal(roles.columns.get('permissions').defaultRaw, "'[]'::jsonb", 'roles.permissions DEFAULT')
  equal(roles.columns.get('version').defaultRaw, "'v1'", 'roles.version DEFAULT')
})

check('F3 — fonction set_updated_at définie une seule fois', () => {
  assert.ok(schema.functions.has('set_updated_at'), 'fonction set_updated_at absente')
  equal([...schema.functions], ['set_updated_at'], 'fonctions définies')
})

check('F4 — un trigger BEFORE UPDATE set_updated_at par table avec updated_at', () => {
  const triggerTables = schema.triggers.map((trigger) => trigger.table).sort()
  equal(triggerTables, [...UPDATED_AT_TABLES].sort(), 'tables portant un trigger updated_at')
  for (const trigger of schema.triggers) {
    equal(trigger.fn, 'set_updated_at', `fonction du trigger ${trigger.name}`)
    assert.match(trigger.name, /updated_at$/, `nom du trigger ${trigger.name}`)
  }
  const names = schema.triggers.map((t) => t.name)
  equal(new Set(names).size, names.length, 'noms de triggers uniques')
})

check('F5 — chaque table avec updated_at a une colonne updated_at TIMESTAMPTZ', () => {
  for (const table of UPDATED_AT_TABLES) {
    const column = schema.tables.get(table).columns.get('updated_at')
    assert.ok(column, `${table} : updated_at absente`)
    equal(column.type, 'TIMESTAMPTZ', `${table}.updated_at type`)
  }
  for (const table of MEMBERSHIP_TABLES.concat('workstream_repositories')) {
    assert.ok(!schema.tables.get(table).columns.has('updated_at'), `${table} : updated_at inattendue`)
  }
})

// ---------------------------------------------------------------------------
// Bloc G — scénarios d'intégrité de clés (isolation tenant)
// ---------------------------------------------------------------------------

check('G1 — les FK de membership incluent organization_id (pas de rôle inter-tenant)', () => {
  for (const table of MEMBERSHIP_TABLES) {
    const parsed = schema.tables.get(table)
    for (const fk of parsed.fks) {
      assert.ok(fk.columns.includes('organization_id'), `${table} : FK ${fk.name} sans organization_id`)
      assert.ok(fk.refColumns.includes('organization_id'), `${table} : FK ${fk.name} cible sans organization_id`)
    }
  }
})

check('G2 — les FK structurelles alignent workstream_id / squad_id sur la cible', () => {
  const structural = schema.tables.get('squad_memberships').fks.find((fk) => fk.refTable === 'squads')
  equal(structural.columns, ['organization_id', 'workstream_id', 'squad_id'], 'squad_memberships -> squads')
  const wr = schema.tables.get('workstream_repositories').fks
  equal(wr.find((fk) => fk.refTable === 'workstreams').columns, ['organization_id', 'workstream_id'], 'wr -> workstreams')
  equal(wr.find((fk) => fk.refTable === 'repositories').columns, ['organization_id', 'repository_id'], 'wr -> repositories')
})

check('G3 — suppression en cascade des enfants structurels (organizations/workstreams/squads/repositories)', () => {
  const cascade = schema.tables.get('workstream_repositories').fks
  assert.ok(cascade.every((fk) => fk.onDelete === 'CASCADE'), 'workstream_repositories : ON DELETE CASCADE attendu')
  assert.equal(schema.tables.get('squads').fks.find((fk) => fk.refTable === 'workstreams').onDelete, 'CASCADE')
  assert.equal(schema.tables.get('squad_memberships').fks.find((fk) => fk.refTable === 'squads').onDelete, 'CASCADE')
})

check('G4 — aucun FK de rôle ne peut cibler une autre organisation (clé composite complète)', () => {
  for (const table of MEMBERSHIP_TABLES) {
    const roleFk = schema.tables.get(table).fks.find((fk) => fk.refTable === 'roles')
    assert.ok(roleFk, `${table} : FK vers roles absente`)
    equal(roleFk.refColumns, ['organization_id', 'role_id', 'version'], `${table} -> roles`)
    assert.ok(roleFk.columns.includes('organization_id'), `${table} : FK roles sans organization_id`)
  }
})

// ---------------------------------------------------------------------------
// Résultat
// ---------------------------------------------------------------------------

console.log(`\nRésultat : ${passed} passé(s), ${failed} échoué(s)`)
process.exit(failed === 0 ? 0 : 1)
