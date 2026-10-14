/**
 * factory-download.ts
 *
 * JAR inventory + blocking download for the managed Factory.
 *
 * Layout (single directory, mirrors agentos/):
 *   <configDir>/factory/factory-service-<version>.jar
 *   <configDir>/factory/plugins/factory-forge-plugin-<version>.jar
 *
 * Assets come from the GitHub Release `release/<version>` (same release as
 * the AgentOS JARs), verified against `checksums.sha256`.
 *
 * Unlike AgentOS there is no background pre-fetch / next/ swap: when the JARs on
 * disk are missing or at another version, the target version is downloaded
 * before the Factory is started.
 *
 * INVARIANTS: never throws, no side effects at import time, no new dependency.
 */

import * as fs from 'fs'
import * as path from 'path'
import { debugLog } from './log'
import { cleanDirectory, extractHashFromManifest, sha256hex } from './agentos-download'

const FETCH_TIMEOUT_MS = 60_000

export const FACTORY_ARTIFACT_IDS = ['factory-service', 'factory-forge-plugin'] as const
export type FactoryArtifactId = (typeof FACTORY_ARTIFACT_IDS)[number]

export type FactoryDownloadOutcome = 'skipped:all-present' | 'skipped:dev-version' | 'error:download-failed' | 'success'

export interface FactoryDownloadResult {
  outcome: FactoryDownloadOutcome
  message: string
}

/** Service JAR at the root, plugins in plugins/. */
export function factoryJarPath(configDir: string, artifactId: FactoryArtifactId, version: string): string {
  const dir = path.join(configDir, 'factory')
  return artifactId === 'factory-service'
    ? path.join(dir, `${artifactId}-${version}.jar`)
    : path.join(dir, 'plugins', `${artifactId}-${version}.jar`)
}

/** Artifacts absent at the expected version. */
export function findMissingFactoryJars(configDir: string, version: string): FactoryArtifactId[] {
  return FACTORY_ARTIFACT_IDS.filter((id) => !fs.existsSync(factoryJarPath(configDir, id, version)))
}

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
 * Download every Factory JAR for `version` (atomic writes, SHA-256 verified),
 * then remove stale JARs of other versions.
 */
export async function downloadFactoryJars(configDir: string, version: string): Promise<FactoryDownloadResult> {
  const missing = findMissingFactoryJars(configDir, version)
  if (missing.length === 0) {
    return { outcome: 'skipped:all-present', message: 'All Factory JARs already present at the expected version' }
  }
  if (version === 'dev') {
    return { outcome: 'skipped:dev-version', message: "Version is 'dev' sentinel — no release URL available" }
  }

  const baseUrl = `https://github.com/whoz-oss/coday/releases/download/release/${version}`
  const factoryDir = path.join(configDir, 'factory')
  const pluginsDir = path.join(factoryDir, 'plugins')

  try {
    fs.mkdirSync(pluginsDir, { recursive: true })

    debugLog('FACTORY', `[DOWNLOAD] Fetching manifest for ${version}`)
    const manifestRes = await fetchWithTimeout(`${baseUrl}/checksums.sha256`)
    if (!manifestRes.ok) throw new Error(`Manifest HTTP ${manifestRes.status}`)
    const manifestText = await manifestRes.text()

    for (const artifactId of FACTORY_ARTIFACT_IDS) {
      const filename = `${artifactId}-${version}.jar`
      const dest = factoryJarPath(configDir, artifactId, version)

      const expectedHash = extractHashFromManifest(manifestText, filename)
      if (!expectedHash) throw new Error(`No manifest entry for '${filename}'`)

      debugLog('FACTORY', `[DOWNLOAD] ${filename}`)
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
      debugLog('FACTORY', `[DOWNLOAD]   written ${dest} (${buffer.length} bytes)`)
    }
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err)
    debugLog('FACTORY', `[DOWNLOAD] Failed: ${msg}`)
    return { outcome: 'error:download-failed', message: msg }
  }

  const cleaned =
    cleanDirectory(factoryDir, new Set([`factory-service-${version}.jar`])) +
    cleanDirectory(pluginsDir, new Set([`factory-forge-plugin-${version}.jar`]))
  debugLog('FACTORY', `[DOWNLOAD] Done — ${cleaned} stale file(s) removed`)
  return { outcome: 'success', message: `Downloaded ${missing.length} JAR(s), cleaned ${cleaned} stale file(s)` }
}
