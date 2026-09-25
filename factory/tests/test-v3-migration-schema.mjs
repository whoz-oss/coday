/**
 * Validation hors-ligne de la migration Flyway V3 (workflow core extension).
 *
 * Le fichier `factory/infra/migrations/V3__workflow_core.sql` étend le schéma du
 * Jalon B2 (Tâche B2-T2). Ce test lit **V1 + V2 + V3**, les parse sans dépendance
 * externe (aucun PostgreSQL, Docker ou driver `pg` requis) et vérifie l'état
 * cumulé du schéma :
 *
 *   Bloc A — propreté syntaxique du SQL (parenthèses équilibrées, terminaux,
 *            dollar-quoting de la fonction trigger).
 *   Bloc B — tables créées et colonnes ajoutées par V3 (workflow_definitions +
 *            les 4 nouvelles tables workflow core).
 *   Bloc C — clés primaires (et clés uniques explicites des cibles de FK).
 *   Bloc D — clés étrangères composites, actions ON DELETE et intégrité : chaque
 *            ensemble de colonnes référencé doit être couvert par une PK/UNIQUE
 *            de la table cible.
 *   Bloc E — contraintes CHECK (visibility, revision >= 1).
 *   Bloc F — valeurs par défaut, fonction `set_updated_at()` et triggers
 *            `updated_at`.
 *   Bloc G — garanties d'isolation tenant (Amendment 8 : FK composites).
 *
 * Usage : node factory/tests/test-v3-migration-schema.mjs
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
]
const V3_FILE = 'V3__workflow_core.sql'

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

/** Construit le modèle de schéma cumulé V1 + V2 + V3. */
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

    // V3 extends an existing table via ALTER TABLE ADD COLUMN / ADD CONSTRAINT.
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
// Spécification attendue pour V3 (état cumulé V1 + V2 + V3)
// ---------------------------------------------------------------------------

const NEW_TABLES = [
  'workflow_definition_versions',
  'workstream_workflow_grants',
  'workflow_step_states',
  'workflow_transitions',
]

const EXPECTED_COLUMNS = {
  workflow_definitions: {
    organization_id: { type: 'VARCHAR(255)', notNull: true },
    workstream_id: { type: 'VARCHAR(255)', notNull: false },
    workflow_type: { type: 'VARCHAR(255)', notNull: true },
    version: { type: 'VARCHAR(64)', notNull: true },
    definition_hash: { type: 'VARCHAR(64)', notNull: true },
    definition_json: { type: 'JSONB', notNull: true },
    created_at: { type: 'TIMESTAMPTZ', notNull: true },
    visibility: { type: 'VARCHAR(32)', notNull: true },
    owner_workstream_id: { type: 'VARCHAR(255)', notNull: false },
  },
  workflow_definition_versions: {
    organization_id: { type: 'VARCHAR(255)', notNull: true },
    workflow_type: { type: 'VARCHAR(255)', notNull: true },
    version: { type: 'VARCHAR(64)', notNull: true },
    revision: { type: 'INTEGER', notNull: true },
    definition_hash: { type: 'VARCHAR(64)', notNull: false },
    definition_json: { type: 'JSONB', notNull: true },
    created_at: { type: 'TIMESTAMPTZ', notNull: true },
    updated_at: { type: 'TIMESTAMPTZ', notNull: true },
  },
  workstream_workflow_grants: {
    organization_id: { type: 'VARCHAR(255)', notNull: true },
    workstream_id: { type: 'VARCHAR(255)', notNull: true },
    workflow_type: { type: 'VARCHAR(255)', notNull: true },
    version: { type: 'VARCHAR(64)', notNull: false },
    enabled: { type: 'BOOLEAN', notNull: true },
    configuration: { type: 'JSONB', notNull: true },
    created_at: { type: 'TIMESTAMPTZ', notNull: true },
    updated_at: { type: 'TIMESTAMPTZ', notNull: true },
  },
  workflow_step_states: {
    organization_id: { type: 'VARCHAR(255)', notNull: true },
    workstream_id: { type: 'VARCHAR(255)', notNull: true },
    namespace_id: { type: 'VARCHAR(255)', notNull: true },
    workflow_id: { type: 'VARCHAR(255)', notNull: true },
    step_id: { type: 'VARCHAR(255)', notNull: true },
    revision: { type: 'INTEGER', notNull: true },
    status: { type: 'VARCHAR(64)', notNull: true },
    payload: { type: 'JSONB', notNull: true },
    created_at: { type: 'TIMESTAMPTZ', notNull: true },
    updated_at: { type: 'TIMESTAMPTZ', notNull: true },
  },
  workflow_transitions: {
    organization_id: { type: 'VARCHAR(255)', notNull: true },
    workstream_id: { type: 'VARCHAR(255)', notNull: true },
    namespace_id: { type: 'VARCHAR(255)', notNull: true },
    workflow_id: { type: 'VARCHAR(255)', notNull: true },
    transition_id: { type: 'VARCHAR(255)', notNull: true },
    from_step_id: { type: 'VARCHAR(255)', notNull: false },
    to_step_id: { type: 'VARCHAR(255)', notNull: true },
    event_name: { type: 'VARCHAR(255)', notNull: true },
    payload: { type: 'JSONB', notNull: true },
    created_at: { type: 'TIMESTAMPTZ', notNull: true },
  },
}

const EXPECTED_PKS = {
  workflow_definitions: ['organization_id', 'workflow_type', 'version'],
  workflow_definition_versions: ['organization_id', 'workflow_type', 'version', 'revision'],
  workstream_workflow_grants: ['organization_id', 'workstream_id', 'workflow_type'],
  workflow_step_states: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id', 'step_id'],
  workflow_transitions: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id', 'transition_id'],
}

const INSTANCE_KEY = ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id']

const EXPECTED_FKS = [
  {
    table: 'workflow_definition_versions',
    columns: ['organization_id', 'workflow_type', 'version'],
    refTable: 'workflow_definitions',
    refColumns: ['organization_id', 'workflow_type', 'version'],
    onDelete: 'CASCADE',
  },
  {
    table: 'workstream_workflow_grants',
    columns: ['organization_id', 'workstream_id'],
    refTable: 'workstreams',
    refColumns: ['organization_id', 'workstream_id'],
    onDelete: 'CASCADE',
  },
  {
    table: 'workflow_step_states',
    columns: INSTANCE_KEY,
    refTable: 'workflow_instances',
    refColumns: INSTANCE_KEY,
    onDelete: 'CASCADE',
  },
  {
    table: 'workflow_transitions',
    columns: INSTANCE_KEY,
    refTable: 'workflow_instances',
    refColumns: INSTANCE_KEY,
    onDelete: 'CASCADE',
  },
]

const REVISION_TABLES = ['workflow_definition_versions', 'workflow_step_states']

const UPDATED_AT_TABLES = ['workflow_definition_versions', 'workstream_workflow_grants', 'workflow_step_states']

// Colonnes `workstream_id`/`namespace_id` portées par les nouvelles tables : elles
// doivent être NOT NULL pour que la FK composite soit un vrai discriminant tenant.
const TENANT_SCOPED_TABLES = [
  'workflow_definitions',
  'workflow_definition_versions',
  'workstream_workflow_grants',
  'workflow_step_states',
  'workflow_transitions',
]

// ---------------------------------------------------------------------------
// Chargement + parsing cumulé
// ---------------------------------------------------------------------------

const rawFiles = new Map(
  SQL_FILES.map((file) => [file, readFileSync(join(MIGRATIONS, file), 'utf8')])
)
const rawSql = SQL_FILES.map((file) => rawFiles.get(file)).join('\n')
const cleanSql = stripComments(rawSql)
const schema = parseSchema(rawSql)

// ---------------------------------------------------------------------------
// Bloc A — propreté syntaxique
// ---------------------------------------------------------------------------

check('A1 — les fichiers V1, V2 et V3 existent, sont non vides et terminent par un point-virgule', () => {
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
  // V1 (2 tables) + V2 (11 tables) + V3 (4 tables) + 3 fonctions + 11 triggers + ALTER/index.
  assert.ok(schema.statements.length >= 17 + 3 + 11, `nombre d'instructions trop faible : ${schema.statements.length}`)
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

check('A4 — la V3 réutilise le dollar-quoting / la fonction set_updated_at de V2', () => {
  const dollars = [...rawSql.matchAll(/\$\$/g)].length
  assert.ok(dollars > 0 && dollars % 2 === 0, `nombre de délimiteurs $$ impair : ${dollars}`)
  assert.match(rawSql, /CREATE\s+OR\s+REPLACE\s+FUNCTION\s+set_updated_at\s*\(\s*\)/i)
  assert.match(rawSql, /RETURNS\s+TRIGGER/i)
  assert.match(rawSql, /LANGUAGE\s+plpgsql/i)
  const v3 = rawFiles.get(V3_FILE)
  assert.match(v3, /EXECUTE\s+FUNCTION\s+set_updated_at\s*\(\)/i, 'V3 doit brancher set_updated_at()')
})

// ---------------------------------------------------------------------------
// Bloc B — tables et colonnes
// ---------------------------------------------------------------------------

check('B1 — les 4 nouvelles tables du workflow core sont créées', () => {
  for (const table of NEW_TABLES) {
    assert.ok(schema.tables.has(table), `table manquante : ${table}`)
  }
  equal(NEW_TABLES.length, 4, 'nombre de nouvelles tables')
})

check('B2 — workflow_definitions expose les colonnes ajoutées par V3', () => {
  const parsed = schema.tables.get('workflow_definitions')
  assert.ok(parsed, 'table manquante : workflow_definitions')
  assert.ok(parsed.columns.has('visibility'), 'workflow_definitions.visibility absente')
  assert.ok(parsed.columns.has('owner_workstream_id'), 'workflow_definitions.owner_workstream_id absente')
})

check('B3 — chaque table exposée a exactement les colonnes attendues (type + NOT NULL)', () => {
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
// Bloc C — clés primaires et uniques
// ---------------------------------------------------------------------------

check('C1 — chaque table workflow core possède la clé primaire composite attendue', () => {
  for (const [table, columns] of Object.entries(EXPECTED_PKS)) {
    const parsed = schema.tables.get(table)
    assert.ok(parsed, `table absente : ${table}`)
    assert.ok(parsed.pk, `${table} : PRIMARY KEY absente`)
    equal(parsed.pk.columns, columns, `PRIMARY KEY de ${table}`)
  }
})

check('C2 — la cible des FK composites porte une contrainte d\'unicité explicite ou une PK', () => {
  // `workstreams` expose un UNIQUE explicite (organization_id, workstream_id).
  const workstreams = schema.tables.get('workstreams')
  const uniqueSets = workstreams.uniques.map((u) => u.columns)
  assert.ok(
    uniqueSets.some((cols) => JSON.stringify(cols) === JSON.stringify(['organization_id', 'workstream_id'])),
    'workstreams : UNIQUE (organization_id, workstream_id) manquante'
  )
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
    equal(fk.onDelete, expected.onDelete, `ON DELETE de ${expected.table} -> ${expected.refTable}`)
  }
})

check('D2 — les nouvelles tables n\'ont aucune clé étrangère inattendue', () => {
  const actual = []
  for (const table of NEW_TABLES) {
    for (const fk of schema.tables.get(table).fks) {
      actual.push(`${table}(${fk.columns.join(',')})->${fk.refTable}(${fk.refColumns.join(',')})`)
    }
  }
  const expected = EXPECTED_FKS.map(
    (fk) => `${fk.table}(${fk.columns.join(',')})->${fk.refTable}(${fk.refColumns.join(',')})`
  )
  equal(actual.sort(), expected.sort(), 'ensemble des clés étrangères V3')
})

check('D3 — chaque colonne locale d\'une FK existe et chaque cible est couverte par une PK/UNIQUE', () => {
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

check('E1 — CHECK visibility IN (platform, organization, workstream) sur workflow_definitions', () => {
  const checks = checkText('workflow_definitions')
  assert.match(
    checks,
    /visibility\s+IN\s*\(\s*'platform'\s*,\s*'organization'\s*,\s*'workstream'\s*\)/i,
    'workflow_definitions : CHECK visibility manquante'
  )
})

check('E2 — CHECK (revision >= 1) sur les tables versionnées de V3', () => {
  for (const table of REVISION_TABLES) {
    assert.ok(schema.tables.get(table).columns.has('revision'), `${table} : colonne revision absente`)
    assert.match(checkText(table), /revision\s*>=\s*1/, `${table} : CHECK revision >= 1 manquante`)
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
// Bloc F — defaults, fonction et triggers
// ---------------------------------------------------------------------------

check("F1 — organization_id est NOT NULL avec DEFAULT 'default' sur les tables workflow", () => {
  for (const table of TENANT_SCOPED_TABLES) {
    const column = schema.tables.get(table).columns.get('organization_id')
    assert.ok(column, `${table} : organization_id absente`)
    assert.ok(column.notNull, `${table} : organization_id doit être NOT NULL`)
    equal(column.defaultRaw, "'default'", `${table} : DEFAULT 'default' attendu`)
  }
})

check("F2 — workstream_id DEFAULT 'default' et revision DEFAULT 1 sur les tables versionnées", () => {
  const stepStates = schema.tables.get('workflow_step_states').columns.get('workstream_id')
  assert.ok(stepStates.hasDefault, 'workflow_step_states.workstream_id doit avoir un DEFAULT')
  equal(stepStates.defaultRaw, "'default'", 'workflow_step_states.workstream_id DEFAULT')

  for (const table of REVISION_TABLES) {
    const revision = schema.tables.get(table).columns.get('revision')
    assert.ok(revision.hasDefault, `${table} : revision doit avoir un DEFAULT`)
    equal(revision.defaultRaw, '1', `${table} : DEFAULT revision`)
  }

  const versions = schema.tables.get('workflow_definition_versions')
  equal(versions.columns.get('definition_json').defaultRaw, "'{}'::jsonb", 'definition_json DEFAULT')
  const grants = schema.tables.get('workstream_workflow_grants')
  equal(grants.columns.get('enabled').defaultRaw, 'true', 'grants.enabled DEFAULT')
  equal(grants.columns.get('configuration').defaultRaw, "'{}'::jsonb", 'grants.configuration DEFAULT')
  equal(schema.tables.get('workflow_step_states').columns.get('payload').defaultRaw, "'{}'::jsonb", 'step state payload DEFAULT')
  equal(schema.tables.get('workflow_transitions').columns.get('payload').defaultRaw, "'{}'::jsonb", 'transition payload DEFAULT')
})

check('F3 — fonction set_updated_at définie (réutilisée depuis V2)', () => {
  assert.ok(schema.functions.has('set_updated_at'), 'fonction set_updated_at absente')
  assert.ok(schema.functions.has('set_workflow_instance_updated_at'), 'fonction V1 absente')
})

check('F4 — un trigger BEFORE UPDATE set_updated_at par nouvelle table avec updated_at', () => {
  const v3Triggers = schema.triggers.filter((trigger) => NEW_TABLES.includes(trigger.table))
  equal(
    v3Triggers.map((trigger) => trigger.table).sort(),
    [...UPDATED_AT_TABLES].sort(),
    'tables V3 portant un trigger updated_at'
  )
  for (const trigger of v3Triggers) {
    equal(trigger.fn, 'set_updated_at', `fonction du trigger ${trigger.name}`)
    assert.match(trigger.name, /updated_at$/, `nom du trigger ${trigger.name}`)
  }
  assert.ok(
    !v3Triggers.some((trigger) => trigger.table === 'workflow_transitions'),
    'workflow_transitions ne doit pas porter de trigger updated_at'
  )
  const names = schema.triggers.map((t) => t.name)
  equal(new Set(names).size, names.length, 'noms de triggers uniques')
})

check('F5 — chaque table avec updated_at a une colonne updated_at TIMESTAMPTZ', () => {
  for (const table of UPDATED_AT_TABLES) {
    const column = schema.tables.get(table).columns.get('updated_at')
    assert.ok(column, `${table} : updated_at absente`)
    equal(column.type, 'TIMESTAMPTZ', `${table}.updated_at type`)
  }
  for (const table of ['workflow_transitions', 'workstream_workflow_grants']) {
    const hasTrigger = schema.triggers.some((trigger) => trigger.table === table)
    if (table === 'workstream_workflow_grants') {
      assert.ok(hasTrigger, `${table} : trigger updated_at attendu`)
    } else {
      assert.ok(!schema.tables.get(table).columns.has('updated_at'), `${table} : updated_at inattendue`)
    }
  }
})

// ---------------------------------------------------------------------------
// Bloc G — garanties d'isolation tenant (Amendment 8)
// ---------------------------------------------------------------------------

check('G1 — la PK de workflow_instances est la clé composite tenant (cible des FK)', () => {
  const instances = schema.tables.get('workflow_instances')
  assert.ok(instances, 'workflow_instances absente')
  equal(instances.pk.columns, INSTANCE_KEY, 'PK workflow_instances')
})

check('G2 — les FK de workflow_step_states / workflow_transitions portent tenant + instance', () => {
  for (const table of ['workflow_step_states', 'workflow_transitions']) {
    const fk = schema.tables.get(table).fks.find((candidate) => candidate.refTable === 'workflow_instances')
    assert.ok(fk, `${table} : FK vers workflow_instances absente`)
    equal(fk.columns, INSTANCE_KEY, `${table} : colonnes FK vers workflow_instances`)
    equal(fk.refColumns, INSTANCE_KEY, `${table} : colonnes cibles FK`)
    equal(fk.onDelete, 'CASCADE', `${table} : ON DELETE`)
  }
})

check('G3 — les FK V3 incluent organization_id (pas de référence inter-tenant)', () => {
  for (const fk of EXPECTED_FKS) {
    const parsed = schema.tables.get(fk.table).fks.find((candidate) => candidate.refTable === fk.refTable)
    assert.ok(parsed, `FK ${fk.table} -> ${fk.refTable} absente`)
    assert.ok(parsed.columns.includes('organization_id'), `${fk.table} : FK sans organization_id`)
    assert.ok(parsed.refColumns.includes('organization_id'), `${fk.table} : cible sans organization_id`)
  }
})

check('G4 — workstream_workflow_grants est isolé par workstream (composite FK complète)', () => {
  const fk = schema.tables
    .get('workstream_workflow_grants')
    .fks.find((candidate) => candidate.refTable === 'workstreams')
  assert.ok(fk, 'workstream_workflow_grants : FK vers workstreams absente')
  equal(fk.columns, ['organization_id', 'workstream_id'], 'FK grants -> workstreams')
  equal(fk.refColumns, ['organization_id', 'workstream_id'], 'FK grants -> workstreams cible')
  equal(fk.onDelete, 'CASCADE', 'FK grants -> workstreams ON DELETE')
})

check('G5 — suppression en cascade de l\'instance vers les enfants workflow', () => {
  for (const table of ['workflow_step_states', 'workflow_transitions']) {
    const fks = schema.tables.get(table).fks
    assert.ok(fks.every((fk) => fk.onDelete === 'CASCADE'), `${table} : ON DELETE CASCADE attendu`)
  }
  const versionsFk = schema.tables
    .get('workflow_definition_versions')
    .fks.find((fk) => fk.refTable === 'workflow_definitions')
  assert.ok(versionsFk, 'workflow_definition_versions : FK vers workflow_definitions absente')
  equal(versionsFk.onDelete, 'CASCADE', 'workflow_definition_versions -> workflow_definitions ON DELETE')
})

// ---------------------------------------------------------------------------
// Résultat
// ---------------------------------------------------------------------------

console.log(`\nRésultat : ${passed} passé(s), ${failed} échoué(s)`)
process.exit(failed === 0 ? 0 : 1)
