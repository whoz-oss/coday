/**
 * Filesystem adapter for the BMAD reads.
 *
 * All parsing is pure and lives in `domain/forge-bmad/forge-bmad-parser.ts`;
 * this adapter only reads (and existence-checks) files, tolerating absence.
 *
 * The TypeScript source is bundled into `factory/runtime/factory-operational.mjs`;
 * `factory/lib/forge-bmad-reader.mjs` re-exports it as a stateless facade.
 */

import { existsSync, readFileSync } from 'node:fs'
import { isAbsolute, join } from 'node:path'
import {
  extractFrontmatter,
  normalizeForgeRunYaml,
  normalizeSprintStatus,
  normalizeStoryFrontmatterFields,
  parseYamlMinimal,
  validateForgeRunStructure,
  validateStrictForgeYamlSyntax,
  type SprintStatus,
} from '../../domain/forge-bmad/forge-bmad-parser.js'

/** Read a text file. Returns null when absent. */
function readFileSafe(filePath: string): string | null {
  if (!existsSync(filePath)) return null
  try {
    return readFileSync(filePath, 'utf8')
  } catch {
    return null
  }
}

/** Read and parse a YAML file. Returns null when absent or unreadable. */
function readYamlFile(filePath: string): Record<string, any> | null {
  const content = readFileSafe(filePath)
  if (content === null) return null
  try {
    return parseYamlMinimal(content)
  } catch {
    return null
  }
}

/**
 * Read `forge/state/forge-runs/<ticketId>.yaml` and return a normalized object.
 * Returns null when the file does not exist and tolerates null YAML values.
 */
export function readForgeRunYaml(repoRoot: string, ticketId: string): Record<string, any> | null {
  const yamlPath = join(repoRoot, 'forge', 'state', 'forge-runs', `${ticketId}.yaml`)
  const raw = readYamlFile(yamlPath)
  if (!raw) return null
  return normalizeForgeRunYaml(raw, ticketId)
}

/**
 * Strict authoritative read for generic publication. This deliberately leaves
 * readForgeRunYaml() permissive for any tolerant read-only consumers.
 */
export function readForgeRunYamlStrict(
  repoRoot: string,
  ticketId: string
): { ok: true; run: Record<string, any> } | { ok: false; error: { code: string; path?: string } } {
  const yamlPath = join(repoRoot, 'forge', 'state', 'forge-runs', `${ticketId}.yaml`)
  if (!existsSync(yamlPath)) return { ok: false, error: { code: 'FORGE_RUN_NOT_FOUND' } }
  let content: string
  try {
    content = readFileSync(yamlPath, 'utf8')
  } catch {
    return { ok: false, error: { code: 'FORGE_RUN_READ_FAILURE' } }
  }
  if (!content.trim()) return { ok: false, error: { code: 'FORGE_RUN_TRUNCATED' } }
  if (!validateStrictForgeYamlSyntax(content)) return { ok: false, error: { code: 'FORGE_RUN_PARSE_INVALID' } }
  let raw: Record<string, any>
  try {
    raw = parseYamlMinimal(content)
  } catch {
    return { ok: false, error: { code: 'FORGE_RUN_PARSE_INVALID' } }
  }
  const structure = validateForgeRunStructure(raw, ticketId)
  if (!structure.ok) return { ok: false, error: structure.error }
  const normalized = readForgeRunYaml(repoRoot, ticketId)
  if (!normalized) return { ok: false, error: { code: 'FORGE_RUN_PARSE_INVALID' } }
  return { ok: true, run: normalized }
}

/**
 * Read the YAML frontmatter of a BMAD Story.
 *
 * `storePath` is either an absolute path or relative to `repoRoot`. Returns null
 * when the file does not exist. Extracts only: status, jira, forge_gate, title,
 * type, created.
 */
export function readStoryFrontmatter(repoRoot: string, storePath: string): Record<string, any> | null {
  const fullPath = isAbsolute(storePath) ? storePath : join(repoRoot, storePath)
  const content = readFileSafe(fullPath)
  if (content === null) return null

  const fmRaw = extractFrontmatter(content)
  if (!fmRaw) return null

  let parsed: Record<string, any>
  try {
    parsed = parseYamlMinimal(fmRaw)
  } catch {
    return null
  }

  return normalizeStoryFrontmatterFields(parsed)
}

/**
 * Read the `sprint-status.yaml` of a workstream.
 * Returns `{ developmentStatus, epicJira, sprintGate }` or null when absent.
 */
export function readSprintStatus(repoRoot: string, workstreamSlug: string): SprintStatus | null {
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
  return normalizeSprintStatus(raw)
}
