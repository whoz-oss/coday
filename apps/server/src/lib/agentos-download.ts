/**
 * agentos-download.ts
 *
 * Download every AgentOS JAR (see AGENTOS_ARTIFACT_IDS) from GitHub Release assets,
 * verify their SHA-256 checksums, write them atomically to disk, and clean up stale JARs.
 *
 * Layout:
 *   <configDir>/agentos/agentos-service-<version>.jar
 *   <configDir>/agentos/plugins/agentos-<name>-plugin-<version>.jar
 *   ... (one per plugin)
 *
 * The requested version always wins: if the JARs on disk are not at the requested
 * version, the requested version is downloaded (blocking) and every other version is
 * removed. There is no cache that can take precedence over the requested version.
 *
 * INVARIANTS:
 *   - Never throws. Every error is caught, logged, and returned as a structured result.
 *   - No side effects at import time.
 *   - No new npm dependencies (uses Node built-in fetch + node:crypto + node:fs).
 */

import * as crypto from 'crypto'
import * as fs from 'fs'
import * as path from 'path'
import { debugLog } from './log'
import { AGENTOS_ARTIFACT_IDS, AgentosArtifactId } from './agentos-lifecycle'
import { releaseBaseUrl } from './release-url'

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Timeout for each individual HTTP request. */
const FETCH_TIMEOUT_MS = 60_000

// ---------------------------------------------------------------------------
// Public types
// ---------------------------------------------------------------------------

export type DownloadOutcome =
  | 'skipped:all-present' // All JARs already at the expected version
  | 'skipped:dev-version' // version is 'dev' — no release to download from
  | 'error:download-failed' // Manifest fetch, or one or more JARs failed to download/verify/write
  | 'success' // All JARs downloaded, verified, written; stale JARs cleaned

export interface DownloadResult {
  outcome: DownloadOutcome
  /** Human-readable description of the outcome. */
  message: string
  /** Number of JARs successfully downloaded. */
  downloaded?: number
  /** Number of stale JARs removed. */
  cleaned?: number
}

// ---------------------------------------------------------------------------
// URL builders
// ---------------------------------------------------------------------------

function buildJarUrl(artifactId: string, version: string): string {
  return `${releaseBaseUrl(version)}/${artifactId}-${version}.jar`
}

function buildManifestUrl(version: string): string {
  return `${releaseBaseUrl(version)}/checksums.sha256`
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

export function extractHashFromManifest(manifestText: string, targetFilename: string): string | null {
  for (const line of manifestText.split('\n')) {
    const trimmed = line.trim()
    if (!trimmed) continue
    const spaceIdx = trimmed.indexOf('  ')
    if (spaceIdx === -1) continue
    const hash = trimmed.slice(0, spaceIdx).trim()
    const filename = trimmed.slice(spaceIdx + 2).trim()
    if (filename === targetFilename && hash.length === 64) return hash
  }
  return null
}

export function sha256hex(data: Buffer): string {
  return crypto.createHash('sha256').update(data).digest('hex')
}

/**
 * Compute the dest path for an artifact inside an arbitrary base directory.
 * Service JAR at root, plugins in plugins/.
 */
function jarDestPath(baseDir: string, artifactId: AgentosArtifactId, version: string): string {
  return artifactId === 'agentos-service'
    ? path.join(baseDir, `${artifactId}-${version}.jar`)
    : path.join(baseDir, 'plugins', `${artifactId}-${version}.jar`)
}

/**
 * Remove any JAR/.tmp in dir that is NOT in allowedFilenames. Never throws.
 */
export function cleanDirectory(dir: string, allowedFilenames: Set<string>): number {
  let removed = 0
  try {
    for (const filename of fs.readdirSync(dir).filter((f) => f.endsWith('.jar') || f.endsWith('.tmp'))) {
      if (!allowedFilenames.has(filename)) {
        const fullPath = path.join(dir, filename)
        try {
          fs.unlinkSync(fullPath)
          debugLog('AGENTOS', `  [CLEAN] Removed stale file: ${fullPath}`)
          removed++
        } catch (err) {
          debugLog('AGENTOS', `  [CLEAN] Failed to remove ${fullPath}: ${err instanceof Error ? err.message : err}`)
        }
      }
    }
  } catch {
    // Directory doesn't exist — nothing to clean
  }
  return removed
}

// ---------------------------------------------------------------------------
// Internal: download all JARs into an arbitrary target directory
// ---------------------------------------------------------------------------

/**
 * Download all AgentOS JARs for `version` into `targetDir`.
 * Creates targetDir and targetDir/plugins/ if needed.
 * Throws on any error — callers must catch.
 */
async function downloadAllToDir(targetDir: string, version: string, label: string): Promise<{ downloaded: number }> {
  fs.mkdirSync(path.join(targetDir, 'plugins'), { recursive: true })

  const manifestUrl = buildManifestUrl(version)
  debugLog('AGENTOS', `${label} Fetching manifest for version ${version}: ${manifestUrl}`)

  const mc = new AbortController()
  const mt = setTimeout(() => mc.abort(), FETCH_TIMEOUT_MS)
  let manifestRes: Response
  try {
    manifestRes = await fetch(manifestUrl, { signal: mc.signal })
  } finally {
    clearTimeout(mt)
  }
  debugLog('AGENTOS', `${label} Manifest HTTP ${manifestRes.status}`)
  if (!manifestRes.ok) throw new Error(`Manifest HTTP ${manifestRes.status}`)
  const manifestText = await manifestRes.text()
  debugLog('AGENTOS', `${label} Manifest fetched (${manifestText.length} chars)`)

  let downloaded = 0
  for (const artifactId of AGENTOS_ARTIFACT_IDS) {
    const jarFilename = `${artifactId}-${version}.jar`
    const dest = jarDestPath(targetDir, artifactId, version)
    const jarUrl = buildJarUrl(artifactId, version)

    debugLog('AGENTOS', `${label} Downloading ${jarFilename}...`)
    debugLog('AGENTOS', `${label}   URL: ${jarUrl}`)

    const expectedHash = extractHashFromManifest(manifestText, jarFilename)
    if (!expectedHash) throw new Error(`No manifest entry for '${jarFilename}'`)
    debugLog('AGENTOS', `${label}   Expected SHA-256: ${expectedHash}`)

    const jc = new AbortController()
    const jt = setTimeout(() => jc.abort(), FETCH_TIMEOUT_MS)
    let jarRes: Response
    try {
      jarRes = await fetch(jarUrl, { signal: jc.signal })
    } finally {
      clearTimeout(jt)
    }
    debugLog('AGENTOS', `${label}   JAR HTTP ${jarRes.status}`)
    if (!jarRes.ok) throw new Error(`JAR HTTP ${jarRes.status} for '${jarFilename}'`)

    const jarBuffer = Buffer.from(await jarRes.arrayBuffer())
    debugLog('AGENTOS', `${label}   Received ${jarBuffer.length} bytes`)

    const computedHash = sha256hex(jarBuffer)
    if (computedHash !== expectedHash) throw new Error(`Checksum mismatch for '${jarFilename}'`)
    debugLog('AGENTOS', `${label}   Checksum OK`)

    const tmp = `${dest}.tmp`
    try {
      fs.writeFileSync(tmp, jarBuffer)
      fs.renameSync(tmp, dest)
      debugLog('AGENTOS', `${label}   Written to ${dest} (${jarBuffer.length} bytes)`)
      downloaded++
    } catch (err) {
      try {
        fs.unlinkSync(tmp)
      } catch {
        /* ignore */
      }
      throw err
    }
  }

  return { downloaded }
}

// ---------------------------------------------------------------------------
// Public: blocking download of the requested version
// ---------------------------------------------------------------------------

/**
 * Download the full AgentOS JAR set at `expectedVersion` when any artifact is absent or at
 * another version, then remove every JAR that is not at `expectedVersion`.
 *
 * The requested version always takes precedence over whatever is on disk: callers must
 * wait for this to finish, then start `expectedVersion` (never an older installed one).
 *
 * @param configDir        Root config directory.
 * @param expectedVersion  Version string from getAgentosVersion().
 * @param missingArtifacts Artifact IDs that are absent or at the wrong version.
 */
export async function downloadAgentosJars(
  configDir: string,
  expectedVersion: string,
  missingArtifacts: AgentosArtifactId[]
): Promise<DownloadResult> {
  if (missingArtifacts.length === 0) {
    debugLog('AGENTOS', '[DOWNLOAD] All JARs already present at the expected version — skipping download')
    return { outcome: 'skipped:all-present', message: 'All JARs already present at the expected version' }
  }

  if (expectedVersion === 'dev') {
    debugLog('AGENTOS', "[DOWNLOAD] Version is 'dev' sentinel — no release to download from")
    return { outcome: 'skipped:dev-version', message: "Version is 'dev' sentinel — no release URL available" }
  }

  const agentosDir = path.join(configDir, 'agentos')
  const pluginsDir = path.join(agentosDir, 'plugins')

  debugLog(
    'AGENTOS',
    `[DOWNLOAD] Version ${expectedVersion} not fully installed — downloading all ${AGENTOS_ARTIFACT_IDS.length} artifact(s)`
  )
  debugLog('AGENTOS', `[DOWNLOAD] Missing or at another version: ${missingArtifacts.join(', ')}`)

  let downloaded: number
  try {
    ;({ downloaded } = await downloadAllToDir(agentosDir, expectedVersion, '[DOWNLOAD]'))
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err)
    debugLog('AGENTOS', `[DOWNLOAD] Download failed: ${msg}`)
    return { outcome: 'error:download-failed', message: msg }
  }

  // Strict cleanup: only the requested version may remain
  const allowedRoot = new Set([`agentos-service-${expectedVersion}.jar`])
  const allowedPlugins = new Set(
    AGENTOS_ARTIFACT_IDS.filter((id) => id !== 'agentos-service').map((id) => `${id}-${expectedVersion}.jar`)
  )
  debugLog('AGENTOS', '[DOWNLOAD] Running strict cleanup...')
  // Drop the leftover next/ staging directory from the former background pre-fetch flow
  try {
    fs.rmSync(path.join(agentosDir, 'next'), { recursive: true, force: true })
  } catch {
    /* ignore */
  }
  const totalCleaned = cleanDirectory(agentosDir, allowedRoot) + cleanDirectory(pluginsDir, allowedPlugins)
  debugLog(
    'AGENTOS',
    totalCleaned > 0
      ? `[DOWNLOAD] Cleanup: removed ${totalCleaned} stale file(s)`
      : '[DOWNLOAD] Cleanup: no stale files found'
  )

  return {
    outcome: 'success',
    message: `Downloaded ${downloaded} JAR(s), cleaned ${totalCleaned} stale file(s)`,
    downloaded,
    cleaned: totalCleaned,
  }
}
