/**
 * Factory Cockpit — projection view convergence unit tests (vanilla, Node
 * built-in test runner, zero dependencies, zero build step).
 *
 *   node --test factory/dashboard/js/views/projection.test.mjs
 *
 * Covers the three convergence guards added to the projection view:
 *   - out-of-order REST responses are discarded (request sequence);
 *   - SSE reconnection forces an authoritative REST re-read;
 *   - returning the tab to the foreground forces the same re-read, and the
 *     listener is removed on teardown.
 */

import test from 'node:test'
import assert from 'node:assert/strict'

import { ProjectionController, mountProjectionView } from './projection.mjs'

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

function flush() {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

/** Minimal DOM-ish container exposing only what mountProjectionView touches. */
function fakeContainer() {
  const listeners = new Map()
  return {
    innerHTML: '',
    addEventListener(type, handler) {
      listeners.set(type, handler)
    },
    removeEventListener(type) {
      listeners.delete(type)
    },
    fire(type, event) {
      listeners.get(type)?.(event)
    },
  }
}

/** Minimal document double with a drivable `visibilitychange` event. */
function fakeDocument(visibilityState = 'hidden') {
  const listeners = new Map()
  return {
    visibilityState,
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
    listenerCount(type) {
      return (listeners.get(type) ?? new Set()).size
    },
  }
}

test('load() ignores a stale response that resolves after a newer one', async () => {
  const pending = []
  const api = {
    get() {
      const entry = deferred()
      pending.push(entry)
      return entry.promise
    },
  }
  const controller = new ProjectionController({ api })

  const first = controller.load('active')
  const second = controller.load('active')

  // The newer request resolves first and wins.
  pending[1].resolve({ items: [{ workflowId: 'fresh' }] })
  await second
  // The slower, older request resolves later and must be discarded.
  pending[0].resolve({ items: [{ workflowId: 'stale' }] })
  await first

  assert.deepEqual(
    controller.workflows.map((workflow) => workflow.workflowId),
    ['fresh'],
  )
})

test('a stale error from an older request never overwrites a fresh state', async () => {
  const pending = []
  const errors = []
  const api = {
    get() {
      const entry = deferred()
      pending.push(entry)
      return entry.promise
    },
  }
  const controller = new ProjectionController({ api, onError: (message) => errors.push(message) })

  const first = controller.load('active')
  const second = controller.load('active')
  pending[1].resolve({ items: [{ workflowId: 'fresh' }] })
  await second
  pending[0].reject(new Error('stale failure'))
  await first

  assert.deepEqual(
    controller.workflows.map((workflow) => workflow.workflowId),
    ['fresh'],
  )
  assert.deepEqual(errors, [])
})

test('an SSE reconnect triggers an authoritative REST reload', async () => {
  const handlers = new Map()
  const sse = {
    on(event, handler) {
      if (!handlers.has(event)) handlers.set(event, new Set())
      handlers.get(event).add(handler)
      return () => handlers.get(event)?.delete(handler)
    },
  }
  let gets = 0
  const api = {
    get: () => {
      gets++
      return Promise.resolve({ items: [] })
    },
  }
  const controller = new ProjectionController({ api, sse })
  await controller.init()
  const afterInit = gets

  for (const handler of handlers.get('reconnect') ?? []) handler({})
  await flush()

  assert.ok(gets > afterInit, 'expected the reconnect to re-read the authoritative list')
  controller.teardown()
})

test('returning to the foreground triggers a reload and teardown removes the listener', async () => {
  const container = fakeContainer()
  const doc = fakeDocument('hidden')
  let gets = 0
  const api = {
    get: () => {
      gets++
      return Promise.resolve({ items: [] })
    },
  }

  const view = mountProjectionView(container, { api, document: doc })
  await view.ready
  const afterInit = gets

  doc.visibilityState = 'visible'
  doc.dispatch('visibilitychange')
  assert.ok(gets > afterInit, 'expected visibilitychange to re-read the authoritative list')

  // A hidden transition must NOT trigger a reload.
  const afterVisible = gets
  doc.visibilityState = 'hidden'
  doc.dispatch('visibilitychange')
  assert.equal(gets, afterVisible)

  view.teardown()
  assert.equal(doc.listenerCount('visibilitychange'), 0)
})
