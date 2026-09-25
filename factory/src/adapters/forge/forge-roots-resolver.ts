/**
 * Filesystem adapter for the Forge roots resolution.
 *
 * The policy vocabulary, the containment predicate and the default location
 * live in `domain/forge-bmad/forge-roots.ts`; this adapter owns the filesystem
 * checks (`realpathSync`, `statSync`, `existsSync`, `mkdirSync`).
 *
 * The TypeScript source is bundled into `factory/runtime/factory-operational.mjs`;
 * `factory/lib/forge-roots.mjs` re-exports it as a stateless facade.
 */

import { existsSync, mkdirSync, realpathSync, statSync } from 'node:fs'
import { basename, dirname, isAbsolute, join, resolve } from 'node:path'
import {
  DEFAULT_RUN_STORE_POLICY,
  EXTERNAL_RUN_STORE_POLICY,
  FORGE_ROOTS_SCHEMA_VERSION,
  FORGE_RUN_STORE_POLICIES,
  REPO_RUN_STORE_POLICY,
  isWithin,
  type ForgeRoots,
  type ForgeRunStorePolicy,
} from '../../domain/forge-bmad/forge-roots.js'

function resolveExistingDirectory(value: unknown, field: string): string {
  if (typeof value !== 'string' || !value.trim()) throw new Error(`${field} is required`)
  if (!isAbsolute(value)) throw new Error(`${field} must be an absolute path`)
  try {
    const real = realpathSync(resolve(value))
    if (!statSync(real).isDirectory()) throw new Error('not a directory')
    return real
  } catch {
    throw new Error(`${field} must exist as a directory and resolve without a broken symlink`)
  }
}

/**
 * The store itself may be absent: Factory owns its idempotent creation. Its
 * immediate parent is nevertheless explicit, existing, and realpath-checked.
 */
function resolveStoreRoot(value: unknown): string {
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

/** Resolve, validate and freeze the Forge roots. */
export function resolveForgeRoots(input: any): ForgeRoots {
  if (!input || typeof input !== 'object') throw new Error('roots object is required')
  const orchestratorRoot = resolveExistingDirectory(input.orchestratorRoot, 'roots.orchestratorRoot')
  const repoRoot = resolveExistingDirectory(input.repoRoot, 'roots.repoRoot')
  const forgeRoot =
    input.forgeRoot === undefined ? undefined : resolveExistingDirectory(input.forgeRoot, 'roots.forgeRoot')
  const runStoreRoot = resolveStoreRoot(input.runStoreRoot)
  const runStorePolicy: unknown = input.runStorePolicy ?? DEFAULT_RUN_STORE_POLICY
  if (!FORGE_RUN_STORE_POLICIES.includes(runStorePolicy as ForgeRunStorePolicy)) {
    throw new Error(
      `roots.runStorePolicy must be ${DEFAULT_RUN_STORE_POLICY}, ${EXTERNAL_RUN_STORE_POLICY}, or ${REPO_RUN_STORE_POLICY}`
    )
  }
  const policy = runStorePolicy as ForgeRunStorePolicy
  if (policy === DEFAULT_RUN_STORE_POLICY && !isWithin(runStoreRoot, orchestratorRoot)) {
    throw new Error(
      'roots.runStoreRoot must remain under roots.orchestratorRoot unless runStorePolicy is external_allowed'
    )
  }
  if (policy === REPO_RUN_STORE_POLICY && !isWithin(runStoreRoot, repoRoot)) {
    throw new Error('roots.runStoreRoot must remain under roots.repoRoot when runStorePolicy is under_repo')
  }
  return Object.freeze({
    schemaVersion: FORGE_ROOTS_SCHEMA_VERSION,
    orchestratorRoot,
    runStoreRoot,
    repoRoot,
    ...(forgeRoot ? { forgeRoot } : {}),
    runStorePolicy: policy,
  })
}

/** Idempotently create the run store directory and return its real path. */
export function ensureForgeRunStore(roots: Pick<ForgeRoots, 'runStoreRoot'>): string {
  mkdirSync(roots.runStoreRoot, { recursive: true })
  return resolveExistingDirectory(roots.runStoreRoot, 'roots.runStoreRoot')
}
