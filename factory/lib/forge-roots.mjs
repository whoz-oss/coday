import { existsSync, mkdirSync, realpathSync, statSync } from 'node:fs'
import { basename, dirname, isAbsolute, join, relative, resolve } from 'node:path'

/** Version 2 adds an explicit policy for an externally hosted run store. */
export const FORGE_ROOTS_SCHEMA_VERSION = 2
export const DEFAULT_RUN_STORE_POLICY = 'under_orchestrator'
export const EXTERNAL_RUN_STORE_POLICY = 'external_allowed'
export const REPO_RUN_STORE_POLICY = 'under_repo'

function resolveExistingDirectory(value, field) {
  if (typeof value !== 'string' || !value.trim()) throw new Error(`${field} is required`)
  if (!isAbsolute(value)) throw new Error(`${field} must be an absolute path`)
  try {
    const real = realpathSync(resolve(value))
    if (!statSync(real).isDirectory()) throw new Error('not a directory')
    return real
  } catch { throw new Error(`${field} must exist as a directory and resolve without a broken symlink`) }
}

function isWithin(child, parent) {
  const rel = relative(parent, child)
  return rel === '' || (!rel.startsWith('..') && !isAbsolute(rel))
}

/**
 * The store itself may be absent: Factory owns its idempotent creation. Its
 * immediate parent is nevertheless explicit, existing, and realpath-checked.
 */
function resolveStoreRoot(value) {
  if (typeof value !== 'string' || !value.trim()) throw new Error('roots.runStoreRoot is required')
  if (!isAbsolute(value)) throw new Error('roots.runStoreRoot must be an absolute path')
  const requested = resolve(value)
  // The requested parent may traverse a platform symlink (for example /var
  // versus /private/var on macOS). Rebuild the direct child from the *real*
  // parent and basename, never by string slicing/prefix matching.
  const parent = resolveExistingDirectory(dirname(requested), 'roots.runStoreParent')
  const candidate = join(parent, basename(requested))
  if (existsSync(candidate)) return resolveExistingDirectory(candidate, 'roots.runStoreRoot')
  return candidate
}

export function resolveForgeRoots(input) {
  if (!input || typeof input !== 'object') throw new Error('roots object is required')
  const orchestratorRoot = resolveExistingDirectory(input.orchestratorRoot, 'roots.orchestratorRoot')
  const repoRoot = resolveExistingDirectory(input.repoRoot, 'roots.repoRoot')
  const forgeRoot = input.forgeRoot === undefined ? undefined : resolveExistingDirectory(input.forgeRoot, 'roots.forgeRoot')
  const runStoreRoot = resolveStoreRoot(input.runStoreRoot)
  const runStorePolicy = input.runStorePolicy ?? DEFAULT_RUN_STORE_POLICY
  if (![DEFAULT_RUN_STORE_POLICY, EXTERNAL_RUN_STORE_POLICY, REPO_RUN_STORE_POLICY].includes(runStorePolicy)) {
    throw new Error(`roots.runStorePolicy must be ${DEFAULT_RUN_STORE_POLICY}, ${EXTERNAL_RUN_STORE_POLICY}, or ${REPO_RUN_STORE_POLICY}`)
  }
  if (runStorePolicy === DEFAULT_RUN_STORE_POLICY && !isWithin(runStoreRoot, orchestratorRoot)) {
    throw new Error('roots.runStoreRoot must remain under roots.orchestratorRoot unless runStorePolicy is external_allowed')
  }
  if (runStorePolicy === REPO_RUN_STORE_POLICY && !isWithin(runStoreRoot, repoRoot)) {
    throw new Error('roots.runStoreRoot must remain under roots.repoRoot when runStorePolicy is under_repo')
  }
  return Object.freeze({ schemaVersion: FORGE_ROOTS_SCHEMA_VERSION, orchestratorRoot, runStoreRoot, repoRoot, ...(forgeRoot ? { forgeRoot } : {}), runStorePolicy })
}

/**
 * Default run store root: <repoRoot>/forge/factory-runs/
 * Use this when the ledgers must live in the target repository, not in the orchestrator.
 */
export function defaultRunStoreRoot(repoRoot) {
  return join(repoRoot, 'forge', 'factory-runs')
}

export function ensureForgeRunStore(roots) {
  mkdirSync(roots.runStoreRoot, { recursive: true })
  return resolveExistingDirectory(roots.runStoreRoot, 'roots.runStoreRoot')
}
