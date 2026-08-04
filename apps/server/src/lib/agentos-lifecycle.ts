/**
 * agentos-lifecycle.ts
 *
 * JAR presence and version check for AgentOS.
 *
 * THIS MODULE HAS NO SIDE EFFECTS AT IMPORT TIME.
 * All filesystem access is strictly inside the exported functions.
 *
 * Current scope: scan candidate directories, identify AgentOS JARs by their
 * versioned filename, compare found versions against the expected version, and
 * log the result. No download, no Java detection, no process spawn, no network.
 */

import * as fs from 'fs'
import * as os from 'os'
import * as path from 'path'
import { fileURLToPath } from 'url'
import { debugLog } from './log'

// ---------------------------------------------------------------------------
// Artifact manifest
// ---------------------------------------------------------------------------

/**
 * Canonical artifact IDs for the AgentOS JARs we own.
 *
 * NAMING CONTRACT — versioned filenames, same convention as `deployPlugins` in
 * agentos/build.gradle.kts:
 *
 *   "<artifactId>-<version>.jar"
 *
 * The artifactId NEVER contains a digit immediately after a hyphen, which makes
 * the split unambiguous: cut at the first "-<digit>" boundary.
 * Example: "agentos-bash-plugin-0.239.0.jar"
 *          └── artifactId: "agentos-bash-plugin"   version: "0.239.0"
 *
 * WHY version-prefix matching is now the expected approach (and why it must
 * be done correctly):
 *   The historical bug was NOT in using prefixes per se — it was an inconsistency
 *   between the producer (CI renamed to version-less names) and the consumer
 *   (code expected version-less names). Now both sides agree: versioned names,
 *   split on "-<digit>". Use `parseJarFilename()` to derive artifactId and version;
 *   NEVER use a raw `startsWith('agentos-bash-plugin-')` without the digit check.
 */
export const AGENTOS_ARTIFACT_IDS = [
  'agentos-service',
  'agentos-bash-plugin',
  'agentos-file-plugin',
  'agentos-mcp-plugin',
  'agentos-tmux-plugin',
] as const

export type AgentosArtifactId = (typeof AGENTOS_ARTIFACT_IDS)[number]

// ---------------------------------------------------------------------------
// Filename parsing — pure function, exported for unit testing
// ---------------------------------------------------------------------------

export interface ParsedJarFilename {
  /** The artifact ID derived from the filename (part before first "-<digit>"). */
  artifactId: string
  /**
   * The version string derived from the filename (part after first "-<digit>"),
   * or null if the filename has no version component.
   */
  version: string | null
}

/**
 * Parse a JAR filename into its artifactId and version components.
 *
 * Convention (from deployPlugins in agentos/build.gradle.kts):
 *   "<artifactId>-<version>.jar"
 * where the artifactId never contains a digit immediately after a hyphen.
 * The split point is the first "-<digit>" boundary.
 *
 * Examples:
 *   "agentos-bash-plugin-0.239.0.jar"  → { artifactId: "agentos-bash-plugin", version: "0.239.0" }
 *   "agentos-service-0.239.0.jar"      → { artifactId: "agentos-service",      version: "0.239.0" }
 *   "my-custom-plugin-1.2.3.jar"       → { artifactId: "my-custom-plugin",     version: "1.2.3" }
 *   "something.jar"                    → { artifactId: "something",            version: null }
 *
 * @param filename  Basename of the JAR file (with or without `.jar` extension).
 */
export function parseJarFilename(filename: string): ParsedJarFilename {
  // Strip .jar extension if present
  const nameWithoutExt = filename.endsWith('.jar') ? filename.slice(0, -4) : filename

  // Split on the first "-<digit>" boundary
  const match = nameWithoutExt.match(/^(.*?)-([0-9].*)$/)
  if (!match) {
    return { artifactId: nameWithoutExt, version: null }
  }

  return { artifactId: match[1]!, version: match[2]! }
}

// ---------------------------------------------------------------------------
// Candidate directories
// ---------------------------------------------------------------------------

/**
 * Ordered list of directories to scan for AgentOS JARs.
 *
 * Easy to extend: add new entries here and the check loop picks them up.
 *
 * - `moduleDir/agentos`  : historical location when JARs were bundled inside
 *   the npm tarball (dist/agentos/). No longer populated; kept as a sentinel
 *   so logs confirm the old path is absent.
 * - `~/.coday/agentos`   : user-level cache aligned with Coday's persistence
 *   model (~/.coday/). Intended home for on-demand downloaded JARs.
 */
function getCandidateDirectories(): string[] {
  // Portable ESM-compatible path resolution — works with tsx (no __dirname)
  // and with the esbuild bundle (which injects a __dirname shim via `banner`).
  const moduleDir = path.dirname(fileURLToPath(import.meta.url))

  return [path.resolve(moduleDir, 'agentos'), path.join(os.homedir(), '.coday', 'agentos')]
}

// ---------------------------------------------------------------------------
// Public types
// ---------------------------------------------------------------------------

export interface ArtifactCheckResult {
  /** Canonical artifact ID (e.g. "agentos-bash-plugin"). */
  artifactId: AgentosArtifactId
  /** Absolute path of the JAR file found, or null if absent. */
  foundAt: string | null
  /** Version string parsed from the filename, or null if not found. */
  foundVersion: string | null
  /** True when the found version matches the expected version. */
  versionMatch: boolean
}

export interface AgentosJarStatus {
  /** True only when every expected artifact was found with the expected version. */
  allPresent: boolean
  /** Expected version (from getCodayVersion()). */
  expectedVersion: string
  /** Per-artifact detail. */
  artifacts: ArtifactCheckResult[]
  /** JARs present in scanned directories that are NOT one of our artifact IDs. */
  unknownJars: string[]
  /** Candidate directories that were inspected. */
  inspectedDirectories: string[]
}

// ---------------------------------------------------------------------------
// Implementation
// ---------------------------------------------------------------------------

/**
 * Scan candidate directories for AgentOS JARs, compare versions, and log.
 *
 * - Never throws: all I/O errors are caught and logged.
 * - No network access, no process spawn.
 * - Safe to call at any point during server startup.
 *
 * @param expectedVersion  Version string to compare against found JARs.
 * @returns A structured status describing what was found and where.
 */
export async function checkAgentosJars(expectedVersion: string): Promise<AgentosJarStatus> {
  const candidates = getCandidateDirectories()
  debugLog('AGENTOS', `Checking JAR availability (expected version: ${expectedVersion})`)
  debugLog('AGENTOS', `Scanning ${candidates.length} candidate director${candidates.length === 1 ? 'y' : 'ies'}:`)
  for (const dir of candidates) {
    debugLog('AGENTOS', `  * ${dir}`)
  }

  // Build a map: artifactId → { path, version } for the first match found
  const found = new Map<string, { filePath: string; version: string | null }>()
  const unknownJars: string[] = []

  for (const dir of candidates) {
    let entries: string[]
    try {
      entries = fs.readdirSync(dir).filter((f) => f.endsWith('.jar'))
    } catch {
      // Directory does not exist or is not readable — expected for absent candidates
      continue
    }

    for (const filename of entries) {
      const { artifactId, version } = parseJarFilename(filename)
      const isOurs = (AGENTOS_ARTIFACT_IDS as readonly string[]).includes(artifactId)

      if (!isOurs) {
        const fullPath = path.join(dir, filename)
        unknownJars.push(fullPath)
        debugLog('AGENTOS', `  [UNKNOWN] ${filename} — not one of our artifacts (${fullPath})`)
        continue
      }

      // First match per artifactId wins (candidates are ordered by priority)
      if (!found.has(artifactId)) {
        found.set(artifactId, { filePath: path.join(dir, filename), version })
      }
    }
  }

  // Build per-artifact results
  const artifacts: ArtifactCheckResult[] = AGENTOS_ARTIFACT_IDS.map((artifactId) => {
    const entry = found.get(artifactId) ?? null
    const foundVersion = entry?.version ?? null
    const versionMatch = foundVersion === expectedVersion

    if (!entry) {
      debugLog('AGENTOS', `  [MISSING]  ${artifactId} — not found in any candidate directory`)
    } else if (!versionMatch) {
      debugLog(
        'AGENTOS',
        `  [MISMATCH] ${artifactId} — found ${foundVersion} at ${entry.filePath} (expected ${expectedVersion})`
      )
    } else {
      debugLog('AGENTOS', `  [OK]       ${artifactId}-${foundVersion}.jar — ${entry.filePath}`)
    }

    return {
      artifactId,
      foundAt: entry?.filePath ?? null,
      foundVersion,
      versionMatch,
    }
  })

  const allPresent = artifacts.every((a) => a.versionMatch)
  const presentCount = artifacts.filter((a) => a.versionMatch).length

  debugLog(
    'AGENTOS',
    allPresent
      ? `JAR check complete: all ${AGENTOS_ARTIFACT_IDS.length} JARs present at version ${expectedVersion}.`
      : `JAR check complete: ${presentCount}/${AGENTOS_ARTIFACT_IDS.length} JARs match version ${expectedVersion} — AgentOS will not start until all JARs are available at the expected version.`
  )

  return {
    allPresent,
    expectedVersion,
    artifacts,
    unknownJars,
    inspectedDirectories: candidates,
  }
}
