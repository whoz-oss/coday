/**
 * The Express application of Coday web, as a factory shared by:
 *   - the Nx `server` task (its own process, ports read from state.json),
 *   - the in-process `--managed` mode (desktop apps, no TTY, no Nx).
 *
 * Order matters and is kept from the original server.ts:
 *   1. /api/setup/*   answers before AgentOS exists (wizard API)
 *   2. /api/agentos and /api/factory proxies, BEFORE express.json() so the body is not consumed
 *   3. express.json(), dev proxies or static client, SPA catch-all, error handler
 */

import express from 'express'
import fs from 'fs'
import http from 'http'
import path from 'path'
import { createProxyMiddleware } from 'http-proxy-middleware'

import { createAgentOsProxy } from './agentos-proxy'
import { CodayOptions } from './coday-options-utils'
import { createFactoryProxy } from './factory-proxy'
import { debugLog } from './log'
import { applyProxyIdentity, decideProxyIdentity } from './proxy-identity'
import { resolveUsername } from './resolve-username'
import { createIdentityProvider } from './runtime/identity'
import { readState } from './runtime/state'
import { createSetupRouter } from './setup/routes'
import { validateAgentOsUrl } from './validate-agentos-url'

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

const AGENTOS_FALLBACK_PORT = 8124
const FACTORY_FALLBACK_PORT = 8141

export interface WebAppOptions {
  codayOptions: CodayOptions
  /** Laptop profile without `--auth`: the first-run wizard API accepts mutations. */
  setupEnabled: boolean
}

export function createWebApp({ codayOptions, setupEnabled }: WebAppOptions): express.Express {
  const app = express()
  const { configDir } = codayOptions

  // ---------------------------------------------------------------------------
  // Proxy targets — two mutually exclusive modes per service, discriminated by AGENTOS_URL / FACTORY_URL
  //
  // EXTERNAL (URL defined): Coday proxies to that URL unchanged (own bootRun, server deployments).
  // MANAGED  (URL absent): the runtime started the service; its port is read from state.json AT
  //          REQUEST TIME (provisioning finishes after the server is already listening).
  // ---------------------------------------------------------------------------
  const AGENTOS_URL_ENV = process.env.AGENTOS_URL
  const FACTORY_URL_ENV = process.env.FACTORY_URL
  if (AGENTOS_URL_ENV) validateAgentOsUrl(AGENTOS_URL_ENV)
  if (FACTORY_URL_ENV) validateAgentOsUrl(FACTORY_URL_ENV)

  const managedUrl = (key: 'agentos' | 'factory', fallbackPort: number): string =>
    `http://127.0.0.1:${readState(configDir)?.ports[key] ?? fallbackPort}`
  const agentosTarget = (): string => AGENTOS_URL_ENV ?? managedUrl('agentos', AGENTOS_FALLBACK_PORT)
  const factoryTarget = (): string => FACTORY_URL_ENV ?? managedUrl('factory', FACTORY_FALLBACK_PORT)

  // Identity of proxied requests when `--auth` is off: env var, else the first-run identity (read per request).
  const currentIdentity = createIdentityProvider(configDir)

  /**
   * All incoming headers are already forwarded by http-proxy-middleware. This hook adds the caller
   * identity expected by the upstream services in X-External-User-Id (see ./proxy-identity.ts).
   */
  const forwardIdentity = (proxyReq: http.ClientRequest, req: http.IncomingMessage): void => {
    applyProxyIdentity(
      proxyReq,
      decideProxyIdentity({
        authEnabled: !!codayOptions.auth,
        envUserId: currentIdentity(),
        resolveUser: () => getUsername(req as express.Request),
      })
    )
  }

  function getUsername(req: express.Request): string {
    const username = resolveUsername(req.headers as Record<string, string | string[] | undefined>, !!codayOptions.auth)
    // Security check: prevent running as system/service accounts
    if (FORBIDDEN_USERNAMES.includes(username.toLowerCase() as (typeof FORBIDDEN_USERNAMES)[number])) {
      throw new Error(
        `Security error: Cannot run with username "${username}". ` +
          'This appears to be a system or service account. ' +
          'When running locally, ensure you are running as a regular user account. ' +
          'When running in production, ensure authentication is properly configured with --auth flag.'
      )
    }
    return username
  }

  // 1. Wizard API (own JSON parser inside).
  app.use('/api/setup', createSetupRouter({ configDir, setupEnabled }))

  // 2. Proxies (before express.json()).
  app.use(
    '/api/agentos',
    createAgentOsProxy(agentosTarget, {
      onProxyReq: (proxyReq, req) => {
        debugLog('AGENTOS', `[PROXY] ${req.method} ${req.url}`)
        forwardIdentity(proxyReq, req)
      },
    })
  )
  // Factory exposes its API with the /api/factory prefix already, so the complete request path is
  // forwarded unchanged (mounted at the root with a pathFilter, see ./factory-proxy.ts).
  app.use(
    createFactoryProxy(factoryTarget, {
      onProxyReq: (proxyReq, req) => {
        debugLog('FACTORY', `[PROXY] ${req.method} ${req.url}`)
        forwardIdentity(proxyReq, req)
      },
    })
  )
  debugLog(
    'INIT',
    `Proxies: /api/agentos -> ${AGENTOS_URL_ENV ?? 'managed AgentOS (state.json)'}, ` +
      `/api/factory -> ${FACTORY_URL_ENV ?? 'managed Factory (state.json)'}`
  )

  // 3. Body parsing for everything that follows.
  app.use(express.json({ limit: '20mb' }))

  if (process.env.BUILD_ENV === 'development') {
    // Development mode: proxy to the Angular dev servers, mirroring the production split between
    // the main client (/) and the factory cockpit (/factory).
    //
    // Both proxies are mounted at the root with a `pathFilter` rather than via `app.use('/factory', …)`:
    //  - Express strips the mount prefix, but the factory dev server serves its bundles under /factory/
    //    (baseHref), so the full path must be preserved.
    //  - With `ws: true` each proxy also subscribes to the server's WebSocket `upgrade` events, where
    //    only `pathFilter` applies. Disjoint filters keep each app's HMR socket routed to its dev server.
    const ANGULAR_DEV_SERVER = `http://localhost:${process.env.ANGULAR_CLIENT_PORT ?? '4200'}`
    const FACTORY_COCKPIT_DEV_SERVER = `http://localhost:${process.env.FACTORY_COCKPIT_PORT ?? '4300'}`
    const isFactoryPath = (p: string) => p === '/factory' || p.startsWith('/factory/')
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
    // Production mode: serve static Angular apps. CODAY_CLIENT_PATH is set by the web launcher;
    // otherwise fall back to the relative path (standalone server usage).
    const clientPath = process.env.CODAY_CLIENT_PATH
      ? path.resolve(process.env.CODAY_CLIENT_PATH)
      : path.resolve(__dirname, '../coday-client/browser')
    const factoryCockpitPath = process.env.CODAY_FACTORY_COCKPIT_PATH
      ? path.resolve(process.env.CODAY_FACTORY_COCKPIT_PATH)
      : path.resolve(__dirname, '../coday-factory-cockpit/browser')

    debugLog('INIT', `Production mode: serving static files from ${clientPath}`)
    debugLog('INIT', `Production mode: serving factory cockpit static files from ${factoryCockpitPath}`)
    warnIfMissing(clientPath, 'client', 'pnpm nx run client:build')
    warnIfMissing(factoryCockpitPath, 'factory cockpit', 'pnpm nx run factory-cockpit:build')

    // The factory cockpit goes before the main client so /factory assets are not intercepted by the root app.
    app.use('/factory', express.static(factoryCockpitPath))
    app.use(express.static(clientPath))

    const indexPath = path.resolve(clientPath, 'index.html')
    const factoryIndexPath = path.resolve(factoryCockpitPath, 'index.html')

    // Catch-all for Angular client-side routing (MUST be after all API routes): serve index.html for any
    // non-API route so the Angular router handles routes on page refresh.
    app.use((req, res, next) => {
      // API routes should have been handled above, but double-check to avoid masking real 404s.
      if (req.path.startsWith('/api') || req.path.startsWith('/events')) {
        res.status(404).send('Not found')
        return
      }
      const requested = req.path === '/factory' || req.path.startsWith('/factory/') ? factoryIndexPath : indexPath
      debugLog('ROUTER', `Serving index.html for client route: ${req.path}`)
      // Read and send the file manually to avoid Express sendFile issues in bundled code
      fs.readFile(requested, 'utf8', (err, data) => {
        if (err) {
          debugLog('ERROR', `Failed to read index.html from ${requested}:`, err)
          next(err)
        } else {
          res.type('html').send(data)
        }
      })
    })
  }

  // Error handling middleware
  app.use((err: Error, req: express.Request, res: express.Response, _: express.NextFunction) => {
    debugLog('ERROR', `Request error on ${req.method} ${req.path}:`, err.message)
    console.error(err.stack)
    if (process.env.BUILD_ENV === 'development') {
      res.status(500).json({ error: 'Internal Server Error', message: err.message, stack: err.stack })
    } else {
      res.status(500).send('Something went wrong!')
    }
  })

  return app
}

function warnIfMissing(dir: string, label: string, buildHint: string): void {
  if (!fs.existsSync(dir) || !fs.existsSync(path.join(dir, 'index.html'))) {
    console.error(`ERROR: ${label} build not found at ${dir}`)
    console.error(`Please build it first with: ${buildHint}`)
  }
}
