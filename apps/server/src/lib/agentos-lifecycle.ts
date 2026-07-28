import { spawn, execFileSync, ChildProcess } from 'child_process'
import * as fs from 'fs'
import * as os from 'os'
import * as path from 'path'
import { debugLog } from './log'
import { findAvailablePort } from './find-available-port'

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Default port for AgentOS — matches the historic env-var default in server.ts */
const AGENTOS_DEFAULT_PORT = 8124

/** How often (ms) to poll the Spring Boot health endpoint while waiting for startup */
const HEALTH_POLL_INTERVAL_MS = 2_000

/** Maximum time (ms) to wait for Spring Boot to become healthy */
const HEALTH_TIMEOUT_MS = 60_000

/** Time (ms) to wait for graceful SIGTERM before escalating to SIGKILL */
const GRACEFUL_SHUTDOWN_TIMEOUT_MS = 10_000

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/** Structured result from the Java detection probe. */
interface JavaDetectionResult {
  available: boolean
  version?: string
  path?: string
}

/** Configuration passed to startAgentos(). Derived from filesystem layout. */
export interface AgentosConfig {
  jarPath: string
  pluginsDir: string
  dataDir: string
  port: number
}

/** Handle returned by startAgentos() once AgentOS is running and healthy. */
export interface AgentosProcess {
  /** The TCP port AgentOS is actually listening on. */
  port: number
  /** The underlying Node.js ChildProcess. */
  process: ChildProcess
  /**
   * Gracefully stop AgentOS.
   * Sends SIGTERM first; escalates to SIGKILL after 10 s if the process has not
   * exited on its own.
   */
  shutdown: () => Promise<void>
}

// ---------------------------------------------------------------------------
// Java detection
// ---------------------------------------------------------------------------

/**
 * Probe the local JVM installation.
 *
 * `java -version` writes its output to **stderr** (by JVM convention), so we
 * capture stderr and parse the version string from there.
 *
 * We require Java >= 17 because Spring Boot 3 dropped support for earlier
 * releases.
 *
 * @returns Structured detection result; `available: false` on any failure.
 */
async function detectJava(): Promise<JavaDetectionResult> {
  try {
    // execFileSync throws on non-zero exit; we capture stderr because java -version
    // writes there even on success.
    const stderr = execFileSync('java', ['-version'], {
      encoding: 'utf8',
      stdio: ['ignore', 'ignore', 'pipe'],
    })

    // The first line typically looks like:
    //   openjdk version "17.0.9" 2023-10-17
    //   java version "1.8.0_382"
    const versionMatch = stderr.match(/"(\d+)(?:\.(\d+))?/)
    if (!versionMatch) {
      debugLog('AGENTOS', 'java -version output could not be parsed:', stderr)
      return { available: false }
    }

    // Java 9+ uses a single-component major version ("17", "21"...).
    // Java 8 and earlier use "1.x" notation.
    const rawMajor = parseInt(versionMatch[1] ?? '0', 10)
    const major = rawMajor === 1 ? parseInt(versionMatch[2] ?? '0', 10) : rawMajor
    const versionString = versionMatch[0].replace(/^"/, '')

    if (major < 17) {
      debugLog('AGENTOS', `Java ${major} detected but >= 17 is required (Spring Boot 3)`)
      return { available: false, version: versionString }
    }

    debugLog('AGENTOS', `Java ${major} detected — OK`)
    return { available: true, version: versionString }
  } catch {
    // java not found in PATH, or execFileSync threw for another reason
    return { available: false }
  }
}

// ---------------------------------------------------------------------------
// Health polling
// ---------------------------------------------------------------------------

/**
 * Poll `http://localhost:<port>/actuator/health` until Spring Boot reports
 * a 2xx status or the timeout is exceeded.
 *
 * Spring Boot can take a while to start (class-loading, plugin discovery, Neo4j
 * embedded init), so we allow up to 60 s.
 *
 * @returns `true` when healthy, `false` on timeout.
 */
async function waitForHealthy(port: number): Promise<boolean> {
  const url = `http://localhost:${port}/actuator/health`
  const deadline = Date.now() + HEALTH_TIMEOUT_MS

  while (Date.now() < deadline) {
    try {
      const res = await fetch(url)
      if (res.ok) {
        debugLog('AGENTOS', `Health check passed on port ${port}`)
        return true
      }
      debugLog('AGENTOS', `Health check returned ${res.status}, retrying...`)
    } catch {
      // Connection refused or network error — process still starting
    }
    await new Promise((resolve) => setTimeout(resolve, HEALTH_POLL_INTERVAL_MS))
  }

  debugLog('AGENTOS', `Health check timed out after ${HEALTH_TIMEOUT_MS / 1000} s`)
  return false
}

// ---------------------------------------------------------------------------
// Main export
// ---------------------------------------------------------------------------

/**
 * Attempt to start the bundled AgentOS Spring Boot JAR as a child process.
 *
 * Design decisions:
 * - The JAR is expected at `<server-dist>/agentos/agentos-service.jar`.
 *   This path is resolved relative to `__dirname` so it works both in the
 *   compiled dist tree and in ts-node/jest contexts.
 * - If the JAR is absent, Java is missing, or startup fails, the function
 *   returns `null` instead of throwing. The caller (server.ts) then falls
 *   back to the env-var-driven proxy configuration, which will simply 503
 *   until someone starts AgentOS externally.
 * - Port selection starts at the historic default (8124) and scans up to 10
 *   consecutive ports to avoid collisions in multi-instance setups.
 * - Data is stored under `~/.coday/agentos/data/` so it survives server
 *   restarts and is consistent with the rest of Coday's persistence model.
 *
 * @returns An `AgentosProcess` handle when AgentOS is healthy, or `null`.
 */
export async function startAgentos(): Promise<AgentosProcess | null> {
  // ------------------------------------------------------------------
  // 1. Resolve JAR path
  // ------------------------------------------------------------------
  const jarPath = path.resolve(__dirname, 'agentos', 'agentos-service.jar')
  const pluginsDir = path.resolve(__dirname, 'agentos', 'plugins')

  if (!fs.existsSync(jarPath)) {
    debugLog('AGENTOS', `JAR not found at ${jarPath} — skipping AgentOS startup`)
    return null
  }

  // ------------------------------------------------------------------
  // 2. Detect Java >= 17
  // ------------------------------------------------------------------
  const java = await detectJava()
  if (!java.available) {
    debugLog(
      'AGENTOS',
      java.version
        ? `Java ${java.version} is too old (need >= 17) — skipping AgentOS startup`
        : 'Java not found in PATH — skipping AgentOS startup'
    )
    return null
  }

  // ------------------------------------------------------------------
  // 3. Find an available port
  // ------------------------------------------------------------------
  let port: number
  try {
    port = await findAvailablePort(AGENTOS_DEFAULT_PORT, 10)
  } catch (err) {
    debugLog('AGENTOS', 'Could not find an available port — skipping AgentOS startup:', err)
    return null
  }

  // ------------------------------------------------------------------
  // 4. Ensure data directory exists
  // ------------------------------------------------------------------
  const dataDir = path.join(os.homedir(), '.coday', 'agentos', 'data')
  fs.mkdirSync(dataDir, { recursive: true })
  debugLog('AGENTOS', `Data directory: ${dataDir}`)

  // ------------------------------------------------------------------
  // 5. Spawn the process
  // ------------------------------------------------------------------
  debugLog('AGENTOS', `Spawning AgentOS on port ${port} with JAR ${jarPath}`)

  const child = spawn(
    'java',
    [
      '-jar',
      jarPath,
      `--server.port=${port}`,
      `--agentos.plugins.dir=${pluginsDir}`,
      `--spring.neo4j.embedded.data-dir=${dataDir}`,
    ],
    {
      stdio: ['ignore', 'pipe', 'pipe'],
      detached: false,
    }
  )

  child.stdout?.on('data', (data: Buffer) => {
    for (const line of data.toString().split('\n')) {
      if (line.trim()) debugLog('AGENTOS', line.trim())
    }
  })

  child.stderr?.on('data', (data: Buffer) => {
    for (const line of data.toString().split('\n')) {
      if (line.trim()) debugLog('AGENTOS', line.trim())
    }
  })

  // Track whether the process exited unexpectedly during startup
  let spawnError: Error | null = null
  let exited = false

  child.on('error', (err) => {
    spawnError = err
    debugLog('AGENTOS', 'Spawn error:', err.message)
  })

  child.on('exit', (code, signal) => {
    exited = true
    debugLog('AGENTOS', `Process exited — code=${code} signal=${signal}`)
  })

  // ------------------------------------------------------------------
  // 6. Wait for healthy
  // ------------------------------------------------------------------
  const healthy = await waitForHealthy(port)

  if (!healthy || exited || spawnError) {
    debugLog('AGENTOS', 'AgentOS did not become healthy — killing process')
    try {
      child.kill('SIGKILL')
    } catch {
      // already dead
    }
    return null
  }

  debugLog('AGENTOS', `AgentOS is up and healthy on port ${port}`)

  // ------------------------------------------------------------------
  // 7. Build and return the handle
  // ------------------------------------------------------------------

  /**
   * Gracefully stop the AgentOS child process.
   *
   * Sends SIGTERM and waits up to 10 s for the JVM to shut down cleanly
   * (Spring Boot registers a shutdown hook). If it is still alive after
   * the grace period, SIGKILL is used as a last resort.
   */
  const shutdown = (): Promise<void> =>
    new Promise((resolve) => {
      if (child.exitCode !== null || child.killed) {
        // Already dead
        resolve()
        return
      }

      const timer = setTimeout(() => {
        debugLog('AGENTOS', 'Graceful shutdown timed out — sending SIGKILL')
        try {
          child.kill('SIGKILL')
        } catch {
          // ignore
        }
      }, GRACEFUL_SHUTDOWN_TIMEOUT_MS)

      child.once('exit', () => {
        clearTimeout(timer)
        debugLog('AGENTOS', 'AgentOS process exited')
        resolve()
      })

      debugLog('AGENTOS', 'Sending SIGTERM to AgentOS')
      try {
        child.kill('SIGTERM')
      } catch (err) {
        clearTimeout(timer)
        debugLog('AGENTOS', 'Error sending SIGTERM:', err)
        resolve()
      }
    })

  return { port, process: child, shutdown }
}
