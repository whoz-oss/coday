/**
 * agentos-lifecycle.ts
 *
 * JAR presence and version check for AgentOS — single-directory edition.
 *
 * There is exactly one location for AgentOS JARs: <configDir>/agentos/.
 * The layout is:
 *   <configDir>/agentos/agentos-service-<version>.jar
 *   <configDir>/agentos/plugins/agentos-bash-plugin-<version>.jar
 *   <configDir>/agentos/plugins/agentos-file-plugin-<version>.jar
 *   <configDir>/agentos/plugins/agentos-mcp-plugin-<version>.jar
 *   <configDir>/agentos/plugins/agentos-tmux-plugin-<version>.jar
 *
 * THIS MODULE HAS NO SIDE EFFECTS AT IMPORT TIME.
 */

import * as fs from 'fs'
import * as path from 'path'
import { debugLog } from './log'

// ---------------------------------------------------------------------------
// Artifact manifest
// ---------------------------------------------------------------------------

/**
 * Canonical artifact IDs for the AgentOS JARs we own.
 *
 * NAMING CONTRACT — versioned filenames:
 *   "<artifactId>-<version>.jar"
 *
 * The artifactId NEVER contains a digit immediately after a hyphen, which makes
 * the split unambiguous: cut at the first "-<digit>" boundary.
 * Example: "agentos-bash-plugin-0.244.0.jar"
 *          └── artifactId: "agentos-bash-plugin"   version: "0.244.0"
 */
export const AGENTOS_ARTIFACT_IDS = [
  'agentos-service',
  'agentos-bash-plugin',
  'agentos-file-plugin',
  'agentos-mcp-plugin',
  'agentos-tmux-plugin',
] as const

export type AgentosArtifactId = (typeof AGENTOS_ARTIFACT_IDS)[number]

/** Artifacts stored at the root of <configDir>/agentos/ */
const ROOT_ARTIFACTS: readonly AgentosArtifactId[] = ['agentos-service']

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
// Layout helpers
// ---------------------------------------------------------------------------

/**
 * Return the expected absolute path for a given artifact at the given version.
 * Encodes the layout: service at root, plugins in plugins/.
 */
export function expectedJarPath(configDir: string, artifactId: AgentosArtifactId, version: string): string {
  const agentosDir = path.join(configDir, 'agentos')
  if ((ROOT_ARTIFACTS as readonly string[]).includes(artifactId)) {
    return path.join(agentosDir, `${artifactId}-${version}.jar`)
  }
  return path.join(agentosDir, 'plugins', `${artifactId}-${version}.jar`)
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
  /** Expected absolute path for this artifact at the expected version. */
  expectedPath: string
}

export interface AgentosJarStatus {
  /** True only when every expected artifact was found with the expected version. */
  allPresent: boolean
  /** Expected version (from getCodayVersion()). */
  expectedVersion: string
  /** Per-artifact detail. */
  artifacts: ArtifactCheckResult[]
  /** Artifacts that are missing or at the wrong version. */
  missing: AgentosArtifactId[]
}

// ---------------------------------------------------------------------------
// Implementation
// ---------------------------------------------------------------------------

/**
 * Check <configDir>/agentos for the 5 expected AgentOS JARs at the expected version.
 *
 * - Never throws: all I/O errors are caught and logged.
 * - No network access, no process spawn.
 * - Safe to call at any point during server startup.
 *
 * @param configDir        Root config directory (from codayOptions.configDir).
 * @param expectedVersion  Version string to compare against found JARs.
 * @returns A structured status describing what was found and where.
 */
export function checkAgentosJars(configDir: string, expectedVersion: string): AgentosJarStatus {
  const agentosDir = path.join(configDir, 'agentos')
  debugLog('AGENTOS', `Checking JAR availability (expected version: ${expectedVersion})`)
  debugLog('AGENTOS', `AgentOS directory: ${agentosDir}`)

  const artifacts: ArtifactCheckResult[] = AGENTOS_ARTIFACT_IDS.map((artifactId) => {
    const expPath = expectedJarPath(configDir, artifactId, expectedVersion)

    let foundAt: string | null = null
    let foundVersion: string | null = null

    try {
      if (fs.existsSync(expPath)) {
        foundAt = expPath
        foundVersion = expectedVersion
      } else {
        // Check if any other version of this artifact exists (for MISMATCH logging)
        const dir = (ROOT_ARTIFACTS as readonly string[]).includes(artifactId)
          ? agentosDir
          : path.join(agentosDir, 'plugins')
        try {
          const entries = fs.readdirSync(dir).filter((f) => f.endsWith('.jar'))
          for (const filename of entries) {
            const parsed = parseJarFilename(filename)
            if (parsed.artifactId === artifactId) {
              foundAt = path.join(dir, filename)
              foundVersion = parsed.version
              break
            }
          }
        } catch {
          // Directory doesn't exist yet — fine
        }
      }
    } catch {
      // existsSync threw — treat as absent
    }

    const versionMatch = foundVersion === expectedVersion

    if (!foundAt) {
      debugLog('AGENTOS', `  [MISSING]  ${artifactId} — expected at ${expPath}`)
    } else if (!versionMatch) {
      debugLog(
        'AGENTOS',
        `  [MISMATCH] ${artifactId} — found version ${foundVersion} at ${foundAt} (expected ${expectedVersion})`
      )
    } else {
      debugLog('AGENTOS', `  [OK]       ${artifactId}-${foundVersion}.jar`)
    }

    return { artifactId, foundAt, foundVersion, versionMatch, expectedPath: expPath }
  })

  const missing = artifacts.filter((a) => !a.versionMatch).map((a) => a.artifactId)
  const allPresent = missing.length === 0
  const presentCount = artifacts.length - missing.length

  debugLog(
    'AGENTOS',
    allPresent
      ? `JAR check complete: all ${AGENTOS_ARTIFACT_IDS.length} JARs present at version ${expectedVersion}.`
      : `JAR check complete: ${presentCount}/${AGENTOS_ARTIFACT_IDS.length} JARs match version ${expectedVersion} — missing: ${missing.join(', ')}`
  )

  return { allPresent, expectedVersion, artifacts, missing }
}
