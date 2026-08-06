/**
 * agentos-download.ts
 *
 * Attempt to download a single AgentOS JAR from the GitHub Release assets.
 *
 * SCOPE: one artefact only — agentos-bash-plugin — to validate the download
 * chain end-to-end (URL reachability, public access without auth, checksum
 * verification, atomic write to disk). Extending to other artefacts is
 * intentionally left for a later iteration once the chain is validated.
 *
 * NAMING CONTRACT NOTE:
 *   The release 0.239.0 (and earlier) published assets with version-less names:
 *   "agentos-bash-plugin.jar", "checksums.sha256", etc.
 *   Starting from the NEXT release, assets will use versioned names:
 *   "agentos-bash-plugin-<version>.jar", etc.
 *   The code below builds URLs with versioned names (the target contract).
 *   On the current release this will produce 404s — that is EXPECTED and logged
 *   as a known transition artefact, NOT a bug. The 404s will disappear once the
 *   first release with the new naming lands.
 *
 * INVARIANTS (same as the rest of this module family):
 *   - Never throws. Every error is caught, logged, and returned as a structured result.
 *   - No side effects at import time.
 *   - No new npm dependencies (uses Node 24 built-in fetch + node:crypto + node:fs).
 *   - No process spawn.
 *   - No network access unless the JAR is absent or at the wrong version.
 */

import * as crypto from 'crypto'
import * as fs from 'fs'
import * as os from 'os'
import * as path from 'path'
import { debugLog } from './log'

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/**
 * The only artefact we attempt to download in this initial probe.
 * Deliberately limited to the smallest JAR (~35 KB) to validate the chain
 * without risking a 227 MB download (agentos-service.jar) at server startup.
 */
const PROBE_ARTIFACT_ID = 'agentos-bash-plugin'

/**
 * Timeout for each individual HTTP request (JAR download + manifest download).
 * 10 s is generous for a ~35 KB file on a normal connection; it avoids hanging
 * the server startup indefinitely on a slow or unreachable network.
 */
const FETCH_TIMEOUT_MS = 10_000

// ---------------------------------------------------------------------------
// Public types
// ---------------------------------------------------------------------------

export type DownloadOutcome =
  | 'skipped:already-present' // JAR already at the expected version — no network access
  | 'skipped:dev-version' // getCodayVersion() returned 'dev' — no release to download from
  | 'error:manifest-unavailable' // checksums.sha256 returned non-2xx
  | 'error:manifest-parse' // checksums.sha256 could not be parsed
  | 'error:jar-unavailable' // JAR URL returned non-2xx (e.g. 404 during naming transition)
  | 'error:checksum-mismatch' // Downloaded JAR hash does not match manifest
  | 'error:write' // Filesystem error during atomic write
  | 'error:network' // fetch threw (DNS failure, timeout, AbortError, etc.)
  | 'success' // JAR downloaded, checksum verified, written to disk

export interface DownloadResult {
  outcome: DownloadOutcome
  /** Absolute path of the JAR (set even on failure if destination was determined). */
  destPath?: string
  /** HTTP status received for the JAR URL, if a request was made. */
  jarHttpStatus?: number
  /** HTTP status received for the manifest URL, if a request was made. */
  manifestHttpStatus?: number
  /** SHA-256 hash of the downloaded bytes, if computed. */
  downloadedHash?: string
  /** SHA-256 hash expected from the manifest, if found. */
  expectedHash?: string
  /** Size of the downloaded JAR in bytes, if received. */
  downloadedBytes?: number
  /** Human-readable description of the outcome. */
  message: string
}

// ---------------------------------------------------------------------------
// Implementation
// ---------------------------------------------------------------------------

/**
 * Build the GitHub Release download URL for a versioned JAR asset.
 *
 * URL pattern: https://github.com/whoz-oss/coday/releases/download/release/<version>/<artifactId>-<version>.jar
 * Tag pattern confirmed in .github/workflows/release.yml: TAG="release/${VERSION}"
 */
function buildJarUrl(artifactId: string, version: string): string {
  return `https://github.com/whoz-oss/coday/releases/download/release/${version}/${artifactId}-${version}.jar`
}

/**
 * Build the GitHub Release download URL for the checksums manifest.
 *
 * NOTE: The manifest filename follows the same naming convention as the JARs.
 * For releases <= 0.239.0: "checksums.sha256" (version-less, old contract).
 * For releases after the naming change: "checksums.sha256" (unchanged — the
 * manifest filename itself has no version).
 */
function buildManifestUrl(version: string): string {
  return `https://github.com/whoz-oss/coday/releases/download/release/${version}/checksums.sha256`
}

/**
 * Parse the checksums.sha256 manifest and extract the hash for a given filename.
 *
 * Format (generated by `sha256sum *.jar`):
 *   <64-hex-chars>  <filename>\n
 * (two spaces between hash and filename)
 *
 * The filename in the manifest matches the asset name as uploaded — which may
 * be versioned ("agentos-bash-plugin-0.240.0.jar") or version-less
 * ("agentos-bash-plugin.jar") depending on the release.
 * We search for a line whose filename component matches `targetFilename` exactly.
 */
function extractHashFromManifest(manifestText: string, targetFilename: string): string | null {
  for (const line of manifestText.split('\n')) {
    const trimmed = line.trim()
    if (!trimmed) continue
    // sha256sum format: "<hash>  <filename>" (two spaces)
    const spaceIdx = trimmed.indexOf('  ')
    if (spaceIdx === -1) continue
    const hash = trimmed.slice(0, spaceIdx).trim()
    const filename = trimmed.slice(spaceIdx + 2).trim()
    if (filename === targetFilename && hash.length === 64) {
      return hash
    }
  }
  return null
}

/**
 * Compute the SHA-256 hash of a Buffer and return the hex digest.
 */
function sha256hex(data: Buffer): string {
  return crypto.createHash('sha256').update(data).digest('hex')
}

/**
 * Attempt to download agentos-bash-plugin from the GitHub Release assets,
 * verify its SHA-256 checksum, and write it atomically to ~/.coday/agentos/.
 *
 * Conditions under which NO network access is attempted:
 *   - The JAR is already present at `destDir/<artifactId>-<version>.jar`
 *   - The resolved version is the sentinel 'dev'
 *
 * @param expectedVersion  Version string from getCodayVersion().
 * @param jarAlreadyPresent  True when checkAgentosJars() found the JAR at the expected version.
 * @returns A structured DownloadResult describing every step taken.
 */
export async function downloadProbeJar(expectedVersion: string, jarAlreadyPresent: boolean): Promise<DownloadResult> {
  const destDir = path.join(os.homedir(), '.coday', 'agentos')
  const jarFilename = `${PROBE_ARTIFACT_ID}-${expectedVersion}.jar`
  const destPath = path.join(destDir, jarFilename)

  // --- Guard: skip if already present at the correct version ---
  if (jarAlreadyPresent) {
    debugLog(
      'AGENTOS',
      `[DOWNLOAD] ${PROBE_ARTIFACT_ID}: already present at version ${expectedVersion} — skipping download`
    )
    return { outcome: 'skipped:already-present', destPath, message: `Already present at ${destPath}` }
  }

  // --- Guard: skip if version is the 'dev' sentinel ---
  if (expectedVersion === 'dev') {
    debugLog('AGENTOS', `[DOWNLOAD] ${PROBE_ARTIFACT_ID}: version is 'dev' sentinel — no release to download from`)
    return { outcome: 'skipped:dev-version', message: "Version is 'dev' sentinel — no release URL available" }
  }

  const jarUrl = buildJarUrl(PROBE_ARTIFACT_ID, expectedVersion)
  const manifestUrl = buildManifestUrl(expectedVersion)

  debugLog('AGENTOS', `[DOWNLOAD] ${PROBE_ARTIFACT_ID}: initiating probe download`)
  debugLog('AGENTOS', `[DOWNLOAD]   JAR URL:      ${jarUrl}`)
  debugLog('AGENTOS', `[DOWNLOAD]   Manifest URL: ${manifestUrl}`)
  debugLog(
    'AGENTOS',
    `[DOWNLOAD]   NOTE: on releases <= 0.239.0 the JAR URL will return 404 (old naming contract).
` + `[DOWNLOAD]         This is expected during the naming transition and will resolve on the next release.`
  )

  // --- Step 1: Fetch the checksums manifest ---
  let manifestText: string
  try {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS)
    let manifestRes: Response
    try {
      manifestRes = await fetch(manifestUrl, { signal: controller.signal })
    } finally {
      clearTimeout(timer)
    }

    debugLog('AGENTOS', `[DOWNLOAD]   manifest HTTP ${manifestRes.status}`)

    if (!manifestRes.ok) {
      return {
        outcome: 'error:manifest-unavailable',
        manifestHttpStatus: manifestRes.status,
        message: `Manifest request failed with HTTP ${manifestRes.status} — cannot verify JAR integrity, aborting download`,
      }
    }
    manifestText = await manifestRes.text()
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err)
    debugLog('AGENTOS', `[DOWNLOAD]   manifest fetch error: ${msg}`)
    return {
      outcome: 'error:network',
      message: `Network error fetching manifest: ${msg}`,
    }
  }

  // --- Step 2: Extract the expected hash from the manifest ---
  // The manifest may list the JAR under its versioned name (new releases) or
  // version-less name (releases <= 0.239.0). Try both.
  const versionedFilename = jarFilename // e.g. "agentos-bash-plugin-0.239.0.jar"
  const versionlessFilename = `${PROBE_ARTIFACT_ID}.jar` // e.g. "agentos-bash-plugin.jar"

  let expectedHash = extractHashFromManifest(manifestText, versionedFilename)
  let hashSource = versionedFilename
  if (!expectedHash) {
    expectedHash = extractHashFromManifest(manifestText, versionlessFilename)
    hashSource = versionlessFilename
  }

  if (!expectedHash) {
    debugLog(
      'AGENTOS',
      `[DOWNLOAD]   manifest does not contain an entry for '${versionedFilename}' or '${versionlessFilename}'`
    )
    return {
      outcome: 'error:manifest-parse',
      message: `Could not find hash for ${versionedFilename} (or ${versionlessFilename}) in manifest`,
    }
  }
  debugLog('AGENTOS', `[DOWNLOAD]   expected SHA-256 (from manifest entry '${hashSource}'): ${expectedHash}`)

  // --- Step 3: Fetch the JAR ---
  let jarBuffer: Buffer
  let jarHttpStatus: number
  try {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS)
    let jarRes: Response
    try {
      jarRes = await fetch(jarUrl, { signal: controller.signal })
    } finally {
      clearTimeout(timer)
    }

    jarHttpStatus = jarRes.status
    debugLog('AGENTOS', `[DOWNLOAD]   JAR HTTP ${jarHttpStatus}`)

    if (!jarRes.ok) {
      return {
        outcome: 'error:jar-unavailable',
        jarHttpStatus,
        message: `JAR request failed with HTTP ${jarHttpStatus} — likely naming transition (expected on releases <= 0.239.0)`,
      }
    }

    const arrayBuffer = await jarRes.arrayBuffer()
    jarBuffer = Buffer.from(arrayBuffer)
    debugLog('AGENTOS', `[DOWNLOAD]   received ${jarBuffer.length} bytes`)
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err)
    debugLog('AGENTOS', `[DOWNLOAD]   JAR fetch error: ${msg}`)
    return {
      outcome: 'error:network',
      message: `Network error fetching JAR: ${msg}`,
    }
  }

  // --- Step 4: Verify checksum ---
  const downloadedHash = sha256hex(jarBuffer)
  debugLog('AGENTOS', `[DOWNLOAD]   computed  SHA-256: ${downloadedHash}`)
  debugLog('AGENTOS', `[DOWNLOAD]   expected  SHA-256: ${expectedHash}`)

  if (downloadedHash !== expectedHash) {
    debugLog('AGENTOS', `[DOWNLOAD]   CHECKSUM MISMATCH — discarding downloaded bytes`)
    return {
      outcome: 'error:checksum-mismatch',
      jarHttpStatus,
      downloadedHash,
      expectedHash,
      downloadedBytes: jarBuffer.length,
      destPath,
      message: `Checksum mismatch for ${jarFilename} — downloaded bytes discarded`,
    }
  }
  debugLog('AGENTOS', `[DOWNLOAD]   checksum OK`)

  // --- Step 5: Atomic write to disk ---
  // Write to a temp file first, then rename, so an interrupted download
  // never leaves a truncated JAR that would be mistaken for valid on next startup.
  const tmpPath = `${destPath}.tmp`
  try {
    fs.mkdirSync(destDir, { recursive: true })
    fs.writeFileSync(tmpPath, jarBuffer)
    fs.renameSync(tmpPath, destPath)
    debugLog('AGENTOS', `[DOWNLOAD]   written to ${destPath} (${jarBuffer.length} bytes)`)
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err)
    debugLog('AGENTOS', `[DOWNLOAD]   write error: ${msg}`)
    // Best-effort cleanup of the temp file
    try {
      fs.unlinkSync(tmpPath)
    } catch {
      /* ignore */
    }
    return {
      outcome: 'error:write',
      jarHttpStatus,
      downloadedHash,
      expectedHash,
      downloadedBytes: jarBuffer.length,
      destPath,
      message: `Failed to write JAR to disk: ${msg}`,
    }
  }

  return {
    outcome: 'success',
    jarHttpStatus,
    downloadedHash,
    expectedHash,
    downloadedBytes: jarBuffer.length,
    destPath,
    message: `Successfully downloaded and verified ${jarFilename} (${jarBuffer.length} bytes)`,
  }
}
