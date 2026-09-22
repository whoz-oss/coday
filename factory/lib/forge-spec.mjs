import { createHash } from 'node:crypto'
import { readFileSync, realpathSync, statSync } from 'node:fs'
import { isAbsolute, relative, resolve } from 'node:path'

export const FORGE_SPEC_SCHEMA_VERSION = 1
export const G2_POLICY_VERSION = 'forge-g2-deterministic-v1'
export const ORACLE_CATALOG = new Set(['front.build', 'front.tests', 'back.build'])

function inside(child, root) {
  const rel = relative(root, child)
  return rel === '' || (!rel.startsWith('..') && !isAbsolute(rel))
}
function fail(code) {
  const error = new Error(code)
  error.code = code
  throw error
}
function scalar(value) {
  const trimmed = value.trim()
  if (/^(true|false)$/.test(trimmed)) return trimmed === 'true'
  if (/^\d+$/.test(trimmed)) return Number(trimmed)
  if ((trimmed.startsWith('"') && trimmed.endsWith('"')) || (trimmed.startsWith("'") && trimmed.endsWith("'")))
    return trimmed.slice(1, -1)
  return trimmed
}
/** Minimal closed YAML subset: mappings and block scalar lists only. */
function parseFrontmatter(text) {
  const lines = text.split('\n')
  const out = {}
  let section = null
  let list = null
  for (const raw of lines) {
    if (!raw.trim() || raw.trimStart().startsWith('#')) continue
    const indent = raw.length - raw.trimStart().length
    const line = raw.trim()
    if (indent === 0) {
      const match = line.match(/^([A-Za-z][A-Za-z0-9]*):\s*(.*)$/)
      if (!match) fail('G2_FRONTMATTER_INVALID')
      const [, key, value] = match
      if (Object.hasOwn(out, key)) fail('G2_FRONTMATTER_INVALID')
      if (value) {
        out[key] = scalar(value)
        section = null
      } else {
        out[key] = {}
        section = key
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
        out[section][match[1]] = scalar(match[2])
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
function validatePattern(pattern) {
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
function validateSpec(data, workItem) {
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
    data.oracles.some((oracle) => typeof oracle !== 'string' || !ORACLE_CATALOG.has(oracle))
  )
    fail('G2_ORACLE_UNKNOWN')
  if (Object.keys(data).some((key) => !['schemaVersion', 'workItem', 'scope', 'oracles'].includes(key)))
    fail('G2_FRONTMATTER_INVALID')
}
export function loadForgeSpec({ specPath, roots, workItem }) {
  if (typeof specPath !== 'string' || !isAbsolute(specPath)) fail('G2_SPEC_PATH_INVALID')
  let path
  try {
    path = realpathSync(resolve(specPath))
    if (!statSync(path).isFile()) fail('G2_SPEC_PATH_INVALID')
  } catch (error) {
    if (error.code?.startsWith('G2_')) throw error
    fail('G2_SPEC_PATH_INVALID')
  }
  if (!inside(path, roots.repoRoot) && !(roots.forgeRoot && inside(path, roots.forgeRoot))) fail('G2_SPEC_OUTSIDE_ROOT')
  const content = readFileSync(path, 'utf8')
  const match = content.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n/)
  if (!match) fail('G2_FRONTMATTER_MISSING')
  const frontmatter = parseFrontmatter(match[1])
  validateSpec(frontmatter, workItem)
  return {
    path,
    sha256: `sha256:${createHash('sha256').update(content).digest('hex')}`,
    schemaVersion: frontmatter.schemaVersion,
    frontmatter,
  }
}
