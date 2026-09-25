/**
 * Pure Epic-spec domain: the Epic spec frontmatter parser, its closed YAML
 * subset, structural validation and content hashing.
 *
 * Disk access (`loadForgeSpec`) lives in
 * `adapters/forge/forge-spec-reader.ts`.
 *
 * Domain purity: only `node:crypto` is used; no `node:fs`, HTTP, AgentOS or Git
 * CLI dependency.
 */

import { createHash } from 'node:crypto'

/** Schema version of the Epic spec frontmatter. */
export const FORGE_SPEC_SCHEMA_VERSION = 1

/** Policy version of the G2 Epic gate. */
export const G2_POLICY_VERSION = 'forge-g2-deterministic-v1'

/** The closed oracle catalog accepted by the Epic spec. */
export const ORACLE_CATALOG: ReadonlySet<string> = new Set(['front.build', 'front.tests', 'back.build'])

/** A work item (Epic or Story) understood by the G2 validators. */
export interface ForgeSpecWorkItem {
  id: string
  kind: string
}

/** A validation error carrying the machine-readable Forge code. */
export interface CodedError extends Error {
  code?: string
}

/** Throw a coded validation error. */
function fail(code: string): never {
  const error = new Error(code) as CodedError
  error.code = code
  throw error
}

/** Scalar conversion of a closed YAML value. */
function scalar(value: string): unknown {
  const trimmed = value.trim()
  if (/^(true|false)$/.test(trimmed)) return trimmed === 'true'
  if (/^\d+$/.test(trimmed)) return Number(trimmed)
  if ((trimmed.startsWith('"') && trimmed.endsWith('"')) || (trimmed.startsWith("'") && trimmed.endsWith("'")))
    return trimmed.slice(1, -1)
  return trimmed
}

/** Minimal closed YAML subset: mappings and block scalar lists only. */
export function parseForgeSpecFrontmatter(text: string): Record<string, any> {
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
    if (indent === 2 && section && line.startsWith('- ') && section === 'oracles') {
      if (!Array.isArray(out.oracles)) out.oracles = []
      out.oracles.push(scalar(line.slice(2)))
      continue
    }
    if (indent === 4 && list && line.startsWith('- ')) {
      list.push(scalar(line.slice(2)))
      continue
    }
    fail('G2_FRONTMATTER_INVALID')
  }
  return out
}

// Patterns are repo-relative POSIX paths. Exact paths and a terminal '/**' are
// supported; '*' matches one non-empty segment. '..', absolute paths, backslash,
// empty segments and glob forms other than '*' / terminal '**' are rejected.
function validatePattern(pattern: unknown): void {
  if (
    typeof pattern !== 'string' ||
    !pattern ||
    pattern.includes('\\') ||
    pattern.startsWith('/') ||
    pattern.includes('..') ||
    pattern.includes('//')
  )
    fail('G2_SCOPE_PATTERN_INVALID')
  const parts = pattern.split('/')
  if (parts.some((part) => !part || (part !== '*' && part !== '**' && !/^[A-Za-z0-9._@-]+$/.test(part))))
    fail('G2_SCOPE_PATTERN_INVALID')
  if (parts.includes('**') && parts.at(-1) !== '**') fail('G2_SCOPE_PATTERN_INVALID')
}

/** Structural validation of the Epic spec frontmatter. */
export function validateForgeSpecSchema(data: Record<string, any>, workItem: ForgeSpecWorkItem): void {
  if (data.schemaVersion !== FORGE_SPEC_SCHEMA_VERSION) fail('G2_SPEC_SCHEMA_UNSUPPORTED')
  if (!data.workItem || data.workItem.id !== workItem.id || data.workItem.kind !== workItem.kind)
    fail('G2_WORK_ITEM_MISMATCH')
  if (!data.scope || typeof data.scope !== 'object') fail('G2_SCOPE_INVALID')
  for (const key of ['allow', 'create', 'deny']) {
    if (!Array.isArray(data.scope[key]) || data.scope[key].length === 0) fail('G2_SCOPE_INVALID')
    data.scope[key].forEach(validatePattern)
  }
  if (
    !Array.isArray(data.oracles) ||
    data.oracles.some((oracle: unknown) => typeof oracle !== 'string' || !ORACLE_CATALOG.has(oracle))
  )
    fail('G2_ORACLE_UNKNOWN')
  if (Object.keys(data).some((key) => !['schemaVersion', 'workItem', 'scope', 'oracles'].includes(key)))
    fail('G2_FRONTMATTER_INVALID')
}

/** Deterministic content hash of a spec document. */
export function computeForgeSpecHash(content: string): string {
  return `sha256:${createHash('sha256').update(content).digest('hex')}`
}

/** Match the frontmatter block of a Markdown spec document. */
export const FORGE_SPEC_FRONTMATTER_PATTERN = /^---\r?\n([\s\S]*?)\r?\n---\r?\n/
