/**
 * agentos-lifecycle.ts
 *
 * Minimal JAR presence check for AgentOS.
 *
 * THIS MODULE HAS NO SIDE EFFECTS AT IMPORT TIME.
 * All filesystem access is strictly inside the exported function.
 *
 * Current scope: check and log whether the expected JARs are present on disk.
 * No download, no Java detection, no process spawn, no network access.
 */

import * as fs from 'fs'
import * as os from 'os'
import * as path from 'path'
import { fileURLToPath } from 'url'
import { debugLog } from './log'

// ---------------------------------------------------------------------------
// JAR manifest
// ---------------------------------------------------------------------------

/**
 * Stable, version-less names of the AgentOS JARs.
 *
 * CONTRACT: these names are fixed by the CI job `upload-agentos-jars` in
 * .github/workflows/release.yml, which copies built artifacts to stable names
 * before uploading them as GitHub Release assets.
 * NEVER use version-prefix matching (e.g. "agentos-bash-plugin-*.jar").
 * Resolution MUST be by exact name only.
 */
const AGENTOS_JARS = [
  'agentos-service.jar',
  'agentos-bash-plugin.jar',
  'agentos-file-plugin.jar',
  'agentos-mcp-plugin.jar',
  'agentos-tmux-plugin.jar',
] as const

type AgentosJarName = (typeof AGENTOS_JARS)[number]

// ---------------------------------------------------------------------------
// Candidate directories
// ---------------------------------------------------------------------------

/**
 * Ordered list of directories where we look for the JARs.
 *
 * The list is intentionally easy to extend: add new entries here and the
 * check loop will pick them up automatically.
 *
 * - `__dirname` / 'agentos' : historical location when JARs were bundled
 *   inside the npm tarball (dist/agentos/).  No longer populated, kept as a
 *   reference sentinel so we can confirm the old path is absent.
 * - `~/.coday/agentos`      : natural cache location aligned with the rest of
 *   Coday's persistence model (~/.coday/).  This is the intended home for
 *   on-demand downloaded JARs in a future iteration.
 */
function getCandidateDirectories(): string[] {
  // Resolve the directory of this module in a way that works both with:
  //   - tsx (ESM native, no __dirname)
  //   - esbuild bundle (injects a __dirname shim via the `banner` option in project.json)
  // Using import.meta.url is the portable ESM approach; it is available in both contexts.
  const moduleDir = path.dirname(fileURLToPath(import.meta.url))

  return [
    // Historical bundle location (relative to compiled output)
    path.resolve(moduleDir, 'agentos'),
    // User-level cache location (aligned with ~/.coday persistence model)
    path.join(os.homedir(), '.coday', 'agentos'),
  ]
}

// ---------------------------------------------------------------------------
// Public types
// ---------------------------------------------------------------------------

export interface JarCheckResult {
  /** Name of the JAR file (stable, version-less). */
  name: AgentosJarName
  /** Absolute path where the JAR was found, or null if absent everywhere. */
  foundAt: string | null
}

export interface AgentosJarStatus {
  /** True only when every expected JAR was found in at least one candidate directory. */
  allPresent: boolean
  /** Per-JAR detail: name + where it was found (or null). */
  jars: JarCheckResult[]
  /** Candidate directories that were inspected. */
  inspectedDirectories: string[]
}

// ---------------------------------------------------------------------------
// Implementation
// ---------------------------------------------------------------------------

/**
 * Check whether the AgentOS JARs are present on disk and log the result.
 *
 * - Never throws: all I/O errors are caught and logged.
 * - No network access.
 * - No process spawn.
 * - Safe to call at any point during server startup.
 *
 * @returns A structured status describing what was found and where.
 */
export async function checkAgentosJars(): Promise<AgentosJarStatus> {
  const candidates = getCandidateDirectories()
  const candidateCount = candidates.length
  debugLog(
    'AGENTOS',
    `Checking JAR availability in ${candidateCount} candidate director${candidateCount === 1 ? 'y' : 'ies'}:`
  )
  for (const dir of candidates) {
    debugLog('AGENTOS', `  * ${dir}`)
  }

  const results: JarCheckResult[] = []

  for (const jarName of AGENTOS_JARS) {
    let foundAt: string | null = null

    for (const dir of candidates) {
      const fullPath = path.join(dir, jarName)
      try {
        fs.accessSync(fullPath, fs.constants.R_OK)
        // File exists and is readable
        foundAt = fullPath
        break // First match wins; no need to check further candidates
      } catch {
        // Not found or not readable at this location — try the next candidate
      }
    }

    results.push({ name: jarName, foundAt })

    if (foundAt) {
      debugLog('AGENTOS', `  [OK] ${jarName} — found at ${foundAt}`)
    } else {
      debugLog('AGENTOS', `  [MISSING] ${jarName} — not found in any candidate directory`)
    }
  }

  const allPresent = results.every((r) => r.foundAt !== null)
  const presentCount = results.filter((r) => r.foundAt !== null).length

  debugLog(
    'AGENTOS',
    allPresent
      ? `JAR check complete: all ${AGENTOS_JARS.length} JARs present.`
      : `JAR check complete: ${presentCount}/${AGENTOS_JARS.length} JARs present — AgentOS will not start until the missing JARs are available.`
  )

  return {
    allPresent,
    jars: results,
    inspectedDirectories: candidates,
  }
}
