import express from 'express'
import path from 'path'
import fs from 'fs'
import { createProxyMiddleware } from 'http-proxy-middleware'

import { debugLog } from './lib/log'
import { parseCodayOptions } from './lib/coday-options-utils'
import { findAvailablePort } from './lib/find-available-port'
import { resolveUsername } from './lib/resolve-username'
import { applyProxyIdentity, decideProxyIdentity } from './lib/proxy-identity'
import { getAgentosVersion, getFactoryVersion } from './lib/version'
import { downloadAgentosJars } from './lib/agentos-download'
import { startAgentos, AgentosProcess } from './lib/agentos-runtime'
import { downloadFactoryJars } from './lib/factory-download'
import { startFactory, FactoryProcess } from './lib/factory-runtime'
import { validateAgentOsUrl } from './lib/validate-agentos-url'
import { createAgentOsProxy } from './lib/agentos-proxy'
import { createFactoryProxy } from './lib/factory-proxy'

const app = express()
const DEFAULT_PORT = process.env.PORT
  ? parseInt(process.env.PORT)
  : process.env.BUILD_ENV === 'development'
    ? 4100
    : 3000

// When PORT is explicitly set, use strict mode (no fallback to next port)
const isPortForced = !!process.env.PORT
const PORT_PROMISE = findAvailablePort(DEFAULT_PORT, isPortForced ? 0 : 10, isPortForced)
// Note: header constants are now managed in ./lib/resolve-username.ts

// Parse options once for all clients
const codayOptions = parseCodayOptions()
debugLog('INIT', 'Coday options:', codayOptions)

// ---------------------------------------------------------------------------
// AgentOS proxy — two mutually exclusive modes, discriminated by AGENTOS_URL
//
// EXTERNAL mode (AGENTOS_URL defined): Coday proxies to that URL unchanged.
//   Use this when running your own AgentOS (./gradlew bootRun) or in server
//   deployments where AgentOS is managed externally.
//
// MANAGED mode (AGENTOS_URL absent): Coday downloads JARs from GitHub
//   Releases, launches the process itself, and stops it at shutdown.
//   Target is always http://localhost:8124 (fixed port).
//
// The proxy middleware is registered synchronously and BEFORE express.json()
// to avoid body-parser consuming the request body before forwarding.
// ---------------------------------------------------------------------------

const AGENTOS_EXTERNAL_USERID = process.env.AGENTOS_EXTERNAL_USERID
const AGENTOS_URL_ENV = process.env.AGENTOS_URL
// Same two modes for the Factory, discriminated by FACTORY_URL:
//   EXTERNAL (FACTORY_URL defined): proxy to it unchanged.
//   MANAGED  (FACTORY_URL absent):  download the Factory JARs from the GitHub
//   release, spawn the process (default port 8141, next free one otherwise),
//   stop it at shutdown. The Factory is provisioned AFTER AgentOS because it
//   needs AgentOS's URL.
const FACTORY_URL_ENV = process.env.FACTORY_URL

// Validate proxy targets (fail fast on misconfiguration)
if (AGENTOS_URL_ENV) {
  validateAgentOsUrl(AGENTOS_URL_ENV)
}
if (FACTORY_URL_ENV) {
  validateAgentOsUrl(FACTORY_URL_ENV)
}

// Mutable proxy target — updated to the managed URL once AgentOS is running.
// In external mode this is set once and never changes.
let currentAgentosUrl = AGENTOS_URL_ENV ?? `http://localhost:8124`

// Mutable Factory proxy target — updated to the managed URL once the Factory is running.
let currentFactoryUrl = FACTORY_URL_ENV ?? 'http://localhost:8141'

// Track the managed process handles (null in external mode or before startup)
let agentosProcess: AgentosProcess | null = null
let factoryProcess: FactoryProcess | null = null

/**
 * All incoming headers are already forwarded by http-proxy-middleware. This hook
 * adds the caller identity expected by the upstream services in X-External-User-Id
 * (see ./lib/proxy-identity.ts). `getUsername` is a hoisted function declaration
 * and is only invoked at request time, once codayOptions is initialised.
 */
function forwardIdentity(proxyReq: import('http').ClientRequest, req: import('http').IncomingMessage): void {
  applyProxyIdentity(
    proxyReq,
    decideProxyIdentity({
      authEnabled: !!codayOptions.auth,
      envUserId: AGENTOS_EXTERNAL_USERID,
      resolveUser: () => getUsername(req as express.Request),
    })
  )
}

app.use(
  '/api/agentos',
  createAgentOsProxy(() => currentAgentosUrl, {
    onProxyReq: (proxyReq, req) => {
      debugLog('AGENTOS', `[PROXY] ${req.method} ${req.url}`)
      forwardIdentity(proxyReq, req)
    },
  })
)
debugLog(
  'INIT',
  `AgentOS proxy configured: /api/agentos → ${currentAgentosUrl} (${AGENTOS_URL_ENV ? 'external mode' : 'managed mode'})`
)

// Factory exposes its API with the /api/factory prefix already, so the complete request path is
// forwarded unchanged (mounted at the root with a pathFilter, see ./lib/factory-proxy.ts).
// Same identity forwarding and stream-abort propagation as the AgentOS proxy above.
app.use(
  createFactoryProxy(() => currentFactoryUrl, {
    onProxyReq: (proxyReq, req) => {
      debugLog('FACTORY', `[PROXY] ${req.method} ${req.url}`)
      forwardIdentity(proxyReq, req)
    },
  })
)
debugLog(
  'INIT',
  `Factory proxy configured: /api/factory → ${currentFactoryUrl}/api/factory (${FACTORY_URL_ENV ? 'external mode' : 'managed mode'})`
)

// Middleware to parse JSON bodies with increased limit
app.use(express.json({ limit: '20mb' }))

// Development mode: proxy to the Angular dev servers, mirroring the production
// split between the main client (/) and the factory cockpit (/factory).
//
// Both proxies are mounted at the root with a `pathFilter` rather than via
// `app.use('/factory', …)`:
//  - Express strips the mount prefix, but the factory dev server serves its
//    bundles under /factory/ (baseHref), so the full path must be preserved.
//    Stripping it makes the dev server answer its SPA fallback (index.html)
//    for /main.js → "Expected a JavaScript module script… MIME type text/html".
//  - With `ws: true` each proxy also subscribes to the server's WebSocket
//    `upgrade` events, where only `pathFilter` applies. Disjoint filters keep
//    each app's HMR socket routed to its own dev server.
if (process.env.BUILD_ENV === 'development') {
  const ANGULAR_DEV_SERVER = `http://localhost:${process.env.ANGULAR_CLIENT_PORT ?? '4200'}`
  const FACTORY_COCKPIT_DEV_SERVER = `http://localhost:${process.env.FACTORY_COCKPIT_PORT ?? '4300'}`

  const isFactoryPath = (p: string) => {
    return p === '/factory' || p.startsWith('/factory/')
  }
  const isBackendPath = (p: string) => p.startsWith('/api') || p.startsWith('/events')

  app.use(
    createProxyMiddleware({
      target: FACTORY_COCKPIT_DEV_SERVER,
      changeOrigin: true,
      ws: true,
      pathFilter: (p) => isFactoryPath(p),
    })
  )

  app.use(
    createProxyMiddleware({
      target: ANGULAR_DEV_SERVER,
      changeOrigin: true,
      ws: true,
      pathFilter: (p) => !isBackendPath(p) && !isFactoryPath(p),
    })
  )

  debugLog(
    'INIT',
    `Development mode: /factory → ${FACTORY_COCKPIT_DEV_SERVER}, other non-API routes → ${ANGULAR_DEV_SERVER}`
  )
} else {
  // Production mode: serve static Angular app
  // Check for CODAY_CLIENT_PATH environment variable (set by web launcher)
  // Otherwise fall back to relative path (for standalone server usage)
  const clientPath = process.env.CODAY_CLIENT_PATH
    ? path.resolve(process.env.CODAY_CLIENT_PATH)
    : path.resolve(__dirname, '../coday-client/browser')

  debugLog('INIT', `Production mode: serving static files from ${clientPath}`)
  if (process.env.CODAY_CLIENT_PATH) {
    debugLog('INIT', 'Using client path from CODAY_CLIENT_PATH environment variable')
  }

  // Verify client path exists
  if (!fs.existsSync(clientPath)) {
    console.error(`ERROR: Client path does not exist: ${clientPath}`)
    console.error('Please build the client first with: pnpm nx run client:build')
  } else {
    const indexPath = path.join(clientPath, 'index.html')
    if (!fs.existsSync(indexPath)) {
      console.error(`ERROR: index.html not found at: ${indexPath}`)
      console.error('Client build may be incomplete. Try rebuilding with: pnpm nx run client:build')
    } else {
      debugLog('INIT', `Verified index.html exists at ${indexPath}`)
    }
  }

  // Serve the factory cockpit before the main client so /factory assets are not
  // intercepted by the root static application.
  const factoryCockpitPath = process.env.CODAY_FACTORY_COCKPIT_PATH
    ? path.resolve(process.env.CODAY_FACTORY_COCKPIT_PATH)
    : path.resolve(__dirname, '../coday-factory-cockpit/browser')

  debugLog('INIT', `Production mode: serving factory cockpit static files from ${factoryCockpitPath}`)
  if (!fs.existsSync(factoryCockpitPath)) {
    console.error(`ERROR: Factory cockpit path does not exist: ${factoryCockpitPath}`)
    console.error('Please build the factory cockpit first with: pnpm nx run factory-cockpit:build')
  } else {
    const factoryIndexPath = path.join(factoryCockpitPath, 'index.html')
    if (!fs.existsSync(factoryIndexPath)) {
      console.error(`ERROR: index.html not found at: ${factoryIndexPath}`)
      console.error('Factory cockpit build may be incomplete. Try rebuilding with: pnpm nx run factory-cockpit:build')
    } else {
      debugLog('INIT', `Verified factory cockpit index.html exists at ${factoryIndexPath}`)
    }
  }

  app.use('/factory', express.static(factoryCockpitPath))

  // Serve static files from the main Angular build output.
  app.use(express.static(clientPath))
}

/**
 * System and service account usernames that are forbidden for security reasons.
 * These accounts are commonly used in containers, CI/CD, and system services
 * and should never be used for multi-user applications as they would create
 * a shared account for all users.
 */
const FORBIDDEN_USERNAMES = [
  'root', // Unix/Linux superuser
  'admin', // Common administrative account
  'administrator', // Windows administrator
  'system', // Windows system account
  'daemon', // Unix daemon account
  'nobody', // Unix unprivileged account
  'node', // Common Node.js container user
  'app', // Generic application user
  'service', // Generic service account
  'docker', // Docker-related accounts
  'www-data', // Web server user (nginx, apache)
  'nginx', // Nginx user
  'apache', // Apache user
  'ansible', // Ansible user
] as const

/**
 * Extract username for authentication and logging purposes.
 *
 * Resolution order when `--auth` is enabled:
 *  1. `Cf-Access-Jwt-Assertion` header — Cloudflare Access JWT (email claim decoded
 *     from the base64url payload; no signature verification needed as
 *     Cloudflare validates the token at the edge).
 *  2. `x-forwarded-email` header — standard reverse-proxy header.
 *  3. Throws if neither source yields a valid email.
 *
 * When `--auth` is NOT enabled, uses the local OS username (development mode).
 *
 * @param req - Express request object containing headers
 * @returns Username string used for proxy identity
 * @throws Error if username is a system/service account (security protection)
 * @throws Error if `--auth` is enabled but no valid identity header is found
 */
function getUsername(req: express.Request): string {
  const username = resolveUsername(req.headers as Record<string, string | string[] | undefined>, !!codayOptions.auth)

  // Security check: prevent running as system/service accounts
  if (FORBIDDEN_USERNAMES.includes(username.toLowerCase() as any)) {
    throw new Error(
      `Security error: Cannot run with username "${username}". ` +
        'This appears to be a system or service account. ' +
        'When running locally, ensure you are running as a regular user account. ' +
        'When running in production, ensure authentication is properly configured with --auth flag.'
    )
  }

  return username
}

// Catch-all route for Angular client-side routing (MUST be after all API routes)
// In production mode, serve index.html for any non-API routes
// This allows Angular router to handle routes on page refresh
if (process.env.BUILD_ENV !== 'development') {
  // Reuse the clientPath that was already resolved and verified at startup
  const clientPath = process.env.CODAY_CLIENT_PATH
    ? path.resolve(process.env.CODAY_CLIENT_PATH)
    : path.resolve(__dirname, '../coday-client/browser')

  const indexPath = path.resolve(clientPath, 'index.html')
  const factoryCockpitPath = process.env.CODAY_FACTORY_COCKPIT_PATH
    ? path.resolve(process.env.CODAY_FACTORY_COCKPIT_PATH)
    : path.resolve(__dirname, '../coday-factory-cockpit/browser')
  const factoryIndexPath = path.resolve(factoryCockpitPath, 'index.html')
  debugLog('INIT', `Catch-all route will serve: ${indexPath}`)
  debugLog('INIT', `Factory cockpit catch-all route will serve: ${factoryIndexPath}`)

  // Use a middleware instead of route pattern to catch all remaining requests
  app.use((req, res, next) => {
    // API routes should have been handled above, but double-check to avoid masking real 404s.
    if (req.path.startsWith('/api') || req.path.startsWith('/events')) {
      res.status(404).send('Not found')
      return
    }

    const requestedIndexPath =
      req.path === '/factory' || req.path.startsWith('/factory/') ? factoryIndexPath : indexPath
    debugLog('ROUTER', `Serving index.html for client route: ${req.path}`)

    // Read and send the file manually to avoid Express sendFile issues in bundled code
    fs.readFile(requestedIndexPath, 'utf8', (err, data) => {
      if (err) {
        debugLog('ERROR', `Failed to read index.html from ${requestedIndexPath}:`, err)
        debugLog('ERROR', `File exists check: ${fs.existsSync(requestedIndexPath)}`)
        next(err)
      } else {
        res.type('html').send(data)
      }
    })
  })
}

// ---------------------------------------------------------------------------
// AgentOS provisioning entry point (managed mode only)
// ---------------------------------------------------------------------------

/**
 * Single entry point called after app.listen() to provision and start AgentOS.
 *
 * In EXTERNAL mode (AGENTOS_URL defined): logs and returns immediately.
 * In MANAGED mode: downloads the requested version unless its JAR set (derived from the
 * release manifest) is already complete — never starts an older cached version → starts the process.
 *
 * Never throws — any error is logged and the function returns gracefully.
 * The Express server stays up regardless of AgentOS provisioning outcome.
 *
 * @param expressPort The port the Express server is listening on.
 */
async function provisionAgentos(expressPort: number): Promise<void> {
  if (AGENTOS_URL_ENV) {
    debugLog('AGENTOS', `[PROVISION] External mode — target: ${AGENTOS_URL_ENV}. No provisioning performed.`)
    return
  }

  debugLog('AGENTOS', '[PROVISION] Managed mode — starting AgentOS provisioning...')

  try {
    const agentosVersion = getAgentosVersion()

    // --- 1. Download the requested version unless its JAR set is already complete ---
    // The set of JARs comes from the release manifest, nothing is listed by hand. The requested
    // version always wins over what is cached on disk: no older version is started in the
    // meantime, and every other version is removed after the download.
    const downloadResult = await downloadAgentosJars(codayOptions.configDir, agentosVersion)
    debugLog('AGENTOS', `[PROVISION] Download outcome: ${downloadResult.outcome} — ${downloadResult.message}`)

    if (
      downloadResult.outcome !== 'success' &&
      downloadResult.outcome !== 'skipped:all-present' &&
      downloadResult.outcome !== 'skipped:dev-version'
    ) {
      debugLog('AGENTOS', '[PROVISION] Download failed — AgentOS will not be started. The proxy will return errors.')
      return
    }

    // --- 2. Start the requested version ---
    debugLog('AGENTOS', '[PROVISION] Starting AgentOS process...')
    agentosProcess = await startAgentos(codayOptions.configDir, agentosVersion, expressPort)

    if (!agentosProcess) {
      debugLog('AGENTOS', '[PROVISION] AgentOS did not start. The proxy will return errors until AgentOS is available.')
      return
    }

    // Update the proxy target (always localhost:8124 in managed mode, but kept
    // explicit so future flexibility (e.g. dynamic port) requires no refactor)
    currentAgentosUrl = `http://localhost:${agentosProcess.port}`
    debugLog(
      'AGENTOS',
      `[PROVISION] AgentOS is ready. Proxy target: ${currentAgentosUrl}` +
        ` (${agentosProcess.spawned ? 'spawned by Coday' : 'adopted existing process'})`
    )
  } catch (error) {
    debugLog('AGENTOS', '[PROVISION] Unexpected error during provisioning (non-fatal):', error)
  }
}

// ---------------------------------------------------------------------------
// Factory provisioning entry point (managed mode only)
// ---------------------------------------------------------------------------

/**
 * Provision and start the Factory. Must run after provisionAgentos() so that
 * `currentAgentosUrl` is the final AgentOS URL.
 *
 * EXTERNAL mode (FACTORY_URL defined): returns immediately.
 * MANAGED mode, same flow as AgentOS: download the requested version (blocking) unless its
 * JAR set is already complete, never starting an older cached version → spawn.
 * With the 'dev' version sentinel nothing is downloaded: the Factory only starts if
 * its JARs were placed by hand in <configDir>/factory/.
 *
 * Never throws.
 */
async function provisionFactory(): Promise<void> {
  if (FACTORY_URL_ENV) {
    debugLog('FACTORY', `[PROVISION] External mode — target: ${FACTORY_URL_ENV}. No provisioning performed.`)
    return
  }

  debugLog('FACTORY', '[PROVISION] Managed mode — starting Factory provisioning...')
  try {
    const factoryVersion = getFactoryVersion()

    // --- 1. Download the requested version unless its JAR set is already complete ---
    // The set of JARs comes from the release manifest, nothing is listed by hand. The requested
    // version always wins over what is cached on disk: no older version is started in the
    // meantime, and every other version is removed after the download.
    const result = await downloadFactoryJars(codayOptions.configDir, factoryVersion)
    debugLog('FACTORY', `[PROVISION] Download outcome: ${result.outcome} — ${result.message}`)
    if (result.outcome !== 'success' && result.outcome !== 'skipped:all-present') {
      debugLog('FACTORY', '[PROVISION] Factory will not be started. The proxy will return errors.')
      return
    }

    // --- 2. Start the requested version ---
    factoryProcess = await startFactory(codayOptions.configDir, factoryVersion, currentAgentosUrl)
    if (!factoryProcess) {
      debugLog('FACTORY', '[PROVISION] Factory did not start. The proxy will return errors until it is available.')
      return
    }

    currentFactoryUrl = `http://localhost:${factoryProcess.port}`
    debugLog('FACTORY', `[PROVISION] Factory is ready. Proxy target: ${currentFactoryUrl}`)
  } catch (error) {
    debugLog('FACTORY', '[PROVISION] Unexpected error during provisioning (non-fatal):', error)
  }
}

// Error handling middleware
app.use((err: any, req: express.Request, res: express.Response, _: express.NextFunction) => {
  debugLog('ERROR', `Request error on ${req.method} ${req.path}:`, err.message)
  console.error(err.stack)

  // Provide more detailed error in development
  if (process.env.BUILD_ENV === 'development') {
    res.status(500).json({
      error: 'Internal Server Error',
      message: err.message,
      stack: err.stack,
    })
  } else {
    res.status(500).send('Something went wrong!')
  }
})

// Use PORT_PROMISE to listen on the available port
PORT_PROMISE.then(async (PORT) => {
  app.listen(PORT, () => {
    console.log(`Server is running on http://localhost:${PORT}`)
  })

  // AgentOS provisioning — fire-and-forget: runs in background so downloads
  // (up to ~230 MB) never block server startup or other services.
  // The proxy will return errors until AgentOS is ready, which is acceptable.
  // Factory is chained after AgentOS (it needs AgentOS's final URL). provisionAgentos never rejects.
  provisionAgentos(PORT)
    .catch((err) => debugLog('AGENTOS', '[PROVISION] Unhandled error:', err))
    .then(() => provisionFactory())
    .catch((err) => debugLog('FACTORY', '[PROVISION] Unhandled error:', err))
}).catch((error) => {
  console.error('Failed to start server:', error)
  process.exit(1)
})

// Handle port-in-use errors from findAvailablePort in strict mode
PORT_PROMISE.catch((error) => {
  if (error instanceof Error && error.message.includes('already in use')) {
    process.stderr.write(`PORT_IN_USE: ${error.message}\n`)
  }
})

// Graceful shutdown with proper cleanup
// Counter-based guard: 1st signal starts shutdown, 2nd is tolerated (child process cascade),
// 3rd force-exits. This prevents data loss from SIGINT propagating to child processes
// in the same process group (managed JVMs) which cascades back as a second signal.
let shutdownSignalCount = 0

async function gracefulShutdown(signal: string) {
  shutdownSignalCount++

  if (shutdownSignalCount === 2) {
    console.log(
      `Received ${signal} during shutdown (likely from child process cascade) — shutdown in progress, press Ctrl+C again to force exit`
    )
    return
  }

  if (shutdownSignalCount >= 3) {
    console.log(`Received ${signal} — forcing exit`)
    process.exit(1)
  }

  console.log(`Received ${signal}, shutting down gracefully...`)

  try {
    // Stop the Factory first (it depends on AgentOS); only set when we spawned it
    if (factoryProcess) {
      console.log('Stopping Factory...')
      await factoryProcess.shutdown()
    }

    // Stop AgentOS (only if we spawned it — not if adopted or external mode)
    if (agentosProcess?.spawned) {
      console.log('Stopping AgentOS...')
      await agentosProcess.shutdown()
    }

    console.log('Graceful shutdown completed')
    process.exit(0)
  } catch (error) {
    console.error('Error during graceful shutdown:', error)
    process.exit(1)
  }
}

// Handle various termination signals
process.on('SIGTERM', () => gracefulShutdown('SIGTERM'))
process.on('SIGINT', () => gracefulShutdown('SIGINT'))
process.on('SIGUSR2', () => gracefulShutdown('SIGUSR2')) // watcher restart
process.on('SIGHUP', () => gracefulShutdown('SIGHUP')) // terminal closed

// Handle uncaught exceptions to prevent hanging
process.on('uncaughtException', (error) => {
  console.error('Uncaught exception:', error)

  if (shutdownSignalCount === 0) {
    gracefulShutdown('uncaughtException')
  }
})

process.on('unhandledRejection', (reason, promise) => {
  console.error('Unhandled rejection at:', promise, 'reason:', reason)

  // Handle timeout and abort errors gracefully - these are expected during high load
  if (reason && typeof reason === 'object') {
    const error = reason as { name?: string; message?: string }
    if (error.name === 'TimeoutError' || error.message?.includes('timeout')) {
      console.log('Detected timeout error - handling gracefully without shutdown')
      return
    }

    // Handle AbortError from timeouts
    if (error.name === 'AbortError' || error.message?.includes('aborted')) {
      console.log('Detected abort error - handling gracefully without shutdown')
      return // Don't shutdown for abort errors
    }
  }

  // Only shutdown for critical unhandled rejections
  console.error('Critical unhandled rejection detected')
  if (shutdownSignalCount === 0) {
    gracefulShutdown('unhandledRejection')
  }
})
