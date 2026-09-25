/**
 * Validation hors-ligne de la migration Flyway V5 (evidence & human interaction).
 *
 * Le fichier `factory/infra/migrations/V5__evidence_and_interaction.sql` ajoute
 * les trois tables du control plane preuve & interaction humaine du Jalon B2
 * (Tâche B2-T4). Ce test lit **V1 + V2 + V3 + V4 + V5**, les parse sans
 * dépendance externe (aucun PostgreSQL, Docker ou driver `pg` requis) et vérifie
 * l'état cumulé du schéma :
 *
 *   Bloc A — propreté syntaxique du SQL (parenthèses équilibrées, terminaux,
 *            dollar-quoting de la fonction trigger).
 *   Bloc B — tables créées par V5 et colonnes attendues (type + NOT NULL).
 *   Bloc C — clés primaires composites tenant-scoped et clés étrangères
 *            composites pointant sur la PK de la table référencée (isolation
 *            tenant / rejet des orphelins et des références inter-tenant).
 *   Bloc D — index de support de chaque table.
 *   Bloc E — contraintes CHECK (`revision >= 1`,
 *            `status IN ('waiting', 'answered', 'closed')`).
 *   Bloc F — nature append-only : `workflow_evidence` et
 *            `human_interaction_events` n'ont ni colonne `updated_at` ni trigger ;
 *            `human_interactions` porte `trg_human_interactions_updated_at`
 *            appelant `set_updated_at()`.
 *   Bloc G — isolation tenant (`organization_id NOT NULL DEFAULT 'default'`).
 *
 * Usage : node factory/tests/test-v5-migration-schema.mjs
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
  'V5__evidence_and_interaction.sql',
]
const V5_FILE = 'V5__evidence_and_interaction.sql'

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

/** Construit le modèle de schéma cumulé V1 + V2 + V3 + V4 + V5. */
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

    // V3/V4/V5 may extend an existing table via ALTER TABLE ADD COLUMN / ADD CONSTRAINT.
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
// Spécification attendue pour V5 (état cumulé V1 + V2 + V3 + V4 + V5)
// ---------------------------------------------------------------------------

const NEW_TABLES = ['workflow_evidence', 'human_interactions', 'human_interaction_events']

const EXPECTED_COLUMNS = {
  workflow_evidence: {
    organization_id: { type: 'VARCHAR(255)', notNull: true },
    workstream_id: { type: 'VARCHAR(255)', notNull: true },
    namespace_id: { type: 'VARCHAR(255)', notNull: true },
    workflow_id: { type: 'VARCHAR(255)', notNull: true },
    evidence_id: { type: 'VARCHAR(255)', notNull: true },
    evidence_type: { type: 'VARCHAR(255)', notNull: true },
    source: { type: 'VARCHAR(255)', notNull: true },
    producer: { type: 'VARCHAR(255)', notNull: true },
    payload: { type: 'JSONB', notNull: true },
    created_at: { type: 'TIMESTAMPTZ', notNull: true },
  },
  human_interactions: {
    organization_id: { type: 'VARCHAR(255)', notNull: true },
    workstream_id: { type: 'VARCHAR(255)', notNull: true },
    namespace_id: { type: 'VARCHAR(255)', notNull: true },
    workflow_id: { type: 'VARCHAR(255)', notNull: true },
    interaction_id: { type: 'VARCHAR(255)', notNull: true },
    interaction_type: { type: 'VARCHAR(255)', notNull: true },
    status: { type: 'VARCHAR(64)', notNull: true },
    revision: { type: 'INTEGER', notNull: true },
    payload: { type: 'JSONB', notNull: true },
    created_at: { type: 'TIMESTAMPTZ', notNull: true },
    updated_at: { type: 'TIMESTAMPTZ', notNull: true },
  },
  human_interaction_events: {
    organization_id: { type: 'VARCHAR(255)', notNull: true },
    workstream_id: { type: 'VARCHAR(255)', notNull: true },
    namespace_id: { type: 'VARCHAR(255)', notNull: true },
    workflow_id: { type: 'VARCHAR(255)', notNull: true },
    interaction_id: { type: 'VARCHAR(255)', notNull: true },
    event_id: { type: 'VARCHAR(255)', notNull: true },
    event_type: { type: 'VARCHAR(255)', notNull: true },
    actor_id: { type: 'VARCHAR(255)', notNull: true },
    payload: { type: 'JSONB', notNull: true },
    created_at: { type: 'TIMESTAMPTZ', notNull: true },
  },
}

const EXPECTED_PKS = {
  workflow_evidence: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id', 'evidence_id'],
  human_interactions: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id', 'interaction_id'],
  human_interaction_events: [
    'organization_id',
    'workstream_id',
    'namespace_id',
    'workflow_id',
    'interaction_id',
    'event_id',
  ],
}

const EXPECTED_FKS = {
  workflow_evidence: [
    {
      refTable: 'workflow_instances',
      columns: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id'],
    },
  ],
  human_interactions: [
    {
      refTable: 'workflow_instances',
      columns: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id'],
    },
  ],
  human_interaction_events: [
    {
      refTable: 'human_interactions',
      columns: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id', 'interaction_id'],
    },
  ],
}

const EXPECTED_INDEXES = {
  idx_workflow_evidence_instance: {
    table: 'workflow_evidence',
    columns: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id', 'created_at'],
  },
  idx_human_interactions_instance: {
    table: 'human_interactions',
    columns: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id', 'status'],
  },
  idx_human_interaction_events_interaction: {
    table: 'human_interaction_events',
    columns: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id', 'interaction_id', 'created_at'],
  },
}

const TENANT_SCOPED_TABLES = NEW_TABLES

// Tables strictement append-only : aucune colonne `updated_at`, aucun trigger.
const APPEND_ONLY_TABLES = ['workflow_evidence', 'human_interaction_events']

// ---------------------------------------------------------------------------
// Chargement + parsing cumulé
// ---------------------------------------------------------------------------

const rawFiles = new Map(SQL_FILES.map((file) => [file, readFileSync(join(MIGRATIONS, file), 'utf8')]))
const rawSql = SQL_FILES.map((file) => rawFiles.get(file)).join('\n')
const cleanSql = stripComments(rawSql)
const schema = parseSchema(rawSql)
const v5Statements = splitStatements(stripComments(rawFiles.get(V5_FILE)))

// ---------------------------------------------------------------------------
// Bloc A — propreté syntaxique
// ---------------------------------------------------------------------------

check('A1 — les fichiers V1..V5 existent, sont non vides et terminent par un point-virgule', () => {
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
  // V1 + V2 + V3 + V4 (>= 31 instructions) + V5 (3 tables + 3 index + DROP + trigger).
  assert.ok(schema.statements.length >= 31 + 8, `nombre d'instructions trop faible : ${schema.statements.length}`)
  equal(v5Statements.length, 8, 'V5 : nombre d\'instructions')
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

check("A4 — la V5 réutilise le dollar-quoting / la fonction set_updated_at de V2", () => {
  const dollars = [...rawSql.matchAll(/\$\$/g)].length
  assert.ok(dollars > 0 && dollars % 2 === 0, `nombre de délimiteurs $$ impair : ${dollars}`)
  assert.match(rawSql, /CREATE\s+OR\s+REPLACE\s+FUNCTION\s+set_updated_at\s*\(\s*\)/i)
  assert.match(rawSql, /RETURNS\s+TRIGGER/i)
  assert.match(rawSql, /LANGUAGE\s+plpgsql/i)
  const v5 = rawFiles.get(V5_FILE)
  assert.match(v5, /EXECUTE\s+FUNCTION\s+set_updated_at\s*\(\)/i, 'V5 doit brancher set_updated_at()')
})

check('A5 — la V5 ne redéclare pas la fonction set_updated_at', () => {
  const v5 = stripComments(rawFiles.get(V5_FILE))
  assert.doesNotMatch(v5, /CREATE\s+(?:OR\s+REPLACE\s+)?FUNCTION\s+set_updated_at/i)
})

// ---------------------------------------------------------------------------
// Bloc B — tables et colonnes
// ---------------------------------------------------------------------------

check('B1 — les 3 nouvelles tables V5 sont créées', () => {
  for (const table of NEW_TABLES) {
    assert.ok(schema.tables.has(table), `table manquante : ${table}`)
  }
  equal(NEW_TABLES.length, 3, 'nombre de nouvelles tables')
})

check('B2 — chaque table V5 expose exactement les colonnes attendues (type + NOT NULL)', () => {
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

check("B3 — valeurs par défaut de workflow_evidence (tenant, payload JSONB, created_at)", () => {
  const table = schema.tables.get('workflow_evidence')
  equal(table.columns.get('organization_id').defaultRaw, "'default'", 'workflow_evidence.organization_id DEFAULT')
  equal(table.columns.get('workstream_id').defaultRaw, "'default'", 'workflow_evidence.workstream_id DEFAULT')
  equal(table.columns.get('payload').defaultRaw, "'{}'::jsonb", 'workflow_evidence.payload DEFAULT')
  equal(table.columns.get('created_at').defaultRaw, 'CURRENT_TIMESTAMP', 'workflow_evidence.created_at DEFAULT')
})

check('B4 — valeurs par défaut de human_interactions (status, revision, timestamps)', () => {
  const table = schema.tables.get('human_interactions')
  equal(table.columns.get('organization_id').defaultRaw, "'default'", 'human_interactions.organization_id DEFAULT')
  equal(table.columns.get('workstream_id').defaultRaw, "'default'", 'human_interactions.workstream_id DEFAULT')
  equal(table.columns.get('status').defaultRaw, "'waiting'", 'human_interactions.status DEFAULT')
  equal(table.columns.get('revision').defaultRaw, '1', 'human_interactions.revision DEFAULT')
  equal(table.columns.get('payload').defaultRaw, "'{}'::jsonb", 'human_interactions.payload DEFAULT')
  equal(table.columns.get('created_at').defaultRaw, 'CURRENT_TIMESTAMP', 'human_interactions.created_at DEFAULT')
  equal(table.columns.get('updated_at').defaultRaw, 'CURRENT_TIMESTAMP', 'human_interactions.updated_at DEFAULT')
})

check("B5 — valeurs par défaut de human_interaction_events (tenant, payload JSONB, created_at)", () => {
  const table = schema.tables.get('human_interaction_events')
  equal(table.columns.get('organization_id').defaultRaw, "'default'", 'human_interaction_events.organization_id DEFAULT')
  equal(table.columns.get('workstream_id').defaultRaw, "'default'", 'human_interaction_events.workstream_id DEFAULT')
  equal(table.columns.get('payload').defaultRaw, "'{}'::jsonb", 'human_interaction_events.payload DEFAULT')
  equal(table.columns.get('created_at').defaultRaw, 'CURRENT_TIMESTAMP', 'human_interaction_events.created_at DEFAULT')
})

// ---------------------------------------------------------------------------
// Bloc C — clés primaires et clés étrangères composites
// ---------------------------------------------------------------------------

check('C1 — chaque table V5 possède la clé primaire composite tenant-scoped attendue', () => {
  for (const [table, columns] of Object.entries(EXPECTED_PKS)) {
    const parsed = schema.tables.get(table)
    assert.ok(parsed, `table absente : ${table}`)
    assert.ok(parsed.pk, `${table} : PRIMARY KEY absente`)
    equal(parsed.pk.columns, columns, `PRIMARY KEY de ${table}`)
  }
})

check('C2 — les clés étrangères composites V5 existent et portent ON DELETE CASCADE', () => {
  for (const [table, expected] of Object.entries(EXPECTED_FKS)) {
    const parsed = schema.tables.get(table)
    assert.ok(parsed, `table absente : ${table}`)
    equal(parsed.fks.length, expected.length, `nombre de FK de ${table}`)
    for (const spec of expected) {
      const fk = parsed.fks.find((candidate) => candidate.refTable === spec.refTable)
      assert.ok(fk, `${table} : FK vers ${spec.refTable} absente`)
      equal(fk.columns, spec.columns, `${table} : colonnes de la FK vers ${spec.refTable}`)
      equal(fk.onDelete, 'CASCADE', `${table} : ON DELETE de la FK vers ${spec.refTable}`)
    }
  }
})

check('C3 — chaque FK cible exactement la PK de la table référencée (rejet des orphelins / inter-tenant)', () => {
  for (const [table, expected] of Object.entries(EXPECTED_FKS)) {
    const parsed = schema.tables.get(table)
    for (const spec of expected) {
      const fk = parsed.fks.find((candidate) => candidate.refTable === spec.refTable)
      const target = schema.tables.get(spec.refTable)
      assert.ok(target, `table référencée absente : ${spec.refTable}`)
      assert.ok(target.pk, `${spec.refTable} : PRIMARY KEY absente`)
      equal(fk.refColumns, target.pk.columns, `${table} → ${spec.refTable} : colonnes référencées`)
      equal(fk.columns, fk.refColumns, `${table} → ${spec.refTable} : arité de la FK`)
    }
  }
})

check("C4 — l'isolation tenant est portée par la tête de chaque FK (organization_id en premier)", () => {
  for (const [table, expected] of Object.entries(EXPECTED_FKS)) {
    const parsed = schema.tables.get(table)
    for (const fk of parsed.fks) {
      equal(fk.columns[0], 'organization_id', `${table} : première colonne de la FK vers ${fk.refTable}`)
      assert.ok(fk.columns.includes('workstream_id'), `${table} : workstream_id requis dans la FK`)
      assert.ok(fk.columns.includes('namespace_id'), `${table} : namespace_id requis dans la FK`)
      assert.ok(fk.columns.includes('workflow_id'), `${table} : workflow_id requis dans la FK`)
    }
  }
})

// ---------------------------------------------------------------------------
// Bloc D — index de support
// ---------------------------------------------------------------------------

check('D1 — les 3 index de support V5 existent et couvrent les colonnes attendues', () => {
  for (const [name, spec] of Object.entries(EXPECTED_INDEXES)) {
    const index = schema.indexes.find((candidate) => candidate.name === name)
    assert.ok(index, `index ${name} absent`)
    equal(index.table, spec.table, `table de ${name}`)
    equal(index.columns, spec.columns, `colonnes de ${name}`)
    equal(index.unique, false, `${name} ne doit pas être unique`)
  }
})

check("D2 — les index de support sont tenant-scopés (organization_id en tête)", () => {
  for (const name of Object.keys(EXPECTED_INDEXES)) {
    const index = schema.indexes.find((candidate) => candidate.name === name)
    equal(index.columns[0], 'organization_id', `première colonne de ${name}`)
  }
})

// ---------------------------------------------------------------------------
// Bloc E — contraintes CHECK
// ---------------------------------------------------------------------------

const checkText = (table) => schema.tables.get(table).checks.join(' | ')

check('E1 — CHECK (revision >= 1) sur human_interactions', () => {
  assert.match(checkText('human_interactions'), /revision\s*>=\s*1/, 'human_interactions : CHECK revision >= 1 manquante')
})

check("E2 — CHECK status IN ('waiting', 'answered', 'closed') sur human_interactions", () => {
  assert.match(
    checkText('human_interactions'),
    /status\s+IN\s*\(\s*'waiting'\s*,\s*'answered'\s*,\s*'closed'\s*\)/i,
    'human_interactions : CHECK status manquante'
  )
})

check('E3 — les tables append-only ne portent aucune contrainte CHECK parasite', () => {
  equal(schema.tables.get('workflow_evidence').checks, [], 'workflow_evidence : aucune CHECK attendue')
  equal(schema.tables.get('human_interaction_events').checks, [], 'human_interaction_events : aucune CHECK attendue')
})

// ---------------------------------------------------------------------------
// Bloc F — nature append-only & trigger updated_at
// ---------------------------------------------------------------------------

check('F1 — les tables append-only V5 n\'ont pas de colonne updated_at', () => {
  for (const table of APPEND_ONLY_TABLES) {
    const parsed = schema.tables.get(table)
    assert.ok(parsed, `table absente : ${table}`)
    assert.ok(!parsed.columns.has('updated_at'), `${table} ne doit pas porter de colonne updated_at`)
  }
})

check('F2 — les tables append-only V5 ne portent aucun trigger', () => {
  for (const table of APPEND_ONLY_TABLES) {
    assert.ok(
      !schema.triggers.some((trigger) => trigger.table === table),
      `${table} ne doit pas porter de trigger`
    )
  }
})

check('F3 — human_interactions porte le trigger trg_human_interactions_updated_at appelant set_updated_at()', () => {
  const triggers = schema.triggers.filter((trigger) => trigger.table === 'human_interactions')
  equal(triggers.length, 1, 'nombre de triggers sur human_interactions')
  equal(triggers[0].name, 'trg_human_interactions_updated_at', 'nom du trigger')
  equal(triggers[0].fn, 'set_updated_at', `fonction du trigger ${triggers[0].name}`)
})

check('F4 — les noms de triggers restent uniques dans le schéma cumulé', () => {
  const names = schema.triggers.map((trigger) => trigger.name)
  equal(new Set(names).size, names.length, 'noms de triggers uniques')
})

// ---------------------------------------------------------------------------
// Bloc G — isolation tenant
// ---------------------------------------------------------------------------

check("G1 — organization_id est NOT NULL avec DEFAULT 'default' sur les tables V5", () => {
  for (const table of TENANT_SCOPED_TABLES) {
    const column = schema.tables.get(table).columns.get('organization_id')
    assert.ok(column, `${table} : organization_id absente`)
    assert.ok(column.notNull, `${table} : organization_id doit être NOT NULL`)
    equal(column.defaultRaw, "'default'", `${table} : DEFAULT 'default' attendu`)
  }
})

check('G2 — les clés primaires incluent organization_id (pas de collision inter-tenant)', () => {
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
