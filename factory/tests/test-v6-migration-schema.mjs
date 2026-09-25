/**
 * Validation hors-ligne de la migration Flyway V6 (artifacts, oracle,
 * agent-step attempts & réservation worker/environment).
 *
 * Le fichier `factory/infra/migrations/V6__artifacts_oracle_agentstep.sql` ajoute
 * les tables du dernier lot Jalon B2 (Tâche B2-T5) : artefacts (rétention, purge,
 * legal hold), exécutions d'oracle, l'agrégat attempt d'étape agent (attempts +
 * events + results + capabilities) et les squelettes de réservation
 * worker/environment. Ce test lit **V1 + V2 + V3 + V4 + V5 + V6**, les parse
 * sans dépendance externe (aucun PostgreSQL, Docker ou driver `pg` requis) et
 * vérifie l'état cumulé du schéma :
 *
 *   Bloc A — propreté syntaxique du SQL (parenthèses équilibrées, terminaux,
 *            dollar-quoting de la fonction trigger).
 *   Bloc B — tables créées par V6 et colonnes attendues (type + NOT NULL).
 *   Bloc C — clés primaires composites tenant-scoped et clés étrangères
 *            composites pointant sur la PK de la table référencée (isolation
 *            tenant / rejet des orphelins et des références inter-tenant).
 *   Bloc D — index de support de chaque table.
 *   Bloc E — contraintes CHECK (3 dimensions orthogonales de l'artefact,
 *            anti-purge sous legal hold, statuts et `revision >= 1`).
 *   Bloc F — nature append-only vs mutable & triggers `updated_at`.
 *   Bloc G — isolation tenant (`organization_id NOT NULL DEFAULT 'default'`).
 *
 * Usage : node factory/tests/test-v6-migration-schema.mjs
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
  'V6__artifacts_oracle_agentstep.sql',
]
const V6_FILE = 'V6__artifacts_oracle_agentstep.sql'

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

/** Construit le modèle de schéma cumulé V1 + V2 + V3 + V4 + V5 + V6. */
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

    // V3/V4/V5/V6 may extend an existing table via ALTER TABLE ADD COLUMN / ADD CONSTRAINT.
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
// Spécification attendue pour V6 (état cumulé V1 + V2 + V3 + V4 + V5 + V6)
// ---------------------------------------------------------------------------

const NEW_TABLES = [
  'artifacts',
  'oracle_executions',
  'agent_step_attempts',
  'agent_step_attempt_events',
  'agent_step_results',
  'result_capabilities',
  'work_units',
  'work_environments',
  'workers',
  'work_unit_leases',
]

const EXPECTED_COLUMNS = {
  artifacts: {
    organization_id: { type: 'VARCHAR(255)', notNull: true },
    workstream_id: { type: 'VARCHAR(255)', notNull: true },
    namespace_id: { type: 'VARCHAR(255)', notNull: true },
    workflow_id: { type: 'VARCHAR(255)', notNull: true },
    artifact_id: { type: 'VARCHAR(255)', notNull: true },
    availability_status: { type: 'VARCHAR(64)', notNull: true },
    retention_status: { type: 'VARCHAR(64)', notNull: true },
    legal_hold: { type: 'BOOLEAN', notNull: true },
    retention_until: { type: 'TIMESTAMPTZ', notNull: false },
    purged_at: { type: 'TIMESTAMPTZ', notNull: false },
    purge_reason: { type: 'TEXT', notNull: false },
    legal_hold_reason: { type: 'TEXT', notNull: false },
    legal_hold_set_at: { type: 'TIMESTAMPTZ', notNull: false },
    content_hash: { type: 'VARCHAR(255)', notNull: true },
    size: { type: 'BIGINT', notNull: true },
    content_type: { type: 'VARCHAR(255)', notNull: true },
    storage_key: { type: 'VARCHAR(1024)', notNull: true },
    payload: { type: 'JSONB', notNull: true },
    created_at: { type: 'TIMESTAMPTZ', notNull: true },
    updated_at: { type: 'TIMESTAMPTZ', notNull: true },
  },
  oracle_executions: {
    organization_id: { type: 'VARCHAR(255)', notNull: true },
    workstream_id: { type: 'VARCHAR(255)', notNull: true },
    namespace_id: { type: 'VARCHAR(255)', notNull: true },
    workflow_id: { type: 'VARCHAR(255)', notNull: true },
    execution_id: { type: 'VARCHAR(255)', notNull: true },
    oracle_id: { type: 'VARCHAR(255)', notNull: true },
    status: { type: 'VARCHAR(64)', notNull: true },
    revision: { type: 'INTEGER', notNull: true },
    evidence_id: { type: 'VARCHAR(255)', notNull: false },
    artifact_id: { type: 'VARCHAR(255)', notNull: false },
    payload: { type: 'JSONB', notNull: true },
    created_at: { type: 'TIMESTAMPTZ', notNull: true },
    updated_at: { type: 'TIMESTAMPTZ', notNull: true },
  },
  agent_step_attempts: {
    organization_id: { type: 'VARCHAR(255)', notNull: true },
    workstream_id: { type: 'VARCHAR(255)', notNull: true },
    namespace_id: { type: 'VARCHAR(255)', notNull: true },
    workflow_id: { type: 'VARCHAR(255)', notNull: true },
    step_id: { type: 'VARCHAR(255)', notNull: true },
    attempt_id: { type: 'VARCHAR(255)', notNull: true },
    agent_id: { type: 'VARCHAR(255)', notNull: true },
    status: { type: 'VARCHAR(64)', notNull: true },
    revision: { type: 'INTEGER', notNull: true },
    idempotency_key: { type: 'VARCHAR(255)', notNull: false },
    payload: { type: 'JSONB', notNull: true },
    created_at: { type: 'TIMESTAMPTZ', notNull: true },
    updated_at: { type: 'TIMESTAMPTZ', notNull: true },
  },
  agent_step_attempt_events: {
    organization_id: { type: 'VARCHAR(255)', notNull: true },
    workstream_id: { type: 'VARCHAR(255)', notNull: true },
    namespace_id: { type: 'VARCHAR(255)', notNull: true },
    workflow_id: { type: 'VARCHAR(255)', notNull: true },
    step_id: { type: 'VARCHAR(255)', notNull: true },
    attempt_id: { type: 'VARCHAR(255)', notNull: true },
    event_id: { type: 'VARCHAR(255)', notNull: true },
    event_type: { type: 'VARCHAR(255)', notNull: true },
    payload: { type: 'JSONB', notNull: true },
    created_at: { type: 'TIMESTAMPTZ', notNull: true },
  },
  agent_step_results: {
    organization_id: { type: 'VARCHAR(255)', notNull: true },
    workstream_id: { type: 'VARCHAR(255)', notNull: true },
    namespace_id: { type: 'VARCHAR(255)', notNull: true },
    workflow_id: { type: 'VARCHAR(255)', notNull: true },
    step_id: { type: 'VARCHAR(255)', notNull: true },
    attempt_id: { type: 'VARCHAR(255)', notNull: true },
    result_id: { type: 'VARCHAR(255)', notNull: true },
    result_status: { type: 'VARCHAR(64)', notNull: true },
    semantic_signature: { type: 'VARCHAR(255)', notNull: false },
    payload: { type: 'JSONB', notNull: true },
    created_at: { type: 'TIMESTAMPTZ', notNull: true },
  },
  result_capabilities: {
    organization_id: { type: 'VARCHAR(255)', notNull: true },
    workstream_id: { type: 'VARCHAR(255)', notNull: true },
    namespace_id: { type: 'VARCHAR(255)', notNull: true },
    workflow_id: { type: 'VARCHAR(255)', notNull: true },
    step_id: { type: 'VARCHAR(255)', notNull: true },
    attempt_id: { type: 'VARCHAR(255)', notNull: true },
    result_id: { type: 'VARCHAR(255)', notNull: true },
    capability_id: { type: 'VARCHAR(255)', notNull: true },
    capability_type: { type: 'VARCHAR(255)', notNull: true },
    payload: { type: 'JSONB', notNull: true },
    created_at: { type: 'TIMESTAMPTZ', notNull: true },
  },
  work_units: {
    organization_id: { type: 'VARCHAR(255)', notNull: true },
    workstream_id: { type: 'VARCHAR(255)', notNull: true },
    work_unit_id: { type: 'VARCHAR(255)', notNull: true },
    unit_type: { type: 'VARCHAR(255)', notNull: true },
    status: { type: 'VARCHAR(64)', notNull: true },
    revision: { type: 'INTEGER', notNull: true },
    payload: { type: 'JSONB', notNull: true },
    created_at: { type: 'TIMESTAMPTZ', notNull: true },
    updated_at: { type: 'TIMESTAMPTZ', notNull: true },
  },
  work_environments: {
    organization_id: { type: 'VARCHAR(255)', notNull: true },
    workstream_id: { type: 'VARCHAR(255)', notNull: true },
    environment_id: { type: 'VARCHAR(255)', notNull: true },
    env_type: { type: 'VARCHAR(255)', notNull: true },
    status: { type: 'VARCHAR(64)', notNull: true },
    revision: { type: 'INTEGER', notNull: true },
    payload: { type: 'JSONB', notNull: true },
    created_at: { type: 'TIMESTAMPTZ', notNull: true },
    updated_at: { type: 'TIMESTAMPTZ', notNull: true },
  },
  workers: {
    organization_id: { type: 'VARCHAR(255)', notNull: true },
    worker_id: { type: 'VARCHAR(255)', notNull: true },
    worker_type: { type: 'VARCHAR(255)', notNull: true },
    status: { type: 'VARCHAR(64)', notNull: true },
    revision: { type: 'INTEGER', notNull: true },
    payload: { type: 'JSONB', notNull: true },
    created_at: { type: 'TIMESTAMPTZ', notNull: true },
    updated_at: { type: 'TIMESTAMPTZ', notNull: true },
  },
  work_unit_leases: {
    organization_id: { type: 'VARCHAR(255)', notNull: true },
    workstream_id: { type: 'VARCHAR(255)', notNull: true },
    work_unit_id: { type: 'VARCHAR(255)', notNull: true },
    lease_id: { type: 'VARCHAR(255)', notNull: true },
    worker_id: { type: 'VARCHAR(255)', notNull: true },
    environment_id: { type: 'VARCHAR(255)', notNull: false },
    status: { type: 'VARCHAR(64)', notNull: true },
    created_at: { type: 'TIMESTAMPTZ', notNull: true },
  },
}

const EXPECTED_PKS = {
  artifacts: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id', 'artifact_id'],
  oracle_executions: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id', 'execution_id'],
  agent_step_attempts: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id', 'step_id', 'attempt_id'],
  agent_step_attempt_events: [
    'organization_id',
    'workstream_id',
    'namespace_id',
    'workflow_id',
    'step_id',
    'attempt_id',
    'event_id',
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
  result_capabilities: [
    'organization_id',
    'workstream_id',
    'namespace_id',
    'workflow_id',
    'step_id',
    'attempt_id',
    'result_id',
    'capability_id',
  ],
  work_units: ['organization_id', 'workstream_id', 'work_unit_id'],
  work_environments: ['organization_id', 'workstream_id', 'environment_id'],
  workers: ['organization_id', 'worker_id'],
  work_unit_leases: ['organization_id', 'workstream_id', 'work_unit_id', 'lease_id'],
}

const EXPECTED_FKS = {
  artifacts: [
    { refTable: 'workflow_instances', columns: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id'] },
  ],
  oracle_executions: [
    { refTable: 'workflow_instances', columns: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id'] },
  ],
  agent_step_attempts: [
    { refTable: 'workflow_instances', columns: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id'] },
  ],
  agent_step_attempt_events: [
    {
      refTable: 'agent_step_attempts',
      columns: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id', 'step_id', 'attempt_id'],
    },
  ],
  agent_step_results: [
    {
      refTable: 'agent_step_attempts',
      columns: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id', 'step_id', 'attempt_id'],
    },
  ],
  result_capabilities: [
    {
      refTable: 'agent_step_results',
      columns: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id', 'step_id', 'attempt_id', 'result_id'],
    },
  ],
  work_units: [{ refTable: 'workstreams', columns: ['organization_id', 'workstream_id'] }],
  work_environments: [{ refTable: 'workstreams', columns: ['organization_id', 'workstream_id'] }],
  workers: [{ refTable: 'organizations', columns: ['organization_id'] }],
  work_unit_leases: [{ refTable: 'work_units', columns: ['organization_id', 'workstream_id', 'work_unit_id'] }],
}

const EXPECTED_INDEXES = {
  idx_artifacts_instance: {
    table: 'artifacts',
    columns: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id', 'availability_status'],
  },
  idx_oracle_executions_instance: {
    table: 'oracle_executions',
    columns: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id', 'status'],
  },
  idx_agent_step_attempts_step: {
    table: 'agent_step_attempts',
    columns: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id', 'step_id', 'status'],
  },
}

const TENANT_SCOPED_TABLES = NEW_TABLES

// Tables strictement append-only : aucune colonne `updated_at`, aucun trigger.
const APPEND_ONLY_TABLES = [
  'agent_step_attempt_events',
  'agent_step_results',
  'result_capabilities',
  'work_unit_leases',
]

// Tables mutables : `updated_at` + trigger `set_updated_at()`.
const MUTABLE_TRIGGERS = {
  artifacts: 'trg_artifacts_updated_at',
  oracle_executions: 'trg_oracle_executions_updated_at',
  agent_step_attempts: 'trg_agent_step_attempts_updated_at',
  work_units: 'trg_work_units_updated_at',
  work_environments: 'trg_work_environments_updated_at',
  workers: 'trg_workers_updated_at',
}

// ---------------------------------------------------------------------------
// Chargement + parsing cumulé
// ---------------------------------------------------------------------------

const rawFiles = new Map(SQL_FILES.map((file) => [file, readFileSync(join(MIGRATIONS, file), 'utf8')]))
const rawSql = SQL_FILES.map((file) => rawFiles.get(file)).join('\n')
const cleanSql = stripComments(rawSql)
const schema = parseSchema(rawSql)
const v6Statements = splitStatements(stripComments(rawFiles.get(V6_FILE)))

// ---------------------------------------------------------------------------
// Bloc A — propreté syntaxique
// ---------------------------------------------------------------------------

check('A1 — les fichiers V1..V6 existent, sont non vides et terminent par un point-virgule', () => {
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
  // V1..V5 (>= 39 instructions) + V6 (10 tables + 6 index + 6 DROP + 6 triggers).
  assert.ok(schema.statements.length >= 39 + 28, `nombre d'instructions trop faible : ${schema.statements.length}`)
  equal(v6Statements.length, 28, "V6 : nombre d'instructions")
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

check('A4 — la V6 réutilise le dollar-quoting / la fonction set_updated_at de V2', () => {
  const dollars = [...rawSql.matchAll(/\$\$/g)].length
  assert.ok(dollars > 0 && dollars % 2 === 0, `nombre de délimiteurs $$ impair : ${dollars}`)
  assert.match(rawSql, /CREATE\s+OR\s+REPLACE\s+FUNCTION\s+set_updated_at\s*\(\s*\)/i)
  assert.match(rawSql, /RETURNS\s+TRIGGER/i)
  assert.match(rawSql, /LANGUAGE\s+plpgsql/i)
  const v6 = rawFiles.get(V6_FILE)
  assert.match(v6, /EXECUTE\s+FUNCTION\s+set_updated_at\s*\(\)/i, 'V6 doit brancher set_updated_at()')
})

check('A5 — la V6 ne redéclare pas la fonction set_updated_at', () => {
  const v6 = stripComments(rawFiles.get(V6_FILE))
  assert.doesNotMatch(v6, /CREATE\s+(?:OR\s+REPLACE\s+)?FUNCTION\s+set_updated_at/i)
})

// ---------------------------------------------------------------------------
// Bloc B — tables et colonnes
// ---------------------------------------------------------------------------

check('B1 — les 10 nouvelles tables V6 sont créées', () => {
  for (const table of NEW_TABLES) {
    assert.ok(schema.tables.has(table), `table manquante : ${table}`)
  }
  equal(NEW_TABLES.length, 10, 'nombre de nouvelles tables')
})

check('B2 — chaque table V6 expose exactement les colonnes attendues (type + NOT NULL)', () => {
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

check("B3 — valeurs par défaut d'artifacts (tenant, dimensions, payload, timestamps)", () => {
  const table = schema.tables.get('artifacts')
  equal(table.columns.get('organization_id').defaultRaw, "'default'", 'artifacts.organization_id DEFAULT')
  equal(table.columns.get('workstream_id').defaultRaw, "'default'", 'artifacts.workstream_id DEFAULT')
  equal(table.columns.get('availability_status').defaultRaw, "'pending'", 'artifacts.availability_status DEFAULT')
  equal(table.columns.get('retention_status').defaultRaw, "'active'", 'artifacts.retention_status DEFAULT')
  equal(table.columns.get('legal_hold').defaultRaw, 'FALSE', 'artifacts.legal_hold DEFAULT')
  equal(table.columns.get('payload').defaultRaw, "'{}'::jsonb", 'artifacts.payload DEFAULT')
  equal(table.columns.get('created_at').defaultRaw, 'CURRENT_TIMESTAMP', 'artifacts.created_at DEFAULT')
  equal(table.columns.get('updated_at').defaultRaw, 'CURRENT_TIMESTAMP', 'artifacts.updated_at DEFAULT')
})

check('B4 — valeurs par défaut oracle / agent-step / squelettes (status, revision, JSONB, timestamps)', () => {
  const oracle = schema.tables.get('oracle_executions')
  equal(oracle.columns.get('status').defaultRaw, "'running'", 'oracle_executions.status DEFAULT')
  equal(oracle.columns.get('revision').defaultRaw, '1', 'oracle_executions.revision DEFAULT')
  equal(oracle.columns.get('payload').defaultRaw, "'{}'::jsonb", 'oracle_executions.payload DEFAULT')
  equal(oracle.columns.get('updated_at').defaultRaw, 'CURRENT_TIMESTAMP', 'oracle_executions.updated_at DEFAULT')

  const attempt = schema.tables.get('agent_step_attempts')
  equal(attempt.columns.get('status').defaultRaw, "'running'", 'agent_step_attempts.status DEFAULT')
  equal(attempt.columns.get('revision').defaultRaw, '1', 'agent_step_attempts.revision DEFAULT')
  equal(attempt.columns.get('payload').defaultRaw, "'{}'::jsonb", 'agent_step_attempts.payload DEFAULT')
  equal(attempt.columns.get('updated_at').defaultRaw, 'CURRENT_TIMESTAMP', 'agent_step_attempts.updated_at DEFAULT')

  equal(
    schema.tables.get('work_units').columns.get('status').defaultRaw,
    "'created'",
    'work_units.status DEFAULT'
  )
  equal(
    schema.tables.get('work_environments').columns.get('status').defaultRaw,
    "'provisioning'",
    'work_environments.status DEFAULT'
  )
  equal(schema.tables.get('workers').columns.get('status').defaultRaw, "'offline'", 'workers.status DEFAULT')
  equal(
    schema.tables.get('work_unit_leases').columns.get('status').defaultRaw,
    "'active'",
    'work_unit_leases.status DEFAULT'
  )
})

check('B5 — les sous-tables append-only V6 exposent payload JSONB et created_at', () => {
  for (const table of APPEND_ONLY_TABLES) {
    const parsed = schema.tables.get(table)
    for (const column of ['payload', 'created_at']) {
      if (column === 'payload' && table === 'work_unit_leases') continue
      assert.ok(parsed.columns.has(column), `${table} : colonne ${column} absente`)
    }
    if (parsed.columns.has('payload')) {
      equal(parsed.columns.get('payload').defaultRaw, "'{}'::jsonb", `${table}.payload DEFAULT`)
    }
    equal(parsed.columns.get('created_at').defaultRaw, 'CURRENT_TIMESTAMP', `${table}.created_at DEFAULT`)
  }
})

// ---------------------------------------------------------------------------
// Bloc C — clés primaires et clés étrangères composites
// ---------------------------------------------------------------------------

check('C1 — chaque table V6 possède la clé primaire composite tenant-scoped attendue', () => {
  for (const [table, columns] of Object.entries(EXPECTED_PKS)) {
    const parsed = schema.tables.get(table)
    assert.ok(parsed, `table absente : ${table}`)
    assert.ok(parsed.pk, `${table} : PRIMARY KEY absente`)
    equal(parsed.pk.columns, columns, `PRIMARY KEY de ${table}`)
  }
})

check('C2 — les clés étrangères composites V6 existent et portent ON DELETE CASCADE', () => {
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
    }
  }
})

check("C5 — l'agrégat attempt est chaîné par FK composites (events/results → attempts, capabilities → results)", () => {
  const eventsFk = schema.tables.get('agent_step_attempt_events').fks[0]
  equal(eventsFk.refTable, 'agent_step_attempts', 'agent_step_attempt_events.refTable')
  const resultsFk = schema.tables.get('agent_step_results').fks[0]
  equal(resultsFk.refTable, 'agent_step_attempts', 'agent_step_results.refTable')
  const capabilitiesFk = schema.tables.get('result_capabilities').fks[0]
  equal(capabilitiesFk.refTable, 'agent_step_results', 'result_capabilities.refTable')
})

// ---------------------------------------------------------------------------
// Bloc D — index de support
// ---------------------------------------------------------------------------

check("D1 — les index de support V6 requis existent et couvrent les colonnes attendues", () => {
  for (const [name, spec] of Object.entries(EXPECTED_INDEXES)) {
    const index = schema.indexes.find((candidate) => candidate.name === name)
    assert.ok(index, `index ${name} absent`)
    equal(index.table, spec.table, `table de ${name}`)
    equal(index.columns, spec.columns, `colonnes de ${name}`)
    equal(index.unique, false, `${name} ne doit pas être unique`)
  }
})

check("D2 — les index de support V6 sont tenant-scopés (organization_id en tête)", () => {
  for (const name of Object.keys(EXPECTED_INDEXES)) {
    const index = schema.indexes.find((candidate) => candidate.name === name)
    equal(index.columns[0], 'organization_id', `première colonne de ${name}`)
  }
})

// ---------------------------------------------------------------------------
// Bloc E — contraintes CHECK (dimensions orthogonales & anti-purge)
// ---------------------------------------------------------------------------

const checkText = (table) => schema.tables.get(table).checks.join(' | ')

check('E1 — les 3 dimensions de statut des artefacts sont orthogonales (colonnes séparées)', () => {
  const table = schema.tables.get('artifacts')
  assert.ok(table.columns.has('availability_status'), 'availability_status absente')
  assert.ok(table.columns.has('retention_status'), 'retention_status absente')
  assert.ok(table.columns.has('legal_hold'), 'legal_hold absente')
  equal(table.columns.get('legal_hold').type, 'BOOLEAN', 'legal_hold type')
})

check("E2 — CHECK availability_status IN ('pending','uploading','available','unavailable','purged')", () => {
  assert.match(
    checkText('artifacts'),
    /availability_status\s+IN\s*\(\s*'pending'\s*,\s*'uploading'\s*,\s*'available'\s*,\s*'unavailable'\s*,\s*'purged'\s*\)/i,
    'artifacts : CHECK availability_status manquante'
  )
})

check("E3 — CHECK retention_status IN ('active','expired')", () => {
  assert.match(
    checkText('artifacts'),
    /retention_status\s+IN\s*\(\s*'active'\s*,\s*'expired'\s*\)/i,
    'artifacts : CHECK retention_status manquante'
  )
})

check('E4 — CHECK interdisant la purge quand legal_hold = TRUE (règle d\'or)', () => {
  assert.match(
    checkText('artifacts'),
    /NOT\s*\(\s*legal_hold\s*=\s*TRUE\s+AND\s+availability_status\s*=\s*'purged'\s*\)/i,
    'artifacts : CHECK anti-purge sous legal hold manquante'
  )
})

check("E5 — CHECK oracle_executions.status IN ('running','succeeded','failed','cancelled') + revision >= 1", () => {
  assert.match(
    checkText('oracle_executions'),
    /status\s+IN\s*\(\s*'running'\s*,\s*'succeeded'\s*,\s*'failed'\s*,\s*'cancelled'\s*\)/i,
    'oracle_executions : CHECK status manquante'
  )
  assert.match(checkText('oracle_executions'), /revision\s*>=\s*1/, 'oracle_executions : CHECK revision >= 1 manquante')
})

check(
  "E6 — CHECK agent_step_attempts.status IN ('running','completed','failed','timed_out','cancelled') + revision >= 1",
  () => {
    assert.match(
      checkText('agent_step_attempts'),
      /status\s+IN\s*\(\s*'running'\s*,\s*'completed'\s*,\s*'failed'\s*,\s*'timed_out'\s*,\s*'cancelled'\s*\)/i,
      'agent_step_attempts : CHECK status manquante'
    )
    assert.match(checkText('agent_step_attempts'), /revision\s*>=\s*1/, 'agent_step_attempts : CHECK revision >= 1 manquante')
  }
)

check("E7 — CHECK agent_step_results.result_status IN ('success','failure','collision_detected')", () => {
  assert.match(
    checkText('agent_step_results'),
    /result_status\s+IN\s*\(\s*'success'\s*,\s*'failure'\s*,\s*'collision_detected'\s*\)/i,
    'agent_step_results : CHECK result_status manquante'
  )
})

check('E8 — les squelettes worker/environment portent status CHECK et revision >= 1', () => {
  assert.match(
    checkText('work_units'),
    /status\s+IN\s*\(\s*'created'\s*,\s*'assigned'\s*,\s*'running'\s*,\s*'completed'\s*,\s*'failed'\s*,\s*'cancelled'\s*\)/i,
    'work_units : CHECK status manquante'
  )
  assert.match(checkText('work_units'), /revision\s*>=\s*1/, 'work_units : CHECK revision >= 1 manquante')
  assert.match(
    checkText('work_environments'),
    /status\s+IN\s*\(\s*'provisioning'\s*,\s*'ready'\s*,\s*'busy'\s*,\s*'decommissioned'\s*\)/i,
    'work_environments : CHECK status manquante'
  )
  assert.match(checkText('work_environments'), /revision\s*>=\s*1/, 'work_environments : CHECK revision >= 1 manquante')
  assert.match(
    checkText('workers'),
    /status\s+IN\s*\(\s*'offline'\s*,\s*'idle'\s*,\s*'busy'\s*,\s*'maintenance'\s*\)/i,
    'workers : CHECK status manquante'
  )
  assert.match(checkText('workers'), /revision\s*>=\s*1/, 'workers : CHECK revision >= 1 manquante')
  assert.match(
    checkText('work_unit_leases'),
    /status\s+IN\s*\(\s*'active'\s*,\s*'released'\s*,\s*'expired'\s*\)/i,
    'work_unit_leases : CHECK status manquante'
  )
})

// ---------------------------------------------------------------------------
// Bloc F — nature append-only vs mutable & triggers updated_at
// ---------------------------------------------------------------------------

check("F1 — les tables append-only V6 n'ont pas de colonne updated_at", () => {
  for (const table of APPEND_ONLY_TABLES) {
    const parsed = schema.tables.get(table)
    assert.ok(parsed, `table absente : ${table}`)
    assert.ok(!parsed.columns.has('updated_at'), `${table} ne doit pas porter de colonne updated_at`)
  }
})

check('F2 — les tables append-only V6 ne portent aucun trigger', () => {
  for (const table of APPEND_ONLY_TABLES) {
    assert.ok(
      !schema.triggers.some((trigger) => trigger.table === table),
      `${table} ne doit pas porter de trigger`
    )
  }
})

check("F3 — chaque racine mutable V6 porte son trigger updated_at appelant set_updated_at()", () => {
  for (const [table, triggerName] of Object.entries(MUTABLE_TRIGGERS)) {
    const triggers = schema.triggers.filter((trigger) => trigger.table === table)
    equal(triggers.length, 1, `nombre de triggers sur ${table}`)
    equal(triggers[0].name, triggerName, `nom du trigger sur ${table}`)
    equal(triggers[0].fn, 'set_updated_at', `fonction du trigger ${triggers[0].name}`)
  }
})

check('F4 — work_unit_leases est un log sans updated_at ni revision', () => {
  const parsed = schema.tables.get('work_unit_leases')
  assert.ok(!parsed.columns.has('updated_at'), 'work_unit_leases ne doit pas porter updated_at')
  assert.ok(!parsed.columns.has('revision'), 'work_unit_leases ne doit pas porter revision')
})

check('F5 — les noms de triggers restent uniques dans le schéma cumulé', () => {
  const names = schema.triggers.map((trigger) => trigger.name)
  equal(new Set(names).size, names.length, 'noms de triggers uniques')
})

// ---------------------------------------------------------------------------
// Bloc G — isolation tenant
// ---------------------------------------------------------------------------

check("G1 — organization_id est NOT NULL avec DEFAULT 'default' sur les tables V6", () => {
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
