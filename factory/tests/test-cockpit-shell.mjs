/**
 * Factory Cockpit shell — offline test suite (Wave 1 / Milestone D).
 *
 * Exercises the pure-Vanilla-ESM cockpit shell without a browser:
 *
 *   A. `api-client.mjs`     — unwrapping, raw payloads, error normalization,
 *                             correlation id + attribution header propagation.
 *   B. `sse-client.mjs`     — event bus, named-event dispatch, idempotent
 *                             teardown and zero-leak guarantees.
 *   C. Server routes        — `/cockpit`, `/css/*`, `/js/*` Content-Type
 *                             enforcement, 404 (never HTML) and the `/` →
 *                             `/cockpit` 302 redirect for the retired
 *                             monolithic `index.html`.
 *
 * No external dependency, no network beyond loopback. Exit code 0 = all pass.
 *
 * Usage: node factory/tests/test-cockpit-shell.mjs
 */

import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

import { ApiClient, ApiClientError } from '../dashboard/js/services/api-client.mjs'
import { SseClient, WORKFLOW_PROJECTION_EVENTS } from '../dashboard/js/services/sse-client.mjs'
import { createCompositionRoot } from '../dashboard/composition-root.mjs'

const __dirname = dirname(fileURLToPath(import.meta.url))

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

let passed = 0
let failed = 0

async function scenario(name, fn) {
  try {
    await fn()
    passed++
    console.log(`✓ ${name}`)
  } catch (error) {
    failed++
    console.log(`✗ ${name}`)
    console.log(`  ${error?.stack ?? error}`)
  }
}

/** Local REST stub used by the ApiClient scenarios. */
function startApiServer() {
  return new Promise((resolveServer) => {
    const server = createServer((req, res) => {
      const path = new URL(req.url, 'http://localhost').pathname
      const sendJson = (status, body) => {
        res.writeHead(status, { 'Content-Type': 'application/json' })
        res.end(typeof body === 'string' ? body : JSON.stringify(body))
      }

      if (path === '/unwrap') return sendJson(200, { data: { items: [1, 2] } })
      if (path === '/raw') return sendJson(200, { raw: true })
      if (path === '/raw-array') return sendJson(200, [1, 2, 3])
      if (path === '/raw-primitive') return sendJson(200, 'just text')
      if (path === '/error-a') return sendJson(400, { error: { code: 'INVALID', message: 'bad input' } })
      if (path === '/error-b') return sendJson(500, { error: 'Something broke' })
      if (path === '/error-plain') {
        res.writeHead(500, { 'Content-Type': 'text/plain' })
        return res.end('plain boom')
      }
      const statusMatch = path.match(/^\/status\/(\d+)$/)
      if (statusMatch) {
        const code = Number(statusMatch[1])
        return sendJson(code, { error: `status ${code}` })
      }
      if (path === '/echo') {
        const chunks = []
        req.on('data', (chunk) => chunks.push(chunk))
        req.on('end', () => {
          let body = null
          const raw = Buffer.concat(chunks).toString('utf8')
          try {
            body = raw ? JSON.parse(raw) : null
          } catch {
            body = raw
          }
          sendJson(200, { data: { headers: req.headers, body } })
        })
        return
      }
      sendJson(404, { error: 'not found' })
    })
    server.listen(0, '127.0.0.1', () => resolveServer(server))
  })
}

const closeServer = (server) => new Promise((resolveClose) => server.close(resolveClose))

/** Minimal EventSource double that records listener wiring. */
class MockEventSource {
  static instances = []

  constructor(url) {
    this.url = url
    this.listeners = new Map()
    this.closed = false
    this.closeCount = 0
    MockEventSource.instances.push(this)
  }

  addEventListener(type, handler) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set())
    this.listeners.get(type).add(handler)
  }

  removeEventListener(type, handler) {
    const handlers = this.listeners.get(type)
    if (!handlers) return
    handlers.delete(handler)
    if (handlers.size === 0) this.listeners.delete(type)
  }

  close() {
    this.closed = true
    this.closeCount++
  }

  dispatch(type, data) {
    const evt = { type, data }
    for (const handler of [...(this.listeners.get(type) ?? [])]) handler(evt)
  }

  listenerCount() {
    let total = 0
    for (const handlers of this.listeners.values()) total += handlers.size
    return total
  }
}

// ---------------------------------------------------------------------------
// A. ApiClient
// ---------------------------------------------------------------------------

const apiServer = await startApiServer()
const { port: apiPort } = apiServer.address()
const client = new ApiClient({ baseUrl: `http://127.0.0.1:${apiPort}` })

console.log('\nApiClient')

await scenario('unwraps `{ data }` envelopes', async () => {
  assert.deepEqual(await client.get('/unwrap'), { items: [1, 2] })
})

await scenario('passes raw object payloads through untouched', async () => {
  assert.deepEqual(await client.get('/raw'), { raw: true })
})

await scenario('passes raw array payloads through untouched', async () => {
  assert.deepEqual(await client.get('/raw-array'), [1, 2, 3])
})

await scenario('parses non-JSON text payloads', async () => {
  assert.equal(await client.get('/raw-primitive'), 'just text')
})

await scenario('normalizes error format A (structured error)', async () => {
  await assert.rejects(
    () => client.get('/error-a'),
    (error) => {
      assert.ok(error instanceof ApiClientError)
      assert.equal(error.code, 'INVALID')
      assert.equal(error.message, 'bad input')
      assert.equal(error.status, 400)
      return true
    },
  )
})

await scenario('normalizes error format B (string error)', async () => {
  await assert.rejects(
    () => client.get('/error-b'),
    (error) => {
      assert.ok(error instanceof ApiClientError)
      assert.equal(error.message, 'Something broke')
      assert.equal(error.code, 'HTTP_500')
      assert.equal(error.status, 500)
      return true
    },
  )
})

await scenario('normalizes non-JSON error bodies', async () => {
  await assert.rejects(
    () => client.get('/error-plain'),
    (error) => {
      assert.equal(error.code, 'HTTP_500')
      assert.equal(error.status, 500)
      return true
    },
  )
})

await scenario('reports the HTTP status on 4xx/5xx', async () => {
  for (const status of [401, 403, 404, 500]) {
    await assert.rejects(
      () => client.get(`/status/${status}`),
      (error) => {
        assert.equal(error.status, status)
        assert.equal(error.code, `HTTP_${status}`)
        return true
      },
    )
  }
})

await scenario('propagates a generated X-Correlation-Id', async () => {
  const echo = await client.get('/echo')
  assert.equal(typeof echo.headers['x-correlation-id'], 'string')
  assert.ok(echo.headers['x-correlation-id'].length > 0)
})

await scenario('propagates a caller-supplied X-Correlation-Id', async () => {
  const echo = await client.get('/echo', { headers: { 'X-Correlation-Id': 'corr-test-123' } })
  assert.equal(echo.headers['x-correlation-id'], 'corr-test-123')
})

await scenario('injects X-Factory-* attribution headers (never authorization)', async () => {
  const echo = await client.get('/echo', {
    attribution: { namespaceId: 'ns-1', caseId: 'case-1', actorId: 'actor-1' },
  })
  assert.equal(echo.headers['x-factory-namespace-id'], 'ns-1')
  assert.equal(echo.headers['x-factory-case-id'], 'case-1')
  assert.equal(echo.headers['x-factory-actor-id'], 'actor-1')
})

await scenario('serializes a JSON body and sets Content-Type', async () => {
  const echo = await client.post('/echo', { hello: 'world' })
  assert.deepEqual(echo.body, { hello: 'world' })
  assert.equal(echo.headers['content-type'], 'application/json')
})

await closeServer(apiServer)

// ---------------------------------------------------------------------------
// B. SseClient
// ---------------------------------------------------------------------------

console.log('\nSseClient')

await scenario('event bus on / emit / off', () => {
  const sse = new SseClient('/events', { EventSource: MockEventSource })
  const seen = []
  const handler = (data) => seen.push(data)
  sse.on('custom', handler)
  sse.emit('custom', { n: 1 })
  sse.off('custom', handler)
  sse.emit('custom', { n: 2 })
  assert.deepEqual(seen, [{ n: 1 }])
  sse.close()
})

await scenario('dispatches named projection events as parsed JSON', () => {
  const sse = new SseClient('/events', { EventSource: MockEventSource })
  const received = new Map()
  for (const event of WORKFLOW_PROJECTION_EVENTS) {
    sse.on(event, (data) => received.set(event, data))
  }
  sse.connect()
  const mock = MockEventSource.instances.at(-1)
  for (const event of WORKFLOW_PROJECTION_EVENTS) {
    mock.dispatch(event, JSON.stringify({ event, ok: true }))
  }
  for (const event of WORKFLOW_PROJECTION_EVENTS) {
    assert.deepEqual(received.get(event), { event, ok: true })
  }
  sse.close()
})

await scenario('dispatches the default `message` event', () => {
  const sse = new SseClient('/events', { EventSource: MockEventSource })
  let payload = null
  sse.on('message', (data) => {
    payload = data
  })
  sse.connect()
  MockEventSource.instances.at(-1).dispatch('message', JSON.stringify({ hello: 'cockpit' }))
  assert.deepEqual(payload, { hello: 'cockpit' })
  sse.close()
})

await scenario('attaches a custom named event registered after connect', () => {
  const sse = new SseClient('/events', { EventSource: MockEventSource })
  sse.connect()
  const mock = MockEventSource.instances.at(-1)
  let payload = null
  sse.on('artifact-published', (data) => {
    payload = data
  })
  mock.dispatch('artifact-published', JSON.stringify({ id: 'a1' }))
  assert.deepEqual(payload, { id: 'a1' })
  sse.close()
})

await scenario('close() is idempotent and detaches every listener', () => {
  const sse = new SseClient('/events', { EventSource: MockEventSource })
  sse.on('message', () => {})
  sse.connect()
  const mock = MockEventSource.instances.at(-1)
  assert.ok(mock.listenerCount() > 0)
  sse.close()
  sse.close()
  sse.close()
  assert.equal(mock.closed, true)
  assert.equal(mock.listenerCount(), 0)
  assert.equal(sse.listeners.size, 0)
  assert.equal(sse.eventSource, null)
  assert.equal(sse.reconnectTimer, null)
})

await scenario('after close() no handler receives a dispatch (zero leak)', () => {
  const sse = new SseClient('/events', { EventSource: MockEventSource })
  let calls = 0
  sse.on('workflow-projection-updated', () => {
    calls++
  })
  sse.connect()
  const mock = MockEventSource.instances.at(-1)
  sse.close()
  mock.dispatch('workflow-projection-updated', JSON.stringify({ x: 1 }))
  assert.equal(calls, 0)
})

await scenario('reconnects once on error without leaking sockets', () => {
  const sse = new SseClient('/events', { EventSource: MockEventSource, reconnectDelayMs: 5 })
  sse.connect()
  const first = MockEventSource.instances.at(-1)
  first.dispatch('error')
  assert.equal(first.closed, true)
  assert.ok(sse.reconnectTimer)
  sse.close()
  assert.equal(sse.reconnectTimer, null)
})

// ---------------------------------------------------------------------------
// C. Server routes
// ---------------------------------------------------------------------------

console.log('\nServer routes')

const dataRoot = await mkdtemp(join(tmpdir(), 'factory-cockpit-test-'))
let cockpitServer = null

await scenario('composition root serves the cockpit and static assets', async () => {
  const { server } = createCompositionRoot({
    FACTORY_BIND_HOST: '127.0.0.1',
    PORT: '0',
    FACTORY_DATA_ROOT: dataRoot,
    FACTORY_ALLOW_LOOPBACK_DEV: 'true',
  })
  await new Promise((resolveListen) => server.listen(0, '127.0.0.1', resolveListen))
  cockpitServer = server
  const { port } = server.address()
  const base = `http://127.0.0.1:${port}`

  const cockpit = await fetch(`${base}/cockpit`)
  assert.equal(cockpit.status, 200)
  assert.equal(cockpit.headers.get('content-type'), 'text/html; charset=utf-8')
  const cockpitBody = await cockpit.text()
  assert.ok(cockpitBody.includes('<dialog id="cockpit-dialog">'))
  assert.ok(cockpitBody.includes('cockpit-topbar'))

  const cockpitHtml = await fetch(`${base}/cockpit.html`)
  assert.equal(cockpitHtml.status, 200)
  assert.equal(cockpitHtml.headers.get('content-type'), 'text/html; charset=utf-8')

  const css = await fetch(`${base}/css/dockyard.css`)
  assert.equal(css.status, 200)
  assert.equal(css.headers.get('content-type'), 'text/css; charset=utf-8')
  const cssBody = await css.text()
  assert.ok(cssBody.includes(':root'))
  assert.ok(cssBody.includes('--bg:#06080f'))

  for (const asset of ['/js/app.mjs', '/js/services/api-client.mjs', '/js/services/sse-client.mjs']) {
    const response = await fetch(`${base}${asset}`)
    assert.equal(response.status, 200, `${asset} should be 200`)
    assert.equal(response.headers.get('content-type'), 'application/javascript; charset=utf-8', `${asset} content-type`)
    const body = await response.text()
    assert.ok(body.length > 0, `${asset} should not be empty`)
  }

  const missingCss = await fetch(`${base}/css/nonexistent.css`)
  assert.equal(missingCss.status, 404)
  assert.equal(missingCss.headers.get('content-type'), 'application/json')
  const missingCssText = await missingCss.text()
  assert.equal(JSON.parse(missingCssText).error.code, 'NOT_FOUND')
  assert.ok(!missingCssText.includes('<html'), 'missing css must never fall back to HTML')

  const missingJs = await fetch(`${base}/js/nonexistent.mjs`)
  assert.equal(missingJs.status, 404)
  assert.equal(missingJs.headers.get('content-type'), 'application/json')
  const missingJsText = await missingJs.text()
  assert.equal(JSON.parse(missingJsText).error.code, 'NOT_FOUND')

  const root = await fetch(`${base}/`, { redirect: 'manual' })
  assert.equal(root.status, 302)
  assert.equal(root.headers.get('location'), '/cockpit')

  const indexHtml = await fetch(`${base}/index.html`, { redirect: 'manual' })
  assert.equal(indexHtml.status, 302)
  assert.equal(indexHtml.headers.get('location'), '/cockpit')
})

if (cockpitServer) await closeServer(cockpitServer)
await rm(dataRoot, { recursive: true, force: true })

// ---------------------------------------------------------------------------

console.log(`\nResult: ${passed} passed, ${failed} failed`)
process.exit(failed ? 1 : 0)
