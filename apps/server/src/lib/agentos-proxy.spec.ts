import express from 'express'
import http from 'node:http'
import type { AddressInfo } from 'node:net'
import { createAgentOsProxy } from './agentos-proxy'
import { applyProxyIdentity, decideProxyIdentity } from './proxy-identity'

type UpstreamEnding = 'end' | 'destroy'

interface ClientOutcome {
  frames: number
  ended: boolean
  closed: boolean
}

const FRAME = 'event: case-event\ndata: {}\n\n'

function listen(server: http.Server): Promise<number> {
  return new Promise((resolve) => server.listen(0, () => resolve((server.address() as AddressInfo).port)))
}

/**
 * An SSE upstream that sends two frames, then ends its response cleanly or drops the socket once
 * `release` is called, so the ending never races the delivery of the frames.
 */
function startUpstream(ending: UpstreamEnding, paths: string[]): { server: http.Server; release: () => void } {
  let release: () => void = () => undefined
  const released = new Promise<void>((resolve) => (release = resolve))
  const server = http.createServer((req, res) => {
    paths.push(req.url ?? '')
    res.writeHead(200, { 'Content-Type': 'text/event-stream' })
    res.write(FRAME)
    res.write(FRAME)
    void released.then(() => (ending === 'end' ? res.end() : res.socket?.destroy()))
  })
  return { server, release: () => release() }
}

const countFrames = (body: string): number => body.split('data:').length - 1

/**
 * Read the stream through the proxy, calling `onFrames` once both frames have arrived. Still open
 * after the deadline means the client was left hanging.
 */
function readThroughProxy(port: number, onFrames: () => void, deadlineMs: number): Promise<ClientOutcome> {
  return new Promise((resolve) => {
    let body = ''
    const outcome: ClientOutcome = { frames: 0, ended: false, closed: false }
    const timer = setTimeout(() => resolve(outcome), deadlineMs)
    const req = http.get({ port, path: '/api/agentos/api/cases/c-1/events' }, (res) => {
      res.on('data', (data: Buffer) => {
        body += data.toString()
        outcome.frames = countFrames(body)
        if (outcome.frames === 2) onFrames()
      })
      res.on('end', () => (outcome.ended = true))
      res.on('error', () => undefined)
      res.on('close', () => {
        outcome.closed = true
        clearTimeout(timer)
        resolve(outcome)
      })
    })
    req.on('error', () => undefined)
  })
}

describe('createAgentOsProxy', () => {
  let servers: http.Server[]

  beforeEach(() => {
    servers = []
  })

  afterEach(async () => {
    await Promise.all(
      servers.map(
        (server) =>
          new Promise<void>((resolve) => {
            server.closeAllConnections()
            server.close(() => resolve())
          })
      )
    )
  })

  async function startProxyTo(upstream: http.Server): Promise<number> {
    servers.push(upstream)
    const upstreamPort = await listen(upstream)
    const app = express()
    app.use(
      '/api/agentos',
      createAgentOsProxy(() => `http://localhost:${upstreamPort}`)
    )
    const proxy = http.createServer(app)
    servers.push(proxy)
    return listen(proxy)
  }

  it('closes the client connection when the upstream drops the stream mid-body', async () => {
    const upstream = startUpstream('destroy', [])
    const proxyPort = await startProxyTo(upstream.server)

    const outcome = await readThroughProxy(proxyPort, upstream.release, 2000)

    expect(outcome).toEqual({ frames: 2, ended: false, closed: true })
  })

  it('forwards the resolved caller identity as x-external-user-id, overwriting a client-sent value', async () => {
    let received: http.IncomingHttpHeaders = {}
    const upstream = http.createServer((req, res) => {
      received = req.headers
      res.end('ok')
    })
    servers.push(upstream)
    const upstreamPort = await listen(upstream)
    const app = express()
    app.use(
      '/api/agentos',
      createAgentOsProxy(() => `http://localhost:${upstreamPort}`, {
        onProxyReq: (proxyReq) =>
          applyProxyIdentity(
            proxyReq,
            decideProxyIdentity({ authEnabled: true, resolveUser: () => 'alice@example.com' })
          ),
      })
    )
    const proxy = http.createServer(app)
    servers.push(proxy)
    const proxyPort = await listen(proxy)

    await new Promise<void>((resolve, reject) => {
      http
        .get(
          { port: proxyPort, path: '/api/agentos/api/cases', headers: { 'X-External-User-Id': 'spoofed' } },
          (res) => {
            res.resume()
            res.on('end', () => resolve())
          }
        )
        .on('error', reject)
    })

    expect(received['x-external-user-id']).toBe('alice@example.com')
  })

  it('passes a stream that ends normally through to its end, with the AgentOS path rewritten', async () => {
    const paths: string[] = []
    const upstream = startUpstream('end', paths)
    const proxyPort = await startProxyTo(upstream.server)

    const outcome = await readThroughProxy(proxyPort, upstream.release, 2000)

    expect(outcome).toEqual({ frames: 2, ended: true, closed: true })
    expect(paths).toEqual(['/api/cases/c-1/events'])
  })
})
