/**
 * Validation hors-ligne de la migration Flyway V7 (lease protocol & fencing).
 *
 * Le fichier `factory/infra/migrations/V7__lease_protocol.sql` complète les
 * squelettes Jalon B2 (`work_units`, `workers`, `work_unit_leases`) créés en V6
 * avec le protocole de bail Jalon C1-T0 :
 *
 *   * `work_unit_leases` : cycle de vie d'un bail actif (fencing_token,
 *     acquired_at, lease_expires_at, heartbeat_at, released_at, expiry_reason)
 *     plus les index d'acquisition et d'expiration.
 *   * `workers` : liveness et négociation (last_heartbeat_at, protocol_version,
 *     capabilities JSONB).
 *   * `work_units` : ordonnancement (priority, not_before, attempt_count) plus
 *     l'index d'éligibilité `SELECT ... FOR UPDATE SKIP LOCKED`.
 *   * La séquence PostgreSQL `work_unit_lease_fencing_seq` — source unique de
 *     fencing tokens STRICTEMENT croissants, garantie par la base (nextval
 *     non transactionnel) et branchée comme DEFAULT de
 *     `work_unit_leases.fencing_token`.
 *
 * Ce test lit **V1 + V2 + V3 + V4 + V5 + V6 + V7**, les parse sans dépendance
 * externe (aucun PostgreSQL, Docker ou driver `pg` requis) et vérifie l'état
 * cumulé du schéma :
 *
 *   Bloc A — propreté syntaxique du SQL cumulé et périmètre de V7.
 *   Bloc B — colonnes ajoutées par V7 aux 3 tables (type, NOT NULL, défaut).
 *   Bloc C — séquence PostgreSQL de fencing token et monotonie strictement
 *            croissante (simulation des appels `nextval`).
 *   Bloc D — index ajoutés par V7 (acquisition, expiration, éligibilité).
 *   Bloc E — isolation tenant conservée et non-destructivité (ALTER-only).
 *
 * Usage : node factory/tests/test-v7-migration-schema.mjs
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
  'V7__lease_protocol.sql',
]
const V7_FILE = 'V7__lease_protocol.sql'

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

/** Remplace les commentaires `--` et block comments par des espaces. */
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

/** Découpe un corps sur les virgules de premier niveau (hors parenthèses/strings). */
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

/** Parse une colonne `name TYPE [contraintes...]`. */
function parseColumn(part) {
  const match = /^([A-Za-z0-9_]+)\s+([A-Za-z]+(?:\s*\([^)]*\))?)([\s\S]*)$/.exec(part)
  if (!match) throw new Error(`définition de colonne illisible : ${part}`)
  const [, name, rawType, rest] = match
  const defaultMatch =
    /\bDEFAULT\s+((?:'(?:[^']|'')*'|[A-Za-z_0-9][A-Za-z0-9_]*)(?:\s*\([^)]*\))?)(\s*::\s*[A-Za-z0-9_ ]+)?/i.exec(rest)
  return {
    name,
    type: rawType.replace(/\s+/g, ' ').toUpperCase(),
    notNull: /\bNOT\s+NULL\b/i.test(rest),
    hasDefault: defaultMatch !== null,
    defaultRaw: defaultMatch ? `${defaultMatch[1]}${defaultMatch[2] ?? ''}`.replace(/\s+/g, '') : null,
  }
}

/**
 * Construit le modèle de schéma cumulé V1..V7 :
 *   * tables : nom -> { columns: Map, checks: [] } ;
 *   * indexes : [{ name, unique, table, columns }] ;
 *   * sequences : nom -> { startWith, increment };
 *   * columnDefaults : "table.colonne" -> expression DEFAULT.
 */
function parseSchema(sql) {
  const statements = splitStatements(stripComments(sql))
  const tables = new Map()
  const indexes = []
  const sequences = new Map()
  const columnDefaults = new Map()

  for (const statement of statements) {
    const tableMatch = /^CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([A-Za-z0-9_]+)\s*\(([\s\S]*)\)\s*$/i.exec(statement)
    if (tableMatch) {
      const [, name, body] = tableMatch
      const table = { name, columns: new Map(), checks: [] }
      for (const part of splitTopLevel(body)) {
        if (/^(CONSTRAINT|PRIMARY\s+KEY|UNIQUE|FOREIGN\s+KEY|CHECK)\b/i.test(part)) {
          const check = /\bCHECK\s*\(([\s\S]*)\)\s*$/i.exec(part)
          if (check) table.checks.push(check[1].trim())
          continue
        }
        const column = parseColumn(part)
        table.columns.set(column.name, column)
        if (column.hasDefault) columnDefaults.set(`${name.toLowerCase()}.${column.name}`, column.defaultRaw)
      }
      tables.set(name.toLowerCase(), table)
      continue
    }

    // ALTER TABLE <t> ADD COLUMN [IF NOT EXISTS] <col>, ADD COLUMN ...
    const addColumnMatch = /^ALTER\s+TABLE\s+([A-Za-z0-9_]+)\s+ADD\s+COLUMN\s+([\s\S]+)$/i.exec(statement)
    if (addColumnMatch) {
      const table = tables.get(addColumnMatch[1].toLowerCase())
      if (table) {
        for (const fragment of splitTopLevel(addColumnMatch[2])) {
          const cleaned = fragment.replace(/^(?:ADD\s+COLUMN\s+)?(?:IF\s+NOT\s+EXISTS\s+)?/i, '').trim()
          if (!cleaned) continue
          const column = parseColumn(cleaned)
          table.columns.set(column.name, column)
          if (column.hasDefault) columnDefaults.set(`${table.name.toLowerCase()}.${column.name}`, column.defaultRaw)
        }
      }
      continue
    }

    // ALTER TABLE <t> ALTER COLUMN <c> SET DEFAULT <expr>
    const setDefaultMatch = /^ALTER\s+TABLE\s+([A-Za-z0-9_]+)\s+ALTER\s+COLUMN\s+([A-Za-z0-9_]+)\s+SET\s+DEFAULT\s+([\s\S]+)$/i.exec(
      statement
    )
    if (setDefaultMatch) {
      const expr = setDefaultMatch[3].trim().replace(/\s+/g, '')
      columnDefaults.set(`${setDefaultMatch[1].toLowerCase()}.${setDefaultMatch[2]}`, expr)
      const table = tables.get(setDefaultMatch[1].toLowerCase())
      const column = table?.columns.get(setDefaultMatch[2])
      if (column) {
        column.hasDefault = true
        column.defaultRaw = expr
      }
      continue
    }

    // ALTER TABLE <t> ADD CONSTRAINT <name> CHECK (...)
    const addConstraintMatch = /^ALTER\s+TABLE\s+([A-Za-z0-9_]+)\s+ADD\s+CONSTRAINT\s+([A-Za-z0-9_]+)\s+([\s\S]+)$/i.exec(
      statement
    )
    if (addConstraintMatch) {
      const table = tables.get(addConstraintMatch[1].toLowerCase())
      const check = /\bCHECK\s*\(([\s\S]*)\)\s*$/i.exec(addConstraintMatch[3].trim())
      if (table && check) table.checks.push(check[1].trim())
      continue
    }

    const sequenceMatch = /^CREATE\s+SEQUENCE\s+(?:IF\s+NOT\s+EXISTS\s+)?([A-Za-z0-9_]+)([\s\S]*)$/i.exec(statement)
    if (sequenceMatch) {
      const startMatch = /START\s+WITH\s+([0-9]+)/i.exec(sequenceMatch[2])
      const incrementMatch = /INCREMENT\s+BY\s+([0-9]+)/i.exec(sequenceMatch[2])
      sequences.set(sequenceMatch[1].toLowerCase(), {
        name: sequenceMatch[1],
        startWith: startMatch ? Number(startMatch[1]) : 1,
        increment: incrementMatch ? Number(incrementMatch[1]) : 1,
      })
      continue
    }

    const indexMatch =
      /^CREATE\s+(UNIQUE\s+)?INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?([A-Za-z0-9_]+)[\s\S]*?\bON\s+([A-Za-z0-9_]+)\s*\(([^)]*)\)/i.exec(
        statement
      )
    if (indexMatch) {
      indexes.push({
        name: indexMatch[2].toLowerCase(),
        unique: Boolean(indexMatch[1]),
        table: indexMatch[3].toLowerCase(),
        columns: indexMatch[4]
          .split(',')
          .map((entry) => entry.trim())
          .filter(Boolean),
      })
    }
  }

  return { statements, tables, indexes, sequences, columnDefaults }
}

// ---------------------------------------------------------------------------
// Simulation de séquence PostgreSQL (nextval monotone)
// ---------------------------------------------------------------------------

/**
 * Simule le comportement de `nextval()` pour une séquence déclarée avec
 * START WITH / INCREMENT BY : renvoie un générateur dont chaque appel produit la
 * valeur strictement suivante (le premier appel renvoie `startWith`). C'est la
 * sémantique PostgreSQL que le test vérifie à partir de la définition SQL.
 */
function createSequenceSimulator({ startWith = 1, increment = 1 }) {
  let current = startWith - increment
  return () => {
    current += increment
    return current
  }
}

// ---------------------------------------------------------------------------
// Chargement + parsing cumulé
// ---------------------------------------------------------------------------

const rawFiles = new Map(SQL_FILES.map((file) => [file, readFileSync(join(MIGRATIONS, file), 'utf8')]))
const rawSql = SQL_FILES.map((file) => rawFiles.get(file)).join('\n')
const cleanSql = stripComments(rawSql)
const schema = parseSchema(rawSql)
const v7Raw = rawFiles.get(V7_FILE)
const v7Clean = stripComments(v7Raw)
const v7Statements = splitStatements(v7Clean)

const getTable = (name) => {
  const table = schema.tables.get(name)
  assert.ok(table, `table absente du schéma cumulé : ${name}`)
  return table
}

// ---------------------------------------------------------------------------
// Bloc A — propreté syntaxique et périmètre de V7
// ---------------------------------------------------------------------------

check('A1 — les fichiers V1..V7 existent, sont non vides et terminent par un point-virgule', () => {
  for (const file of SQL_FILES) {
    const raw = rawFiles.get(file)
    assert.ok(raw.trim().length > 0, `${file} : fichier vide`)
    assert.ok(stripComments(raw).trim().endsWith(';'), `${file} : dernière instruction sans ;`)
  }
})

check('A2 — V7 est la dernière version (ordre lexical Flyway) et ne contient aucune instruction vide', () => {
  equal(SQL_FILES[SQL_FILES.length - 1], V7_FILE, 'V7 doit clore la chaîne de migrations')
  for (const statement of v7Statements) {
    assert.ok(statement.replace(/[\s;]/g, '').length > 0, 'instruction vide détectée dans V7')
  }
  equal(v7Statements.length, 10, "V7 : nombre d'instructions")
})

check('A3 — parenthèses équilibrées sur tout le fichier nettoyé cumulé', () => {
  let depth = 0
  for (const char of cleanSql) {
    if (char === '(') depth++
    else if (char === ')') depth--
    assert.ok(depth >= 0, 'parenthèse fermante surnuméraire')
  }
  equal(depth, 0, 'profondeur finale')
})

check('A4 — V7 est purement ALTER/CREATE INDEX/CREATE SEQUENCE (aucune table recréée)', () => {
  assert.doesNotMatch(v7Clean, /CREATE\s+TABLE/i, 'V7 ne doit pas recréer de table')
  assert.doesNotMatch(v7Clean, /DROP\s+TABLE/i, 'V7 ne doit pas supprimer de table')
  assert.match(v7Clean, /ALTER\s+TABLE\s+work_unit_leases/i, 'V7 doit étendre work_unit_leases')
  assert.match(v7Clean, /ALTER\s+TABLE\s+workers/i, 'V7 doit étendre workers')
  assert.match(v7Clean, /ALTER\s+TABLE\s+work_units/i, 'V7 doit étendre work_units')
})

check('A5 — V7 est non destructif : aucun DROP COLUMN ni changement de type', () => {
  assert.doesNotMatch(v7Clean, /DROP\s+COLUMN/i, 'V7 ne doit supprimer aucune colonne')
  assert.doesNotMatch(v7Clean, /ALTER\s+COLUMN\s+[A-Za-z0-9_]+\s+TYPE/i, 'V7 ne doit retyper aucune colonne')
})

// ---------------------------------------------------------------------------
// Bloc B — colonnes ajoutées par V7
// ---------------------------------------------------------------------------

const EXPECTED_COLUMNS = {
  work_unit_leases: {
    fencing_token: { type: 'BIGINT', notNull: false },
    acquired_at: { type: 'TIMESTAMPTZ', notNull: false },
    lease_expires_at: { type: 'TIMESTAMPTZ', notNull: false },
    heartbeat_at: { type: 'TIMESTAMPTZ', notNull: false },
    released_at: { type: 'TIMESTAMPTZ', notNull: false },
    expiry_reason: { type: 'VARCHAR(255)', notNull: false },
  },
  workers: {
    last_heartbeat_at: { type: 'TIMESTAMPTZ', notNull: false },
    protocol_version: { type: 'VARCHAR(64)', notNull: false },
    capabilities: { type: 'JSONB', notNull: true, defaultRaw: "'[]'::jsonb" },
  },
  work_units: {
    priority: { type: 'INTEGER', notNull: true, defaultRaw: '0' },
    not_before: { type: 'TIMESTAMPTZ', notNull: false },
    attempt_count: { type: 'INTEGER', notNull: true, defaultRaw: '0' },
  },
}

check('B1 — V7 ajoute les 6 colonnes de cycle de vie à work_unit_leases', () => {
  const table = getTable('work_unit_leases')
  for (const [column, spec] of Object.entries(EXPECTED_COLUMNS.work_unit_leases)) {
    const parsed = table.columns.get(column)
    assert.ok(parsed, `work_unit_leases.${column} absente`)
    equal(parsed.type, spec.type, `work_unit_leases.${column} type`)
    equal(parsed.notNull, spec.notNull, `work_unit_leases.${column} NOT NULL`)
  }
})

check('B2 — V7 ajoute liveness/capabilities à workers (capabilities NON NULL DEFAULT [])', () => {
  const table = getTable('workers')
  for (const [column, spec] of Object.entries(EXPECTED_COLUMNS.workers)) {
    const parsed = table.columns.get(column)
    assert.ok(parsed, `workers.${column} absente`)
    equal(parsed.type, spec.type, `workers.${column} type`)
    equal(parsed.notNull, spec.notNull, `workers.${column} NOT NULL`)
    if (spec.defaultRaw !== undefined) {
      equal(schema.columnDefaults.get(`workers.${column}`), spec.defaultRaw, `workers.${column} DEFAULT`)
    }
  }
})

check('B3 — V7 ajoute priority (NOT NULL DEFAULT 0), not_before (nullable) et attempt_count à work_units', () => {
  const table = getTable('work_units')
  for (const [column, spec] of Object.entries(EXPECTED_COLUMNS.work_units)) {
    const parsed = table.columns.get(column)
    assert.ok(parsed, `work_units.${column} absente`)
    equal(parsed.type, spec.type, `work_units.${column} type`)
    equal(parsed.notNull, spec.notNull, `work_units.${column} NOT NULL`)
    if (spec.defaultRaw !== undefined) {
      equal(schema.columnDefaults.get(`work_units.${column}`), spec.defaultRaw, `work_units.${column} DEFAULT`)
    }
  }
  const notBefore = table.columns.get('not_before')
  assert.ok(!notBefore.hasDefault, 'work_units.not_before ne doit pas porter de DEFAULT (NULL = éligible maintenant)')
})

check('B4 — les colonnes V7 sont bien appliquées au schéma cumulé V1..V7', () => {
  assert.ok(getTable('work_unit_leases').columns.has('fencing_token'), 'fencing_token absente du cumulé')
  assert.ok(getTable('workers').columns.has('capabilities'), 'workers.capabilities absente du cumulé')
  assert.ok(getTable('work_units').columns.has('priority'), 'work_units.priority absente du cumulé')
})

check('B5 — les CHECK de cohérence V7 existent (attempt_count >= 0, capabilities array)', () => {
  assert.match(
    getTable('work_units').checks.join(' | '),
    /attempt_count\s*>=\s*0/,
    'work_units : CHECK attempt_count >= 0 manquante'
  )
  assert.match(
    getTable('workers').checks.join(' | '),
    /jsonb_typeof\s*\(\s*capabilities\s*\)\s*=\s*'array'/i,
    'workers : CHECK capabilities JSON array manquante'
  )
})

// ---------------------------------------------------------------------------
// Bloc C — séquence PostgreSQL de fencing token & monotonie
// ---------------------------------------------------------------------------

check('C1 — V7 crée la séquence work_unit_lease_fencing_seq (START 1, INCREMENT 1)', () => {
  const sequence = schema.sequences.get('work_unit_lease_fencing_seq')
  assert.ok(sequence, 'séquence work_unit_lease_fencing_seq absente')
  equal(sequence.startWith, 1, 'START WITH')
  equal(sequence.increment, 1, 'INCREMENT BY')
  assert.ok(sequence.increment > 0, "l'incrément doit être positif pour être croissant")
  assert.match(v7Clean, /CREATE\s+SEQUENCE\s+IF\s+NOT\s+EXISTS\s+work_unit_lease_fencing_seq/i, 'instruction CREATE SEQUENCE manquante')
})

check('C2 — fencing_token est alimenté par la séquence en base (DEFAULT nextval)', () => {
  const defaultRaw = schema.columnDefaults.get('work_unit_leases.fencing_token')
  assert.ok(defaultRaw, 'work_unit_leases.fencing_token doit porter un DEFAULT')
  assert.match(
    defaultRaw,
    /nextval\s*\(\s*'work_unit_lease_fencing_seq'/,
    `DEFAULT de fencing_token doit appeler nextval('work_unit_lease_fencing_seq'), obtenu ${defaultRaw}`
  )
})

check('C3 — la séquence produit des fencing tokens STRICTEMENT croissants (monotonie)', () => {
  const sequence = schema.sequences.get('work_unit_lease_fencing_seq')
  const nextval = createSequenceSimulator(sequence)
  const tokens = Array.from({ length: 100 }, () => nextval())
  equal(tokens[0], sequence.startWith, 'premier token = START WITH')
  for (let i = 1; i < tokens.length; i++) {
    assert.ok(tokens[i] > tokens[i - 1], `token non strictement croissant : ${tokens[i - 1]} -> ${tokens[i]}`)
    equal(tokens[i] - tokens[i - 1], sequence.increment, `pas d'incrément au rang ${i}`)
  }
})

check('C4 — la monotonie est documentée dans V7 (choix de la séquence justifié)', () => {
  assert.match(v7Raw, /fencing\s+token/i, 'V7 doit documenter le fencing token')
  assert.match(v7Raw, /nextval/, 'V7 doit documenter nextval')
  assert.match(v7Raw, /INCREMENT\s+BY\s+1/i, "V7 doit documenter l'incrément strictement positif")
})

// ---------------------------------------------------------------------------
// Bloc D — index ajoutés par V7
// ---------------------------------------------------------------------------

const EXPECTED_INDEXES = {
  idx_work_unit_leases_acquisition: {
    table: 'work_unit_leases',
    columns: ['organization_id', 'workstream_id', 'work_unit_id', 'status'],
  },
  idx_work_unit_leases_expiry: {
    table: 'work_unit_leases',
    columns: ['organization_id', 'lease_expires_at'],
  },
  idx_work_units_eligibility: {
    table: 'work_units',
    columns: ['organization_id', 'workstream_id', 'status', 'priority DESC', 'not_before'],
  },
}

check('D1 — les index V7 existent avec les colonnes attendues', () => {
  for (const [name, spec] of Object.entries(EXPECTED_INDEXES)) {
    const index = schema.indexes.find((candidate) => candidate.name === name)
    assert.ok(index, `index ${name} absent`)
    equal(index.table, spec.table, `table de ${name}`)
    equal(index.columns, spec.columns, `colonnes de ${name}`)
    equal(index.unique, false, `${name} ne doit pas être unique`)
  }
})

check('D2 — idx_work_units_eligibility sert le scan SKIP LOCKED (priority DESC + not_before)', () => {
  const index = schema.indexes.find((candidate) => candidate.name === 'idx_work_units_eligibility')
  assert.ok(index.columns.includes('priority DESC'), 'priority DESC manquant (ordre de priorité)')
  assert.ok(index.columns.includes('not_before'), 'not_before manquant (porte temporelle)')
  equal(index.columns[index.columns.length - 1], 'not_before', 'not_before doit clore l\'index')
})

check('D3 — les index V7 sont tenant-scopés (organization_id en tête)', () => {
  for (const name of Object.keys(EXPECTED_INDEXES)) {
    const index = schema.indexes.find((candidate) => candidate.name === name)
    equal(index.columns[0], 'organization_id', `première colonne de ${name}`)
  }
})

// ---------------------------------------------------------------------------
// Bloc E — isolation tenant & intégrité du schéma cumulé
// ---------------------------------------------------------------------------

const TENANT_TABLES = ['work_unit_leases', 'workers', 'work_units']

check("E1 — l'isolation tenant est conservée sur les 3 tables V7 (organization_id NOT NULL DEFAULT 'default')", () => {
  for (const tableName of TENANT_TABLES) {
    const column = getTable(tableName).columns.get('organization_id')
    assert.ok(column, `${tableName} : organization_id absente`)
    assert.ok(column.notNull, `${tableName} : organization_id doit être NOT NULL`)
    equal(column.defaultRaw, "'default'", `${tableName} : DEFAULT 'default' attendu`)
  }
})

check('E2 — V7 ne réintroduit pas organization_id (V6 la porte déjà : ALTER-only)', () => {
  assert.doesNotMatch(
    v7Clean,
    /ADD\s+COLUMN\s+(?:IF\s+NOT\s+EXISTS\s+)?organization_id/i,
    'organization_id est créée en V6, V7 ne doit pas la redéclarer'
  )
})

check('E3 — le schéma cumulé V1..V7 conserve les tables des jalons précédents', () => {
  for (const table of [
    'workflow_definitions',
    'workflow_instances',
    'organizations',
    'workstreams',
    'outbox_events',
    'idempotency_records',
    'workflow_evidence',
    'human_interactions',
    'artifacts',
    'agent_step_attempts',
    'work_units',
    'workers',
    'work_unit_leases',
  ]) {
    assert.ok(schema.tables.has(table), `table V1..V6 disparue du cumulé : ${table}`)
  }
})

check('E4 — les noms de séquences et index V7 sont uniques dans le schéma cumulé', () => {
  const indexNames = schema.indexes.map((index) => index.name)
  equal(new Set(indexNames).size, indexNames.length, 'noms d\'index uniques')
  const sequenceNames = [...schema.sequences.keys()]
  equal(new Set(sequenceNames).size, sequenceNames.length, 'noms de séquences uniques')
})

// ---------------------------------------------------------------------------
// Résultat
// ---------------------------------------------------------------------------

console.log(`\nRésultat : ${passed} passé(s), ${failed} échoué(s)`)
process.exit(failed === 0 ? 0 : 1)
