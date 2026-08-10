/**
 * agentos-download.ts
 *
 * Download all 5 AgentOS JARs from GitHub Release assets, verify their
 * SHA-256 checksums, write them atomically to disk, and clean up stale JARs.
 *
 * Active layout:
 *   <configDir>/agentos/agentos-service-<version>.jar
 *   <configDir>/agentos/plugins/agentos-bash-plugin-<version>.jar
 *   ... (4 plugins)
 *
 * Background update layout (next/):
 *   <configDir>/agentos/next/agentos-service-<version>.jar
 *   <configDir>/agentos/next/plugins/agentos-bash-plugin-<version>.jar
 *   ... (same structure)
 *
 * On next startup, checkAndSwapNext() promotes next/ to the active layout.
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
  | 'skipped:already-in-next' // next/ already contains all JARs at the target version
  | 'error:manifest-unavailable' // checksums.sha256 returned non-2xx
  | 'error:manifest-parse' // checksums.sha256 could not be parsed
  | 'error:download-failed' // One or more JARs failed to download/verify/write
  | 'success' // All needed JARs downloaded, verified, written; stale JARs cleaned

export interface DownloadResult {
  outcome: DownloadOutcome
  /** Human-readable description of the outcome. */
  message: string
  /** Number of JARs successfully downloaded. */
  downloaded?: number
  /** Number of stale JARs removed. */
  cleaned?: number
}

export type SwapOutcome =
  | 'swapped' // next/ promoted to active, old JARs cleaned
  | 'skipped:no-next' // next/ does not exist
  | 'skipped:incomplete' // next/ exists but missing some JARs at target version
  | 'error' // rename/unlink failed

export interface SwapResult {
  outcome: SwapOutcome
  message: string
  version?: string
}

// ---------------------------------------------------------------------------
// URL builders
// ---------------------------------------------------------------------------

function buildJarUrl(artifactId: string, version: string): string {
  return `https://github.com/whoz-oss/coday/releases/download/release/${version}/${artifactId}-${version}.jar`
}

function buildManifestUrl(version: string): string {
  return `https://github.com/whoz-oss/coday/releases/download/release/${version}/checksums.sha256`
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function extractHashFromManifest(manifestText: string, targetFilename: string): string | null {
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

function sha256hex(data: Buffer): string {
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
function cleanDirectory(dir: string, allowedFilenames: Set<string>): number {
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
// Internal: download all 5 JARs into an arbitrary target directory
// ---------------------------------------------------------------------------

/**
 * Download all 5 AgentOS JARs for `version` into `targetDir`.
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
// Public: swap next/ into active layout
// ---------------------------------------------------------------------------

/**
 * Check whether <configDir>/agentos/next/ contains all 5 JARs at targetVersion,
 * and if so promote them to the active layout via atomic renames, then clean up.
 *
 * Called at startup BEFORE the inventory check.
 */
export function checkAndSwapNext(configDir: string, targetVersion: string): SwapResult {
  const agentosDir = path.join(configDir, 'agentos')
  const nextDir = path.join(agentosDir, 'next')
  const activePluginsDir = path.join(agentosDir, 'plugins')

  if (!fs.existsSync(nextDir)) {
    return { outcome: 'skipped:no-next', message: 'No next/ directory — nothing to swap' }
  }

  // Verify completeness
  const missing = AGENTOS_ARTIFACT_IDS.filter((id) => !fs.existsSync(jarDestPath(nextDir, id, targetVersion)))
  if (missing.length > 0) {
    debugLog(
      'AGENTOS',
      `[SWAP] next/ incomplete for version ${targetVersion} — missing: ${missing.join(', ')} — skipping`
    )
    return { outcome: 'skipped:incomplete', message: `next/ missing: ${missing.join(', ')}` }
  }

  debugLog('AGENTOS', `[SWAP] Promoting next/ (version ${targetVersion}) to active layout...`)

  try {
    // Move service JAR
    fs.renameSync(
      jarDestPath(nextDir, 'agentos-service', targetVersion),
      path.join(agentosDir, `agentos-service-${targetVersion}.jar`)
    )
    debugLog('AGENTOS', `[SWAP]   agentos-service-${targetVersion}.jar`)

    // Move plugin JARs
    fs.mkdirSync(activePluginsDir, { recursive: true })
    for (const artifactId of AGENTOS_ARTIFACT_IDS.filter((id) => id !== 'agentos-service')) {
      fs.renameSync(
        jarDestPath(nextDir, artifactId, targetVersion),
        path.join(activePluginsDir, `${artifactId}-${targetVersion}.jar`)
      )
      debugLog('AGENTOS', `[SWAP]   ${artifactId}-${targetVersion}.jar`)
    }

    // Remove next/ entirely
    fs.rmSync(nextDir, { recursive: true, force: true })
    debugLog('AGENTOS', `[SWAP] next/ removed`)

    // Strict cleanup of old versions
    const allowedRoot = new Set([`agentos-service-${targetVersion}.jar`])
    const allowedPlugins = new Set(
      AGENTOS_ARTIFACT_IDS.filter((id) => id !== 'agentos-service').map((id) => `${id}-${targetVersion}.jar`)
    )
    const cleaned = cleanDirectory(agentosDir, allowedRoot) + cleanDirectory(activePluginsDir, allowedPlugins)
    if (cleaned > 0) debugLog('AGENTOS', `[SWAP] Removed ${cleaned} stale JAR(s) from previous version`)

    debugLog('AGENTOS', `[SWAP] Complete — AgentOS ${targetVersion} is now active`)
    return { outcome: 'swapped', message: `Promoted version ${targetVersion} from next/`, version: targetVersion }
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err)
    debugLog('AGENTOS', `[SWAP] Error during swap: ${msg}`)
    return { outcome: 'error', message: msg }
  }
}

// ---------------------------------------------------------------------------
// Public: background pre-fetch into next/
// ---------------------------------------------------------------------------

/**
 * Download all 5 AgentOS JARs for targetVersion into <configDir>/agentos/next/.
 * Skips silently if next/ already contains all JARs at targetVersion.
 * Fire-and-forget safe — never throws.
 *
 * @param configDir     Root config directory.
 * @param targetVersion Version to pre-fetch (AGENTOS_BUNDLED_VERSION).
 */
export async function downloadAgentosJarsToNext(configDir: string, targetVersion: string): Promise<DownloadResult> {
  if (targetVersion === 'dev') {
    return { outcome: 'skipped:dev-version', message: "Version is 'dev' — nothing to pre-fetch" }
  }

  const nextDir = path.join(configDir, 'agentos', 'next')

  // Skip if next/ already complete
  const alreadyComplete = AGENTOS_ARTIFACT_IDS.every((id) => fs.existsSync(jarDestPath(nextDir, id, targetVersion)))
  if (alreadyComplete) {
    debugLog('AGENTOS', `[UPDATE] next/ already complete for version ${targetVersion} — skipping`)
    return { outcome: 'skipped:already-in-next', message: `next/ already complete for ${targetVersion}` }
  }

  debugLog('AGENTOS', `[UPDATE] Pre-fetching version ${targetVersion} into next/ (background)...`)

  try {
    const { downloaded } = await downloadAllToDir(nextDir, targetVersion, '[UPDATE]')
    debugLog(
      'AGENTOS',
      `[UPDATE] Pre-fetch complete: ${downloaded} JAR(s) ready in next/. Will activate on next Coday startup.`
    )
    return { outcome: 'success', message: `Pre-fetched ${downloaded} JAR(s) to next/`, downloaded }
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err)
    debugLog('AGENTOS', `[UPDATE] Pre-fetch failed (non-fatal): ${msg}`)
    return { outcome: 'error:download-failed', message: msg }
  }
}

// ---------------------------------------------------------------------------
// Public: blocking download to active layout (first-run / missing JARs)
// ---------------------------------------------------------------------------

/**
 * Download all missing AgentOS JARs directly to the active layout, then clean up stale JARs.
 * Used only on first run or when JARs are missing/corrupted.
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
    `[DOWNLOAD] Starting download of ${missingArtifacts.length} artifact(s) for version ${expectedVersion}`
  )
  debugLog('AGENTOS', `[DOWNLOAD] Missing: ${missingArtifacts.join(', ')}`)

  try {
    await downloadAllToDir(agentosDir, expectedVersion, '[DOWNLOAD]')
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err)
    debugLog('AGENTOS', `[DOWNLOAD] Download failed: ${msg}`)
    return { outcome: 'error:download-failed', message: msg }
  }

  // Strict cleanup
  const allowedRoot = new Set([`agentos-service-${expectedVersion}.jar`])
  const allowedPlugins = new Set(
    AGENTOS_ARTIFACT_IDS.filter((id) => id !== 'agentos-service').map((id) => `${id}-${expectedVersion}.jar`)
  )
  debugLog('AGENTOS', '[DOWNLOAD] Running strict cleanup...')
  const cleanedRoot = cleanDirectory(agentosDir, allowedRoot)
  const cleanedPlugins = cleanDirectory(pluginsDir, allowedPlugins)
  const totalCleaned = cleanedRoot + cleanedPlugins
  if (totalCleaned > 0) {
    debugLog('AGENTOS', `[DOWNLOAD] Cleanup: removed ${totalCleaned} stale file(s)`)
  } else {
    debugLog('AGENTOS', '[DOWNLOAD] Cleanup: no stale files found')
  }

  return {
    outcome: 'success',
    message: `Downloaded ${missingArtifacts.length} JAR(s), cleaned ${totalCleaned} stale file(s)`,
    downloaded: missingArtifacts.length,
    cleaned: totalCleaned,
  }
}
