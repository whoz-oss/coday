import * as fs from 'fs'
import http from 'node:http'
import type { AddressInfo } from 'node:net'
import * as os from 'os'
import * as path from 'path'
import { CodayOptions } from './coday-options-utils'
import { initialState, updateState } from './runtime/state'
import { createWebApp } from './web-server'

/**
 * Rules out a server-side cause of the "white page" seen when the browser is opened on `/`: Express itself serves the
 * Angular index.html on `/` and `/welcome`, and answers the wizard API, with no AgentOS anywhere.
 */
describe('createWebApp without AgentOS', () => {
  const html = '<!doctype html><title>fake client</title><app-root></app-root>'
  const saved = {
    client: process.env['CODAY_CLIENT_PATH'],
    cockpit: process.env['CODAY_FACTORY_COCKPIT_PATH'],
    agentosUrl: process.env['AGENTOS_URL'],
    factoryUrl: process.env['FACTORY_URL'],
  }
  let dir: string
  let server: http.Server
  let port: number

  beforeEach(async () => {
    dir = fs.mkdtempSync(path.join(os.tmpdir(), 'web-app-'))
    const client = path.join(dir, 'client')
    const cockpit = path.join(dir, 'cockpit')
    for (const d of [client, cockpit]) {
      fs.mkdirSync(d)
      fs.writeFileSync(path.join(d, 'index.html'), html)
    }
    process.env['CODAY_CLIENT_PATH'] = client
    process.env['CODAY_FACTORY_COCKPIT_PATH'] = cockpit
    // Hermetic: an AGENTOS_URL / FACTORY_URL of the developer's shell (e.g. a running dev stack) would make the
    // proxies reach a real service instead of the dead ports provisioned below.
    delete process.env['AGENTOS_URL']
    delete process.env['FACTORY_URL']
    // Provisioned, but nothing listens on the AgentOS / Factory ports (1 is refused at once).
    updateState(
      dir,
      { profile: 'laptop', provisioned: true, ports: { express: 3000, agentos: 1, factory: 1 } },
      initialState('nx', 'laptop')
    )
    const options: CodayOptions = { configDir: dir, auth: false, host: '127.0.0.1', noOpen: false }
    server = http.createServer(createWebApp({ codayOptions: options, setupEnabled: true }))
    port = await new Promise<number>((resolve) =>
      server.listen(0, '127.0.0.1', () => resolve((server.address() as AddressInfo).port))
    )
  })

  afterEach(async () => {
    for (const [key, value] of [
      ['CODAY_CLIENT_PATH', saved.client],
      ['CODAY_FACTORY_COCKPIT_PATH', saved.cockpit],
      ['AGENTOS_URL', saved.agentosUrl],
      ['FACTORY_URL', saved.factoryUrl],
    ] as const) {
      if (value === undefined) delete process.env[key]
      else process.env[key] = value
    }
    await new Promise<void>((resolve) => server.close(() => resolve()))
    fs.rmSync(dir, { recursive: true, force: true })
  })

  const get = (urlPath: string) =>
    new Promise<{ status: number; type: string; body: string }>((resolve, reject) => {
      http
        .get({ host: '127.0.0.1', port, path: urlPath }, (res) => {
          let body = ''
          res.setEncoding('utf8')
          res.on('data', (chunk: string) => (body += chunk))
          res.on('end', () => resolve({ status: res.statusCode ?? 0, type: String(res.headers['content-type']), body }))
        })
        .on('error', reject)
    })

  it.each(['/', '/welcome', '/home'])('serves the client index.html on %s', async (urlPath) => {
    const res = await get(urlPath)
    expect(res.status).toBe(200)
    expect(res.type).toContain('text/html')
    expect(res.body).toBe(html)
  })

  it('answers the wizard status as JSON although AgentOS is down', async () => {
    const res = await get('/api/setup/status')
    expect(res.status).toBe(200)
    expect(res.type).toContain('application/json')
    const status = JSON.parse(res.body)
    expect(status).toMatchObject({ mode: 'laptop', setupEnabled: true, setupDone: false })
    expect(status.steps.agentos.state).not.toBe('done')
  })

  it('answers the AgentOS API with an error, not a hang, while AgentOS is down', async () => {
    const res = await get('/api/agentos/api/namespaces')
    expect(res.status).toBeGreaterThanOrEqual(500)
  })
})
