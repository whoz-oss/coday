import express from 'express'
import http from 'node:http'
import type { AddressInfo } from 'node:net'
import { createAgentOsProxy } from './agentos-proxy'

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

/** An SSE upstream that sends two frames, then ends its response cleanly or drops the socket. */
function startUpstream(ending: UpstreamEnding, paths: string[]): http.Server {
  return http.createServer((req, res) => {
    paths.push(req.url ?? '')
    res.writeHead(200, { 'Content-Type': 'text/event-stream' })
    res.write(FRAME)
    res.write(FRAME)
    setTimeout(() => (ending === 'end' ? res.end() : res.socket?.destroy()), 50)
  })
}

/** Read the stream through the proxy; still open after the deadline means the client was left hanging. */
function readThroughProxy(port: number, deadlineMs: number): Promise<ClientOutcome> {
  return new Promise((resolve) => {
    const outcome: ClientOutcome = { frames: 0, ended: false, closed: false }
    const timer = setTimeout(() => resolve(outcome), deadlineMs)
    const req = http.get({ port, path: '/api/agentos/api/cases/c-1/events' }, (res) => {
      res.on('data', (data: Buffer) => (outcome.frames += data.toString().split('data:').length - 1))
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
    const proxyPort = await startProxyTo(startUpstream('destroy', []))

    const outcome = await readThroughProxy(proxyPort, 2000)

    expect(outcome).toEqual({ frames: 2, ended: false, closed: true })
  })

  it('passes a stream that ends normally through to its end, with the AgentOS path rewritten', async () => {
    const paths: string[] = []
    const proxyPort = await startProxyTo(startUpstream('end', paths))

    const outcome = await readThroughProxy(proxyPort, 2000)

    expect(outcome).toEqual({ frames: 2, ended: true, closed: true })
    expect(paths).toEqual(['/api/cases/c-1/events'])
  })
})
