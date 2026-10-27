/**
 * factory-runtime.ts
 *
 * Launch the Factory Spring Boot JAR as a managed child process (counterpart of
 * agentos-runtime.ts).
 *
 *   - Port: findAvailablePort(8141) — 8141 is the Factory's historical default
 *     (also the AgentOS factory-bridge default); falls back to the next free port.
 *   - cwd = <configDir>/factory: the Factory's relative paths (plugins/, data/,
 *     factory/runs) land inside that directory.
 *   - AGENTOS_URL is passed through so the Factory talks to the AgentOS Coday manages.
 *   - Health probe: /actuator/health (Spring Boot actuator).
 *
 * Never throws: returns null when the Factory cannot be started.
 * NO SIDE EFFECTS AT IMPORT TIME.
 */

import { spawn, ChildProcess } from 'child_process'
import * as fs from 'fs'
import * as path from 'path'
import { debugLog } from './log'
import { findAvailablePort } from './find-available-port'
import { detectJava } from './agentos-runtime'
import { factoryJarPath } from './factory-download'

const FACTORY_DEFAULT_PORT = 8141
const HEALTH_POLL_INTERVAL_MS = 2_000
const HEALTH_TIMEOUT_MS = 90_000
const GRACEFUL_SHUTDOWN_TIMEOUT_MS = 10_000

export interface FactoryProcess {
  port: number
  /** Gracefully stop the Factory: SIGTERM, then SIGKILL after 10 s. */
  shutdown: () => Promise<void>
}

async function waitForHealthy(port: number, isDead: () => boolean): Promise<boolean> {
  const url = `http://localhost:${port}/actuator/health`
  const deadline = Date.now() + HEALTH_TIMEOUT_MS
  while (Date.now() < deadline && !isDead()) {
    try {
      const res = await fetch(url)
      if (res.ok) {
        debugLog('FACTORY', `[RUNTIME] Health check passed on port ${port}`)
        return true
      }
    } catch {
      // still starting
    }
    await new Promise((resolve) => setTimeout(resolve, HEALTH_POLL_INTERVAL_MS))
  }
  return false
}

/**
 * Start the Factory service JAR and wait for it to be healthy.
 *
 * @param configDir  Root config directory (codayOptions.configDir).
 * @param version    Factory version whose JARs are on disk.
 * @param agentosUrl URL of the AgentOS the Factory must call (managed or external).
 */
export async function startFactory(
  configDir: string,
  version: string,
  agentosUrl: string
): Promise<FactoryProcess | null> {
  const jarPath = factoryJarPath(configDir, 'factory-service', version)
  if (!fs.existsSync(jarPath)) {
    debugLog('FACTORY', `[RUNTIME] JAR not found at ${jarPath} — skipping Factory startup`)
    return null
  }

  if (!detectJava().available) {
    debugLog('FACTORY', '[RUNTIME] No suitable Java — skipping Factory startup')
    return null
  }

  const factoryDir = path.join(configDir, 'factory')
  fs.mkdirSync(path.join(factoryDir, 'plugins'), { recursive: true })

  const port = await findAvailablePort(FACTORY_DEFAULT_PORT, 10)
  debugLog('FACTORY', `[RUNTIME] Spawning Factory — port ${port}, JAR ${jarPath}, CWD ${factoryDir}`)

  const javaHome = process.env.JAVA_HOME
  const javaBin = javaHome ? path.join(javaHome, 'bin', 'java') : 'java'

  const child: ChildProcess = spawn(
    javaBin,
    ['--enable-native-access=ALL-UNNAMED', '-jar', jarPath, `--server.port=${port}`],
    {
      cwd: factoryDir,
      env: {
        ...process.env,
        // Both properties point to AgentOS in factory-service's application.yml.
        AGENTOS_URL: agentosUrl,
      },
      stdio: ['ignore', 'pipe', 'pipe'],
      detached: false,
    }
  )

  const log = (data: Buffer) => {
    for (const line of data.toString().split('\n')) {
      if (line.trim()) debugLog('FACTORY', line.trim())
    }
  }
  child.stdout?.on('data', log)
  child.stderr?.on('data', log)

  let exited = false
  let spawnError: string | null = null
  child.on('error', (err) => {
    spawnError = err.message
    debugLog('FACTORY', `[RUNTIME] Spawn error: ${err.message}`)
  })
  child.on('exit', (code, signal) => {
    exited = true
    debugLog('FACTORY', `[RUNTIME] Process exited — code=${code} signal=${signal}`)
  })

  const healthy = await waitForHealthy(port, () => exited || spawnError !== null)
  if (!healthy || exited || spawnError) {
    debugLog('FACTORY', `[RUNTIME] Factory did not become healthy (exited=${exited}, error=${spawnError}) — killing`)
    try {
      child.kill('SIGKILL')
    } catch {
      // already dead
    }
    return null
  }

  // Last-resort cleanup if Node exits without going through gracefulShutdown.
  const exitHandler = () => {
    if (child.exitCode === null && !child.killed) {
      try {
        child.kill('SIGKILL')
      } catch {
        // ignore
      }
    }
  }
  process.on('exit', exitHandler)

  const shutdown = (): Promise<void> =>
    new Promise((resolve) => {
      process.removeListener('exit', exitHandler)
      if (child.exitCode !== null || child.killed) {
        resolve()
        return
      }
      const timer = setTimeout(() => {
        debugLog('FACTORY', '[RUNTIME] Graceful shutdown timed out — sending SIGKILL')
        try {
          child.kill('SIGKILL')
        } catch {
          /* ignore */
        }
      }, GRACEFUL_SHUTDOWN_TIMEOUT_MS)
      child.once('exit', () => {
        clearTimeout(timer)
        resolve()
      })
      try {
        child.kill('SIGTERM')
      } catch {
        clearTimeout(timer)
        resolve()
      }
    })

  return { port, shutdown }
}
