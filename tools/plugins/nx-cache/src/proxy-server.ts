/**
 * Standalone proxy server process.
 * Spawned as a detached child by the nx-cache plugin's preTasksExecution hook.
 *
 * Protocol:
 *   1. Binds to a free port on 127.0.0.1
 *   2. Writes JSON { port, pid } to the file path passed as argv[2]
 *   3. Serves GET/PUT /v1/cache/:hash backed by @actions/cache
 *   4. Exits when the parent sends SIGTERM (postTasksExecution)
 *
 * This runs as a separate OS process so it outlives the isolated plugin
 * worker that spawns it.
 */

import * as cache from '@actions/cache'
import * as fs from 'fs'
import * as http from 'http'
import * as net from 'net'

if (!cache.isFeatureAvailable()) {
  console.warn('[nx-cache-server] GitHub Actions cache service is not available, exiting cleanly')
  process.exit(0)
}

const stateFile = process.argv[2]
if (!stateFile) {
  console.error('[nx-cache-server] missing state file argument')
  process.exit(1)
}

const baseSha = process.env['NX_CACHE_BASE_SHA'] ?? 'unknown'
const CACHE_KEY_PREFIX = 'nx-'
const storedHashes = new Set<string>()

function getCacheKey(hash: string): string {
  return `${CACHE_KEY_PREFIX}${hash}-${baseSha}`
}

function collectBody(req: http.IncomingMessage): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = []
    req.on('data', (chunk: Buffer) => chunks.push(chunk))
    req.on('end', () => resolve(Buffer.concat(chunks)))
    req.on('error', reject)
  })
}

const server = http.createServer(async (req, res) => {
  try {
    const match = req.url?.match(/^\/v1\/cache\/([^/]+)$/)
    const hash = match?.[1]
    if (!hash) {
      res.writeHead(404).end()
      return
    }
    const cacheKey = getCacheKey(hash)

    if (req.method === 'GET') {
      const tmpDir = process.env['RUNNER_TEMP'] ?? '/tmp'
      const tarPath = `${tmpDir}/nx-cache-${hash}.tar.gz`

      const hitKey = await cache.restoreCache([tarPath], cacheKey)
      if (!hitKey) {
        res.writeHead(404).end()
        return
      }

      if (!fs.existsSync(tarPath)) {
        res.writeHead(404).end()
        return
      }

      const stat = fs.statSync(tarPath)
      res.writeHead(200, {
        'Content-Type': 'application/octet-stream',
        'Content-Length': stat.size,
      })
      fs.createReadStream(tarPath).pipe(res)
      res.on('finish', () => fs.rmSync(tarPath, { force: true }))
    } else if (req.method === 'PUT') {
      if (storedHashes.has(hash)) {
        res.writeHead(409).end()
        return
      }

      const body = await collectBody(req)
      const tmpDir = process.env['RUNNER_TEMP'] ?? '/tmp'
      const tarPath = `${tmpDir}/nx-cache-${hash}.tar.gz`

      fs.writeFileSync(tarPath, body)

      try {
        await cache.saveCache([tarPath], cacheKey)
        storedHashes.add(hash)
        res.writeHead(200).end()
      } catch (err: unknown) {
        if (err instanceof Error && err.name === 'ReserveCacheError') {
          storedHashes.add(hash)
          res.writeHead(409).end()
        } else {
          throw err
        }
      } finally {
        fs.rmSync(tarPath, { force: true })
      }
    } else {
      res.writeHead(405).end()
    }
  } catch (err) {
    console.error('[nx-cache-server] error:', err)
    res.writeHead(500).end()
  }
})

// Bind to a free port, write state, then stay alive
server.listen(0, '127.0.0.1', () => {
  const address = server.address() as net.AddressInfo
  const state = JSON.stringify({ port: address.port, pid: process.pid })
  fs.writeFileSync(stateFile, state, 'utf8')
  console.log(`[nx-cache-server] listening on http://127.0.0.1:${address.port} (base SHA: ${baseSha})`)
})

process.on('SIGTERM', () => {
  server.close(() => {
    try {
      fs.rmSync(stateFile, { force: true })
    } catch {}
    process.exit(0)
  })
})
