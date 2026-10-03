/**
 * Factory Cockpit — router convergence unit tests (vanilla, Node built-in test
 * runner, zero dependencies, zero build step).
 *
 *   node --test factory/dashboard/js/app.test.mjs
 *
 * Pins the two router fixes:
 *   - the route IDENTITY includes the query string, so navigating between
 *     `#/detail?workflowId=A` and `#/detail?workflowId=B` tears down A and
 *     mounts B instead of silently no-op'ing on the shared `/detail` path;
 *   - the shared SSE stream is (re)scoped to the ACTIVE namespace rather than
 *     frozen at bootstrap.
 */

import test from 'node:test'
import assert from 'node:assert/strict'

import { createRouter, buildRouteIdentity, workflowStreamUrl, resolveWorkflowId } from './app.mjs'

/** Minimal window double with a mutable hash and drivable `hashchange`. */
function fakeWindow(hash) {
  const listeners = new Map()
  const win = {
    location: { hash, search: '' },
    addEventListener(type, handler) {
      if (!listeners.has(type)) listeners.set(type, new Set())
      listeners.get(type).add(handler)
    },
    removeEventListener(type, handler) {
      listeners.get(type)?.delete(handler)
    },
    dispatch(type) {
      for (const handler of [...(listeners.get(type) ?? [])]) handler({ type })
    },
    setHash(next) {
      win.location.hash = next
      win.dispatch('hashchange')
    },
  }
  win.history = {
    replaceState: (_state, _title, url) => {
      win.location.hash = url
    },
  }
  return win
}

/** Minimal document double: the router only scans for view/nav containers. */
function fakeDocument() {
  return {
    getElementById: () => null,
    querySelectorAll: () => [],
  }
}

class FakeSseClient {
  constructor(url) {
    this.url = url
    this.connected = false
    this.closed = false
    FakeSseClient.created.push(url)
  }

  connect() {
    this.connected = true
    return this
  }

  close() {
    this.closed = true
    return this
  }

  on() {
    return () => {}
  }
}
FakeSseClient.created = []

test('buildRouteIdentity carries the full query string', () => {
  assert.equal(buildRouteIdentity('/runs', fakeWindow('#/runs')), '/runs')
  assert.equal(buildRouteIdentity('/detail', fakeWindow('#/detail?workflowId=A')), '/detail?workflowId=A')
  assert.equal(buildRouteIdentity('/detail', fakeWindow('#/detail?workflowId=B&ns=1')), '/detail?workflowId=B&ns=1')
})

test('navigating between two /detail details tears down the first and mounts the second', () => {
  const win = fakeWindow('#/detail?workflowId=A')
  const doc = fakeDocument()
  const mounted = []
  const tornDown = []

  const router = createRouter(win, doc, {
    onMountOwnedRoutes: ['/detail'],
    onMount: (_route, ctx) => {
      const id = resolveWorkflowId(win)
      mounted.push(id)
      ctx.registerTeardown(() => tornDown.push(id))
    },
  })

  router.start()
  assert.deepEqual(mounted, ['A'])
  assert.deepEqual(tornDown, [])

  win.setHash('#/detail?workflowId=B')

  assert.deepEqual(tornDown, ['A'], 'the previous detail must be unmounted')
  assert.deepEqual(mounted, ['A', 'B'], 'the new detail must mount with its own arguments')
  assert.equal(router.getCurrentRouteIdentity(), '/detail?workflowId=B')
})

test('re-applying the same identity is a no-op (no remount)', () => {
  const win = fakeWindow('#/detail?workflowId=A')
  const doc = fakeDocument()
  let mounts = 0
  const router = createRouter(win, doc, {
    onMountOwnedRoutes: ['/detail'],
    onMount: (_route, ctx) => {
      mounts++
      ctx.registerTeardown(() => {})
    },
  })

  router.applyHash()
  router.applyHash()

  assert.equal(mounts, 1)
})

test('the shared SSE stream follows the active namespace', () => {
  FakeSseClient.created = []
  let namespaceId = 'ns-a'
  const win = fakeWindow('#/runs')
  const doc = fakeDocument()

  const router = createRouter(win, doc, {
    SseClient: FakeSseClient,
    resolveNamespace: () => namespaceId,
  })

  router.start()
  assert.equal(FakeSseClient.created.length, 1)
  assert.match(FakeSseClient.created[0], /namespaceId=ns-a/)

  namespaceId = 'ns-b'
  win.setHash('#/runs?ns=ns-b')

  assert.equal(FakeSseClient.created.length, 2, 'a namespace change must recreate the stream')
  assert.match(FakeSseClient.created[1], /namespaceId=ns-b/)
  assert.ok(!FakeSseClient.created[0].includes('ns-b'))
})

test('getSseClient reuses the client for an unchanged namespace', () => {
  FakeSseClient.created = []
  const win = fakeWindow('#/runs')
  const doc = fakeDocument()
  const router = createRouter(win, doc, { SseClient: FakeSseClient, resolveNamespace: () => 'ns-a' })

  router.applyHash()
  const first = router.getSseClient('ns-a')
  const second = router.getSseClient('ns-a')
  assert.equal(first, second)
  assert.equal(FakeSseClient.created.length, 1)
})

test('workflowStreamUrl omits the namespace parameter when absent', () => {
  assert.equal(workflowStreamUrl(null), '/api/factory/workflows/stream')
  assert.equal(workflowStreamUrl('ns a'), '/api/factory/workflows/stream?namespaceId=ns%20a')
})
