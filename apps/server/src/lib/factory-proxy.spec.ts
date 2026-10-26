import express from 'express'
import http from 'node:http'
import type { AddressInfo } from 'node:net'
import { createFactoryProxy, isFactoryApiPath } from './factory-proxy'
import { applyProxyIdentity, decideProxyIdentity } from './proxy-identity'

const FRAME = 'event: case-event\ndata: {}\n\n'

function listen(server: http.Server): Promise<number> {
  return new Promise((resolve) => server.listen(0, () => resolve((server.address() as AddressInfo).port)))
}

describe('isFactoryApiPath', () => {
  it('matches the factory API prefix and its sub-paths only', () => {
    expect(isFactoryApiPath('/api/factory')).toBe(true)
    expect(isFactoryApiPath('/api/factory/workflows')).toBe(true)
    expect(isFactoryApiPath('/api/factoryx')).toBe(false)
    expect(isFactoryApiPath('/api/agentos/x')).toBe(false)
    expect(isFactoryApiPath('/factory/main.js')).toBe(false)
  })
})

describe('createFactoryProxy', () => {
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

  async function startProxyTo(upstream: http.Server, options?: Parameters<typeof createFactoryProxy>[1]) {
    servers.push(upstream)
    const upstreamPort = await listen(upstream)
    const app = express()
    app.use(createFactoryProxy(() => `http://localhost:${upstreamPort}`, options))
    const proxy = http.createServer(app)
    servers.push(proxy)
    return listen(proxy)
  }

  const get = (port: number, path: string, headers: http.OutgoingHttpHeaders = {}) =>
    new Promise<void>((resolve, reject) => {
      http
        .get({ port, path, headers }, (res) => {
          res.resume()
          res.on('end', () => resolve())
        })
        .on('error', reject)
    })

  it('forwards the complete /api/factory path unchanged', async () => {
    const paths: string[] = []
    const upstream = http.createServer((req, res) => {
      paths.push(req.url ?? '')
      res.end('ok')
    })
    const port = await startProxyTo(upstream)

    await get(port, '/api/factory/workflows?x=1')

    expect(paths).toEqual(['/api/factory/workflows?x=1'])
  })

  it('forwards the resolved caller identity as x-external-user-id, overwriting a client-sent value', async () => {
    let received: http.IncomingHttpHeaders = {}
    const upstream = http.createServer((req, res) => {
      received = req.headers
      res.end('ok')
    })
    const port = await startProxyTo(upstream, {
      onProxyReq: (proxyReq) =>
        applyProxyIdentity(
          proxyReq,
          decideProxyIdentity({ authEnabled: true, resolveUser: () => 'alice@example.com' })
        ),
    })

    await get(port, '/api/factory/workflows', { 'X-External-User-Id': 'spoofed' })

    expect(received['x-external-user-id']).toBe('alice@example.com')
  })

  it('removes any client-sent x-external-user-id when no identity can be resolved in auth mode', async () => {
    let received: http.IncomingHttpHeaders = {}
    const upstream = http.createServer((req, res) => {
      received = req.headers
      res.end('ok')
    })
    const port = await startProxyTo(upstream, {
      onProxyReq: (proxyReq) =>
        applyProxyIdentity(
          proxyReq,
          decideProxyIdentity({
            authEnabled: true,
            resolveUser: () => {
              throw new Error('no identity')
            },
          })
        ),
    })

    await get(port, '/api/factory/workflows', { 'X-External-User-Id': 'spoofed' })

    expect(received['x-external-user-id']).toBeUndefined()
  })

  it('closes the client connection when the upstream drops the stream mid-body', async () => {
    let release: () => void = () => undefined
    const released = new Promise<void>((resolve) => (release = resolve))
    const upstream = http.createServer((_req, res) => {
      res.writeHead(200, { 'Content-Type': 'text/event-stream' })
      res.write(FRAME)
      res.write(FRAME)
      void released.then(() => res.socket?.destroy())
    })
    const port = await startProxyTo(upstream)

    const outcome = await new Promise<{ ended: boolean; closed: boolean }>((resolve) => {
      const result = { ended: false, closed: false }
      const timer = setTimeout(() => resolve(result), 2000)
      let body = ''
      http
        .get({ port, path: '/api/factory/api/cases/c-1/events' }, (res) => {
          res.on('data', (data: Buffer) => {
            body += data.toString()
            if (body.split('data:').length - 1 === 2) release()
          })
          res.on('end', () => (result.ended = true))
          res.on('error', () => undefined)
          res.on('close', () => {
            result.closed = true
            clearTimeout(timer)
            resolve(result)
          })
        })
        .on('error', () => undefined)
    })

    expect(outcome).toEqual({ ended: false, closed: true })
  })
})
