/**
 * Factory Cockpit — projection governance offline test suite (Milestone D, Wave 2).
 *
 * Standalone, no browser, no network, no external dependency:
 *
 *   $ node factory/tests/test-projection-governance.mjs
 *
 * Covers:
 *   A. temporal-lanes layout (classification, states, timing, rendering);
 *   B. workflow-card SSRF-safe link generation (agentos vs coday-express);
 *   C. SSE invalidation handling (updated/removed/restored/purged + refetch);
 *   D. case grouping and sub-case hierarchy unfolding;
 *   E. lifecycle actions and 409 / REVISION_CONFLICT handling;
 *   F. view mount/teardown with zero SSE or listener leaks.
 *
 * Exit code 0 = all pass, 1 = at least one failure.
 */

import assert from 'node:assert/strict'

import { buildBlueprintLayout, renderTemporalLanes, classifyActorKind } from '../dashboard/js/components/temporal-lanes.mjs'
import { buildAgentosCaseUrl, renderWorkflowCard, formatDuration } from '../dashboard/js/components/workflow-card.mjs'
import { SseClient } from '../dashboard/js/services/sse-client.mjs'
import {
  ProjectionController,
  createProjectionController,
  createDialogConfirm,
  deriveGroupKey,
  groupWorkflows,
  isConflictError,
  mountProjectionView,
  renderProjection,
} from '../dashboard/js/views/projection.mjs'

const NS = '11111111-1111-4111-8111-111111111111'

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

let passed = 0
let failed = 0

async function scenario(name, fn) {
  try {
    await fn()
    passed++
    console.log(`\u2713 ${name}`)
  } catch (error) {
    failed++
    console.log(`\u2717 ${name}`)
    console.log(`  ${error?.stack ?? error}`)
  }
}

const tick = () => new Promise((resolve) => setTimeout(resolve, 0))

/** Minimal EventSource double, mirroring test-cockpit-shell.mjs. */
class MockEventSource {
  static instances = []

  constructor(url) {
    this.url = url
    this.listeners = new Map()
    this.closed = false
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

/** Listener-tracking SSE double (external client, never owned). */
function createFakeSse() {
  const listeners = new Map()
  return {
    closed: false,
    listeners,
    on(event, handler) {
      if (!listeners.has(event)) listeners.set(event, new Set())
      listeners.get(event).add(handler)
      return () => {
        const handlers = listeners.get(event)
        handlers?.delete(handler)
        if (handlers?.size === 0) listeners.delete(event)
      }
    },
    close() {
      this.closed = true
      listeners.clear()
    },
    listenerCount() {
      let total = 0
      for (const handlers of listeners.values()) total += handlers.size
      return total
    },
  }
}

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

/** Build a synthetic delegated click event for the container double. */
function createFakeClick({ mode, action, workflowId } = {}) {
  return {
    target: {
      closest(selector) {
        if (selector === '[data-projection-mode]' && mode) return { dataset: { projectionMode: mode } }
        if (selector === '[data-action]' && action) return { dataset: { action, workflowId } }
        return null
      },
    },
  }
}

/** API double recording calls; handlers are overridable per scenario. */
function createApi(handlers = {}) {
  const calls = []
  return {
    calls,
    async get(path) {
      calls.push({ method: 'GET', path })
      return handlers.get ? handlers.get(path, calls) : null
    },
    async post(path, body) {
      calls.push({ method: 'POST', path, body })
      return handlers.post ? handlers.post(path, body, calls) : {}
    },
    async delete(path) {
      calls.push({ method: 'DELETE', path })
      return handlers.delete ? handlers.delete(path, calls) : {}
    },
  }
}

function makeSnapshot(overrides = {}) {
  return {
    workflowId: 'wf-1',
    revision: 1,
    projection: {
      schemaVersion: '2',
      workflowId: 'wf-1',
      workflowType: 'delivery',
      title: 'Delivery',
      status: 'running',
      steps: [
        { id: 'define', name: 'Define', status: 'completed', responsibility: { kind: 'human' } },
        { id: 'build', name: 'Build', status: 'running', responsibility: { kind: 'agent' } },
        { id: 'test', name: 'Test', status: 'pending', responsibility: { kind: 'code' } },
      ],
    },
    controllerExecution: { runtimeId: 'agentos-primary', kind: 'agentos', agentId: 'agent-1', caseId: 'case-1' },
    relations: { rootWorkflowId: 'wf-1' },
    ...overrides,
  }
}

// ---------------------------------------------------------------------------
// A. Temporal lanes
// ---------------------------------------------------------------------------

console.log('\nA. Temporal lanes')

await scenario('classifies steps into human/agent/code lanes by responsibility', () => {
  const layout = buildBlueprintLayout([
    { id: 'h', name: 'H', status: 'completed', responsibility: { kind: 'human' } },
    { id: 'a', name: 'A', status: 'running', responsibility: { kind: 'agent' } },
    { id: 'c', name: 'C', status: 'pending', responsibility: { kind: 'code' } },
  ])
  assert.deepEqual(Object.keys(layout.lanes), ['human', 'agent', 'code'])
  assert.equal(layout.lanes.human.length, 1)
  assert.equal(layout.lanes.agent.length, 1)
  assert.equal(layout.lanes.code.length, 1)
  assert.equal(layout.lanes.human[0].state, 'completed')
  assert.equal(layout.lanes.agent[0].state, 'active')
  assert.equal(layout.lanes.code[0].state, 'pending')
  assert.deepEqual(layout.summary.laneCounts, { human: 1, agent: 1, code: 1 })
  assert.equal(layout.summary.totalSteps, 3)
  assert.ok(Math.abs(layout.summary.completionRate - 1 / 3) < 1e-9)
})

await scenario('falls back to deterministic name heuristics', () => {
  assert.equal(classifyActorKind({ id: 'x', name: 'Human review gate' }), 'human')
  assert.equal(classifyActorKind({ id: 'x', name: 'Run tests' }), 'code')
  assert.equal(classifyActorKind({ id: 'x', name: 'Write documentation' }), 'agent')
  assert.equal(classifyActorKind({ responsibility: { kind: 'code' }, name: 'Human review' }), 'code')
})

await scenario('computes failed state and honours an explicit active step id', () => {
  const layout = buildBlueprintLayout(
    [
      { id: 'boom', name: 'Boom', status: 'failed' },
      { id: 'next', name: 'Next', status: 'pending' },
    ],
    'next',
  )
  assert.equal(layout.steps.find((node) => node.id === 'boom').state, 'failed')
  assert.equal(layout.steps.find((node) => node.id === 'next').state, 'active')
  assert.equal(layout.summary.activeStepId, 'next')
})

await scenario('flattens phase-wrapped steps and derives monotonic timing', () => {
  const layout = buildBlueprintLayout([
    {
      id: 'phase-1',
      name: 'Phase 1',
      steps: [
        { id: 'build', name: 'Build the app', status: 'completed' },
        { id: 'review-gate', name: 'Human review gate', status: 'ready' },
      ],
    },
  ])
  assert.equal(layout.summary.totalSteps, 2)
  assert.equal(layout.lanes.code.length, 1)
  assert.equal(layout.lanes.human.length, 1)
  assert.ok(layout.steps[1].timing.startRatio > layout.steps[0].timing.startRatio)
  assert.ok(layout.steps[0].timing.widthRatio > 0)
})

await scenario('renders the three swimlanes as HTML', () => {
  const layout = buildBlueprintLayout([
    { id: 'h', name: 'H', status: 'completed', responsibility: { kind: 'human' } },
    { id: 'a', name: 'A', status: 'running', responsibility: { kind: 'agent' } },
    { id: 'c', name: 'C', status: 'pending', responsibility: { kind: 'code' } },
  ])
  const html = renderTemporalLanes(layout)
  assert.ok(html.includes('data-lane="human"'))
  assert.ok(html.includes('data-lane="agent"'))
  assert.ok(html.includes('data-lane="code"'))
  assert.ok(html.includes('data-state="completed"'))
  assert.ok(html.includes('data-step-id="a"'))
})

// ---------------------------------------------------------------------------
// B. Workflow card & SSRF link safety
// ---------------------------------------------------------------------------

console.log('\nB. Workflow card & SSRF')

await scenario('builds a safe absolute agentos case URL', () => {
  assert.equal(buildAgentosCaseUrl('case-1', 'https://agentos.example.com'), 'https://agentos.example.com/case/case-1')
  assert.equal(
    buildAgentosCaseUrl('case-1', 'https://agentos.example.com/base/'),
    'https://agentos.example.com/case/case-1',
  )
  assert.equal(
    buildAgentosCaseUrl('a b/c?d', 'https://agentos.example.com'),
    'https://agentos.example.com/case/a%20b%2Fc%3Fd',
  )
})

await scenario('refuses missing, malformed or untrusted base URLs', () => {
  for (const base of [
    undefined,
    null,
    '',
    '   ',
    'not a url',
    'javascript:alert(1)',
    'ftp://agentos.example.com',
    'file:///etc/passwd',
    'https://user:pass@agentos.example.com',
    '//agentos.example.com',
  ]) {
    assert.equal(buildAgentosCaseUrl('case-1', base), null, `base ${String(base)} must not yield a URL`)
  }
  assert.equal(buildAgentosCaseUrl('', 'https://agentos.example.com'), null)
})

await scenario('caseId injection cannot change the target origin (SSRF)', () => {
  const href = buildAgentosCaseUrl('//evil.example.com/x', 'https://agentos.example.com')
  assert.ok(href)
  assert.equal(new URL(href).origin, 'https://agentos.example.com')
  assert.equal(new URL(href).host, 'agentos.example.com')
})

await scenario('renders an agentos link only with a trusted base URL', () => {
  const snapshot = makeSnapshot()
  const linked = renderWorkflowCard(snapshot, { agentosUrl: 'https://agentos.example.com' })
  assert.ok(
    linked.includes(
      '<a class="case-link" href="https://agentos.example.com/case/case-1" target="_blank" rel="noopener noreferrer"',
    ),
  )
  assert.ok(linked.includes('data-action="remove"'))
  assert.ok(linked.includes('data-action="purge"'))
  assert.ok(linked.includes('data-workflow-id="wf-1"'))

  const unlinked = renderWorkflowCard(snapshot, { agentosUrl: 'javascript:alert(1)' })
  assert.ok(!unlinked.includes('<a '))
  assert.ok(unlinked.includes('Case case-1'))
})

await scenario('renders coday-express threads as unclickable text', () => {
  const snapshot = makeSnapshot({
    workflowId: 'wf-express',
    projection: {
      schemaVersion: '2',
      workflowId: 'wf-express',
      workflowType: 'coday',
      title: 'Express',
      status: 'ready',
      steps: [],
    },
    controllerExecution: { runtimeId: 'coday-express-transitional', kind: 'coday-express', agentId: 'Sway', threadId: 't-1' },
    relations: { rootWorkflowId: 'wf-express' },
  })
  const html = renderWorkflowCard(snapshot, { agentosUrl: 'https://agentos.example.com' })
  assert.ok(!html.includes('<a '))
  assert.ok(html.includes('<span class="thread-id'))
  assert.ok(html.includes('Thread t-1'))

  // Even a stray caseId on a coday-express execution must stay unclickable.
  const stray = renderWorkflowCard(
    { workflowId: 'wf-x', projection: { title: 'X', status: 'ready', steps: [] }, controllerExecution: { kind: 'coday-express', caseId: 'case-x' } },
    { agentosUrl: 'https://agentos.example.com' },
  )
  assert.ok(!stray.includes('<a '))
})

await scenario('escapes untrusted card content', () => {
  const html = renderWorkflowCard({
    workflowId: 'wf"<script>',
    projection: { title: '<img src=x onerror=alert(1)>', status: 'ready', steps: [] },
    controllerExecution: { kind: 'coday-express', threadId: '<b>' },
  })
  assert.ok(!html.includes('<img'))
  assert.ok(html.includes('&lt;img'))
  assert.ok(!html.includes('<b>'))
})

await scenario('removed mode exposes restore but not remove', () => {
  const html = renderWorkflowCard(makeSnapshot(), { agentosUrl: 'https://agentos.example.com', mode: 'removed' })
  assert.ok(html.includes('data-action="restore"'))
  assert.ok(!html.includes('data-action="remove"'))
  assert.ok(html.includes('data-action="purge"'))
})

await scenario('formats durations and detects conflicts', () => {
  assert.equal(formatDuration(500), '500 ms')
  assert.ok(formatDuration(2000).includes('s'))
  assert.equal(formatDuration(Number.NaN), null)
  assert.equal(isConflictError({ status: 409 }), true)
  assert.equal(isConflictError({ code: 'REVISION_CONFLICT' }), true)
  assert.equal(isConflictError({ status: 400 }), false)
})

// ---------------------------------------------------------------------------
// C. SSE invalidation handling
// ---------------------------------------------------------------------------

console.log('\nC. SSE invalidation')

function createSseController(detailRef) {
  const item = makeSnapshot()
  const api = createApi({
    get: async (path) => {
      if (path.startsWith('/api/factory/workflows?')) return { items: detailRef.value === 'purged' ? [] : [item] }
      if (path.includes('/api/factory/workflows/wf-1?')) {
        if (detailRef.value === 'removed') return { state: 'removed', workflowId: 'wf-1' }
        if (detailRef.value === 'purged' || detailRef.value === 'absent') return { state: 'absent', workflowId: 'wf-1' }
        return { state: 'existing', ...item, revision: 2, projection: { ...item.projection, title: 'Updated' } }
      }
      return null
    },
  })
  return { controller: createProjectionController({ api, namespaceId: NS }), api }
}

await scenario('updated event triggers a targeted refetch and merge', async () => {
  const ref = { value: 'existing' }
  const { controller, api } = createSseController(ref)
  await controller.load('active')
  controller.handleSseEvent('workflow-projection-updated', { workflowId: 'wf-1' })
  await tick()
  assert.equal(controller.active.get('wf-1').projection.title, 'Updated')
  assert.ok(api.calls.some((call) => call.path.startsWith('/api/factory/workflows/wf-1?')))
})

await scenario('removed event moves the workflow to the removed map', async () => {
  const ref = { value: 'existing' }
  const { controller } = createSseController(ref)
  await controller.load('active')
  controller.handleSseEvent('workflow-projection-removed', { workflowId: 'wf-1' })
  assert.equal(controller.active.has('wf-1'), false)
  assert.equal(controller.removed.has('wf-1'), true)
  assert.equal(controller.removed.get('wf-1').state, 'removed')
})

await scenario('restored event returns the workflow to the active map', async () => {
  const ref = { value: 'existing' }
  const { controller } = createSseController(ref)
  await controller.load('active')
  controller.handleSseEvent('workflow-projection-removed', { workflowId: 'wf-1' })
  controller.handleSseEvent('workflow-projection-restored', { workflowId: 'wf-1' })
  await tick()
  assert.equal(controller.active.has('wf-1'), true)
  assert.equal(controller.removed.has('wf-1'), false)
})

await scenario('purged event drops the workflow from every map', async () => {
  const ref = { value: 'existing' }
  const { controller } = createSseController(ref)
  await controller.load('active')
  controller.handleSseEvent('workflow-projection-removed', { workflowId: 'wf-1' })
  controller.handleSseEvent('workflow-projection-purged', { workflowId: 'wf-1' })
  assert.equal(controller.active.has('wf-1'), false)
  assert.equal(controller.removed.has('wf-1'), false)
})

await scenario('dispatches named events through the real SseClient transport', async () => {
  const ref = { value: 'existing' }
  const { controller } = createSseController(ref)
  const sse = new SseClient(`/api/factory/workflows/stream?namespaceId=${NS}`, { EventSource: MockEventSource })
  controller.sse = sse
  controller.attachSse()
  sse.connect()
  const mock = MockEventSource.instances.at(-1)
  await controller.load('active')
  mock.dispatch('workflow-projection-updated', JSON.stringify({ workflowId: 'wf-1', namespaceId: NS }))
  await tick()
  assert.equal(controller.active.get('wf-1').projection.title, 'Updated')
  controller.teardown()
  assert.equal(sse.listeners.size, 0)
  sse.close()
  assert.equal(mock.listenerCount(), 0)
})

// ---------------------------------------------------------------------------
// D. Case grouping & hierarchy unfolding
// ---------------------------------------------------------------------------

console.log('\nD. Grouping & unfolding')

await scenario('derives group keys from caseId then relations then fallback', () => {
  assert.equal(deriveGroupKey({ controllerExecution: { caseId: 'case-9' }, relations: { rootWorkflowId: 'r' } }), 'case-9')
  assert.equal(deriveGroupKey({ relations: { rootWorkflowId: 'root-7' } }), 'root-7')
  assert.equal(deriveGroupKey({}), '__ungrouped__')
})

await scenario('groups by case and nests sub-cases by parent relation', () => {
  const snapshots = [
    { workflowId: 'root-1', controllerExecution: { caseId: 'case-1' }, relations: { rootWorkflowId: 'root-1' } },
    {
      workflowId: 'child-1',
      controllerExecution: { caseId: 'case-1' },
      instance: { relations: { rootWorkflowId: 'root-1', parentWorkflowId: 'root-1' } },
    },
    {
      workflowId: 'child-2',
      controllerExecution: { caseId: 'case-1' },
      instance: { relations: { rootWorkflowId: 'root-1', parentWorkflowId: 'child-1' } },
    },
    { workflowId: 'root-2', controllerExecution: { caseId: 'case-2' } },
    { workflowId: 'orphan-1' },
  ]
  const groups = groupWorkflows(snapshots)
  const byKey = Object.fromEntries(groups.map((group) => [group.key, group]))

  assert.deepEqual(Object.keys(byKey).sort(), ['__ungrouped__', 'case-1', 'case-2'])
  assert.equal(groups.at(-1).key, '__ungrouped__')

  const case1 = byKey['case-1']
  assert.equal(case1.roots.length, 1)
  assert.equal(case1.roots[0].workflowId, 'root-1')
  assert.equal(case1.roots[0].children.length, 1)
  assert.equal(case1.roots[0].children[0].workflowId, 'child-1')
  assert.equal(case1.roots[0].children[0].children[0].workflowId, 'child-2')

  assert.equal(byKey['case-2'].roots[0].workflowId, 'root-2')
  assert.equal(byKey['__ungrouped__'].roots[0].workflowId, 'orphan-1')
})

await scenario('survives cyclic parent relations without dropping workflows', () => {
  const controller = createProjectionController({ api: createApi(), namespaceId: NS })
  controller.active.set(
    'a',
    { workflowId: 'a', controllerExecution: { caseId: 'cycle' }, instance: { relations: { parentWorkflowId: 'b' } } },
  )
  controller.active.set(
    'b',
    { workflowId: 'b', controllerExecution: { caseId: 'cycle' }, instance: { relations: { parentWorkflowId: 'a' } } },
  )
  const html = renderProjection(controller)
  assert.ok(html.includes('data-workflow-id="a"'))
  assert.ok(html.includes('data-workflow-id="b"'))
})

await scenario('renders collapsible sub-cases inside a group', () => {
  const controller = createProjectionController({ api: createApi(), namespaceId: NS })
  controller.active.set('root-1', {
    workflowId: 'root-1',
    controllerExecution: { caseId: 'case-1' },
    projection: { title: 'Root', status: 'running', steps: [] },
    relations: { rootWorkflowId: 'root-1' },
  })
  controller.active.set('child-1', {
    workflowId: 'child-1',
    controllerExecution: { caseId: 'case-1' },
    projection: { title: 'Child', status: 'pending', steps: [] },
    instance: { relations: { rootWorkflowId: 'root-1', parentWorkflowId: 'root-1' } },
  })
  const html = renderProjection(controller)
  assert.ok(html.includes('class="projection-group"'))
  assert.ok(html.includes('data-group-key="case-1"'))
  assert.ok(html.includes('class="workflow-subcases"'))
  assert.ok(html.includes('data-subcase-count="1"'))
})

// ---------------------------------------------------------------------------
// E. Lifecycle actions & conflicts
// ---------------------------------------------------------------------------

console.log('\nE. Lifecycle actions')

await scenario('remove action deletes and refreshes the list', async () => {
  let items = [makeSnapshot()]
  const api = createApi({
    get: async (path) => {
      if (path.startsWith('/api/factory/workflows?')) return { items }
      return null
    },
    delete: async () => {
      items = []
      return { state: 'removed' }
    },
  })
  const controller = createProjectionController({ api, namespaceId: NS })
  await controller.load('active')
  const result = await controller.performLifecycle('remove', 'wf-1')
  assert.equal(result.ok, true)
  assert.equal(controller.active.has('wf-1'), false)
  const call = api.calls.find((entry) => entry.method === 'DELETE')
  assert.ok(call.path.includes('/api/factory/workflows/wf-1?namespaceId='))
})

await scenario('restore action posts to the restore endpoint', async () => {
  const api = createApi({
    get: async (path) => (path.startsWith('/api/factory/workflows?') ? { items: [] } : null),
    post: async () => ({ state: 'active' }),
  })
  const controller = createProjectionController({ api, namespaceId: NS })
  await controller.load('removed')
  const result = await controller.performLifecycle('restore', 'wf-2')
  assert.equal(result.ok, true)
  const call = api.calls.find((entry) => entry.method === 'POST')
  assert.ok(call.path.includes('/api/factory/workflows/wf-2/restore?namespaceId='))
})

await scenario('purge action deletes the purge endpoint', async () => {
  const api = createApi({
    get: async (path) => (path.startsWith('/api/factory/workflows?') ? { items: [] } : null),
  })
  const controller = createProjectionController({ api, namespaceId: NS })
  await controller.load('removed')
  await controller.performLifecycle('purge', 'wf-3')
  const call = api.calls.find((entry) => entry.method === 'DELETE')
  assert.ok(call.path.includes('/api/factory/workflows/wf-3/purge?namespaceId='))
})

await scenario('409 REVISION_CONFLICT surfaces structured feedback and refetches', async () => {
  const errors = []
  const conflict = Object.assign(new Error('stale revision'), { status: 409, code: 'REVISION_CONFLICT' })
  const api = createApi({
    get: async (path) => (path.startsWith('/api/factory/workflows?') ? { items: [makeSnapshot()] } : null),
    delete: async () => {
      throw conflict
    },
  })
  const controller = createProjectionController({ api, namespaceId: NS, onError: (message) => errors.push(message) })
  await controller.load('active')
  const before = api.calls.filter((entry) => entry.method === 'GET').length
  const result = await controller.performLifecycle('remove', 'wf-1')
  const after = api.calls.filter((entry) => entry.method === 'GET').length

  assert.equal(result.ok, false)
  assert.equal(result.conflict, true)
  assert.equal(result.code, 'REVISION_CONFLICT')
  assert.equal(result.status, 409)
  assert.equal(errors.length, 1)
  assert.ok(errors[0].includes('Conflit'))
  assert.ok(after > before, 'a conflict must re-fetch the current state')
})

await scenario('fetchTiming reads the trusted timing endpoint', async () => {
  const timing = { complete: true, totalElapsedMs: 4200, activeMs: 3000 }
  const api = createApi({
    get: async (path) =>
      path.includes('/api/factory/workflows/wf-1/timing?')
        ? { workflowId: 'wf-1', timing }
        : null,
  })
  const controller = createProjectionController({ api, namespaceId: NS })
  assert.deepEqual(await controller.fetchTiming('wf-1'), timing)
  const call = api.calls.find((entry) => entry.path.includes('/timing'))
  assert.ok(call.path.includes('/api/factory/workflows/wf-1/timing?namespaceId='))
})

await scenario('requestAction runs only after a positive confirmation', async () => {
  const api = createApi({
    get: async (path) => (path.startsWith('/api/factory/workflows?') ? { items: [] } : null),
    delete: async () => ({ state: 'purged' }),
  })
  let confirmations = 0
  const controller = createProjectionController({
    api,
    namespaceId: NS,
    confirm: async () => {
      confirmations++
      return false
    },
  })
  const cancelled = await controller.requestAction('purge', 'wf-9')
  assert.equal(cancelled.cancelled, true)
  assert.equal(confirmations, 1)
  assert.equal(api.calls.some((entry) => entry.method === 'DELETE'), false)

  const approved = createProjectionController({ api, namespaceId: NS, confirm: async () => true })
  const done = await approved.requestAction('purge', 'wf-9')
  assert.equal(done.ok, true)
  assert.ok(api.calls.some((entry) => entry.method === 'DELETE'))
})

await scenario('native <dialog> confirmation resolves confirm and cancel', async () => {
  const hostHandlers = new Set()
  const host = {
    innerHTML: '',
    addEventListener: (type, handler) => {
      if (type === 'click') hostHandlers.add(handler)
    },
    removeEventListener: (type, handler) => {
      if (type === 'click') hostHandlers.delete(handler)
    },
  }
  const dialog = {
    open: false,
    showModal() {
      this.open = true
    },
    close() {
      this.open = false
    },
  }
  const doc = {
    getElementById: (id) =>
      id === 'cockpit-dialog-content' ? host : id === 'cockpit-dialog' ? dialog : null,
  }
  const confirm = createDialogConfirm({ doc })

  const approved = confirm({ action: 'purge', workflowId: 'wf-1', label: 'Purger', warning: 'w' })
  assert.equal(dialog.open, true)
  for (const handler of [...hostHandlers]) {
    handler({ target: { closest: (selector) => (selector === '[data-dialog-action]' ? { dataset: { dialogAction: 'confirm' } } : null) } })
  }
  assert.equal(await approved, true)
  assert.equal(dialog.open, false)
  assert.equal(hostHandlers.size, 0)

  const declined = confirm({ action: 'remove', workflowId: 'wf-1', label: 'Supprimer', warning: 'w' })
  for (const handler of [...hostHandlers]) {
    handler({ target: { closest: (selector) => (selector === '[data-dialog-action]' ? { dataset: { dialogAction: 'cancel' } } : null) } })
  }
  assert.equal(await declined, false)
})

// ---------------------------------------------------------------------------
// F. Mount & teardown (zero leak)
// ---------------------------------------------------------------------------

console.log('\nF. Mount & teardown')

function createMountApi() {
  return createApi({
    get: async (path) => {
      if (path === '/api/config') return { agentosUrl: 'https://agentos.example.com' }
      if (path.startsWith('/api/factory/workflows?')) return { items: [makeSnapshot()] }
      if (path.includes('/api/factory/workflows/wf-1?')) return { state: 'existing', ...makeSnapshot(), revision: 2 }
      return null
    },
    delete: async () => ({ state: 'removed' }),
  })
}

await scenario('mounts, registers teardown and renders the list', async () => {
  const container = createFakeContainer()
  const sse = createFakeSse()
  let teardownHook = null
  const handle = mountProjectionView(container, {
    api: createMountApi(),
    sse,
    namespaceId: NS,
    registerTeardown: (hook) => {
      teardownHook = hook
    },
    confirm: async () => true,
  })
  await handle.ready

  assert.ok(container.innerHTML.includes('class="workflow-card"'))
  assert.ok(container.innerHTML.includes('data-workflow-id="wf-1"'))
  assert.equal(handle.controller.agentosUrl, 'https://agentos.example.com')
  assert.equal(handle.controller.unsubscribers.length, 4)
  assert.equal(sse.listeners.size, 4)
  assert.equal(typeof teardownHook, 'function')

  handle.teardown()
  handle.teardown()
  assert.equal(sse.listeners.size, 0)
  assert.equal(handle.controller.unsubscribers.length, 0)
  assert.equal(container.listenerCount(), 0)

  teardownHook()
  assert.equal(sse.listeners.size, 0)
})

await scenario('delegated clicks switch mode and trigger lifecycle actions', async () => {
  const container = createFakeContainer()
  const sse = createFakeSse()
  const api = createMountApi()
  let confirmations = 0
  const handle = mountProjectionView(container, {
    api,
    sse,
    namespaceId: NS,
    confirm: async () => {
      confirmations++
      return true
    },
  })
  await handle.ready

  container.dispatch('click', createFakeClick({ mode: 'removed' }))
  await tick()
  assert.equal(handle.controller.mode, 'removed')

  container.dispatch('click', createFakeClick({ action: 'remove', workflowId: 'wf-1' }))
  await tick()
  assert.equal(confirmations, 1)
  assert.ok(api.calls.some((entry) => entry.method === 'DELETE'))

  handle.teardown()
  assert.equal(sse.listeners.size, 0)
  assert.equal(container.listenerCount(), 0)
})

await scenario('owns and closes an auto-created SseClient on teardown', async () => {
  class FakeSseClient {
    static instances = []
    constructor(url) {
      this.url = url
      this.listeners = new Map()
      this.closed = false
      FakeSseClient.instances.push(this)
    }
    on(event, handler) {
      if (!this.listeners.has(event)) this.listeners.set(event, new Set())
      this.listeners.get(event).add(handler)
      return () => this.listeners.get(event)?.delete(handler)
    }
    connect() {
      this.connected = true
      return this
    }
    close() {
      this.closed = true
      this.listeners.clear()
    }
  }
  const container = createFakeContainer()
  const handle = mountProjectionView(container, {
    api: createMountApi(),
    SseClient: FakeSseClient,
    namespaceId: NS,
    confirm: async () => true,
  })
  await handle.ready
  const created = FakeSseClient.instances.at(-1)
  assert.ok(created.url.includes('/api/factory/workflows/stream?namespaceId='))
  assert.equal(handle.controller.ownsSse, true)
  assert.equal(created.listeners.size, 4)

  handle.teardown()
  assert.equal(created.closed, true)
  assert.equal(created.listeners.size, 0)
})

await scenario('teardown stops reacting to late SSE deliveries', async () => {
  const ref = { value: 'existing' }
  const { controller } = createSseController(ref)
  const sse = createFakeSse()
  controller.sse = sse
  controller.attachSse()
  await controller.load('active')
  assert.equal(sse.listeners.size, 4)
  controller.teardown()
  assert.equal(sse.listeners.size, 0)
  // Late delivery after teardown must be a no-op.
  controller.handleSseEvent('workflow-projection-purged', { workflowId: 'wf-1' })
  await tick()
  assert.equal(controller.active.has('wf-1'), true)
})

await scenario('mount rejects a missing container', () => {
  assert.throws(() => mountProjectionView(null, {}), TypeError)
})

await scenario('exposes the controller class and factory consistently', () => {
  const controller = createProjectionController({ namespaceId: NS })
  assert.ok(controller instanceof ProjectionController)
  const state = controller.getState()
  assert.equal(state.mode, 'active')
  assert.equal(state.sseListeners, 0)
})

// ---------------------------------------------------------------------------

console.log(`\nResult: ${passed} passed, ${failed} failed`)
process.exit(failed ? 1 : 0)
