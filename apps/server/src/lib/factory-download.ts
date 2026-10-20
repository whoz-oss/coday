/**
 * factory-download.ts
 *
 * JAR inventory, download and upgrade flow for the managed Factory. Mirrors
 * agentos-download.ts.
 *
 * Active layout (single directory, mirrors agentos/):
 *   <configDir>/factory/factory-service-<version>.jar
 *   <configDir>/factory/plugins/factory-forge-plugin-<version>.jar
 *
 * Background update layout (next/):
 *   <configDir>/factory/next/factory-service-<version>.jar
 *   <configDir>/factory/next/plugins/factory-forge-plugin-<version>.jar
 *
 * Assets come from the GitHub Release of that version (`release/<version>`, or
 * `workstream-<slug>-<n>` for workstream prereleases; same release as the AgentOS
 * JARs), verified against `checksums.sha256`.
 *
 * Upgrade flow (same as AgentOS):
 *   1. checkAndSwapNextFactory()   promote a complete next/ to the active layout
 *   2. target JARs present         start them
 *   3. target missing, older set   start the older set, downloadFactoryJarsToNext()
 *      installed                   in the background (activated on next startup)
 *   4. nothing installed           downloadFactoryJars() blocking, then start
 *
 * INVARIANTS: never throws, no side effects at import time, no new dependency.
 */

import * as fs from 'fs'
import * as path from 'path'
import { debugLog } from './log'
import { cleanDirectory, extractHashFromManifest, sha256hex } from './agentos-download'
import { parseJarFilename } from './agentos-lifecycle'
import { releaseBaseUrl } from './release-url'

const FETCH_TIMEOUT_MS = 60_000

export const FACTORY_ARTIFACT_IDS = ['factory-service', 'factory-forge-plugin'] as const
export type FactoryArtifactId = (typeof FACTORY_ARTIFACT_IDS)[number]

export type FactoryDownloadOutcome =
  | 'skipped:all-present' // All JARs already at the expected version
  | 'skipped:dev-version' // version is 'dev' — no release to download from
  | 'skipped:already-in-next' // next/ already contains all JARs at the target version
  | 'error:download-failed' // manifest/JAR fetch, checksum or write failed
  | 'success'

export interface FactoryDownloadResult {
  outcome: FactoryDownloadOutcome
  message: string
}

export type FactorySwapOutcome =
  | 'swapped' // next/ promoted to active, old JARs cleaned
  | 'skipped:no-next' // next/ does not exist
  | 'skipped:incomplete' // next/ exists but misses some JARs at the target version
  | 'error' // rename failed

export interface FactorySwapResult {
  outcome: FactorySwapOutcome
  message: string
}

// ---------------------------------------------------------------------------
// Layout
// ---------------------------------------------------------------------------

/** Service JAR at the root of `baseDir`, plugins in `baseDir/plugins/`. */
function jarDestPath(baseDir: string, artifactId: FactoryArtifactId, version: string): string {
  return artifactId === 'factory-service'
    ? path.join(baseDir, `${artifactId}-${version}.jar`)
    : path.join(baseDir, 'plugins', `${artifactId}-${version}.jar`)
}

/** Active-layout path of an artifact. */
export function factoryJarPath(configDir: string, artifactId: FactoryArtifactId, version: string): string {
  return jarDestPath(path.join(configDir, 'factory'), artifactId, version)
}

/** Artifacts absent at the expected version. */
export function findMissingFactoryJars(configDir: string, version: string): FactoryArtifactId[] {
  return FACTORY_ARTIFACT_IDS.filter((id) => !fs.existsSync(factoryJarPath(configDir, id, version)))
}

function listVersionsOf(dir: string, artifactId: FactoryArtifactId): string[] {
  try {
    const versions: string[] = []
    for (const filename of fs.readdirSync(dir)) {
      if (!filename.endsWith('.jar')) continue
      const parsed = parseJarFilename(filename)
      if (parsed.artifactId === artifactId && parsed.version !== null) versions.push(parsed.version)
    }
    return versions
  } catch {
    return [] // directory does not exist yet
  }
}

/**
 * Version of the coherent JAR set currently on disk in the active layout (every artifact
 * present at that same version), or null when there is none (first run, partial set).
 */
export function findInstalledFactoryVersion(configDir: string): string | null {
  const factoryDir = path.join(configDir, 'factory')
  const perArtifact = FACTORY_ARTIFACT_IDS.map((id) =>
    listVersionsOf(id === 'factory-service' ? factoryDir : path.join(factoryDir, 'plugins'), id)
  )
  const [candidates = [], ...others] = perArtifact
  return candidates.find((version) => others.every((versions) => versions.includes(version))) ?? null
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

async function fetchWithTimeout(url: string): Promise<Response> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS)
  try {
    return await fetch(url, { signal: controller.signal })
  } finally {
    clearTimeout(timer)
  }
}

/**
 * Download every Factory JAR for `version` into `targetDir` (atomic writes, SHA-256 verified).
 * Creates targetDir and targetDir/plugins/. Throws on any error — callers must catch.
 */
async function downloadAllToDir(targetDir: string, version: string, label: string): Promise<void> {
  fs.mkdirSync(path.join(targetDir, 'plugins'), { recursive: true })

  const baseUrl = releaseBaseUrl(version)
  debugLog('FACTORY', `${label} Fetching manifest for ${version}: ${baseUrl}/checksums.sha256`)
  const manifestRes = await fetchWithTimeout(`${baseUrl}/checksums.sha256`)
  debugLog('FACTORY', `${label} Manifest HTTP ${manifestRes.status}`)
  if (!manifestRes.ok) throw new Error(`Manifest HTTP ${manifestRes.status}`)
  const manifestText = await manifestRes.text()

  for (const artifactId of FACTORY_ARTIFACT_IDS) {
    const filename = `${artifactId}-${version}.jar`
    const dest = jarDestPath(targetDir, artifactId, version)

    const expectedHash = extractHashFromManifest(manifestText, filename)
    if (!expectedHash) throw new Error(`No manifest entry for '${filename}'`)

    debugLog('FACTORY', `${label} ${filename}`)
    const jarRes = await fetchWithTimeout(`${baseUrl}/${filename}`)
    if (!jarRes.ok) throw new Error(`JAR HTTP ${jarRes.status} for '${filename}'`)
    const buffer = Buffer.from(await jarRes.arrayBuffer())
    if (sha256hex(buffer) !== expectedHash) throw new Error(`Checksum mismatch for '${filename}'`)

    const tmp = `${dest}.tmp`
    try {
      fs.writeFileSync(tmp, buffer)
      fs.renameSync(tmp, dest)
    } catch (err) {
      try {
        fs.unlinkSync(tmp)
      } catch {
        /* ignore */
      }
      throw err
    }
    debugLog('FACTORY', `${label}   written ${dest} (${buffer.length} bytes)`)
  }
}

/** Remove from the active layout every JAR that is not at `version`. */
function cleanActiveLayout(factoryDir: string, version: string): number {
  return (
    cleanDirectory(factoryDir, new Set([`factory-service-${version}.jar`])) +
    cleanDirectory(path.join(factoryDir, 'plugins'), new Set([`factory-forge-plugin-${version}.jar`]))
  )
}

// ---------------------------------------------------------------------------
// Public: swap next/ into the active layout
// ---------------------------------------------------------------------------

/**
 * Promote <configDir>/factory/next/ to the active layout when it holds every JAR at
 * `targetVersion`, then clean up the previous version. Called at startup BEFORE the
 * inventory check.
 */
export function checkAndSwapNextFactory(configDir: string, targetVersion: string): FactorySwapResult {
  const factoryDir = path.join(configDir, 'factory')
  const nextDir = path.join(factoryDir, 'next')

  if (!fs.existsSync(nextDir)) {
    return { outcome: 'skipped:no-next', message: 'No next/ directory — nothing to swap' }
  }

  const missing = FACTORY_ARTIFACT_IDS.filter((id) => !fs.existsSync(jarDestPath(nextDir, id, targetVersion)))
  if (missing.length > 0) {
    debugLog('FACTORY', `[SWAP] next/ incomplete for ${targetVersion} — missing: ${missing.join(', ')} — skipping`)
    return { outcome: 'skipped:incomplete', message: `next/ missing: ${missing.join(', ')}` }
  }

  debugLog('FACTORY', `[SWAP] Promoting next/ (version ${targetVersion}) to active layout...`)
  try {
    fs.mkdirSync(path.join(factoryDir, 'plugins'), { recursive: true })
    for (const artifactId of FACTORY_ARTIFACT_IDS) {
      fs.renameSync(jarDestPath(nextDir, artifactId, targetVersion), jarDestPath(factoryDir, artifactId, targetVersion))
      debugLog('FACTORY', `[SWAP]   ${artifactId}-${targetVersion}.jar`)
    }
    fs.rmSync(nextDir, { recursive: true, force: true })

    const cleaned = cleanActiveLayout(factoryDir, targetVersion)
    if (cleaned > 0) debugLog('FACTORY', `[SWAP] Removed ${cleaned} stale JAR(s) from previous version`)

    debugLog('FACTORY', `[SWAP] Complete — Factory ${targetVersion} is now active`)
    return { outcome: 'swapped', message: `Promoted version ${targetVersion} from next/` }
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err)
    debugLog('FACTORY', `[SWAP] Error during swap: ${msg}`)
    return { outcome: 'error', message: msg }
  }
}

// ---------------------------------------------------------------------------
// Public: background pre-fetch into next/
// ---------------------------------------------------------------------------

/**
 * Download every Factory JAR for `version` into <configDir>/factory/next/, to be activated
 * on the next startup. Skips when next/ is already complete. Fire-and-forget safe.
 */
export async function downloadFactoryJarsToNext(configDir: string, version: string): Promise<FactoryDownloadResult> {
  if (version === 'dev') {
    return { outcome: 'skipped:dev-version', message: "Version is 'dev' — nothing to pre-fetch" }
  }

  const nextDir = path.join(configDir, 'factory', 'next')
  if (FACTORY_ARTIFACT_IDS.every((id) => fs.existsSync(jarDestPath(nextDir, id, version)))) {
    debugLog('FACTORY', `[UPDATE] next/ already complete for ${version} — skipping`)
    return { outcome: 'skipped:already-in-next', message: `next/ already complete for ${version}` }
  }

  debugLog('FACTORY', `[UPDATE] Pre-fetching ${version} into next/ (background)...`)
  try {
    await downloadAllToDir(nextDir, version, '[UPDATE]')
    debugLog('FACTORY', '[UPDATE] Pre-fetch complete. Will activate on next Coday startup.')
    return { outcome: 'success', message: `Pre-fetched ${FACTORY_ARTIFACT_IDS.length} JAR(s) to next/` }
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err)
    debugLog('FACTORY', `[UPDATE] Pre-fetch failed (non-fatal): ${msg}`)
    return { outcome: 'error:download-failed', message: msg }
  }
}

// ---------------------------------------------------------------------------
// Public: blocking download to the active layout (first run / nothing usable)
// ---------------------------------------------------------------------------

/**
 * Download every missing Factory JAR for `version` straight into the active layout, then
 * remove stale JARs of other versions.
 */
export async function downloadFactoryJars(configDir: string, version: string): Promise<FactoryDownloadResult> {
  const missing = findMissingFactoryJars(configDir, version)
  if (missing.length === 0) {
    return { outcome: 'skipped:all-present', message: 'All Factory JARs already present at the expected version' }
  }
  if (version === 'dev') {
    return { outcome: 'skipped:dev-version', message: "Version is 'dev' sentinel — no release URL available" }
  }

  const factoryDir = path.join(configDir, 'factory')
  try {
    await downloadAllToDir(factoryDir, version, '[DOWNLOAD]')
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err)
    debugLog('FACTORY', `[DOWNLOAD] Failed: ${msg}`)
    return { outcome: 'error:download-failed', message: msg }
  }

  const cleaned = cleanActiveLayout(factoryDir, version)
  debugLog('FACTORY', `[DOWNLOAD] Done — ${cleaned} stale file(s) removed`)
  return { outcome: 'success', message: `Downloaded ${missing.length} JAR(s), cleaned ${cleaned} stale file(s)` }
}
