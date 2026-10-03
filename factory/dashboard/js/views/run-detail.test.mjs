/**
 * Factory Cockpit — run-detail human checkpoint unit tests (vanilla, Node
 * built-in test runner, zero dependencies, zero build step).
 *
 *   node --test factory/dashboard/js/views/run-detail.test.mjs
 *
 * The mounted view is exercised against a minimal fake container (only the DOM
 * surface run-detail actually touches: `innerHTML`, the delegated click
 * listener and `querySelector('#checkpoint-comment')`). The fake ApiClient
 * records every call, so the tests pin the exact endpoint, body and headers of
 * the checkpoint reply — including the authoritative `expectedRevision`.
 */

import test from 'node:test'
import assert from 'node:assert/strict'

import { mount, buildReplyBody } from './run-detail.mjs'

/** Flush the pending microtask queue (async render chains). */
function flush() {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

/**
 * Minimal DOM-ish container plus a `fire(type, event)` helper to trigger the
 * delegated listeners registered through `addEventListener`.
 */
function fakeContainer() {
  const listeners = new Map()
  const fields = new Map()
  return {
    innerHTML: '',
    addEventListener(type, handler) {
      listeners.set(type, handler)
    },
    removeEventListener(type) {
      listeners.delete(type)
    },
    querySelector(selector) {
      return fields.get(selector) ?? null
    },
    setField(selector, value) {
      fields.set(selector, value)
    },
    fire(type, event) {
      const handler = listeners.get(type)
      if (handler) handler(event)
    },
  }
}

function humanGateStep(overrides = {}) {
  return {
    id: 'gate',
    name: 'Approval gate',
    status: 'waiting_human',
    lane: 'human',
    responsibility: { kind: 'human', name: 'engineer' },
    startedAt: '2026-01-01T00:00:00.000Z',
    ...overrides,
  }
}

function agentQuestionStep(overrides = {}) {
  return {
    id: 'agent',
    name: 'Agent work',
    status: 'running',
    lane: 'agent',
    startedAt: '2026-01-01T00:00:00.000Z',
    waitingQuestion: {
      questionRef: 'q-1',
      text: 'Votre choix ?',
      type: 'FREE_TEXT',
      options: [],
    },
    ...overrides,
  }
}

function workflowPayload(step = humanGateStep()) {
  return {
    state: 'existing',
    revision: 4,
    projection: {
      status: 'waiting_human',
      workflowType: 'feature-session',
      title: 'Run 1',
      startedAt: '2026-01-01T00:00:00.000Z',
      steps: [step],
    },
  }
}

function waitingInteraction(overrides = {}) {
  return {
    interactionId: 'i-1',
    workflowId: 'wf-1',
    stepId: 'gate',
    interactionType: 'human',
    status: 'waiting',
    revision: 3,
    prompt: 'Please review the change.',
    actions: [
      { id: 'approve', label: 'Approve' },
      { id: 'reject', label: 'Reject' },
    ],
    ...overrides,
  }
}

/** Build an ApiClient fake whose interactions list is pluggable. */
function fakeApiClient({ interactions = [waitingInteraction()], replyError = null, step = humanGateStep(), agentAnswerPost = null } = {}) {
  const calls = { get: [], post: [] }
  return {
    calls,
    get(path) {
      calls.get.push(path)
      if (path.startsWith('/api/factory/workflows/wf-1?') || path === '/api/factory/workflows/wf-1') {
        return Promise.resolve(workflowPayload(step))
      }
      if (path.includes('/interactions')) return Promise.resolve({ items: interactions })
      if (path.includes('/timing')) return Promise.resolve({ timing: null })
      if (path.includes('/evidence')) return Promise.resolve({ items: [] })
      if (path.includes('/metrics')) return Promise.resolve({})
      return Promise.resolve(null)
    },
    post(path, body, options) {
      calls.post.push({ path, body, options })
      if (replyError && path.includes('/reply')) return Promise.reject(replyError)
      if (path.includes('/agent-questions/') && agentAnswerPost) return agentAnswerPost(path, body, options)
      return Promise.resolve({ ok: true })
    },
  }
}

function agentAnswerClick() {
  return {
    target: {
      closest(selector) {
        if (selector === '[data-agent-answer-submit]') return { dataset: {} }
        return null
      },
    },
    preventDefault() {},
  }
}

function checkpointClick(action) {
  return {
    target: {
      closest(selector) {
        if (selector === '[data-checkpoint-action]') return { dataset: { checkpointAction: action } }
        return null
      },
    },
    preventDefault() {},
  }
}

function panelClick({ close = false } = {}) {
  return {
    target: {
      closest(selector) {
        if (selector === '[data-phase-panel-close]') return close ? { dataset: {} } : null
        if (selector === '[data-phase-panel]') return { dataset: { stepId: 'gate' } }
        if (selector === '[data-step-id]') return { dataset: { stepId: 'gate' } }
        return null
      },
    },
    preventDefault() {},
  }
}

async function mountWith(container, apiClient) {
  const handle = await mount(container, {
    workflowId: 'wf-1',
    namespaceId: 'ns-1',
    apiClient,
  })
  return handle
}

// ------------------------------------------------------------- payload builder

test('buildReplyBody emits only the strict contract fields', () => {
  assert.deepEqual(buildReplyBody(waitingInteraction(), 'approve', ''), {
    expectedRevision: 3,
    actionId: 'approve',
  })
  assert.deepEqual(buildReplyBody(waitingInteraction(), 'reject', '  nope  '), {
    expectedRevision: 3,
    actionId: 'reject',
    text: 'nope',
  })
  assert.equal(buildReplyBody(waitingInteraction(), 'approve', 'x'.repeat(2500)).text.length, 2000)
})

// ---------------------------------------------------------------- rendering

test('selecting a human waiting step shows the decision buttons', async () => {
  const container = fakeContainer()
  const apiClient = fakeApiClient()
  const handle = await mountWith(container, apiClient)

  handle.selectStep('gate')
  await flush()

  assert.ok(container.innerHTML.includes('data-checkpoint-action="approve"'), 'expected approve button')
  assert.ok(container.innerHTML.includes('data-checkpoint-action="reject"'), 'expected reject button')
  assert.ok(container.innerHTML.includes('id="checkpoint-comment"'), 'expected comment field')
  handle.unmount()
})

test('internal phase panel clicks keep the selected panel open', async () => {
  const container = fakeContainer()
  const handle = await mountWith(container, fakeApiClient())

  handle.selectStep('gate')
  await flush()
  container.fire('click', panelClick())

  assert.equal(handle.getState().selectedStepId, 'gate')
  assert.ok(container.innerHTML.includes('id="checkpoint-comment"'), 'expected comment field to remain mounted')
  handle.unmount()
})

test('the explicit phase panel close button closes the selected panel', async () => {
  const container = fakeContainer()
  const handle = await mountWith(container, fakeApiClient())

  handle.selectStep('gate')
  await flush()
  assert.ok(container.innerHTML.includes('data-phase-panel-close="true"'), 'expected explicit close button')

  container.fire('click', panelClick({ close: true }))

  assert.equal(handle.getState().selectedStepId, null)
  assert.ok(!container.innerHTML.includes('id="checkpoint-comment"'), 'expected comment field to close')
  handle.unmount()
})

test('a human step without a waiting interaction shows the state but no buttons', async () => {
  const container = fakeContainer()
  const apiClient = fakeApiClient({ interactions: [] })
  const handle = await mountWith(container, apiClient)

  handle.selectStep('gate')
  await flush()

  assert.ok(!container.innerHTML.includes('data-checkpoint-action="approve"'), 'expected no approve button')
  assert.ok(!container.innerHTML.includes('data-checkpoint-action="reject"'), 'expected no reject button')
  handle.unmount()
})

// ------------------------------------------------------------------ replies

test('clicking Approuver replies with actionId approve and the interaction revision', async () => {
  const container = fakeContainer()
  const apiClient = fakeApiClient()
  const handle = await mountWith(container, apiClient)

  handle.selectStep('gate')
  await flush()
  container.setField('#checkpoint-comment', { value: '  LGTM  ' })

  container.fire('click', checkpointClick('approve'))
  await flush()

  const reply = apiClient.calls.post.find((call) => call.path.includes('/reply'))
  assert.ok(reply, 'expected a reply POST')
  assert.equal(reply.path, '/api/factory/workflows/wf-1/interactions/i-1/reply')
  assert.deepEqual(reply.body, { expectedRevision: 3, actionId: 'approve', text: 'LGTM' })
  assert.equal(reply.options.namespaceId, 'ns-1')
  assert.equal(reply.options.actorId, 'local-dev-user')

  const resume = apiClient.calls.post.find((call) => call.path.endsWith('/continue'))
  assert.ok(resume, 'expected a continue POST to resume the DAG')
  assert.equal(resume.path, '/api/factory/workflows/wf-1/continue')
  assert.deepEqual(resume.body, { namespaceId: 'ns-1' })
  handle.unmount()
})

test('clicking Rejeter replies with actionId reject', async () => {
  const container = fakeContainer()
  const apiClient = fakeApiClient()
  const handle = await mountWith(container, apiClient)

  handle.selectStep('gate')
  await flush()

  container.fire('click', checkpointClick('reject'))
  await flush()

  const reply = apiClient.calls.post.find((call) => call.path.includes('/reply'))
  assert.ok(reply, 'expected a reply POST')
  assert.deepEqual(reply.body, { expectedRevision: 3, actionId: 'reject' })
  handle.unmount()
})

test('a stale revision conflict reloads the interaction and keeps the buttons', async () => {
  const container = fakeContainer()
  const conflict = Object.assign(new Error('stale'), { code: 'REVISION_CONFLICT', status: 409 })
  const apiClient = fakeApiClient({ replyError: conflict })
  const handle = await mountWith(container, apiClient)

  handle.selectStep('gate')
  await flush()
  const interactionsFetchesAfterSelect = apiClient.calls.get.filter((path) => path.includes('/interactions')).length

  container.fire('click', checkpointClick('approve'))
  await flush()

  const interactionsFetches = apiClient.calls.get.filter((path) => path.includes('/interactions')).length
  assert.ok(interactionsFetches > interactionsFetchesAfterSelect, 'expected a reload of the interactions')
  assert.ok(container.innerHTML.includes('Conflit de révision'), 'expected the conflict feedback')
  assert.ok(container.innerHTML.includes('data-checkpoint-action="approve"'), 'expected the buttons back')
  handle.unmount()
})

// --------------------------------------------------------- AgentOS answers

test('accepted AgentOS answer hides controls, survives rerender, and blocks duplicate clicks', async () => {
  const container = fakeContainer()
  const accepted = deferred()
  const apiClient = fakeApiClient({ step: agentQuestionStep(), agentAnswerPost: () => accepted.promise })
  const handle = await mountWith(container, apiClient)
  handle.selectStep('agent')
  await flush()
  container.setField('#agent-answer-text', { value: 'draft answer', focus() {} })

  container.fire('click', agentAnswerClick())
  container.fire('click', agentAnswerClick())
  assert.equal(apiClient.calls.post.filter((call) => call.path.includes('/agent-questions/')).length, 1)
  assert.ok(container.innerHTML.includes('data-agent-answer-submit="true" disabled'))

  accepted.resolve({ status: 202 })
  await flush()
  assert.ok(container.innerHTML.includes('confirmation AgentOS en attente'))
  assert.ok(!container.innerHTML.includes('data-agent-answer-submit'))
  assert.ok(!container.innerHTML.includes('id="agent-answer-text"'))

  await handle.refresh()
  assert.ok(container.innerHTML.includes('confirmation AgentOS en attente'))
  container.fire('click', agentAnswerClick())
  await flush()
  assert.equal(apiClient.calls.post.filter((call) => call.path.includes('/agent-questions/')).length, 1)
  handle.unmount()
})

test('failed AgentOS answer restores controls, draft, focus intent, and error', async () => {
  const container = fakeContainer()
  const error = new Error('network down')
  const apiClient = fakeApiClient({ step: agentQuestionStep(), agentAnswerPost: () => Promise.reject(error) })
  const handle = await mountWith(container, apiClient)
  handle.selectStep('agent')
  await flush()
  const field = { value: 'kept draft', focus() {} }
  container.setField('#agent-answer-text', field)

  container.fire('click', agentAnswerClick())
  await flush()

  assert.ok(container.innerHTML.includes('Échec de l’envoi'))
  assert.ok(container.innerHTML.includes('kept draft'))
  assert.ok(container.innerHTML.includes('data-agent-answer-submit="true"'))
  assert.ok(!container.innerHTML.includes('data-agent-answer-submit="true" disabled'))
  assert.equal(handle.getState().agentAnswerDrafts.get('agent'), 'kept draft')
  handle.unmount()
})

// ------------------------------------------------------- convergence guards

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

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

const BASE_PATH = '/api/factory/workflows/wf-1?namespaceId=ns-1'

function detailPayload(revision, status) {
  return {
    state: 'existing',
    revision,
    projection: { status, workflowType: 'feature-session', title: `Run ${revision}`, steps: [] },
  }
}

/** ApiClient whose base `GET` resolves from a FIFO queue controlled by tests. */
function sequencedApiClient() {
  const pending = []
  const calls = { get: [] }
  return {
    pending,
    calls,
    get(path) {
      calls.get.push(path)
      if (path === BASE_PATH) {
        const entry = deferred()
        pending.push(entry)
        return entry.promise
      }
      if (path.includes('/timing')) return Promise.resolve({ timing: null })
      if (path.includes('/evidence')) return Promise.resolve({ items: [] })
      if (path.includes('/metrics')) return Promise.resolve({})
      if (path.includes('/interactions')) return Promise.resolve({ items: [] })
      return Promise.resolve(null)
    },
    post: () => Promise.resolve({ ok: true }),
  }
}

function baseFetches(apiClient) {
  return apiClient.calls.get.filter((path) => path === BASE_PATH).length
}

test('a slow loadAll is discarded in favour of the most recent one', async () => {
  const container = fakeContainer()
  const apiClient = sequencedApiClient()

  const mounting = mount(container, { workflowId: 'wf-1', namespaceId: 'ns-1', apiClient })
  await flush()
  apiClient.pending[0].resolve(detailPayload(1, 'running'))
  const handle = await mounting
  assert.equal(handle.getState().workflow.revision, 1)

  const first = handle.refresh()
  const second = handle.refresh()
  await flush()

  // The newest refresh is the 3rd base request; it resolves first with rev 9.
  apiClient.pending[2].resolve(detailPayload(9, 'completed'))
  await second
  // The older refresh resolves afterwards with stale rev 2 and must be dropped.
  apiClient.pending[1].resolve(detailPayload(2, 'running'))
  await first

  assert.equal(handle.getState().workflow.revision, 9)
  assert.equal(handle.getState().workflow.projection.status, 'completed')
  handle.unmount()
})

test('an SSE reconnect triggers an authoritative loadAll', async () => {
  const container = fakeContainer()
  const apiClient = fakeApiClient()
  const handlers = new Map()
  const sseClient = {
    on(event, handler) {
      if (!handlers.has(event)) handlers.set(event, new Set())
      handlers.get(event).add(handler)
      return () => handlers.get(event)?.delete(handler)
    },
  }
  const handle = await mount(container, { workflowId: 'wf-1', namespaceId: 'ns-1', apiClient, sseClient })
  const before = apiClient.calls.get.filter((path) => path.startsWith('/api/factory/workflows/wf-1?')).length

  for (const handler of handlers.get('reconnect') ?? []) handler({ workflowId: 'wf-1' })
  await flush()

  const after = apiClient.calls.get.filter((path) => path.startsWith('/api/factory/workflows/wf-1?')).length
  assert.ok(after > before, 'expected the reconnect to re-read the authoritative projection')
  handle.unmount()
})

test('returning to the foreground triggers a loadAll and unmount removes the listener', async () => {
  const container = fakeContainer()
  const doc = fakeDocument('hidden')
  const apiClient = fakeApiClient()
  const handle = await mount(container, {
    workflowId: 'wf-1',
    namespaceId: 'ns-1',
    apiClient,
    document: doc,
  })
  const before = apiClient.calls.get.filter((path) => path.startsWith('/api/factory/workflows/wf-1?')).length

  doc.visibilityState = 'visible'
  doc.dispatch('visibilitychange')
  await flush()

  const after = apiClient.calls.get.filter((path) => path.startsWith('/api/factory/workflows/wf-1?')).length
  assert.ok(after > before, 'expected visibilitychange to re-read the authoritative projection')

  handle.unmount()
  assert.equal(doc.listenerCount('visibilitychange'), 0)
})
