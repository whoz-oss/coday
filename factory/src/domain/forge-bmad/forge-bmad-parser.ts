/**
 * Pure BMAD parser domain: the dependency-free line-by-line YAML subset used to
 * read BMAD frontmatter and state files, plus the pure normalizers that turn a
 * parsed document into the shapes consumed by the application.
 *
 * File reads (`readForgeRunYaml`, `readForgeRunYamlStrict`, `readStoryFrontmatter`,
 * `readSprintStatus`) live in `adapters/forge/forge-bmad-file-reader.ts`.
 *
 * Domain purity: no `node:fs`, HTTP, AgentOS or Git CLI dependency. All parsers
 * tolerate missing files by the caller returning `null`; they never read disk.
 */

// ---------------------------------------------------------------------------
// Minimalist YAML parser (line by line, no dependency)
// ---------------------------------------------------------------------------

/** Normalize line endings and split into lines. */
function toLines(raw: string): string[] {
  return raw.replace(/\r\n/g, '\n').split('\n')
}

/**
 * Parse the scalar value of a YAML field.
 *
 *   field: null         → null
 *   field:              → null  (empty value)
 *   field: "null"       → "null"  (string)
 *   field: 'foo bar'    → "foo bar"
 *   field: "foo bar"    → "foo bar"
 *   field: foo          → "foo"
 */
function parseScalar(raw: string): string | null {
  const v = raw.trim()
  if (v === '' || v === 'null' || v === '~') return null
  if (v.startsWith('"') && v.endsWith('"')) return v.slice(1, -1)
  if (v.startsWith("'") && v.endsWith("'")) return v.slice(1, -1)
  return v
}

/** Return the indentation level (number of leading spaces). */
function indentOf(line: string): number {
  return line.length - line.trimStart().length
}

/**
 * Parse a simple YAML block (no lists, no nesting deeper than 2 levels).
 *
 * Returns a flat object for the given level and sub-objects for nested blocks.
 */
function parseBlock(lines: string[], start: number, indent: number): { obj: Record<string, any>; nextIndex: number } {
  const obj: Record<string, any> = {}
  let i = start

  while (i < lines.length) {
    const line = lines[i]!
    const trimmed = line.trimStart()

    // Ignore blank lines and comments
    if (trimmed === '' || trimmed.startsWith('#')) {
      i++
      continue
    }

    const currentIndent = indentOf(line)

    // End of the current block: we climbed back to a parent level
    if (currentIndent < indent) break

    // Ignore lines more indented than expected (multi-line data, etc.)
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

    // Look at whether the next non-blank line is more indented → sub-block
    let nextMeaningful = i + 1
    while (nextMeaningful < lines.length && lines[nextMeaningful]!.trim() === '') nextMeaningful++

    const hasSubBlock =
      nextMeaningful < lines.length &&
      lines[nextMeaningful]!.trim() !== '' &&
      !lines[nextMeaningful]!.trimStart().startsWith('#') &&
      indentOf(lines[nextMeaningful]!) > indent

    if (hasSubBlock && rest.trim() === '') {
      // Sub-block
      const { obj: subObj, nextIndex } = parseBlock(lines, i + 1, indentOf(lines[nextMeaningful]!))
      obj[key] = subObj
      i = nextIndex
    } else {
      obj[key] = parseScalar(rest)
      i++
    }
  }

  return { obj, nextIndex: i }
}

/** Parse a complete YAML document into a JS object. */
export function parseYamlMinimal(content: string): Record<string, any> {
  const lines = toLines(content)
  const { obj } = parseBlock(lines, 0, 0)
  return obj
}

/**
 * Extract the YAML frontmatter section (between the `---` markers).
 * Returns null when absent.
 */
export function extractFrontmatter(content: string): string | null {
  const lines = toLines(content)
  if (lines[0]?.trim() !== '---') return null
  const end = lines.findIndex((l, i) => i > 0 && l.trim() === '---')
  if (end < 0) return null
  return lines.slice(1, end).join('\n')
}

// ---------------------------------------------------------------------------
// Null-safe accessors on the parsed data
// ---------------------------------------------------------------------------

/** Extract a null-safe scalar value from an object. */
function str(obj: unknown, key: string): string | null {
  if (!obj || typeof obj !== 'object') return null
  const v = (obj as Record<string, unknown>)[key]
  if (v === null || v === undefined) return null
  if (typeof v === 'string') return v
  return String(v)
}

/** Extract a null-safe sub-object from an object. */
function sub(obj: unknown, key: string): Record<string, any> | null {
  if (!obj || typeof obj !== 'object') return null
  const v = (obj as Record<string, unknown>)[key]
  if (!v || typeof v !== 'object') return null
  return v as Record<string, any>
}

// ---------------------------------------------------------------------------
// Pure normalizers
// ---------------------------------------------------------------------------

/**
 * Normalize a parsed `forge-runs/<ticketId>.yaml` document into the flat shape
 * consumed by the workflow adapter and the dashboard.
 */
export function normalizeForgeRunYaml(raw: Record<string, any>, ticketId: string): Record<string, any> {
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

/** Normalize the frontmatter of a BMAD Story. */
export function normalizeStoryFrontmatterFields(parsed: Record<string, any>): Record<string, any> {
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

/** Result of normalizing a `sprint-status.yaml` document. */
export interface SprintStatus {
  developmentStatus: Map<string, string>
  epicJira: string | null
  sprintGate: string | null
}

/** Normalize a parsed `sprint-status.yaml` document. */
export function normalizeSprintStatus(raw: Record<string, any>): SprintStatus {
  const devStatusRaw = sub(raw, 'development_status')
  const developmentStatus = new Map<string, string>()

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

// ---------------------------------------------------------------------------
// Strict syntax and structure checks
// ---------------------------------------------------------------------------

const STRICT_GATE_FIELDS = ['started_at', 'decided_at', 'human_decision']

/**
 * Dependency-free, deliberately narrow YAML syntax check for authoritative
 * Forge run publication. It accepts the mapping/scalar subset used by copied
 * BMAD runs and fails closed on lists, flow collections, block scalars, tabs,
 * malformed mappings, dangling quoted scalars, and empty block values.
 * Exotic otherwise-valid YAML is intentionally rejected at this boundary.
 */
export function validateStrictForgeYamlSyntax(content: string): boolean {
  const meaningful = toLines(content)
    .map((line, index) => ({ line, index }))
    .filter(({ line }) => line.trim() && !line.trimStart().startsWith('#') && line.trim() !== '---')

  for (let position = 0; position < meaningful.length; position++) {
    const { line } = meaningful[position]!
    if (line.includes('\t')) return false
    const indent = indentOf(line)
    if (indent % 2 !== 0) return false
    const text = line.trim()
    if (text.startsWith('- ') || text.startsWith('[') || text.startsWith('{')) return false

    let quote: string | null = null
    let colon = -1
    for (let i = 0; i < text.length; i++) {
      const char = text[i]!
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
      const first = rawValue[0]!
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

/** Failure envelope of the strict Forge run structure validation. */
export interface ForgeRunStructureFailure {
  ok: false
  error: { code: string; path?: string }
}

/**
 * Validate the structure of an authoritative Forge run document.
 *
 * The check is pure: the caller has already parsed the YAML and read the file.
 */
export function validateForgeRunStructure(
  raw: Record<string, any> | null | undefined,
  ticketId: string
): { ok: true } | ForgeRunStructureFailure {
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
  return { ok: true }
}
