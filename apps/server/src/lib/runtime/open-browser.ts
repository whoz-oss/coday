/**
 * Open the user's browser on the welcome page, once, when the Nx `server` task is up. No dependency: one platform switch.
 *
 * Never throws and never delays the server: the child is detached, its output ignored, and a failure (no
 * `xdg-open`, headless Linux...) only produces one log line.
 *
 * WHEN: as soon as Express listens, whatever the profile. `/welcome` needs no AgentOS: it is what shows the progress
 * (download, Java, AgentOS, Factory) and hands over to the app once AgentOS is up.
 *
 * Only the Nx `server` task calls this. The in-process mode (desktop apps, no TTY, `--managed`) never does.
 */

import { spawn, SpawnOptions } from 'child_process'
import { appUrl } from './app-url'
import { LOOPBACK } from './services'
import { RuntimeState } from './state'

const LOOPBACK_HOSTS = new Set(['127.0.0.1', 'localhost', '::1', '[::1]'])

export interface BrowserOpenInput {
  /** `--no-open` was given. */
  noOpenFlag: boolean
  env: NodeJS.ProcessEnv
  /** `--auth` deployment. */
  auth: boolean
  /** Interface Express listens on. */
  host: string
  state: Pick<RuntimeState, 'ports'>
}

export type BrowserOpenPlan = { url: string } | { skip: string }

const isTrue = (value: string | undefined): boolean => value === '1' || value === 'true'
/** CI systems set `CI` to `true`, `1`... but `CI=false` / `CI=0` mean not in CI. */
const isCi = (value: string | undefined): boolean => !!value && !['0', 'false'].includes(value.toLowerCase())

/** Whether to open the browser and on which URL; otherwise why not. */
export function planBrowserOpen(input: BrowserOpenInput): BrowserOpenPlan {
  if (input.noOpenFlag) return { skip: '--no-open' }
  if (isTrue(input.env['CODAY_NO_OPEN'])) return { skip: 'CODAY_NO_OPEN is set' }
  if (isCi(input.env['CI'])) return { skip: 'CI is set' }
  if (input.auth) return { skip: '--auth deployment' }
  if (!LOOPBACK_HOSTS.has(input.host)) return { skip: `listening on ${input.host}, not on loopback` }
  const url = appUrl(input.state)
  return url ? { url } : { skip: 'no Express port known' }
}

/**
 * The health URL of AgentOS: the external one (`AGENTOS_URL`, used unchanged by the proxy) or the managed one, whose
 * port is read from state.json.
 */
export function agentosHealthUrl(
  externalUrl: string | undefined,
  state: Pick<RuntimeState, 'ports'> | undefined
): string | undefined {
  if (externalUrl) return `${externalUrl.replace(/\/+$/, '')}/management/health`
  const port = state?.ports.agentos
  return port ? `http://${LOOPBACK}:${port}/management/health` : undefined
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
