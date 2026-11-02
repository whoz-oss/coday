/**
 * Open the user's browser on the app, once, when the Nx `server` task is up. No dependency: one platform switch.
 *
 * Never throws and never delays the server: the child is detached, its output ignored, and a failure (no
 * `xdg-open`, headless Linux...) only produces one log line.
 *
 * WHEN: immediately on a first run (the wizard needs no AgentOS), otherwise once AgentOS answers (the app is blank
 * until then, see app-url.ts). The wait runs beside the server (fire and forget in the caller), bounded and abortable.
 *
 * Only the Nx `server` task calls this. The in-process mode (desktop apps, no TTY, `--managed`) never does.
 */

import { spawn, SpawnOptions } from 'child_process'
import { appUrl, OpenTiming, openTiming } from './app-url'
import { isHealthy, waitUntil } from './process'
import { LOOPBACK } from './services'
import { RuntimeState } from './state'
import { WebSetup } from './web-setup'

const LOOPBACK_HOSTS = new Set(['127.0.0.1', 'localhost', '::1', '[::1]'])

/** AgentOS takes 20-60 s to boot; three minutes also covers a slow disk or a first JAR extraction. */
export const BACKEND_WAIT_MS = 180_000
const BACKEND_POLL_MS = 1_500

export interface BrowserOpenInput {
  /** `--no-open` was given. */
  noOpenFlag: boolean
  env: NodeJS.ProcessEnv
  /** `--auth` deployment. */
  auth: boolean
  /** Interface Express listens on. */
  host: string
  state: Pick<RuntimeState, 'profile' | 'ports'>
  setup: WebSetup
}

export type BrowserOpenPlan = { url: string; timing: OpenTiming } | { skip: string }

const isTrue = (value: string | undefined): boolean => value === '1' || value === 'true'
/** CI systems set `CI` to `true`, `1`... but `CI=false` / `CI=0` mean not in CI. */
const isCi = (value: string | undefined): boolean => !!value && !['0', 'false'].includes(value.toLowerCase())

/**
 * Whether to open the browser, on which URL and when; otherwise why not. Every skip condition is decided HERE,
 * before anything waits or probes.
 */
export function planBrowserOpen(input: BrowserOpenInput): BrowserOpenPlan {
  if (input.noOpenFlag) return { skip: '--no-open' }
  if (isTrue(input.env['CODAY_NO_OPEN'])) return { skip: 'CODAY_NO_OPEN is set' }
  if (isCi(input.env['CI'])) return { skip: 'CI is set' }
  if (input.auth) return { skip: '--auth deployment' }
  if (!LOOPBACK_HOSTS.has(input.host)) return { skip: `listening on ${input.host}, not on loopback` }
  const url = appUrl(input.state, input.setup)
  return url ? { url, timing: openTiming(input.state, input.setup) } : { skip: 'no Express port known' }
}

/**
 * The health URL the Express proxy depends on: the external AgentOS (`AGENTOS_URL`, used unchanged by the proxy) or
 * the managed one, whose port is read from state.json. Factory is not needed: only /factory uses it.
 */
export function agentosHealthUrl(
  externalUrl: string | undefined,
  state: Pick<RuntimeState, 'ports'> | undefined
): string | undefined {
  if (externalUrl) return `${externalUrl.replace(/\/+$/, '')}/management/health`
  const port = state?.ports.agentos
  return port ? `http://${LOOPBACK}:${port}/management/health` : undefined
}

export interface BackendWaitOptions {
  /** Re-evaluated at every poll: the port only appears in state.json once provisioning ran. */
  healthUrl: () => string | undefined
  signal?: AbortSignal
  timeoutMs?: number
  intervalMs?: number
  probe?: (url: string) => Promise<boolean>
  sleep?: (ms: number) => Promise<void>
  now?: () => number
}

/** True once AgentOS answers; false on timeout or when the signal aborts. */
export async function waitForAgentosHealthy(options: BackendWaitOptions): Promise<boolean> {
  const { timeoutMs = BACKEND_WAIT_MS, intervalMs = BACKEND_POLL_MS, now = Date.now } = options
  const probe = options.probe ?? ((url: string) => isHealthy(url))
  const deadline = now() + timeoutMs
  const outcome = await waitUntil(
    async () => {
      if (now() >= deadline) return 'timeout' as const
      const url = options.healthUrl()
      return url && (await probe(url)) ? ('ready' as const) : false
    },
    { intervalMs, signal: options.signal, sleep: options.sleep }
  )
  return outcome === 'ready'
}

export function openCommand(url: string, platform: NodeJS.Platform): { command: string; args: string[] } {
  switch (platform) {
    case 'darwin':
      return { command: 'open', args: [url] }
    case 'win32':
      // The empty string is the window title `start` would otherwise take the URL for.
      return { command: 'cmd', args: ['/c', 'start', '', url] }
    default:
      return { command: 'xdg-open', args: [url] }
  }
}

export interface OpenChild {
  on(event: 'error', listener: (error: Error) => void): unknown
  unref(): void
}
export type OpenSpawn = (command: string, args: string[], options: SpawnOptions) => OpenChild

export interface OpenBrowserOptions {
  platform?: NodeJS.Platform
  spawnFn?: OpenSpawn
  log?: (message: string) => void
}

/** Fire and forget; returns whether the launcher process could be spawned. */
export function openBrowser(url: string, options: OpenBrowserOptions = {}): boolean {
  const { platform = process.platform, spawnFn = (c, a, o) => spawn(c, a, o), log = () => undefined } = options
  const { command, args } = openCommand(url, platform)
  const failed = (reason: string) => log(`Could not open the browser (${reason}): open ${url} yourself.`)
  try {
    const child = spawnFn(command, args, { detached: true, stdio: 'ignore' })
    // Without a listener a missing binary (ENOENT) would crash the process.
    child.on('error', (error) => failed(error.message))
    child.unref()
    log(`Opening ${url} (disable with --no-open or CODAY_NO_OPEN=1)`)
    return true
  } catch (error) {
    failed(error instanceof Error ? error.message : String(error))
    return false
  }
}

export interface OpenWhenReadyDeps {
  open: (url: string) => unknown
  /** Resolves true once AgentOS answers, false on timeout or abort. */
  waitForBackend: () => Promise<boolean>
  log: (message: string) => void
  /** The foreground() signal: an abort cancels the wait without opening anything. */
  signal?: AbortSignal
}

/** Open now, or wait for AgentOS first (see OpenTiming). Returns whether the browser was opened. */
export async function openWhenReady(
  plan: { url: string; timing: OpenTiming },
  deps: OpenWhenReadyDeps
): Promise<boolean> {
  if (plan.timing === 'when-backend-ready') {
    if (deps.signal?.aborted) return false
    deps.log(`Waiting for AgentOS before opening the browser (${plan.url})...`)
    const ready = await deps.waitForBackend()
    if (deps.signal?.aborted) return false
    if (!ready) {
      deps.log(`AgentOS is not healthy yet: open ${plan.url} yourself when it is.`)
      return false
    }
  }
  deps.open(plan.url)
  return true
}
