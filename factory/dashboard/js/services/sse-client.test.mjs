/**
 * Factory Cockpit — SSE client reconnection unit tests (vanilla, Node built-in
 * test runner, zero dependencies, zero build step).
 *
 *   node --test factory/dashboard/js/services/sse-client.test.mjs
 *
 * A fake EventSource lets the tests drive the connection lifecycle
 * synchronously (open → error → reconnect) and pin the contract views rely on:
 * the FIRST `open` emits only `open`, every re-open AFTER a drop also emits
 * `reconnect`.
 */

import test from 'node:test'
import assert from 'node:assert/strict'

import { SseClient } from './sse-client.mjs'

/** Minimal EventSource double: records listeners and lets tests dispatch. */
class FakeEventSource {
  constructor(url) {
    this.url = url
    this.listeners = new Map()
    this.closed = false
  }

  addEventListener(type, handler) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set())
    this.listeners.get(type).add(handler)
  }

  removeEventListener(type, handler) {
    this.listeners.get(type)?.delete(handler)
  }

  close() {
    this.closed = true
  }

  dispatch(type, evt = {}) {
    for (const handler of [...(this.listeners.get(type) ?? [])]) handler(evt)
  }
}

function flush() {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

test('the first connection emits `open` but never `reconnect`', () => {
  const client = new SseClient('/api/factory/workflows/stream', { EventSource: FakeEventSource })
  const events = []
  client.on('open', () => events.push('open'))
  client.on('reconnect', () => events.push('reconnect'))

  client.connect()
  client.eventSource.dispatch('open', {})

  assert.deepEqual(events, ['open'])
})

test('a re-open after an error emits both `reconnect` and `open`', async () => {
  const client = new SseClient('/api/factory/workflows/stream', {
    EventSource: FakeEventSource,
    reconnectDelayMs: 0,
  })
  const events = []
  client.on('open', () => events.push('open'))
  client.on('reconnect', () => events.push('reconnect'))

  client.connect()
  client.eventSource.dispatch('open', {})
  assert.deepEqual(events, ['open'], 'the initial connection is not a reconnect')

  // Drop the connection; the client schedules a single auto-reconnect.
  client.eventSource.dispatch('error', {})
  await flush()

  client.eventSource.dispatch('open', {})
  assert.deepEqual(events, ['open', 'reconnect', 'open'], 'reconnect fires before open on recovery')
})

test('a reconnect handler registered after connect still receives the event', async () => {
  const client = new SseClient('/api/factory/workflows/stream', {
    EventSource: FakeEventSource,
    reconnectDelayMs: 0,
  })
  client.connect()
  client.eventSource.dispatch('open', {})

  let reconnects = 0
  client.on('reconnect', () => {
    reconnects++
  })

  client.eventSource.dispatch('error', {})
  await flush()
  client.eventSource.dispatch('open', {})

  assert.equal(reconnects, 1)
})

test('close() clears timers and detaches the socket idempotently', async () => {
  const client = new SseClient('/api/factory/workflows/stream', {
    EventSource: FakeEventSource,
    reconnectDelayMs: 0,
  })
  client.connect()
  client.eventSource.dispatch('error', {})
  client.close()
  client.close()
  await flush()

  assert.equal(client.eventSource, null)
  assert.equal(client.reconnectTimer, null)
})
