/**
 * Filesystem adapter for the Forge spec reads.
 *
 * The Epic/Story frontmatter parsers and validators are pure and live in
 * `domain/forge-bmad/forge-spec.ts` and `domain/forge-bmad/forge-story-spec.ts`;
 * this adapter owns the filesystem boundary (`realpathSync`, `statSync`,
 * `readFileSync`) and the root-confinement checks.
 *
 * The TypeScript source is bundled into `factory/runtime/factory-operational.mjs`;
 * `factory/lib/forge-spec.mjs` and `factory/lib/forge-story-spec.mjs` re-export
 * it as stateless facades.
 */

import { readFileSync, realpathSync, statSync } from 'node:fs'
import { isAbsolute, relative, resolve } from 'node:path'
import {
  FORGE_SPEC_FRONTMATTER_PATTERN,
  computeForgeSpecHash,
  parseForgeSpecFrontmatter,
  validateForgeSpecSchema,
  type CodedError,
  type ForgeSpecWorkItem,
} from '../../domain/forge-bmad/forge-spec.js'
import {
  computeStorySpecHash,
  parseStorySpecFrontmatter,
  validateStorySpec,
} from '../../domain/forge-bmad/forge-story-spec.js'

/** Roots carrying the repo/forge confinement used by the spec readers. */
export interface ForgeSpecRoots {
  repoRoot: string
  forgeRoot?: string
}

function inside(child: string, root: string): boolean {
  const rel = relative(root, child)
  return rel === '' || (!rel.startsWith('..') && !isAbsolute(rel))
}

function fail(code: string): never {
  const error = new Error(code) as CodedError
  error.code = code
  throw error
}

/** Load and validate an Epic spec, checking the file is within the allowed roots. */
export function loadForgeSpec({
  specPath,
  roots,
  workItem,
}: {
  specPath: string
  roots: ForgeSpecRoots
  workItem: ForgeSpecWorkItem
}): {
  path: string
  sha256: string
  schemaVersion: number
  frontmatter: Record<string, any>
} {
  if (typeof specPath !== 'string' || !isAbsolute(specPath)) fail('G2_SPEC_PATH_INVALID')
  let path: string
  try {
    path = realpathSync(resolve(specPath))
    if (!statSync(path).isFile()) fail('G2_SPEC_PATH_INVALID')
  } catch (error) {
    if ((error as CodedError).code?.startsWith('G2_')) throw error
    fail('G2_SPEC_PATH_INVALID')
  }
  if (!inside(path!, roots.repoRoot) && !(roots.forgeRoot && inside(path!, roots.forgeRoot)))
    fail('G2_SPEC_OUTSIDE_ROOT')
  const content = readFileSync(path!, 'utf8')
  const match = content.match(FORGE_SPEC_FRONTMATTER_PATTERN)
  if (!match) fail('G2_FRONTMATTER_MISSING')
  const frontmatter = parseForgeSpecFrontmatter(match[1]!)
  validateForgeSpecSchema(frontmatter, workItem)
  return {
    path: path!,
    sha256: computeForgeSpecHash(content),
    schemaVersion: frontmatter.schemaVersion,
    frontmatter,
  }
}

/**
 * Read and structurally validate a Story spec from disk.
 * Checks that the file is within the allowed roots before reading.
 */
export function readStorySpec(
  specPath: string,
  roots: ForgeSpecRoots
): { path: string; sha256: string; schemaVersion: number; frontmatter: Record<string, any>; rawContent: string } {
  if (typeof specPath !== 'string' || !isAbsolute(specPath)) fail('G2_US_SPEC_PATH_INVALID')
  let realPath: string
  try {
    realPath = realpathSync(resolve(specPath))
    if (!statSync(realPath).isFile()) fail('G2_US_SPEC_PATH_INVALID')
  } catch (error) {
    if ((error as CodedError).code?.startsWith('G2_')) throw error
    fail('G2_US_SPEC_PATH_INVALID')
  }
  if (!inside(realPath!, roots.repoRoot) && !(roots.forgeRoot && inside(realPath!, roots.forgeRoot)))
    fail('G2_US_SPEC_OUTSIDE_ROOT')

  const rawContent = readFileSync(realPath!, 'utf8')
  const match = rawContent.match(FORGE_SPEC_FRONTMATTER_PATTERN)
  if (!match) fail('G2_FRONTMATTER_MISSING')

  const frontmatter = parseStorySpecFrontmatter(match[1]!)
  validateStorySpec(frontmatter)

  return {
    path: realPath!,
    sha256: computeStorySpecHash(rawContent),
    schemaVersion: frontmatter.schemaVersion,
    frontmatter,
    rawContent,
  }
}

/** Compute the SHA-256 hash of a Story spec file without full validation. */
export function hashStorySpec(specPath: string): string {
  if (typeof specPath !== 'string' || !isAbsolute(specPath)) fail('G2_US_SPEC_PATH_INVALID')
  let realPath: string
  try {
    realPath = realpathSync(resolve(specPath))
    if (!statSync(realPath).isFile()) fail('G2_US_SPEC_PATH_INVALID')
  } catch (error) {
    if ((error as CodedError).code?.startsWith('G2_')) throw error
    fail('G2_US_SPEC_PATH_INVALID')
  }
  const content = readFileSync(realPath!, 'utf8')
  return computeStorySpecHash(content)
}
