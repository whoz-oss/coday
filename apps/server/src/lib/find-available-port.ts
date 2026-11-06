import { debugLog } from './log'

/**
 * Addresses a port must be free on to be reported available: every interface AND loopback.
 *
 * The wildcard alone is not enough: Coday binds Express and the JVMs to 127.0.0.1, and some systems (macOS) let a
 * wildcard listen succeed while another process holds the same port on 127.0.0.1. The probe then says "free" and the
 * real bind fails with EADDRINUSE (e.g. a second Coday started while `coday-web` runs on 3000).
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

export async function findAvailablePort(
  startPort: number,
  maxAttempts = 100,
  strict = false,
  hosts: Array<string | undefined> = DEFAULT_PROBE_HOSTS
): Promise<number> {
  const net = await import('node:net')

  const isFree = async (port: number): Promise<boolean> => {
    for (const host of hosts) {
      if ((await probe(net, port, host)) === 'busy') return false
    }
    return true
  }

  for (let port = startPort, attempts = maxAttempts; ; port++, attempts--) {
    if (await isFree(port)) {
      debugLog('PORT', `Port ${port} is available`)
      return port
    }
    if (strict) throw new Error(`Port ${port} is already in use. Please free the port and restart CodayTwin.`)
    if (attempts <= 0) throw new Error(`Could not find an available port starting from ${startPort}`)
    debugLog('PORT', `Port ${port} is in use, trying next`)
  }
}
