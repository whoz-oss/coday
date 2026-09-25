/**
 * Pure Story-spec domain: the Story spec frontmatter parser, structural
 * validation and the deterministic Epic/Story inheritance rules used by G2-US.
 *
 * Disk access (`readStorySpec`, `hashStorySpec`) lives in
 * `adapters/forge/forge-spec-reader.ts`.
 *
 * Domain purity: only `node:crypto` is used; no `node:fs`, HTTP, AgentOS or Git
 * CLI dependency.
 */

import { createHash } from 'node:crypto'

/** Schema version of the Story spec frontmatter. */
export const FORGE_STORY_SPEC_SCHEMA_VERSION = 1

/** Policy version of the G2-US Story gate. */
export const G2_US_POLICY_VERSION = 'forge-g2-us-deterministic-v1'

// Allowed top-level keys in a Story spec frontmatter
const STORY_SPEC_ALLOWED_KEYS = new Set([
  'schemaVersion',
  'workItem',
  'scope',
  'oracles',
  'acceptanceCriteria',
  'impacts',
])

interface CodedError extends Error {
  code?: string
}

function fail(code: string, detail?: string): never {
  const error = new Error(detail ?? code) as CodedError
  error.code = code
  throw error
}

function scalar(value: string): unknown {
  const trimmed = value.trim()
  if (/^(true|false)$/.test(trimmed)) return trimmed === 'true'
  if (/^\d+$/.test(trimmed)) return Number(trimmed)
  if ((trimmed.startsWith('"') && trimmed.endsWith('"')) || (trimmed.startsWith("'") && trimmed.endsWith("'")))
    return trimmed.slice(1, -1)
  return trimmed
}

/**
 * Minimal closed YAML subset — same parser as forge-spec, extended for
 * acceptanceCriteria and impacts (list of strings under root).
 */
export function parseStorySpecFrontmatter(text: string): Record<string, any> {
  const lines = text.split('\n')
  const out: Record<string, any> = {}
  let section: string | null = null
  let list: any[] | null = null
  for (const raw of lines) {
    if (!raw.trim() || raw.trimStart().startsWith('#')) continue
    const indent = raw.length - raw.trimStart().length
    const line = raw.trim()
    if (indent === 0) {
      const match = line.match(/^([A-Za-z][A-Za-z0-9]*):\s*(.*)$/)
      if (!match) fail('G2_FRONTMATTER_INVALID')
      const [, key, value] = match
      if (Object.hasOwn(out, key!)) fail('G2_FRONTMATTER_INVALID')
      if (value) {
        out[key!] = scalar(value)
        section = null
      } else {
        out[key!] = {}
        section = key!
      }
      list = null
      continue
    }
    // Nested mapping under a section (e.g. scope.allow:, workItem.id:)
    if (indent === 2 && section && line.match(/^([A-Za-z][A-Za-z0-9]*):\s*$/)) {
      out[section][line.slice(0, -1)] = []
      list = out[section][line.slice(0, -1)]
      continue
    }
    if (indent === 2 && section) {
      const match = line.match(/^([A-Za-z][A-Za-z0-9]*):\s*(.+)$/)
      if (match) {
        out[section][match[1]!] = scalar(match[2]!)
        list = null
        continue
      }
    }
    // Root-level list items (oracles, acceptanceCriteria, impacts)
    if (indent === 2 && section && line.startsWith('- ')) {
      if (!Array.isArray(out[section])) out[section] = []
      out[section].push(scalar(line.slice(2)))
      continue
    }
    // Nested list items (scope.allow, scope.create, scope.deny)
    if (indent === 4 && list && line.startsWith('- ')) {
      list.push(scalar(line.slice(2)))
      continue
    }
    fail('G2_FRONTMATTER_INVALID')
  }
  return out
}

/** Validate structural requirements of a Story spec frontmatter. */
export function validateStorySpec(data: Record<string, any>): void {
  if (data.schemaVersion !== FORGE_STORY_SPEC_SCHEMA_VERSION) fail('G2_US_SPEC_SCHEMA_UNSUPPORTED')
  // Extra keys not allowed
  for (const key of Object.keys(data)) {
    if (!STORY_SPEC_ALLOWED_KEYS.has(key)) fail('G2_FRONTMATTER_INVALID', `unexpected key: ${key}`)
  }
  // workItem
  if (!data.workItem || typeof data.workItem !== 'object') fail('G2_US_WORK_ITEM_KIND_INVALID')
  if (data.workItem.kind !== 'Story') fail('G2_US_WORK_ITEM_KIND_INVALID')
  if (typeof data.workItem.id !== 'string' || !data.workItem.id) fail('G2_FRONTMATTER_INVALID')
  if (typeof data.workItem.parentId !== 'string' || !data.workItem.parentId) fail('G2_US_PARENT_ID_MISSING')
  // scope
  if (!data.scope || typeof data.scope !== 'object') fail('G2_SCOPE_INVALID')
  for (const key of ['allow', 'create', 'deny']) {
    if (!Array.isArray(data.scope[key]) || data.scope[key].length === 0) fail('G2_SCOPE_INVALID')
  }
  // oracles (optional — may be omitted; when present must be a list)
  if (data.oracles !== undefined && !Array.isArray(data.oracles)) fail('G2_FRONTMATTER_INVALID')
  // acceptanceCriteria / impacts — optional lists of strings
  for (const key of ['acceptanceCriteria', 'impacts']) {
    if (data[key] !== undefined) {
      if (!Array.isArray(data[key])) fail('G2_FRONTMATTER_INVALID')
    }
  }
}

/** A single inheritance violation reported by G2-US. */
export interface InheritanceViolation {
  code: string
  detail: string
}

/**
 * Validate inheritance rules between a Story spec and its Epic spec.
 *
 * Rules:
 *   scope.allow (Story)  ⊆ scope.allow (Epic)   — Story cannot allow more than Epic
 *   scope.create (Story) ⊆ scope.create (Epic)   — idem
 *   scope.deny (Story)   ⊇ scope.deny (Epic)     — Story must deny at least everything Epic denies
 *   oracles (Story)      ⊆ oracles (Epic)        — Story oracles must come from Epic oracle list
 *
 * NOTE: Inheritance is validated by exact string set membership, not by
 * glob/wildcard resolution. This is intentionally conservative for the MVP:
 * ambiguity in wildcard semantics would make the gate non-deterministic.
 */
export function validateInheritance(
  storySpec: Record<string, any>,
  epicSpec: Record<string, any>
): { valid: boolean; violations: InheritanceViolation[] } {
  const violations: InheritanceViolation[] = []

  const epicAllow = new Set(epicSpec.scope?.allow ?? [])
  const epicCreate = new Set(epicSpec.scope?.create ?? [])
  const epicDeny = new Set(epicSpec.scope?.deny ?? [])
  const epicOracles = new Set(epicSpec.oracles ?? [])

  // allow(Story) ⊆ allow(Epic)
  for (const pattern of storySpec.scope?.allow ?? []) {
    if (!epicAllow.has(pattern)) {
      violations.push({ code: 'G2_US_ALLOW_EXCEEDS_EPIC', detail: `allow pattern "${pattern}" not in Epic allow set` })
    }
  }

  // create(Story) ⊆ create(Epic)
  for (const pattern of storySpec.scope?.create ?? []) {
    if (!epicCreate.has(pattern)) {
      violations.push({
        code: 'G2_US_CREATE_EXCEEDS_EPIC',
        detail: `create pattern "${pattern}" not in Epic create set`,
      })
    }
  }

  // deny(Story) ⊇ deny(Epic) — every Epic deny must be present in Story deny
  const storyDeny = new Set(storySpec.scope?.deny ?? [])
  for (const pattern of epicDeny) {
    if (!storyDeny.has(pattern)) {
      violations.push({
        code: 'G2_US_DENY_WEAKER_THAN_EPIC',
        detail: `Epic deny pattern "${pattern}" missing from Story deny set`,
      })
    }
  }

  // oracles(Story) ⊆ oracles(Epic)
  for (const oracle of storySpec.oracles ?? []) {
    if (!epicOracles.has(oracle)) {
      violations.push({
        code: 'G2_US_ORACLE_UNKNOWN_IN_EPIC',
        detail: `oracle "${oracle}" not declared in Epic oracles`,
      })
    }
  }

  return { valid: violations.length === 0, violations }
}

/** Deterministic content hash of a Story spec document. */
export function computeStorySpecHash(content: string): string {
  return `sha256:${createHash('sha256').update(content).digest('hex')}`
}
