import { spawn } from 'child_process'
import * as fs from 'fs'
import * as os from 'os'
import * as path from 'path'

/**
 * Nx task lifecycle plugin — remote cache via GitHub Actions cache service.
 *
 * preTasksExecution:
 *   Spawns proxy-server.ts as a detached child process. The server binds to a
 *   free port, writes { port, pid } to a temp file, then stays alive for the
 *   entire nx run. Sets NX_SELF_HOSTED_REMOTE_CACHE_SERVER so Nx's DbCache
 *   routes all GET/PUT calls through the proxy.
 *
 * postTasksExecution:
 *   Reads the state file, sends SIGTERM to the server process, cleans up.
 *
 * No-op outside GitHub Actions (ACTIONS_RESULTS_URL / ACTIONS_CACHE_URL absent).
 *
 * Note: Nx loads local plugins via @swc-node/register in CJS mode, so
 * __dirname is available and import.meta.url is not needed.
 */

function isGitHubActionsEnvironment(): boolean {
  return process.env['GITHUB_ACTIONS'] === 'true'
}

function getStateFilePath(): string {
  return path.join(os.tmpdir(), `nx-cache-proxy-${process.pid}.json`)
}

export async function preTasksExecution(): Promise<void> {
  if (!isGitHubActionsEnvironment()) {
    return
  }

  // ACTIONS_RESULTS_URL (cache v2) and ACTIONS_CACHE_URL (cache v1) live in the
  // runner agent process environment but are NOT forwarded to step shells or
  // plugin worker processes by default. Check here before spawning.
  const cacheAvailable = !!process.env['ACTIONS_RESULTS_URL'] || !!process.env['ACTIONS_CACHE_URL']
  if (!cacheAvailable) {
    console.log('[nx-cache] GitHub Actions cache credentials not available in this process, skipping remote cache')
    return
  }

  const stateFile = getStateFilePath()
  const serverScript = path.join(__dirname, 'proxy-server.ts')

  const child = spawn(process.execPath, ['--require', '@swc-node/register', serverScript, stateFile], {
    detached: true,
    stdio: 'inherit',
    env: {
      ...process.env,
      NX_CACHE_BASE_SHA: process.env['NX_CACHE_BASE_SHA'] ?? 'unknown',
    },
  })
  child.unref()

  const port = await waitForPort(stateFile, 10_000)
  process.env['NX_SELF_HOSTED_REMOTE_CACHE_SERVER'] = `http://127.0.0.1:${port}`
  console.log(`[nx-cache] proxy started on http://127.0.0.1:${port}`)
}

export async function postTasksExecution(): Promise<void> {
  const stateFile = getStateFilePath()

  if (!fs.existsSync(stateFile)) {
    return
  }

  try {
    const { pid } = JSON.parse(fs.readFileSync(stateFile, 'utf8')) as { port: number; pid: number }
    process.kill(pid, 'SIGTERM')
    console.log(`[nx-cache] proxy stopped (pid: ${pid})`)
  } catch (err) {
    // Server may have already exited — not fatal
    console.warn('[nx-cache] could not stop proxy:', err)
  } finally {
    fs.rmSync(stateFile, { force: true })
    delete process.env['NX_SELF_HOSTED_REMOTE_CACHE_SERVER']
  }
}

function waitForPort(stateFile: string, timeoutMs: number): Promise<number> {
  return new Promise((resolve, reject) => {
    const start = Date.now()
    const interval = setInterval(() => {
      if (fs.existsSync(stateFile)) {
        clearInterval(interval)
        try {
          const { port } = JSON.parse(fs.readFileSync(stateFile, 'utf8')) as { port: number; pid: number }
          resolve(port)
        } catch {
          reject(new Error('[nx-cache] failed to parse server state file'))
        }
        return
      }
      if (Date.now() - start > timeoutMs) {
        clearInterval(interval)
        reject(new Error('[nx-cache] proxy server did not start within timeout'))
      }
    }, 50)
  })
}
