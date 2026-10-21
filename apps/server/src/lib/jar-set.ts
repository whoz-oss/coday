/**
 * jar-set.ts
 *
 * Shared download/inventory logic for the managed JVM services (AgentOS, Factory).
 *
 * Nothing is listed by hand: the set of JARs to install is DERIVED from the release's own
 * `checksums.sha256` manifest, so whatever the release publishes is what gets installed.
 *
 *   service  : `<kind>-service-<version>.jar`            -> <configDir>/<kind>/
 *   plugins  : `<kind>-<anything>-plugin-<version>.jar`  -> <configDir>/<kind>/plugins/
 *
 * Everything else in the manifest (shared libs such as `agentos-git`, the other platform's
 * JARs in a combined workstream manifest, ...) is ignored.
 *
 * Completeness is decided offline: after a successful install the matching manifest lines are
 * written to `<configDir>/<kind>/installed.sha256`. A set is complete for a version when that
 * marker lists only JARs of that version, includes the service, and every listed JAR exists.
 * The marker is removed before a download starts and written last, so an interrupted download
 * is never mistaken for a complete install.
 *
 * The requested version always wins: when the set is not complete for it, the full set is
 * downloaded (blocking) and every JAR of another version is removed.
 *
 * INVARIANTS: ensureJarSet never throws, no side effects at import time, no new dependency.
 */

import * as crypto from 'crypto'
import * as fs from 'fs'
import * as path from 'path'
import { debugLog } from './log'
import { releaseBaseUrl } from './release-url'

/** Timeout for each individual HTTP request. */
const FETCH_TIMEOUT_MS = 60_000

/** Marker listing the JARs of the last complete install (`<hash>  <filename>` lines). */
const INSTALLED_MARKER = 'installed.sha256'

export type JarSetKind = 'agentos' | 'factory'

export type DownloadOutcome =
  | 'skipped:all-present' // The set is already complete at the expected version
  | 'skipped:dev-version' // version is 'dev' — no release to download from
  | 'error:download-failed' // manifest fetch/selection, or a JAR failed to download/verify/write
  | 'success' // Full set downloaded, verified, written; stale JARs cleaned

export interface DownloadResult {
  outcome: DownloadOutcome
  /** Human-readable description of the outcome. */
  message: string
  /** Number of JARs downloaded. */
  downloaded?: number
  /** Number of stale files removed. */
  cleaned?: number
}

export interface ManifestEntry {
  hash: string
  filename: string
}

export interface SelectedArtifact {
  artifactId: string
  entry: ManifestEntry
}

// ---------------------------------------------------------------------------
// Manifest
// ---------------------------------------------------------------------------

/** Parse `sha256sum` output (`<64 hex>  <filename>` lines); malformed lines are skipped. */
export function parseManifest(manifestText: string): ManifestEntry[] {
  const entries: ManifestEntry[] = []
  for (const line of manifestText.split('\n')) {
    const trimmed = line.trim()
    if (!trimmed) continue
    const spaceIdx = trimmed.indexOf('  ')
    if (spaceIdx === -1) continue
    const hash = trimmed.slice(0, spaceIdx).trim()
    const filename = trimmed.slice(spaceIdx + 2).trim()
    if (hash.length === 64 && filename) entries.push({ hash, filename })
  }
  return entries
}

export function sha256hex(data: Buffer): string {
  return crypto.createHash('sha256').update(data).digest('hex')
}

function serviceId(kind: JarSetKind): string {
  return `${kind}-service`
}

/**
 * Pick, from a manifest, the artifacts of `kind` at `version`: the service and every plugin.
 * Throws when the manifest has no service JAR for that version.
 */
export function selectArtifacts(entries: ManifestEntry[], kind: JarSetKind, version: string): SelectedArtifact[] {
  const suffix = `-${version}.jar`
  const pluginPattern = new RegExp(`^${kind}-.+-plugin$`)
  const selected: SelectedArtifact[] = []
  for (const entry of entries) {
    if (!entry.filename.endsWith(suffix)) continue
    const artifactId = entry.filename.slice(0, -suffix.length)
    if (artifactId === serviceId(kind) || pluginPattern.test(artifactId)) selected.push({ artifactId, entry })
  }
  if (!selected.some((a) => a.artifactId === serviceId(kind))) {
    throw new Error(`No '${serviceId(kind)}' entry for version ${version} in the manifest`)
  }
  return selected
}

// ---------------------------------------------------------------------------
// Layout
// ---------------------------------------------------------------------------

/** Service JAR at the root of `baseDir`, plugins in `baseDir/plugins/`. */
function jarPathIn(baseDir: string, kind: JarSetKind, artifactId: string, version: string): string {
  return artifactId === serviceId(kind)
    ? path.join(baseDir, `${artifactId}-${version}.jar`)
    : path.join(baseDir, 'plugins', `${artifactId}-${version}.jar`)
}

/** Path of an artifact in the managed layout of `kind`. */
export function jarFilePath(configDir: string, kind: JarSetKind, artifactId: string, version: string): string {
  return jarPathIn(path.join(configDir, kind), kind, artifactId, version)
}

/**
 * Artifact IDs of the complete install at `version`, or null when the set is not complete
 * (no marker, marker of another version, no service, or a listed JAR is missing). Offline.
 */
export function readInstalledSet(configDir: string, kind: JarSetKind, version: string): string[] | null {
  let text: string
  try {
    text = fs.readFileSync(path.join(configDir, kind, INSTALLED_MARKER), 'utf8')
  } catch {
    return null
  }
  const suffix = `-${version}.jar`
  const artifactIds: string[] = []
  for (const { filename } of parseManifest(text)) {
    if (!filename.endsWith(suffix)) return null
    artifactIds.push(filename.slice(0, -suffix.length))
  }
  if (!artifactIds.includes(serviceId(kind))) return null
  if (!artifactIds.every((id) => fs.existsSync(jarFilePath(configDir, kind, id, version)))) return null
  return artifactIds
}

/**
 * Remove any JAR/.tmp in dir that is NOT in allowedFilenames. Never throws.
 */
export function cleanDirectory(dir: string, allowedFilenames: Set<string>, logContext = 'AGENTOS'): number {
  let removed = 0
  try {
    for (const filename of fs.readdirSync(dir).filter((f) => f.endsWith('.jar') || f.endsWith('.tmp'))) {
      if (!allowedFilenames.has(filename)) {
        const fullPath = path.join(dir, filename)
        try {
          fs.unlinkSync(fullPath)
          debugLog(logContext, `  [CLEAN] Removed stale file: ${fullPath}`)
          removed++
        } catch (err) {
          debugLog(logContext, `  [CLEAN] Failed to remove ${fullPath}: ${err instanceof Error ? err.message : err}`)
        }
      }
    }
  } catch {
    // Directory doesn't exist — nothing to clean
  }
  return removed
}

// ---------------------------------------------------------------------------
// Download
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

function writeAtomically(dest: string, data: Buffer | string): void {
  const tmp = `${dest}.tmp`
  try {
    fs.writeFileSync(tmp, data)
    fs.renameSync(tmp, dest)
  } catch (err) {
    try {
      fs.unlinkSync(tmp)
    } catch {
      /* ignore */
    }
    throw err
  }
}

/**
 * Make sure the full JAR set of `kind` is installed at `version`.
 *
 * - 'dev' sentinel: nothing is downloaded; "present" when the service JAR was placed by hand.
 * - set already complete for `version`: nothing to do, no network.
 * - otherwise: download the manifest, derive the set from it, download + verify every JAR,
 *   remove every JAR of another version (and any leftover next/ directory), then write the marker.
 *
 * Callers must wait for this to finish, then start `version` (never an older installed one).
 */
export async function ensureJarSet(configDir: string, kind: JarSetKind, version: string): Promise<DownloadResult> {
  const ctx = kind.toUpperCase()
  const baseDir = path.join(configDir, kind)
  const pluginsDir = path.join(baseDir, 'plugins')

  if (version === 'dev') {
    const handPlaced = fs.existsSync(jarPathIn(baseDir, kind, serviceId(kind), version))
    debugLog(
      ctx,
      `[DOWNLOAD] Version is 'dev' sentinel — no release to download from (JAR placed by hand: ${handPlaced})`
    )
    return handPlaced
      ? { outcome: 'skipped:all-present', message: "Version is 'dev' sentinel — using the JAR placed by hand" }
      : { outcome: 'skipped:dev-version', message: "Version is 'dev' sentinel — no release URL available" }
  }

  const installed = readInstalledSet(configDir, kind, version)
  if (installed) {
    debugLog(ctx, `[DOWNLOAD] ${installed.length} JAR(s) already installed at ${version} — skipping download`)
    return { outcome: 'skipped:all-present', message: `All ${installed.length} JAR(s) already present at ${version}` }
  }

  try {
    fs.mkdirSync(pluginsDir, { recursive: true })
    // Uncommit before touching any JAR: an interrupted download must read as incomplete.
    fs.rmSync(path.join(baseDir, INSTALLED_MARKER), { force: true })

    const baseUrl = releaseBaseUrl(version)
    debugLog(ctx, `[DOWNLOAD] Version ${version} not fully installed — fetching ${baseUrl}/checksums.sha256`)
    const manifestRes = await fetchWithTimeout(`${baseUrl}/checksums.sha256`)
    debugLog(ctx, `[DOWNLOAD] Manifest HTTP ${manifestRes.status}`)
    if (!manifestRes.ok) throw new Error(`Manifest HTTP ${manifestRes.status}`)

    const artifacts = selectArtifacts(parseManifest(await manifestRes.text()), kind, version)
    debugLog(ctx, `[DOWNLOAD] Release provides: ${artifacts.map((a) => a.artifactId).join(', ')}`)

    for (const { artifactId, entry } of artifacts) {
      debugLog(ctx, `[DOWNLOAD] ${entry.filename}`)
      const jarRes = await fetchWithTimeout(`${baseUrl}/${entry.filename}`)
      if (!jarRes.ok) throw new Error(`JAR HTTP ${jarRes.status} for '${entry.filename}'`)
      const buffer = Buffer.from(await jarRes.arrayBuffer())
      if (sha256hex(buffer) !== entry.hash) throw new Error(`Checksum mismatch for '${entry.filename}'`)
      const dest = jarPathIn(baseDir, kind, artifactId, version)
      writeAtomically(dest, buffer)
      debugLog(ctx, `[DOWNLOAD]   written ${dest} (${buffer.length} bytes)`)
    }

    // Strict cleanup: only the installed set may remain. Also drop the next/ staging directory
    // left over from the former background pre-fetch flow.
    fs.rmSync(path.join(baseDir, 'next'), { recursive: true, force: true })
    const wantedRoot = new Set<string>()
    const wantedPlugins = new Set<string>()
    for (const { artifactId, entry } of artifacts) {
      const target = artifactId === serviceId(kind) ? wantedRoot : wantedPlugins
      target.add(entry.filename)
    }
    const cleaned = cleanDirectory(baseDir, wantedRoot, ctx) + cleanDirectory(pluginsDir, wantedPlugins, ctx)
    debugLog(ctx, `[DOWNLOAD] Cleanup: removed ${cleaned} stale file(s)`)

    // Commit last.
    writeAtomically(
      path.join(baseDir, INSTALLED_MARKER),
      artifacts.map(({ entry }) => `${entry.hash}  ${entry.filename}`).join('\n') + '\n'
    )

    return {
      outcome: 'success',
      message: `Downloaded ${artifacts.length} JAR(s), cleaned ${cleaned} stale file(s)`,
      downloaded: artifacts.length,
      cleaned,
    }
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err)
    debugLog(ctx, `[DOWNLOAD] Failed: ${msg}`)
    return { outcome: 'error:download-failed', message: msg }
  }
}
