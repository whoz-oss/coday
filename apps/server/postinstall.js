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
 *   CODAY_SKIP_AGENTOS_DOWNLOAD=1   skip the download entirely (CI, offline, etc.)
 */
import { createWriteStream, existsSync, mkdirSync } from 'fs'
import { pipeline } from 'stream/promises'
import { dirname, resolve } from 'path'
import { fileURLToPath } from 'url'
import { createReadStream } from 'fs'
import { createGunzip } from 'zlib'

const __dirname = dirname(fileURLToPath(import.meta.url))

const REPO = 'whoz-oss/coday'

// JARs expected relative to this script's directory after download
const JARS = [
  { asset: 'agentos-service.jar', dest: 'agentos/agentos-service.jar' },
  { asset: 'agentos-bash-plugin.jar', dest: 'agentos/plugins/agentos-bash-plugin.jar' },
  { asset: 'agentos-file-plugin.jar', dest: 'agentos/plugins/agentos-file-plugin.jar' },
  { asset: 'agentos-mcp-plugin.jar', dest: 'agentos/plugins/agentos-mcp-plugin.jar' },
  { asset: 'agentos-tmux-plugin.jar', dest: 'agentos/plugins/agentos-tmux-plugin.jar' },
]

async function fetchJson(url) {
  const res = await fetch(url, {
    headers: { Accept: 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28' },
  })
  if (!res.ok) throw new Error(`GET ${url} → ${res.status} ${res.statusText}`)
  return res.json()
}

async function downloadFile(url, destPath) {
  mkdirSync(dirname(destPath), { recursive: true })
  const res = await fetch(url, { headers: { Accept: 'application/octet-stream' }, redirect: 'follow' })
  if (!res.ok) throw new Error(`Download ${url} → ${res.status} ${res.statusText}`)
  await pipeline(res.body, createWriteStream(destPath))
}

async function main() {
  if (process.env.CODAY_SKIP_AGENTOS_DOWNLOAD === '1') {
    console.log('[coday-server] CODAY_SKIP_AGENTOS_DOWNLOAD=1 — skipping AgentOS JAR download')
    return
  }

  // Read version from package.json sitting next to this script
  const { createRequire } = await import('module')
  const require = createRequire(import.meta.url)
  const { version } = require('./package.json')

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
    console.warn('[coday-server] AgentOS will not be available. Set CODAY_SKIP_AGENTOS_DOWNLOAD=1 to suppress this warning.')
    return
  }

  let failed = false
  for (const { asset, dest } of JARS) {
    const destPath = resolve(__dirname, dest)
    if (existsSync(destPath)) {
      console.log(`[coday-server]   ✓ ${asset} already present`)
      continue
    }

    const found = assets.find((a) => a.name === asset)
    if (!found) {
      console.warn(`[coday-server]   ✗ asset ${asset} not found in release ${tag}`)
      failed = true
      continue
    }

    process.stdout.write(`[coday-server]   ↓ ${asset}...`)
    try {
      await downloadFile(found.browser_download_url, destPath)
      process.stdout.write(' done\n')
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
