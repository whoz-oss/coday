/**
 * forge-bmad-reader.mjs
 *
 * Lecture des fichiers BMAD (YAML frontmatter + state files) sans dépendance npm.
 * Tous les parseurs sont artisanaux (regex ligne par ligne) et tolèrent :
 *   - les valeurs `null` YAML (`field: null` → null, champ absent → undefined)
 *   - les fins de ligne Windows (`\r\n`)
 *   - les fichiers absents (retourne null)
 *
 * Fonctions exportées :
 *   readForgeRunYaml(repoRoot, ticketId)
 *   readStoryFrontmatter(repoRoot, storePath)
 *   readSprintStatus(repoRoot, workstreamSlug)
 */

import { readFileSync, existsSync } from 'node:fs'
import { join, isAbsolute } from 'node:path'

// ---------------------------------------------------------------------------
// Parseur YAML minimaliste (ligne par ligne, sans dépendance)
// ---------------------------------------------------------------------------

/**
 * Normalise les fins de ligne et découpe en lignes.
 * @param {string} raw
 * @returns {string[]}
 */
function toLines(raw) {
  return raw.replace(/\r\n/g, '\n').split('\n')
}

/**
 * Parse la valeur scalaire d'un champ YAML.
 *
 * Règles :
 *   field: null         → null
 *   field:              → null  (valeur vide)
 *   field: "null"       → "null"  (chaîne)
 *   field: 'foo bar'    → "foo bar"
 *   field: "foo bar"    → "foo bar"
 *   field: foo          → "foo"
 *
 * @param {string} raw  Partie droite du `:` (déjà trimée)
 * @returns {string | null}
 */
function parseScalar(raw) {
  const v = raw.trim()
  if (v === '' || v === 'null' || v === '~') return null
  // Guillemets doubles
  if (v.startsWith('"') && v.endsWith('"')) return v.slice(1, -1)
  // Guillemets simples
  if (v.startsWith("'") && v.endsWith("'")) return v.slice(1, -1)
  return v
}

/**
 * Retourne le niveau d'indentation (nombre d'espaces en tête).
 * @param {string} line
 * @returns {number}
 */
function indentOf(line) {
  return line.length - line.trimStart().length
}

/**
 * Parse un bloc YAML simple (pas de listes, pas d'imbrication > 2 niveaux).
 *
 * Retourne un objet plat pour le niveau donné, et des sous-objets pour
 * les blocs imbriqués d'un niveau supplémentaire.
 *
 * @param {string[]} lines   Tableau de lignes YAML
 * @param {number}   start   Index de départ (0 = première ligne à parser)
 * @param {number}   indent  Niveau d'indentation attendu pour ce bloc
 * @returns {{ obj: Record<string, unknown>, nextIndex: number }}
 */
function parseBlock(lines, start, indent) {
  const obj = {}
  let i = start

  while (i < lines.length) {
    const line = lines[i]
    const trimmed = line.trimStart()

    // Ignorer les lignes vides et les commentaires
    if (trimmed === '' || trimmed.startsWith('#')) {
      i++
      continue
    }

    const currentIndent = indentOf(line)

    // Fin du bloc courant : on est remonté à un niveau parent
    if (currentIndent < indent) break

    // Ignorer les lignes plus indentées que prévu (données multi-lignes, etc.)
    if (currentIndent > indent) {
      i++
      continue
    }

    const colonIdx = trimmed.indexOf(':')
    if (colonIdx < 0) {
      i++
      continue
    }

    const key = trimmed.slice(0, colonIdx).trim()
    const rest = trimmed.slice(colonIdx + 1)

    // Regarder si la prochaine ligne non-vide est plus indentée → sous-bloc
    let nextMeaningful = i + 1
    while (nextMeaningful < lines.length && lines[nextMeaningful].trim() === '') nextMeaningful++

    const hasSubBlock =
      nextMeaningful < lines.length &&
      lines[nextMeaningful].trim() !== '' &&
      !lines[nextMeaningful].trimStart().startsWith('#') &&
      indentOf(lines[nextMeaningful]) > indent

    if (hasSubBlock && rest.trim() === '') {
      // Sous-bloc
      const { obj: subObj, nextIndex } = parseBlock(lines, i + 1, indentOf(lines[nextMeaningful]))
      obj[key] = subObj
      i = nextIndex
    } else {
      obj[key] = parseScalar(rest)
      i++
    }
  }

  return { obj, nextIndex: i }
}

/**
 * Parse un document YAML complet en objet JS.
 * @param {string} content
 * @returns {Record<string, unknown>}
 */
function parseYaml(content) {
  const lines = toLines(content)
  const { obj } = parseBlock(lines, 0, 0)
  return obj
}

/**
 * Extrait la section frontmatter YAML (entre les `---`).
 * Retourne null si absente.
 * @param {string} content
 * @returns {string | null}
 */
function extractFrontmatter(content) {
  const lines = toLines(content)
  if (lines[0]?.trim() !== '---') return null
  const end = lines.findIndex((l, i) => i > 0 && l.trim() === '---')
  if (end < 0) return null
  return lines.slice(1, end).join('\n')
}

// ---------------------------------------------------------------------------
// Helpers de lecture sécurisée
// ---------------------------------------------------------------------------

/**
 * Lit un fichier texte. Retourne null si absent.
 * @param {string} filePath
 * @returns {string | null}
 */
function readFileSafe(filePath) {
  if (!existsSync(filePath)) return null
  try {
    return readFileSync(filePath, 'utf8')
  } catch {
    return null
  }
}

/**
 * Lit et parse un fichier YAML. Retourne null si absent ou illisible.
 * @param {string} filePath
 * @returns {Record<string, unknown> | null}
 */
function readYamlFile(filePath) {
  const content = readFileSafe(filePath)
  if (content === null) return null
  try {
    return parseYaml(content)
  } catch {
    return null
  }
}

// ---------------------------------------------------------------------------
// Accesseurs sur les données parsées
// ---------------------------------------------------------------------------

/**
 * Extrait une valeur scalaire null-safe depuis un objet.
 * @param {unknown} obj
 * @param {string} key
 * @returns {string | null}
 */
function str(obj, key) {
  if (!obj || typeof obj !== 'object') return null
  const v = obj[key]
  if (v === null || v === undefined) return null
  if (typeof v === 'string') return v
  return String(v)
}

/**
 * Extrait un sous-objet null-safe.
 * @param {unknown} obj
 * @param {string} key
 * @returns {Record<string, unknown> | null}
 */
function sub(obj, key) {
  if (!obj || typeof obj !== 'object') return null
  const v = obj[key]
  if (!v || typeof v !== 'object') return null
  return v
}

// ---------------------------------------------------------------------------
// API publique
// ---------------------------------------------------------------------------

/**
 * Lit `forge/state/forge-runs/<ticketId>.yaml` et retourne un objet normalisé.
 *
 * Retourne null si le fichier n'existe pas.
 * Tolère les valeurs null dans YAML (ne jette pas).
 *
 * @param {string} repoRoot
 * @param {string} ticketId
 * @returns {ForgeRunYaml | null}
 */
export function readForgeRunYaml(repoRoot, ticketId) {
  const yamlPath = join(repoRoot, 'forge', 'state', 'forge-runs', `${ticketId}.yaml`)
  const raw = readYamlFile(yamlPath)
  if (!raw) return null

  const g1 = sub(raw, 'gate_1')
  const g2 = sub(raw, 'gate_2')
  const g3 = sub(raw, 'gate_3')
  const g4 = sub(raw, 'gate_4')
  const outcome = sub(raw, 'run_outcome')

  return {
    forgeRunId: str(raw, 'forge_run_id'),
    ticketId: str(raw, 'ticket_id') ?? ticketId,
    ticketSummary: str(raw, 'ticket_summary'),
    workstream: str(raw, 'workstream'),
    gates: {
      gate_1: {
        startedAt: str(g1, 'started_at'),
        decidedAt: str(g1, 'decided_at'),
        humanDecision: str(g1, 'human_decision'),
        reviewVerdict: str(sub(g1, 'review'), 'verdict'),
      },
      gate_2: {
        startedAt: str(g2, 'started_at'),
        decidedAt: str(g2, 'decided_at'),
        humanDecision: str(g2, 'human_decision'),
        reviewVerdict: str(sub(g2, 'review'), 'verdict'),
        specFile: str(g2, 'spec_file'),
        branch: str(g2, 'branch'),
      },
      gate_3: {
        startedAt: str(g3, 'started_at'),
        decidedAt: str(g3, 'decided_at'),
        humanDecision: str(g3, 'human_decision'),
        reviewVerdict: str(sub(g3, 'review'), 'verdict'),
        prLink: str(g3, 'pr_link'),
      },
      gate_4: {
        startedAt: str(g4, 'started_at'),
        decidedAt: str(g4, 'decided_at'),
        humanDecision: str(g4, 'human_decision'),
      },
    },
    runOutcome: {
      status: str(outcome, 'status') ?? 'in-progress',
      branch: str(outcome, 'branch'),
      prLink: str(outcome, 'pr_link'),
    },
  }
}

const STRICT_GATE_FIELDS = ['started_at', 'decided_at', 'human_decision']

/**
 * Dependency-free, deliberately narrow YAML syntax check for authoritative
 * Forge run publication. It accepts the mapping/scalar subset used by copied
 * BMAD runs and fails closed on lists, flow collections, block scalars, tabs,
 * malformed mappings, dangling quoted scalars, and empty block values.
 * Exotic otherwise-valid YAML is intentionally rejected at this boundary.
 */
function validateStrictForgeYamlSyntax(content) {
  const meaningful = toLines(content)
    .map((line, index) => ({ line, index }))
    .filter(({ line }) => line.trim() && !line.trimStart().startsWith('#') && line.trim() !== '---')

  for (let position = 0; position < meaningful.length; position++) {
    const { line } = meaningful[position]
    if (line.includes('\t')) return false
    const indent = indentOf(line)
    if (indent % 2 !== 0) return false
    const text = line.trim()
    if (text.startsWith('- ') || text.startsWith('[') || text.startsWith('{')) return false

    let quote = null
    let colon = -1
    for (let i = 0; i < text.length; i++) {
      const char = text[i]
      if (quote) {
        if (char === quote && (quote === "'" || text[i - 1] !== '\\')) quote = null
      } else if (char === "'" || char === '"') quote = char
      else if (char === ':') {
        colon = i
        break
      }
    }
    if (quote || colon <= 0 || !/^[A-Za-z0-9_-]+$/.test(text.slice(0, colon).trim())) return false

    const rawValue = text.slice(colon + 1).trim()
    if (rawValue.startsWith('|') || rawValue.startsWith('>') || rawValue.startsWith('[') || rawValue.startsWith('{'))
      return false
    if (rawValue) {
      const first = rawValue[0]
      if (first === "'" || first === '"') {
        let closedAt = -1
        for (let i = 1; i < rawValue.length; i++) {
          if (rawValue[i] === first && (first === "'" || rawValue[i - 1] !== '\\')) {
            closedAt = i
            break
          }
        }
        if (closedAt < 0 || !/^\s*(?:#.*)?$/.test(rawValue.slice(closedAt + 1))) return false
      }
    } else {
      const next = meaningful[position + 1]?.line
      if (!next || indentOf(next) <= indent) return false
    }
  }
  return meaningful.length > 0
}

/**
 * Strict authoritative read for generic publication. This deliberately leaves
 * readForgeRunYaml() permissive for any tolerant read-only consumers.
 */
export function readForgeRunYamlStrict(repoRoot, ticketId) {
  const yamlPath = join(repoRoot, 'forge', 'state', 'forge-runs', `${ticketId}.yaml`)
  if (!existsSync(yamlPath)) return { ok: false, error: { code: 'FORGE_RUN_NOT_FOUND' } }
  let content
  try {
    content = readFileSync(yamlPath, 'utf8')
  } catch {
    return { ok: false, error: { code: 'FORGE_RUN_READ_FAILURE' } }
  }
  if (!content.trim()) return { ok: false, error: { code: 'FORGE_RUN_TRUNCATED' } }
  if (!validateStrictForgeYamlSyntax(content)) return { ok: false, error: { code: 'FORGE_RUN_PARSE_INVALID' } }
  let raw
  try {
    raw = parseYaml(content)
  } catch {
    return { ok: false, error: { code: 'FORGE_RUN_PARSE_INVALID' } }
  }
  if (!raw || typeof raw !== 'object' || Array.isArray(raw) || Object.keys(raw).length === 0)
    return { ok: false, error: { code: 'FORGE_RUN_TRUNCATED' } }
  if (!Object.hasOwn(raw, 'ticket_id') || typeof raw.ticket_id !== 'string' || raw.ticket_id !== ticketId)
    return { ok: false, error: { code: 'FORGE_TICKET_MISMATCH' } }
  const outcome = raw.run_outcome
  if (
    !outcome ||
    typeof outcome !== 'object' ||
    Array.isArray(outcome) ||
    !Object.hasOwn(outcome, 'status') ||
    typeof outcome.status !== 'string' ||
    !['in-progress', 'completed', 'abandoned'].includes(outcome.status)
  ) {
    return { ok: false, error: { code: 'INVALID_FORGE_RUN_STRUCTURE', path: 'run_outcome.status' } }
  }
  for (let number = 1; number <= 4; number++) {
    const key = `gate_${number}`
    if (!Object.hasOwn(raw, key)) continue
    const gate = raw[key]
    if (!gate || typeof gate !== 'object' || Array.isArray(gate))
      return { ok: false, error: { code: 'INVALID_FORGE_RUN_STRUCTURE', path: key } }
    for (const field of STRICT_GATE_FIELDS) {
      if (!Object.hasOwn(gate, field) || (gate[field] !== null && typeof gate[field] !== 'string'))
        return { ok: false, error: { code: 'INVALID_FORGE_RUN_STRUCTURE', path: `${key}.${field}` } }
    }
  }
  const normalized = readForgeRunYaml(repoRoot, ticketId)
  if (!normalized) return { ok: false, error: { code: 'FORGE_RUN_PARSE_INVALID' } }
  return { ok: true, run: normalized }
}

/**
 * Lit le frontmatter YAML d'une story BMAD.
 *
 * `storePath` est soit un chemin absolu, soit relatif à `repoRoot`.
 * Retourne null si le fichier n'existe pas.
 * Extrait uniquement : status, jira, forge_gate, title, type, created.
 *
 * @param {string} repoRoot
 * @param {string} storePath
 * @returns {StoryFrontmatter | null}
 */
export function readStoryFrontmatter(repoRoot, storePath) {
  const fullPath = isAbsolute(storePath) ? storePath : join(repoRoot, storePath)
  const content = readFileSafe(fullPath)
  if (content === null) return null

  const fmRaw = extractFrontmatter(content)
  if (!fmRaw) return null

  let parsed
  try {
    parsed = parseYaml(fmRaw)
  } catch {
    return null
  }

  return {
    status: str(parsed, 'status'),
    jira: str(parsed, 'jira'),
    jiraEpic: str(parsed, 'jira-epic'),
    forgeGate: str(parsed, 'forge_gate'),
    title: str(parsed, 'title'),
    type: str(parsed, 'type'),
    created: str(parsed, 'created'),
  }
}

/**
 * Lit le `sprint-status.yaml` d'un workstream.
 *
 * Retourne `{ developmentStatus, epicJira, sprintGate }` ou null si absent.
 *
 * @param {string} repoRoot
 * @param {string} workstreamSlug
 * @returns {{ developmentStatus: Map<string, string>, epicJira: string|null, sprintGate: string|null } | null}
 */
export function readSprintStatus(repoRoot, workstreamSlug) {
  const yamlPath = join(
    repoRoot,
    'forge',
    'bmad',
    'workstreams',
    workstreamSlug,
    'implementation-artifacts',
    'sprint-status.yaml'
  )
  const raw = readYamlFile(yamlPath)
  if (!raw) return null

  const devStatusRaw = sub(raw, 'development_status')
  const developmentStatus = new Map()

  if (devStatusRaw) {
    for (const [k, v] of Object.entries(devStatusRaw)) {
      if (typeof v === 'string') developmentStatus.set(k, v)
      else if (v === null) developmentStatus.set(k, 'unknown')
    }
  }

  return {
    developmentStatus,
    epicJira: str(raw, 'epic_jira'),
    sprintGate: str(raw, 'sprint_gate'),
  }
}
