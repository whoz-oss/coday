/**
 * `/api/setup/*`: the first-run wizard API. Registered BEFORE the AgentOS proxy and before any
 * guard that needs AgentOS, so it answers while AgentOS is still downloading or starting.
 *
 *   GET  /api/setup/status    steps + mode, driven by state.json, web-setup.json, live health probes
 *   POST /api/setup/identity  { email }
 *   GET  /api/setup/browse    ?path=  (disabled once setup is done)
 *   POST /api/setup/mkdir     { path, name } (disabled once setup is done)
 *   POST /api/setup/apply     { providers: [{ id, apiKey, rank }], sharedExchangePath }
 *
 * Every mutating route and the folder browser only answer when `setupEnabled` (laptop profile, no
 * `--auth`) AND the caller is a page of this machine on loopback: this API can read the filesystem and
 * receives API keys.
 */

import express, { Router } from 'express'
import { isHealthy } from '../runtime/process'
import { LOOPBACK } from '../runtime/services'
import { readState } from '../runtime/state'
import {
  agentosDatabaseExists,
  isValidEmail,
  markSetupDone,
  normalizeEmail,
  readWebSetup,
  saveIdentity,
} from '../runtime/web-setup'
import { AgentosClient } from './agentos-client'
import { ApplyValidationError, runApply, validateApplyInput } from './apply'
import { browse, BrowseError, defaultCodayFolder, mkdir } from './browse'
import { MODEL_CATALOGUE } from './model-catalogue'
import { ApplyTracker, buildSetupStatus, decideIdentityChange } from './progress'

export interface SetupRouterOptions {
  configDir: string
  /** Laptop profile and no `--auth`: the wizard and its mutations apply. */
  setupEnabled: boolean
  fetchFn?: typeof fetch
  /** `AGENTOS_URL` / `FACTORY_URL`: services Coday does not run. The status probes them instead of the managed ports. */
  externalAgentosUrl?: string
  externalFactoryUrl?: string
  /** Overridable for tests. */
  homeDir?: string
  tracker?: ApplyTracker
  applyTimings?: { namespaceAttempts?: number; namespaceIntervalMs?: number }
}

const LOOPBACK_ADDRESSES = new Set(['127.0.0.1', '::1', '::ffff:127.0.0.1'])

const trimSlash = (url: string): string => url.replace(/\/+$/, '')

export const isLoopbackAddress = (address: string | undefined): boolean => !!address && LOOPBACK_ADDRESSES.has(address)

const LOOPBACK_HOSTNAMES = new Set(['localhost', '127.0.0.1', '[::1]'])

const hasLoopbackHostname = (url: string): boolean => {
  try {
    return LOOPBACK_HOSTNAMES.has(new URL(url).hostname)
  } catch {
    return false
  }
}

/**
 * The page calling is served from this machine. A loopback socket is not enough: a page of another site whose name was
 * rebound to 127.0.0.1 (DNS rebinding) reaches the server from loopback, but with its own name in Host and Origin.
 */
export const isLoopbackPage = (host: string | undefined, origin: string | undefined): boolean =>
  !!host && hasLoopbackHostname(`http://${host}`) && (origin === undefined || hasLoopbackHostname(origin))

function agentosBase(configDir: string): string | undefined {
  const port = readState(configDir)?.ports.agentos
  return port ? `http://${LOOPBACK}:${port}` : undefined
}

export function createSetupRouter(options: SetupRouterOptions): Router {
  const { configDir, setupEnabled } = options
  const fetchFn = options.fetchFn ?? fetch
  const tracker = options.tracker ?? new ApplyTracker()
  const router = Router()
  // Own JSON parser: this router is mounted before the app-wide express.json().
  router.use(express.json({ limit: '100kb' }))

  const guard: express.RequestHandler = (req, res, next) => {
    if (!setupEnabled) {
      res.status(403).json({ error: 'First-run setup is not available here.' })
      return
    }
    if (!isLoopbackAddress(req.socket.remoteAddress) || !isLoopbackPage(req.headers.host, req.headers.origin)) {
      res.status(403).json({ error: 'First-run setup is only available from this machine.' })
      return
    }
    next()
  }
  const notDone: express.RequestHandler = (_req, res, next) => {
    if (readWebSetup(configDir).setupDone) {
      res.status(403).json({ error: 'Setup is already done.' })
      return
    }
    next()
  }

  router.get('/status', async (_req, res) => {
    const state = readState(configDir)
    const base = agentosBase(configDir)
    const factoryPort = state?.ports.factory
    const agentosHealthUrl = options.externalAgentosUrl
      ? `${trimSlash(options.externalAgentosUrl)}/management/health`
      : base && `${base}/management/health`
    const factoryHealthUrl = options.externalFactoryUrl
      ? `${trimSlash(options.externalFactoryUrl)}/actuator/health`
      : factoryPort && `http://${LOOPBACK}:${factoryPort}/actuator/health`
    const [agentosHealthy, factoryHealthy] = await Promise.all([
      agentosHealthUrl ? isHealthy(agentosHealthUrl, fetchFn) : false,
      factoryHealthUrl ? isHealthy(factoryHealthUrl, fetchFn) : false,
    ])
    res.setHeader('Cache-Control', 'no-store')
    res.json({
      ...buildSetupStatus({
        setupEnabled,
        setup: readWebSetup(configDir),
        state,
        agentosHealthy,
        factoryHealthy,
        apply: tracker.get(),
        external: { agentos: !!options.externalAgentosUrl, factory: !!options.externalFactoryUrl },
      }),
      defaults: { sharedExchangePath: defaultCodayFolder(options.homeDir) },
      providers: Object.values(MODEL_CATALOGUE).map((p) => ({
        id: p.id,
        label: p.label,
        description: p.description,
        keyUrl: p.keyUrl,
      })),
    })
  })

  router.post('/identity', guard, notDone, (req, res) => {
    const email = (req.body as { email?: unknown } | undefined)?.email
    if (!isValidEmail(email)) {
      res.status(400).json({ error: 'Enter a valid email address.' })
      return
    }
    const normalized = normalizeEmail(email)
    const decision = decideIdentityChange(
      setupEnabled,
      readWebSetup(configDir),
      readState(configDir),
      normalized,
      agentosDatabaseExists(configDir)
    )
    if (!decision.ok) {
      res.status(decision.status).json({ error: decision.message })
      return
    }
    res.json({ identity: saveIdentity(configDir, normalized).identity })
  })

  router.get('/browse', guard, notDone, (req, res) => {
    try {
      const requested = typeof req.query['path'] === 'string' ? req.query['path'] : undefined
      res.json(browse(requested, { home: options.homeDir, showHidden: req.query['hidden'] === 'true' }))
    } catch (err) {
      if (err instanceof BrowseError) res.status(err.status).json({ error: err.message })
      else res.status(500).json({ error: 'Could not list this folder.' })
    }
  })

  router.post('/mkdir', guard, notDone, (req, res) => {
    const { path: parent, name } = (req.body ?? {}) as { path?: unknown; name?: unknown }
    try {
      if (typeof parent !== 'string' || typeof name !== 'string')
        throw new BrowseError(400, 'path and name are required.')
      res.status(201).json(mkdir(parent, name))
    } catch (err) {
      if (err instanceof BrowseError) res.status(err.status).json({ error: err.message })
      else res.status(500).json({ error: 'The folder could not be created.' })
    }
  })

  router.post('/apply', guard, notDone, (req, res) => {
    const email = readWebSetup(configDir).identity?.email
    if (!email) {
      res.status(409).json({ error: 'Enter your email first.' })
      return
    }
    if (tracker.isRunning()) {
      res.status(202).json({ status: 'running' })
      return
    }
    let input
    try {
      input = validateApplyInput(req.body)
    } catch (err) {
      res.status(400).json({ error: err instanceof ApplyValidationError ? err.message : 'Invalid request.' })
      return
    }

    // Fire and forget: the wizard polls /status for the per-step results.
    void runApply(
      input,
      {
        client: new AgentosClient(() => agentosBase(configDir) ?? `http://${LOOPBACK}:0`, email, fetchFn),
        waitForAgentos: async () => {
          const deadline = Date.now() + 10 * 60_000
          while (Date.now() < deadline) {
            const base = agentosBase(configDir)
            if (base && (await isHealthy(`${base}/management/health`, fetchFn))) return true
            await new Promise((r) => setTimeout(r, 1_500))
          }
          return false
        },
        tracker,
        userEmail: email,
        ...options.applyTimings,
      },
      () => markSetupDone(configDir)
    )
    res.status(202).json({ status: 'running' })
  })

  return router
}
