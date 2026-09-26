/**
 * Factory Cockpit — Wave 2 run launch view & router wiring — offline test suite.
 *
 * Exercises the pure-Vanilla-ESM launch stack without a browser and without any
 * network call:
 *
 *   A. `run-launch.mjs`  — form rendering, definition/agent loading, governed
 *                          submission (`POST /api/factory/workflows/:id/run`),
 *                          success routing and error handling (400/409/500).
 *   B. `app.mjs`         — additive `/launch` route wiring and view mount /
 *                          teardown through the hash router.
 *
 * No external dependency, no network. Exit code 0 = all pass.
 *
 * Usage: node factory/tests/test-cockpit-run-launch.mjs
 */

import assert from 'node:assert/strict'

import {
  LAUNCH_ROUTE,
  DETAIL_ROUTE,
  buildRunUrl,
  buildLegacyRunUrl,
  buildDetailHash,
  describeLaunchError,
  isConflictError,
  normalizeDefinitions,
  normalizeAgents,
  renderRunLaunch,
  mountRunLaunchView,
  mount,
} from '../dashboard/js/views/run-launch.mjs'
import { ROUTES, DEFAULT_ROUTE, parseHash, createRouter, VIEW_MOUNTERS } from '../dashboard/js/app.mjs'

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

const flush = () => new Promise((resolve) => setImmediate(resolve))

const NS = '11111111-1111-4111-8111-111111111111'

/** DOM container double capturing HTML and delegated listeners. */
function createFakeContainer() {
  const handlers = new Map()
  return {
    innerHTML: '',
    handlers,
    addEventListener(type, handler) {
      if (!handlers.has(type)) handlers.set(type, new Set())
      handlers.get(type).add(handler)
    },
    removeEventListener(type, handler) {
      handlers.get(type)?.delete(handler)
    },
    listenerCount() {
      let total = 0
      for (const set of handlers.values()) total += set.size
      return total
    },
    dispatch(type, event) {
      for (const handler of [...(handlers.get(type) ?? [])]) handler(event)
    },
  }
}

/** Controllable timer double recording pending timeouts. */
function createTimerSpy() {
  let sequence = 0
  const pending = new Map()
  return {
    pending,
    setTimeoutFn: (fn, ms) => {
      const id = ++sequence
      pending.set(id, { fn, ms })
      return id
    },
    clearTimeoutFn: (id) => pending.delete(id),
    flush: () => {
      const entries = [...pending.values()]
      pending.clear()
      for (const { fn } of entries) fn()
    },
  }
}

const DEFINITIONS = {
  items: [
    { workflowType: 'bmad-story-frontend', version: '1.0.0', title: 'Frontend story' },
    { workflowType: 'bmad-story', version: '1.1.0', title: 'Story' },
  ],
}

const AGENTS = { items: [{ agentId: 'ForgeFrontendWorker', name: 'Frontend Worker' }, { id: 'Searcher' }] }

/**
 * API double recording every call. `post` can be overridden to throw or to
 * return a custom result.
 */
function createApiClient(options = {}) {
  const calls = []
  const fail = options.fail ?? {}
  return {
    calls,
    async get(path) {
      calls.push({ method: 'GET', path })
      if (path === '/api/factory/workflow-definitions') {
        if (fail.definitions) throw new Error('registry down')
        return options.definitions ?? DEFINITIONS
      }
      if (path.startsWith('/api/agents?')) {
        if (fail.agents) throw new Error('agentos down')
        return options.agents ?? AGENTS
      }
      throw new Error(`unexpected GET ${path}`)
    },
    async post(path, body, requestOptions = {}) {
      calls.push({ method: 'POST', path, body, signal: requestOptions.signal })
      if (options.postError) throw options.postError
      return options.postResult ?? { status: 'ACCEPTED', workflowId: 'wf-1' }
    },
  }
}

// ---------------------------------------------------------------------------
// A. Pure helpers
// ---------------------------------------------------------------------------

console.log('\nrun-launch helpers')

await scenario('buildRunUrl() targets the governed route and encodes the id', () => {
  assert.equal(buildRunUrl('wf-1'), '/api/factory/workflows/wf-1/run')
  assert.equal(buildRunUrl('a/b c'), '/api/factory/workflows/a%2Fb%20c/run')
  assert.equal(buildLegacyRunUrl(), '/api/factory/runs')
  assert.notEqual(buildRunUrl('wf-1'), buildLegacyRunUrl())
})

await scenario('routes and detail hash constants are stable', () => {
  assert.equal(LAUNCH_ROUTE, '/launch')
  assert.equal(DETAIL_ROUTE, '/detail')
  assert.equal(buildDetailHash('wf-1', NS), `/detail?workflowId=wf-1&namespaceId=${NS}`)
})

await scenario('describeLaunchError() maps 400, 409, 5xx and network failures', () => {
  assert.ok(describeLaunchError({ status: 400, code: 'INVALID_RUN_REQUEST' }).includes('INVALID_RUN_REQUEST'))
  assert.ok(describeLaunchError({ status: 409, code: 'HTTP_409' }).startsWith('Conflit'))
  assert.ok(
    describeLaunchError({
      status: 409,
      code: 'HTTP_409',
      details: { data: { code: 'AGENT_PREFLIGHT_FAILED' } },
    }).includes('AGENT_PREFLIGHT_FAILED')
  )
  assert.ok(describeLaunchError({ status: 500 }).startsWith('Erreur serveur'))
  assert.ok(describeLaunchError(new TypeError('Failed to fetch')).startsWith('Lancement impossible'))
  assert.equal(isConflictError({ status: 409 }), true)
  assert.equal(isConflictError({ status: 400 }), false)
})

await scenario('normalizeDefinitions()/normalizeAgents() tolerate multiple envelopes', () => {
  assert.equal(normalizeDefinitions(DEFINITIONS).length, 2)
  assert.equal(normalizeDefinitions([{ workflowType: 'x' }])[0].id, 'x')
  assert.deepEqual(normalizeDefinitions(null), [])
  assert.equal(normalizeDefinitions(DEFINITIONS)[0].id, 'bmad-story-frontend')

  assert.equal(normalizeAgents(AGENTS).length, 2)
  assert.equal(normalizeAgents(AGENTS)[0].id, 'ForgeFrontendWorker')
  assert.equal(normalizeAgents(['plain'])[0].id, 'plain')
  assert.deepEqual(normalizeAgents(undefined), [])
})

await scenario('renderRunLaunch() escapes hostile definition titles', () => {
  const html = renderRunLaunch({
    phase: 'ready',
    definitions: [{ id: 'wf"1', name: '"><script>alert(1)</script>' }],
    workflowId: 'wf"1',
    namespaceId: NS,
    agents: [],
    selectedAgents: [],
  })
  assert.ok(html.includes('data-launch-form="true"'))
  assert.ok(!html.includes('<script'))
  assert.ok(html.includes('&lt;script&gt;alert(1)&lt;/script&gt;'))
})

// ---------------------------------------------------------------------------
// B. Mount — rendering & loading
// ---------------------------------------------------------------------------

console.log('\nrun-launch mount')

await scenario('mount() validates its inputs', async () => {
  await assert.rejects(() => mountRunLaunchView(null, {}), TypeError)
  await assert.rejects(() => mountRunLaunchView(createFakeContainer(), {}), TypeError)
  await assert.rejects(
    () => mountRunLaunchView(createFakeContainer(), { apiClient: { get: async () => null } }),
    TypeError
  )
  assert.equal(mount, mountRunLaunchView)
})

await scenario('renders the launch form and loads definitions + agents', async () => {
  const container = createFakeContainer()
  const apiClient = createApiClient()
  const handle = await mountRunLaunchView(container, { apiClient, namespaceId: NS })

  assert.ok(apiClient.calls.some((call) => call.path === '/api/factory/workflow-definitions'))
  assert.ok(apiClient.calls.some((call) => call.path === `/api/agents?namespaceId=${NS}`))

  assert.ok(container.innerHTML.includes('data-run-launch="true"'))
  assert.ok(container.innerHTML.includes('data-launch-form="true"'))
  assert.ok(container.innerHTML.includes('id="launch-workflow-id"'))
  assert.ok(container.innerHTML.includes('id="launch-namespace-id"'))
  assert.ok(container.innerHTML.includes('id="launch-factory-root"'))
  assert.ok(container.innerHTML.includes('id="launch-ticket"'))
  assert.ok(container.innerHTML.includes('data-launch-submit="true"'))
  assert.ok(container.innerHTML.includes('btn-primary'))
  assert.ok(container.innerHTML.includes('btn-danger'))
  assert.ok(container.innerHTML.includes('data-launch-agent="ForgeFrontendWorker"'))
  assert.ok(container.innerHTML.includes('data-launch-agent="Searcher"'))

  const state = handle.getState()
  assert.equal(state.definitions.length, 2)
  assert.equal(state.workflowId, 'bmad-story-frontend')
  assert.equal(state.agents.length, 2)
  assert.equal(state.phase, 'ready')
  assert.equal(container.listenerCount(), 4)

  handle.unmount()
})

await scenario('changing the namespace reloads agents for that scope', async () => {
  const container = createFakeContainer()
  const apiClient = createApiClient()
  const handle = await mountRunLaunchView(container, { apiClient })

  assert.ok(container.innerHTML.includes('data-launch-agents-hint="true"'))

  container.dispatch('change', { target: { name: 'namespaceId', value: NS } })
  await flush()

  assert.ok(apiClient.calls.some((call) => call.path === `/api/agents?namespaceId=${NS}`))
  assert.ok(container.innerHTML.includes('data-launch-agent="Searcher"'))
  assert.equal(handle.getState().namespaceId, NS)

  handle.unmount()
})

await scenario('surfaces a definition-loading failure without crashing', async () => {
  const container = createFakeContainer()
  const apiClient = createApiClient({ fail: { definitions: true } })
  const handle = await mountRunLaunchView(container, { apiClient })

  assert.equal(handle.getState().phase, 'error')
  assert.ok(container.innerHTML.includes('data-launch-error="true"'))
  assert.ok(!container.innerHTML.includes('data-launch-form="true"'))

  handle.unmount()
})

// ---------------------------------------------------------------------------
// C. Submission — governed route & success routing
// ---------------------------------------------------------------------------

console.log('\nrun-launch submission')

await scenario('submits to POST /api/factory/workflows/:id/run with { namespaceId, ticket }', async () => {
  const container = createFakeContainer()
  const apiClient = createApiClient()
  const navigations = []
  const handle = await mountRunLaunchView(container, {
    apiClient,
    namespaceId: NS,
    onNavigate: (route, params) => navigations.push({ route, params }),
  })

  container.dispatch('change', { target: { name: 'workflowId', value: 'wf-1' } })
  container.dispatch('change', { target: { name: 'ticket', value: 'ABC-1' } })
  container.dispatch('submit', { preventDefault() {} })
  await flush()

  const posts = apiClient.calls.filter((call) => call.method === 'POST')
  assert.equal(posts.length, 1)
  assert.equal(posts[0].path, '/api/factory/workflows/wf-1/run')
  assert.deepEqual(posts[0].body, { namespaceId: NS, ticket: 'ABC-1' })
  // The legacy JSONL route must never be used.
  assert.ok(!apiClient.calls.some((call) => call.path === '/api/factory/runs'))
  assert.equal(handle.getState().submitError, null)
  assert.ok(container.innerHTML.includes('data-launch-submit-success="true"'))

  handle.unmount()
})

await scenario('omits `ticket` from the body when empty and routes to detail on success', async () => {
  const container = createFakeContainer()
  const apiClient = createApiClient()
  const navigations = []
  const handle = await mountRunLaunchView(container, {
    apiClient,
    namespaceId: NS,
    onNavigate: (route, params) => navigations.push({ route, params }),
  })

  container.dispatch('change', { target: { name: 'workflowId', value: 'wf-1' } })
  container.dispatch('submit', { preventDefault() {} })
  await flush()

  const post = apiClient.calls.find((call) => call.method === 'POST')
  assert.deepEqual(post.body, { namespaceId: NS })
  assert.deepEqual(navigations, [{ route: DETAIL_ROUTE, params: { workflowId: 'wf-1', namespaceId: NS } }])

  handle.unmount()
})

await scenario('defaults navigation to the canonical #/detail hash', async () => {
  const container = createFakeContainer()
  const apiClient = createApiClient()
  const win = { location: { hash: '' } }
  const handle = await mountRunLaunchView(container, { apiClient, namespaceId: NS, window: win })

  container.dispatch('change', { target: { name: 'workflowId', value: 'wf-1' } })
  container.dispatch('submit', { preventDefault() {} })
  await flush()

  assert.equal(win.location.hash, `#${buildDetailHash('wf-1', NS)}`)

  handle.unmount()
})

await scenario('blocks submission when required fields are missing', async () => {
  const container = createFakeContainer()
  const apiClient = createApiClient({ definitions: { items: [] } })
  const handle = await mountRunLaunchView(container, { apiClient, namespaceId: NS })

  handle.getState().workflowId = ''
  container.dispatch('submit', { preventDefault() {} })
  await flush()

  assert.equal(apiClient.calls.filter((call) => call.method === 'POST').length, 0)
  assert.ok(container.innerHTML.includes('data-launch-submit-error="true"'))
  assert.ok(container.innerHTML.includes('Sélectionnez une définition'))

  handle.unmount()
})

// ---------------------------------------------------------------------------
// D. Error handling — 400 / 409 / 500
// ---------------------------------------------------------------------------

console.log('\nrun-launch errors')

await scenario('handles 400 INVALID_RUN_REQUEST with a clear message', async () => {
  const container = createFakeContainer()
  const apiClient = createApiClient({
    postError: { status: 400, code: 'INVALID_RUN_REQUEST', message: 'bad request' },
  })
  const navigations = []
  const handle = await mountRunLaunchView(container, {
    apiClient,
    namespaceId: NS,
    onNavigate: (...args) => navigations.push(args),
  })

  container.dispatch('change', { target: { name: 'workflowId', value: 'wf-1' } })
  container.dispatch('submit', { preventDefault() {} })
  await flush()

  assert.ok(container.innerHTML.includes('data-launch-submit-error="true"'))
  assert.ok(handle.getState().submitError.includes('INVALID_RUN_REQUEST'))
  assert.equal(handle.getState().submitting, false)
  assert.equal(navigations.length, 0)

  handle.unmount()
})

await scenario('handles 409 conflicts and surfaces the underlying code', async () => {
  const container = createFakeContainer()
  const apiClient = createApiClient({
    postError: {
      status: 409,
      code: 'HTTP_409',
      message: 'Conflict',
      details: { data: { code: 'AGENT_PREFLIGHT_FAILED' } },
    },
  })
  const navigations = []
  const handle = await mountRunLaunchView(container, {
    apiClient,
    namespaceId: NS,
    onNavigate: (...args) => navigations.push(args),
  })

  container.dispatch('change', { target: { name: 'workflowId', value: 'wf-1' } })
  container.dispatch('submit', { preventDefault() {} })
  await flush()

  assert.ok(container.innerHTML.includes('data-launch-submit-error="true"'))
  assert.ok(handle.getState().submitError.startsWith('Conflit'))
  assert.ok(handle.getState().submitError.includes('AGENT_PREFLIGHT_FAILED'))
  assert.equal(navigations.length, 0)

  handle.unmount()
})

await scenario('handles server and network failures gracefully', async () => {
  for (const postError of [{ status: 500, code: 'HTTP_500', message: 'boom' }, new TypeError('Failed to fetch')]) {
    const container = createFakeContainer()
    const apiClient = createApiClient({ postError })
    const handle = await mountRunLaunchView(container, { apiClient, namespaceId: NS })
    container.dispatch('change', { target: { name: 'workflowId', value: 'wf-1' } })
    container.dispatch('submit', { preventDefault() {} })
    await flush()

    assert.ok(container.innerHTML.includes('data-launch-submit-error="true"'))
    assert.ok(handle.getState().submitError)
    handle.unmount()
  }
})

await scenario('can retry after a failure and recover', async () => {
  const container = createFakeContainer()
  const apiClient = createApiClient()
  let attempts = 0
  apiClient.post = async (path, body) => {
    attempts++
    if (attempts === 1) throw { status: 400, code: 'INVALID_RUN_REQUEST', message: 'bad' }
    return { status: 'ACCEPTED' }
  }
  const navigations = []
  const handle = await mountRunLaunchView(container, {
    apiClient,
    namespaceId: NS,
    onNavigate: (route, params) => navigations.push({ route, params }),
  })

  container.dispatch('change', { target: { name: 'workflowId', value: 'wf-1' } })
  container.dispatch('submit', { preventDefault() {} })
  await flush()
  assert.ok(handle.getState().submitError)

  container.dispatch('submit', { preventDefault() {} })
  await flush()
  assert.equal(handle.getState().submitError, null)
  assert.equal(navigations.length, 1)

  handle.unmount()
})

// ---------------------------------------------------------------------------
// E. Teardown — zero leak
// ---------------------------------------------------------------------------

console.log('\nrun-launch teardown')

await scenario('unmount() detaches every listener and aborts in-flight requests', async () => {
  const created = []
  const PreviousAbortController = globalThis.AbortController
  class TrackingAbortController extends PreviousAbortController {
    constructor() {
      super()
      created.push(this)
    }
  }
  globalThis.AbortController = TrackingAbortController

  try {
    const container = createFakeContainer()
    const apiClient = createApiClient()
    const handle = await mountRunLaunchView(container, { apiClient, namespaceId: NS })

    assert.equal(container.listenerCount(), 4)
    handle.unmount()
    assert.equal(handle.isMounted(), false)
    assert.equal(container.listenerCount(), 0, 'no lingering DOM listener')
    assert.equal(container.innerHTML, '')
    assert.equal(created.at(-1).signal.aborted, true, 'in-flight request aborted')

    // Idempotent, and a post-unmount event cannot revive the view.
    handle.unmount()
    container.dispatch('submit', { preventDefault() {} })
    assert.equal(container.innerHTML, '')
    assert.equal(apiClient.calls.filter((call) => call.method === 'POST').length, 0)
  } finally {
    globalThis.AbortController = PreviousAbortController
  }
})

await scenario('unmount() clears a pending redirect timer (zero timer leak)', async () => {
  const container = createFakeContainer()
  const apiClient = createApiClient()
  const timers = createTimerSpy()
  const navigations = []
  const handle = await mountRunLaunchView(container, {
    apiClient,
    namespaceId: NS,
    redirectDelayMs: 50,
    setTimeoutFn: timers.setTimeoutFn,
    clearTimeoutFn: timers.clearTimeoutFn,
    onNavigate: (route, params) => navigations.push({ route, params }),
  })

  container.dispatch('change', { target: { name: 'workflowId', value: 'wf-1' } })
  container.dispatch('submit', { preventDefault() {} })
  await flush()

  assert.equal(timers.pending.size, 1)
  assert.notEqual(handle.getPendingTimer(), null)
  assert.equal(navigations.length, 0)

  handle.unmount()
  assert.equal(handle.getPendingTimer(), null)
  assert.equal(timers.pending.size, 0, 'no lingering redirect timer')

  // Firing the (now-cleared) timers must not navigate after unmount.
  timers.flush()
  assert.equal(navigations.length, 0)
})

await scenario('a delayed redirect fires once when not unmounted', async () => {
  const container = createFakeContainer()
  const apiClient = createApiClient()
  const timers = createTimerSpy()
  const navigations = []
  const handle = await mountRunLaunchView(container, {
    apiClient,
    namespaceId: NS,
    redirectDelayMs: 50,
    setTimeoutFn: timers.setTimeoutFn,
    clearTimeoutFn: timers.clearTimeoutFn,
    onNavigate: (route, params) => navigations.push({ route, params }),
  })

  container.dispatch('change', { target: { name: 'workflowId', value: 'wf-1' } })
  container.dispatch('submit', { preventDefault() {} })
  await flush()

  timers.flush()
  assert.deepEqual(navigations, [{ route: DETAIL_ROUTE, params: { workflowId: 'wf-1', namespaceId: NS } }])
  assert.equal(handle.getPendingTimer(), null)

  handle.unmount()
})

// ---------------------------------------------------------------------------
// F. Router wiring (app.mjs)
// ---------------------------------------------------------------------------

console.log('\napp.mjs router wiring')

function createFakeWin(hash) {
  return {
    location: { hash },
    history: { replaceState() {} },
    addEventListener() {},
  }
}

function createFakeSection(id) {
  return {
    id,
    classList: {
      active: false,
      toggle(className, on) {
        if (className === 'active') this.active = on
      },
    },
  }
}

function createFakeDoc(containers) {
  return {
    getElementById: (id) => containers[id] ?? null,
    querySelectorAll: (selector) => (selector === '.cockpit-view' ? Object.values(containers.sections ?? []) : []),
  }
}

await scenario('/launch is registered without disturbing existing routes', () => {
  assert.equal(ROUTES['/launch'].id, 'view-launch')
  assert.equal(ROUTES['/launch'].label, 'Lancer')
  for (const route of ['/runs', '/detail', '/projection', '/forge', '/admin']) {
    assert.ok(ROUTES[route], `${route} must remain registered`)
  }
  assert.equal(DEFAULT_ROUTE, '/runs')
  assert.equal(parseHash('#/launch'), '/launch')
  assert.equal(parseHash('#/launch?workflowId=wf-1'), '/launch')
  assert.equal(parseHash('#/unknown'), DEFAULT_ROUTE)
  assert.equal(VIEW_MOUNTERS['/launch'], mountRunLaunchView)
})

await scenario('router mounts the launch view on #/launch and tears it down on exit', async () => {
  const sections = {
    'view-runs': createFakeSection('view-runs'),
    'view-launch': createFakeSection('view-launch'),
  }
  const launchHost = createFakeContainer()
  const win = createFakeWin('#/launch')
  const doc = createFakeDoc({ 'view-launch': launchHost, sections })
  const apiClient = createApiClient()
  const router = createRouter(win, doc, { apiClient, mounters: VIEW_MOUNTERS })

  router.start()
  await flush()
  await flush()

  assert.equal(router.getCurrentRoute(), '/launch')
  assert.ok(launchHost.innerHTML.includes('data-run-launch="true"'))
  assert.equal(launchHost.listenerCount(), 4)
  assert.equal(sections['view-launch'].classList.active, true)
  assert.equal(sections['view-runs'].classList.active, false)

  // Leaving the route must run the launch view teardown.
  router.mount('/runs')
  assert.equal(router.getCurrentRoute(), '/runs')
  assert.equal(launchHost.innerHTML, '')
  assert.equal(launchHost.listenerCount(), 0)
  assert.equal(sections['view-runs'].classList.active, true)
})

// ---------------------------------------------------------------------------

console.log(`\nResult: ${passed} passed, ${failed} failed`)
process.exit(failed ? 1 : 0)
