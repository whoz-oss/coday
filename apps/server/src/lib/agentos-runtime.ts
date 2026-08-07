/**
 * agentos-runtime.ts
 *
 * Launch the AgentOS Spring Boot JAR as a managed child process.
 *
 * Design decisions:
 *   - Port dynamique : findAvailablePort(expressPort + 1) — AgentOS ne prend
 *     jamais le port du serveur Express, et s'adapte si le suivant est pris.
 *   - Avant de spawner, on sonde /management/health sur le port choisi. Si un
 *     AgentOS répond déjà, on l'ADOPTE sans le tuer — et on ne le tue PAS au shutdown.
 *   - cwd = <configDir>/agentos : les chemins relatifs de Spring (plugins/,
 *     data/, data/exchange/) tombent exactement sur le layout voulu sans
 *     aucune env var supplémentaire.
 *   - AGENTOS_ENCRYPTION_KEY=NONE + AGENTOS_ENCRYPTION_SALT=NONE : désactive
 *     explicitement le chiffrement (temporaire — credentials en clair sur
 *     disque, AgentOS logue un WARN). À remplacer par de vraies valeurs quand
 *     la gestion des secrets sera en place.
 *   - AGENTOS_OAUTH_REDIRECT_URI : dérivé du port du serveur Express pour que
 *     le callback OAuth pointe sur la bonne URL.
 *
 * THIS MODULE HAS NO SIDE EFFECTS AT IMPORT TIME.
 */

import { spawn, execFileSync, ChildProcess } from 'child_process'
import * as path from 'path'
import { debugLog } from './log'
import { findAvailablePort } from './find-available-port'

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Minimum Java major version required. AgentOS toolchain targets Java 25. */
const JAVA_MIN_VERSION = 25

/** How often (ms) to poll the Spring Boot health endpoint while waiting for startup. */
const HEALTH_POLL_INTERVAL_MS = 2_000

/** Maximum time (ms) to wait for Spring Boot to become healthy. */
const HEALTH_TIMEOUT_MS = 60_000

/** Time (ms) to wait for graceful SIGTERM before escalating to SIGKILL. */
const GRACEFUL_SHUTDOWN_TIMEOUT_MS = 10_000

// ---------------------------------------------------------------------------
// Public types
// ---------------------------------------------------------------------------

/** Handle returned by startAgentos() once AgentOS is running and healthy. */
export interface AgentosProcess {
  /** The TCP port AgentOS is listening on (always AGENTOS_PORT = 8124). */
  port: number
  /**
   * Whether this process was spawned by us (true) or adopted (false).
   * Only spawned processes are stopped at shutdown.
   */
  spawned: boolean
  /**
   * Gracefully stop AgentOS.
   * No-op if the process was adopted (not spawned by us).
   * Sends SIGTERM first; escalates to SIGKILL after 10 s.
   */
  shutdown: () => Promise<void>
}

// ---------------------------------------------------------------------------
// Java detection
// ---------------------------------------------------------------------------

/**
 * Probe the local JVM installation.
 *
 * `java -version` writes its output to stderr (JVM convention).
 * We require Java >= 25 (AgentOS toolchain, see agentos/gradle/libs.versions.toml).
 *
 * @returns { available: true, version } when a suitable JVM is found, otherwise { available: false }.
 */
function detectJava(): { available: boolean; version?: string } {
  // JAVA_HOME may not be set in non-interactive shells (e.g. SDKMAN, Homebrew).
  // Resolve the java binary path: prefer JAVA_HOME/bin/java, fall back to 'java' on PATH.
  const javaHome = process.env.JAVA_HOME
  const javaBin = javaHome ? path.join(javaHome, 'bin', 'java') : 'java'
  if (javaHome) {
    debugLog('AGENTOS', `[RUNTIME] Using JAVA_HOME: ${javaHome}`)
  }
  try {
    // --version writes to stdout and exits 0 on Java 9+.
    // -version writes to stderr — unreliable with execFileSync.
    const stdout = execFileSync(javaBin, ['--version'], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
    })

    // First line: `openjdk 25 2025-09-16` or `openjdk 21.0.3 2024-04-16`
    const versionMatch = stdout.match(/^\S+\s+(\d+)/)
    if (!versionMatch) {
      debugLog('AGENTOS', `[RUNTIME] java --version output could not be parsed: ${stdout.slice(0, 200)}`)
      return { available: false }
    }

    const major = parseInt(versionMatch[1] ?? '0', 10)
    const versionString = stdout.split('\n')[0]?.trim() ?? `Java ${major}`

    if (major < JAVA_MIN_VERSION) {
      debugLog(
        'AGENTOS',
        `[RUNTIME] WARN: Java ${major} detected but >= ${JAVA_MIN_VERSION} is required by AgentOS toolchain.` +
          ` AgentOS will NOT be started. The proxy will return errors until a suitable JVM is installed.`
      )
      return { available: false, version: versionString }
    }

    debugLog('AGENTOS', `[RUNTIME] Java ${major} detected — OK (${versionString})`)
    return { available: true, version: versionString }
  } catch {
    debugLog(
      'AGENTOS',
      `[RUNTIME] WARN: java binary not found (tried: ${javaHome ? path.join(javaHome, 'bin', 'java') : 'java in PATH'}).` +
        ` AgentOS will NOT be started. Set JAVA_HOME or ensure java >= ${JAVA_MIN_VERSION} is on PATH.`
    )
    return { available: false }
  }
}

// ---------------------------------------------------------------------------
// Health polling
// ---------------------------------------------------------------------------

/**
 * Check if an AgentOS instance is already healthy at the fixed port.
 * Single probe, no retry. Used for adoption detection before spawn.
 */
async function isAlreadyRunning(port: number): Promise<boolean> {
  try {
    const url = `http://localhost:${port}/management/health`
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), 3_000)
    let res: Response
    try {
      res = await fetch(url, { signal: controller.signal })
    } finally {
      clearTimeout(timer)
    }
    return res.ok
  } catch {
    return false
  }
}

/**
 * Poll /management/health until Spring Boot reports 2xx or timeout.
 * Spring Boot with Neo4j embedded can take up to ~60 s on first start.
 */
async function waitForHealthy(port: number): Promise<boolean> {
  const url = `http://localhost:${port}/management/health`
  const deadline = Date.now() + HEALTH_TIMEOUT_MS

  while (Date.now() < deadline) {
    try {
      const res = await fetch(url)
      if (res.ok) {
        debugLog('AGENTOS', `[RUNTIME] Health check passed on port ${port}`)
        return true
      }
      debugLog(
        'AGENTOS',
        `[RUNTIME] Health check on port ${port} returned ${res.status}, retrying in ${HEALTH_POLL_INTERVAL_MS}ms...`
      )
    } catch {
      // Connection refused or network error — process still starting
    }
    await new Promise((resolve) => setTimeout(resolve, HEALTH_POLL_INTERVAL_MS))
  }

  debugLog('AGENTOS', `[RUNTIME] Health check on port ${port} timed out after ${HEALTH_TIMEOUT_MS / 1000} s`)
  return false
}

// ---------------------------------------------------------------------------
// Main export
// ---------------------------------------------------------------------------

/**
 * Attempt to start the AgentOS Spring Boot JAR as a child process.
 *
 * If an AgentOS instance is already responding at port 8124, it is ADOPTED
 * (not spawned, not killed at shutdown). This handles orphaned processes from
 * a previous Coday killed with SIGKILL, or a developer's own bootRun instance.
 *
 * Returns null (without throwing) if:
 *   - Java >= 25 is not available
 *   - The JAR file does not exist at the expected path
 *   - The process fails to become healthy within 60 s
 *
 * A null return means the proxy will return errors — the Express server still starts normally.
 *
 * @param configDir   Root config directory (from codayOptions.configDir).
 * @param version     Expected version string (from getCodayVersion()).
 * @param expressPort Port the Express server is listening on (for OAuth redirect URI).
 * @returns An AgentosProcess handle when AgentOS is running and healthy, or null.
 */
export async function startAgentos(
  configDir: string,
  version: string,
  expressPort: number
): Promise<AgentosProcess | null> {
  // ------------------------------------------------------------------
  // 1. Find available ports (expressPort + 1 as starting point)
  // AgentOS HTTP port, then Neo4j Bolt port right after it — both
  // dynamically assigned to avoid conflicts in dev (multiple managed
  // instances, standalone Neo4j on the default 7687/7688 ports, etc.).
  // ------------------------------------------------------------------
  const agentosPort = await findAvailablePort(expressPort + 1, 10)
  const neo4jBoltPort = await findAvailablePort(agentosPort + 1, 10)
  debugLog('AGENTOS', `[RUNTIME] Selected port ${agentosPort} for AgentOS (Express is on ${expressPort})`)
  debugLog('AGENTOS', `[RUNTIME] Selected port ${neo4jBoltPort} for Neo4j embedded Bolt`)

  // ------------------------------------------------------------------
  // 2. Check if an AgentOS instance is already running on that port (adoption)
  // ------------------------------------------------------------------
  const alreadyRunning = await isAlreadyRunning(agentosPort)
  if (alreadyRunning) {
    debugLog(
      'AGENTOS',
      `[RUNTIME] An AgentOS instance is already responding at http://localhost:${agentosPort}.` +
        ` ADOPTING it — Coday did NOT start this process and will NOT stop it at shutdown.` +
        ` NOTE: it may be running an older version. If you observe unexpected behaviour, kill it manually and restart Coday.`
    )
    return {
      port: agentosPort,
      spawned: false,
      shutdown: async () => {
        debugLog('AGENTOS', '[RUNTIME] Adopted process — skipping shutdown')
      },
    }
  }

  // ------------------------------------------------------------------
  // 3. Detect Java >= 25
  // ------------------------------------------------------------------
  const java = detectJava()
  if (!java.available) {
    // Warning already logged inside detectJava()
    return null
  }

  // ------------------------------------------------------------------
  // 4. Verify JAR exists
  // ------------------------------------------------------------------
  const agentosDir = path.join(configDir, 'agentos')
  const jarPath = path.join(agentosDir, `agentos-service-${version}.jar`)

  // We import fs lazily here to avoid any import-time side effects
  const { existsSync } = await import('fs')
  if (!existsSync(jarPath)) {
    debugLog('AGENTOS', `[RUNTIME] JAR not found at ${jarPath} — skipping AgentOS startup`)
    return null
  }

  // ------------------------------------------------------------------
  // 5. Spawn the process
  // ------------------------------------------------------------------
  debugLog('AGENTOS', `[RUNTIME] Spawning AgentOS — config:`)
  debugLog('AGENTOS', `[RUNTIME]   port:     ${agentosPort}`)
  debugLog('AGENTOS', `[RUNTIME]   JAR:      ${jarPath}`)
  debugLog('AGENTOS', `[RUNTIME]   CWD:      ${agentosDir}`)
  debugLog('AGENTOS', `[RUNTIME]   JAVA_HOME: ${process.env.JAVA_HOME ?? '(not set, using PATH)'}`)
  debugLog('AGENTOS', `[RUNTIME]   encryption: NONE (plaintext — temporary)`)
  debugLog('AGENTOS', `[RUNTIME]   OAuth redirect: http://localhost:${expressPort}/agentos/oauth/callback`)

  const javaHome = process.env.JAVA_HOME
  const javaBin = javaHome ? path.join(javaHome, 'bin', 'java') : 'java'

  const child: ChildProcess = spawn(javaBin, ['-jar', jarPath, `--server.port=${agentosPort}`], {
    cwd: agentosDir,
    // Inherit env from parent process, then add our overrides.
    // AGENTOS_ENCRYPTION_KEY/SALT=NONE: explicitly disables field encryption
    // (temporary — credentials stored in plaintext on disk; AgentOS logs a WARN).
    // Replace with real cryptographic values when secret management is in place.
    env: {
      ...process.env,
      // Fallback to NONE only if not already set — real values from the environment
      // take precedence (production deployments with actual encryption keys).
      AGENTOS_ENCRYPTION_KEY: process.env.AGENTOS_ENCRYPTION_KEY ?? 'NONE',
      AGENTOS_ENCRYPTION_SALT: process.env.AGENTOS_ENCRYPTION_SALT ?? 'NONE',
      // Derive the OAuth redirect URI from the actual Express port so the
      // browser callback URL is correct regardless of which port was assigned.
      // An existing env var takes precedence (e.g. production reverse-proxy URL).
      AGENTOS_OAUTH_REDIRECT_URI:
        process.env.AGENTOS_OAUTH_REDIRECT_URI ?? `http://localhost:${expressPort}/agentos/oauth/callback`,
      // Assign a dynamic Bolt port for the embedded Neo4j instance to avoid
      // conflicts with other AgentOS instances or a standalone Neo4j on 7687/7688.
      // Also update the spring.neo4j.uri to match so SDN connects to the right port.
      // Both env vars respect any existing value; SPRING_NEO4J_URI falls back to
      // whichever Bolt port was ultimately chosen (env override or dynamic).
      AGENTOS_PERSISTENCE_EMBEDDED_BOLT_PORT:
        process.env.AGENTOS_PERSISTENCE_EMBEDDED_BOLT_PORT ?? String(neo4jBoltPort),
      SPRING_NEO4J_URI:
        process.env.SPRING_NEO4J_URI ??
        `bolt://localhost:${process.env.AGENTOS_PERSISTENCE_EMBEDDED_BOLT_PORT ?? neo4jBoltPort}`,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
    detached: false,
  })

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

  let spawnError: { message: string } | null = null
  let exited = false

  child.on('error', (err) => {
    spawnError = err
    debugLog('AGENTOS', `[RUNTIME] Spawn error: ${err.message}`)
  })

  child.on('exit', (code, signal) => {
    exited = true
    debugLog('AGENTOS', `[RUNTIME] Process exited — code=${code} signal=${signal}`)
  })

  // ------------------------------------------------------------------
  // 6. Wait for healthy
  // ------------------------------------------------------------------
  debugLog(
    'AGENTOS',
    `[RUNTIME] Waiting for AgentOS to become healthy on port ${agentosPort} (up to ${HEALTH_TIMEOUT_MS / 1000} s)...`
  )
  const healthy = await waitForHealthy(agentosPort)

  const spawnErrorMsg = spawnError ? (spawnError as { message: string }).message : null
  if (!healthy || exited || spawnError) {
    debugLog(
      'AGENTOS',
      `[RUNTIME] AgentOS did not become healthy (healthy=${healthy}, exited=${exited}, spawnError=${spawnErrorMsg}) — killing process`
    )
    try {
      child.kill('SIGKILL')
    } catch {
      // already dead
    }
    return null
  }

  debugLog('AGENTOS', `[RUNTIME] AgentOS is up and healthy on port ${agentosPort}`)

  // ------------------------------------------------------------------
  // 7. Build and return the handle
  // ------------------------------------------------------------------
  const shutdown = (): Promise<void> =>
    new Promise((resolve) => {
      if (child.exitCode !== null || child.killed) {
        resolve()
        return
      }

      const timer = setTimeout(() => {
        debugLog('AGENTOS', '[RUNTIME] Graceful shutdown timed out — sending SIGKILL')
        try {
          child.kill('SIGKILL')
        } catch {
          /* ignore */
        }
      }, GRACEFUL_SHUTDOWN_TIMEOUT_MS)

      child.once('exit', () => {
        clearTimeout(timer)
        debugLog('AGENTOS', '[RUNTIME] AgentOS process exited')
        resolve()
      })

      debugLog('AGENTOS', '[RUNTIME] Sending SIGTERM to AgentOS')
      try {
        child.kill('SIGTERM')
      } catch (err) {
        clearTimeout(timer)
        debugLog('AGENTOS', `[RUNTIME] Error sending SIGTERM: ${err}`)
        resolve()
      }
    })

  return { port: agentosPort, spawned: true, shutdown }
}
