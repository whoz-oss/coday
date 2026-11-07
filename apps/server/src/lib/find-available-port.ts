import { execFile } from 'node:child_process'
import { debugLog } from './log'

/**
 * Addresses a port must be free on to be reported available: every interface AND loopback.
 *
 * The wildcard alone is not enough: Coday binds Express and the JVMs to 127.0.0.1, and some systems (macOS) let a
 * wildcard listen succeed while another process holds the same port on 127.0.0.1. The probe then says "free" and the
 * real bind fails with EADDRINUSE (e.g. a second Coday started while another one runs on the same port).
 */
const DEFAULT_PROBE_HOSTS: Array<string | undefined> = [undefined, '127.0.0.1']

type Probe = 'free' | 'busy'

async function probe(net: typeof import('node:net'), port: number, host: string | undefined): Promise<Probe> {
  return new Promise((resolve, reject) => {
    const server = net.createServer()
    server.once('error', (err: NodeJS.ErrnoException) => {
      if (err.code === 'EADDRINUSE') resolve('busy')
      else reject(err)
    })
    const onListening = () => server.close(() => resolve('free'))
    if (host === undefined) server.listen(port, onListening)
    else server.listen(port, host, onListening)
  })
}

/** True when nothing listens on `port`, on any of the probed addresses. */
export async function isPortFree(
  port: number,
  hosts: Array<string | undefined> = DEFAULT_PROBE_HOSTS
): Promise<boolean> {
  const net = await import('node:net')
  for (const host of hosts) {
    if ((await probe(net, port, host)) === 'busy') return false
  }
  return true
}

/**
 * A port the OS hands out (far from the Coday blocks): the last resort when every block Coday may use is taken.
 * The probe releases it right away, so the caller must bind it soon.
 */
export async function findEphemeralPort(): Promise<number> {
  const net = await import('node:net')
  return new Promise((resolve, reject) => {
    const server = net.createServer()
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address() as import('node:net').AddressInfo
      server.close(() => resolve(port))
    })
  })
}

/**
 * `lsof -F` output (`p<pid>` then `c<command>` per process) -> `command, pid 123`. Only the first process is reported.
 */
export function parseLsofOwner(output: string): string | undefined {
  let pid: string | undefined
  let command: string | undefined
  for (const line of output.split('\n')) {
    if (line.startsWith('p') && pid === undefined) pid = line.slice(1).trim()
    else if (line.startsWith('c') && command === undefined) command = line.slice(1).trim()
  }
  if (!pid) return undefined
  return command ? `${command}, pid ${pid}` : `pid ${pid}`
}

/**
 * The process listening on `port` (`node, pid 123`), best effort: `lsof` on macOS and Linux, undefined anywhere else or
 * when it is not installed, takes too long or finds nothing. Never throws.
 */
export function findPortOwner(port: number, platform: NodeJS.Platform = process.platform): Promise<string | undefined> {
  if (platform === 'win32') return Promise.resolve(undefined)
  return new Promise((resolve) => {
    try {
      execFile(
        'lsof',
        ['-nP', `-iTCP:${port}`, '-sTCP:LISTEN', '-Fpc'],
        { timeout: 2000, encoding: 'utf8' },
        (error, stdout) => resolve(error ? undefined : parseLsofOwner(String(stdout)))
      )
    } catch {
      resolve(undefined)
    }
  })
}

/** Ports macOS keeps for the AirPlay Receiver (a known owner worth a hint, not a port of the Coday scheme). */
const MACOS_AIRPLAY_PORTS = [5000, 7000]

/**
 * The message of a taken port, on ONE line (the desktop apps parse `Port N is already in use...`): the port, the
 * process holding it when known, a hint for known owners, and what to do.
 */
export function buildPortInUseMessage(
  port: number,
  owner?: string,
  platform: NodeJS.Platform = process.platform
): string {
  const by = owner ? ` (by ${owner})` : ''
  const hint =
    platform === 'darwin' && MACOS_AIRPLAY_PORTS.includes(port)
      ? ' On macOS this port is normally held by the AirPlay Receiver (System Settings > General > AirDrop & Handoff > AirPlay Receiver).'
      : ''
  return `Port ${port} is already in use${by}.${hint} Stop what uses it or choose another port (PORT=<n>, or CODAY_PORT_BASE=<n> for a whole block).`
}

/** `buildPortInUseMessage` with the owner looked up (injectable). */
export async function describePortInUse(
  port: number,
  lookup: (port: number) => Promise<string | undefined> = findPortOwner,
  platform: NodeJS.Platform = process.platform
): Promise<string> {
  let owner: string | undefined
  try {
    owner = await lookup(port)
  } catch {
    owner = undefined
  }
  return buildPortInUseMessage(port, owner, platform)
}

export async function findAvailablePort(
  startPort: number,
  maxAttempts = 100,
  strict = false,
  hosts: Array<string | undefined> = DEFAULT_PROBE_HOSTS
): Promise<number> {
  for (let port = startPort, attempts = maxAttempts; ; port++, attempts--) {
    if (await isPortFree(port, hosts)) {
      debugLog('PORT', `Port ${port} is available`)
      return port
    }
    if (strict) throw new Error(await describePortInUse(port))
    if (attempts <= 0) throw new Error(`Could not find an available port starting from ${startPort}`)
    debugLog('PORT', `Port ${port} is in use, trying next`)
  }
}
