#!/usr/bin/env node
/**
 * postinstall.js — downloads AgentOS JARs from the matching GitHub Release.
 *
 * The JARs are too large to bundle in the npm tarball (they are already-compressed
 * ZIP archives and do not benefit from gzip). Instead, they are uploaded as assets
 * on the GitHub Release that corresponds to the version of this package, and this
 * script fetches them on first install.
 *
 * The script is intentionally dependency-free (only Node built-ins) so it works
 * in any environment without a prior `npm install`.
 *
 * Environment variables:
 *   AGENTOS_HOSTNAME        if set, an external AgentOS instance is configured — skip download
 *   AGENTOS_PORT            if set, an external AgentOS instance is configured — skip download
 *   CODAY_AGENTOS_VERSION   override the version used to find the GitHub Release (e.g. for testing)
 */
import { createHash } from 'crypto'
import { createWriteStream, existsSync, mkdirSync, readFileSync } from 'fs'
import { pipeline } from 'stream/promises'
import { dirname, resolve } from 'path'
import { fileURLToPath } from 'url'

const __dirname = dirname(fileURLToPath(import.meta.url))

const REPO = 'whoz-oss/coday'
const SEMVER_RE = /^\d+\.\d+\.\d+$/
const ALLOWED_DOWNLOAD_HOSTS = ['github.com', 'objects.githubusercontent.com']

// JARs expected relative to this script's directory after download
const JARS = [
  { asset: 'agentos-service.jar', dest: 'agentos/agentos-service.jar' },
  // Plugin JARs are uploaded with versioned names (e.g. agentos-bash-plugin-1.2.3.jar).
  // We match by prefix and rename to a stable filename on download.
  { assetPrefix: 'agentos-bash-plugin-', dest: 'agentos/plugins/agentos-bash-plugin.jar' },
  { assetPrefix: 'agentos-file-plugin-', dest: 'agentos/plugins/agentos-file-plugin.jar' },
  { assetPrefix: 'agentos-mcp-plugin-', dest: 'agentos/plugins/agentos-mcp-plugin.jar' },
  { assetPrefix: 'agentos-tmux-plugin-', dest: 'agentos/plugins/agentos-tmux-plugin.jar' },
]

async function fetchJson(url) {
  const res = await fetch(url, {
    headers: { Accept: 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28' },
  })
  if (!res.ok) throw new Error(`GET ${url} → ${res.status} ${res.statusText}`)
  return res.json()
}

function validateDownloadUrl(url) {
  const parsed = new URL(url)
  if (!ALLOWED_DOWNLOAD_HOSTS.includes(parsed.hostname)) {
    throw new Error(`Refusing to download from unexpected host: ${parsed.hostname}`)
  }
}

async function downloadFile(url, destPath) {
  validateDownloadUrl(url)
  mkdirSync(dirname(destPath), { recursive: true })
  const res = await fetch(url, { headers: { Accept: 'application/octet-stream' }, redirect: 'follow' })
  if (!res.ok) throw new Error(`Download ${url} → ${res.status} ${res.statusText}`)
  await pipeline(res.body, createWriteStream(destPath))
}

function sha256File(filePath) {
  const hash = createHash('sha256')
  hash.update(readFileSync(filePath))
  return hash.digest('hex')
}

async function fetchChecksums(assets, tag) {
  const checksumAsset = assets.find((a) => a.name === 'checksums.sha256')
  if (!checksumAsset) {
    console.warn(`[coday-server] No checksums.sha256 found in release ${tag} — skipping integrity check`)
    return null
  }
  validateDownloadUrl(checksumAsset.browser_download_url)
  const res = await fetch(checksumAsset.browser_download_url, {
    headers: { Accept: 'application/octet-stream' },
    redirect: 'follow',
  })
  if (!res.ok) throw new Error(`Download checksums.sha256 → ${res.status} ${res.statusText}`)
  const text = await res.text()
  // Parse "<hash>  <filename>" lines
  const map = {}
  for (const line of text.split('\n')) {
    const parts = line.trim().split(/\s+/)
    if (parts.length >= 2) map[parts[1]] = parts[0]
  }
  return map
}

async function main() {
  if (process.env.AGENTOS_HOSTNAME || process.env.AGENTOS_PORT) {
    console.log('[coday-server] External AgentOS configured — skipping JAR download')
    return
  }

  // Read version from package.json sitting next to this script (or use override)
  const { createRequire } = await import('module')
  const require = createRequire(import.meta.url)
  const { version: pkgVersion } = require('./package.json')
  const rawVersion = process.env.CODAY_AGENTOS_VERSION ?? pkgVersion
  if (!SEMVER_RE.test(rawVersion)) {
    console.warn(`[coday-server] Invalid version format: "${rawVersion}" — skipping JAR download`)
    return
  }
  const version = rawVersion

  const tag = `release/${version}`
  const allPresent = JARS.every(({ dest }) => existsSync(resolve(__dirname, dest)))
  if (allPresent) {
    console.log(`[coday-server] AgentOS JARs already present (v${version}) — skipping download`)
    return
  }

  console.log(`[coday-server] Downloading AgentOS JARs v${version} from GitHub Release ${tag}...`)

  let assets
  try {
    const release = await fetchJson(`https://api.github.com/repos/${REPO}/releases/tags/${tag}`)
    assets = release.assets
  } catch (err) {
    console.warn(`[coday-server] Could not fetch release ${tag}: ${err.message}`)
    console.warn('[coday-server] AgentOS will not be available.')
    return
  }

  const checksums = await fetchChecksums(assets, tag)

  let failed = false
  for (const { asset, assetPrefix, dest } of JARS) {
    const destPath = resolve(__dirname, dest)
    if (existsSync(destPath)) {
      console.log(`[coday-server]   ✓ ${dest} already present`)
      continue
    }

    const found = asset
      ? assets.find((a) => a.name === asset)
      : assets.find((a) => a.name.startsWith(assetPrefix) && a.name.endsWith('.jar'))
    const assetLabel = asset ?? assetPrefix + '*'
    if (!found) {
      console.warn(`[coday-server]   ✗ asset ${assetLabel} not found in release ${tag}`)
      failed = true
      continue
    }

    process.stdout.write(`[coday-server]   ↓ ${found.name}...`)
    try {
      await downloadFile(found.browser_download_url, destPath)

      // Verify checksum if manifest is available
      if (checksums) {
        // The manifest uses the stable filename (basename of dest)
        const stableFilename = dest.split('/').pop()
        const expectedHash = checksums[stableFilename]
        if (!expectedHash) {
          console.warn(`\n[coday-server]   ⚠ No checksum entry for ${stableFilename} — cannot verify integrity`)
        } else {
          const actualHash = sha256File(destPath)
          if (actualHash !== expectedHash) {
            process.stdout.write(` INTEGRITY FAILURE\n`)
            console.error(`[coday-server]   ✗ Checksum mismatch for ${stableFilename}:`)
            console.error(`[coday-server]     expected: ${expectedHash}`)
            console.error(`[coday-server]     actual:   ${actualHash}`)
            // Remove the corrupted file
            import('fs').then(({ unlinkSync }) => { try { unlinkSync(destPath) } catch {} })
            failed = true
            continue
          }
          process.stdout.write(' ✓\n')
        }
      } else {
        process.stdout.write(' done\n')
      }
    } catch (err) {
      process.stdout.write(` FAILED: ${err.message}\n`)
      failed = true
    }
  }

  if (failed) {
    console.warn('[coday-server] Some JARs could not be downloaded. AgentOS may not be available.')
  } else {
    console.log('[coday-server] AgentOS JARs downloaded successfully.')
  }
}

main().catch((err) => {
  // Never fail the install — missing JARs just means AgentOS won't start
  console.warn('[coday-server] postinstall error:', err.message)
})
