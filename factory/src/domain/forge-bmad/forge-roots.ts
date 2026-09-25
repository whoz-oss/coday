/**
 * Pure Forge roots policy: the store-location vocabulary and the path
 * containment rules that decide where a Forge run store may live.
 *
 * The filesystem-touching resolution (`realpathSync`, `statSync`, `mkdirSync`)
 * lives in `adapters/forge/forge-roots-resolver.ts`; this module only owns the
 * policy constants, the pure containment predicate and the default location.
 *
 * Domain purity: only `node:path` is used; no `node:fs`, HTTP, AgentOS or Git
 * CLI dependency.
 */

import { isAbsolute, join, relative } from 'node:path'

/** Version 2 adds an explicit policy for an externally hosted run store. */
export const FORGE_ROOTS_SCHEMA_VERSION = 2
export const DEFAULT_RUN_STORE_POLICY = 'under_orchestrator'
export const EXTERNAL_RUN_STORE_POLICY = 'external_allowed'
export const REPO_RUN_STORE_POLICY = 'under_repo'

/** The accepted run-store policies, in canonical order. */
export const FORGE_RUN_STORE_POLICIES = Object.freeze([
  DEFAULT_RUN_STORE_POLICY,
  EXTERNAL_RUN_STORE_POLICY,
  REPO_RUN_STORE_POLICY,
] as const)

/** One of the accepted run-store policies. */
export type ForgeRunStorePolicy = (typeof FORGE_RUN_STORE_POLICIES)[number]

/** Resolved, frozen Forge roots returned by the adapter. */
export interface ForgeRoots {
  schemaVersion: number
  orchestratorRoot: string
  runStoreRoot: string
  repoRoot: string
  forgeRoot?: string
  runStorePolicy: ForgeRunStorePolicy
}

/**
 * Path-segment containment: a sibling sharing a text prefix is NOT inside the
 * parent. Uses `relative`, never prefix string matching.
 */
export function isWithin(child: string, parent: string): boolean {
  const rel = relative(parent, child)
  return rel === '' || (!rel.startsWith('..') && !isAbsolute(rel))
}

/**
 * Default run store root: `<repoRoot>/forge/factory-runs/`
 * Use this when the ledgers must live in the target repository, not in the
 * orchestrator.
 */
export function defaultRunStoreRoot(repoRoot: string): string {
  return join(repoRoot, 'forge', 'factory-runs')
}
