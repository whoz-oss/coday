/**
 * Factory Cockpit — Wave 2 (salve B) forge-cockpit offline test suite.
 *
 * Standalone, no browser, no network, no external dependency:
 *
 *   $ node factory/tests/test-cockpit-forge.mjs
 *
 * Covers:
 *   A. forge-activity.mjs  — SSE folding, streaming text, terminal status,
 *                            idempotent disconnect and mount/unmount leaks;
 *   B. delivery-panel.mjs  — stage calculation, URL sanitization, operations
 *                            and mount/trigger lifecycle;
 *   C. forge-cockpit.mjs   — 4-screen navigation, run mapping, G1 decision
 *                            payload (no actorId/authorityId) and lifecycle;
 *   D. app.mjs wiring       — additive /forge mount hook + namespace parsing.
 *
 * Exit code 0 = all pass, 1 = at least one failure.
 */

import assert from 'node:assert/strict'

import {
  ForgeActivityStream,
  buildFeedItems,
  mountForgeActivity,
  renderForgeActivity,
  statusLabel,
} from '../dashboard/js/components/forge-activity.mjs'
import {
  DELIVERY_OPERATIONS,
  DELIVERY_STAGES,
  mountDeliveryPanel,
  renderDeliveryPanel,
  stageIndex,
  stageItems,
  trustedUrl,
} from '../dashboard/js/components/delivery-panel.mjs'
import {
  ForgeCockpitController,
  STEPS,
  TONES,
  US_STEPS,
  buildG1DecisionBody,
  deriveScreen,
  headOf,
  mapForgeRunToEpicRun,
  mapStoryToStoryRun,
  mount,
  renderForgeCockpit,
  stateOf,
} from '../dashboard/js/views/forge-cockpit.mjs'
import { SseClient } from '../dashboard/js/services/sse-client.mjs'
import { createRouter, resolveNamespaceId } from '../dashboard/js/app.mjs'

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

const tick = () => new Promise((resolve) => setImmediate(resolve))
const clone = (value) => JSON.parse(JSON.stringify(value))

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

/** DOM-event double: exposes only what the delegated listeners use. */
function closestTarget(map) {
  return {
    closest: (selector) => (map[selector] ? { dataset: map[selector] } : null),
  }
}

class FakeElement {
  constructor() {
    this.innerHTML = ''
    this.listeners = new Map()
  }

  addEventListener(type, handler) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set())
    this.listeners.get(type).add(handler)
  }

  removeEventListener(type, handler) {
    this.listeners.get(type)?.delete(handler)
  }

  dispatch(type, event) {
    for (const handler of [...(this.listeners.get(type) ?? [])]) handler(event)
  }

  listenerCount() {
    let total = 0
    for (const handlers of this.listeners.values()) total += handlers.size
    return total
  }
}

/** Container whose `querySelector` resolves the sub-component hosts by markup. */
class FakeContainer extends FakeElement {
  querySelector(selector) {
    if (selector === '[data-forge-activity-host]' && this.innerHTML.includes('data-forge-activity-host')) {
      this._activityHost ??= new FakeElement()
      return this._activityHost
    }
    if (selector === '[data-forge-delivery-host]' && this.innerHTML.includes('data-forge-delivery-host')) {
      this._deliveryHost ??= new FakeElement()
      return this._deliveryHost
    }
    return null
  }
}

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

const runDto = {
  schemaVersion: '1',
  runId: 'epic-run-1',
  runType: 'EpicRun',
  workflow: 'forge-bmad',
  workItem: { id: 'EPIC-1', kind: 'epic' },
  roots: { repoRoot: '/repo' },
  startedAt: '2026-09-26T10:00:00.000Z',
  status: 'waiting_human',
  gates: [
    {
      gate: 'G1',
      attempt: 1,
      status: 'waiting_human',
      policyVersion: 'forge-g1-human-v1',
      evidenceSetHash: 'sha256:abc',
      decision: null,
    },
  ],
  stories: [
    {
      runId: 'story-run-1',
      ordinal: 0,
      status: 'finished',
      workItem: { id: 'US-1', kind: 'story' },
      executions: [
        {
          executionId: 'exec-1',
          caseId: 'case-123',
          runtime: 'agentos',
          role: 'analyst',
          agentName: 'analyst',
          status: 'finished',
          outcome: 'planned',
          analysisValidation: { status: 'valid', code: null },
          observedAt: '2026-09-26T10:05:00.000Z',
        },
      ],
      edits: [],
      oracleCampaigns: [],
    },
  ],
}

const deliverySnapshot = {
  deliveryId: 'epic-run-1-delivery',
  namespaceId: NS,
  workflowId: 'epic-run-1',
  environmentId: 'env',
  environmentHash: 'hash',
  parentCaseId: 'case-123',
  runtimeId: 'agentos',
  branch: 'main',
  baseCommit: 'base',
  headCommit: 'head',
  stage: 'release-approved',
  revision: 4,
  git: {
    checkpoint: { commit: 'c', diffHash: 'd' },
    push: { headCommit: 'head' },
    pullRequest: { id: '42', url: 'https://github.com/acme/repo/pull/42', draft: false, state: 'open' },
  },
  artifact: { state: 'succeeded' },
  release: { state: 'succeeded' },
  deployment: { state: 'pending' },
  verification: { state: 'pending' },
  blockers: [{ code: 'AWAITING_APPROVAL', message: 'en attente' }],
  deliveryOperations: [
    {
      operationId: 'op-1',
      kind: 'deployment',
      state: 'succeeded',
      targetRef: { targetId: 'prod' },
      attempt: 1,
      requestedAt: '2026-09-26T10:00:00.000Z',
    },
  ],
  unresolvedIndeterminate: [],
  rollbackRequests: [
    {
      rollbackRequestId: 'rb-1',
      status: 'requested',
      targetId: 'prod',
      reasonCode: 'INCIDENT',
      requestedAt: '2026-09-26T10:00:00.000Z',
    },
  ],
}

function createForgeApi(options = {}) {
  const calls = { get: [], post: [] }
  return {
    calls,
    async get(path, requestOptions = {}) {
      calls.get.push({ path, options: requestOptions })
      if (requestOptions.signal?.aborted) {
        const error = new Error('aborted')
        error.name = 'AbortError'
        throw error
      }
      if (path.startsWith('/api/factory/forge/runs')) return [clone(options.run ?? runDto)]
      if (path.startsWith('/api/factory/workstreams')) return [{ slug: 'ws-1', name: 'WS 1', status: 'planning' }]
      if (path.includes('/executions')) return clone((options.run ?? runDto).stories[0].executions)
      if (path.includes('/oracles')) return clone((options.run ?? runDto).stories[0].oracleCampaigns)
      if (path.includes('/edits')) return clone((options.run ?? runDto).stories[0].edits)
      if (path.includes('/delivery')) return clone(deliverySnapshot)
      throw new Error(`unexpected path ${path}`)
    },
    async post(path, body, requestOptions = {}) {
      calls.post.push({ path, body, options: requestOptions })
      return { data: { ok: true } }
    },
  }
}

// ---------------------------------------------------------------------------
// A. forge-activity.mjs
// ---------------------------------------------------------------------------

console.log('\nforge-activity.mjs')

await scenario('statusLabel() maps the AgentOS case statuses', () => {
  assert.equal(statusLabel('RUNNING'), 'En cours')
  assert.equal(statusLabel('IDLE'), 'En attente')
  assert.equal(statusLabel('PENDING'), 'Démarrage')
  assert.equal(statusLabel('KILLED'), 'Arrêté')
  assert.equal(statusLabel('ERROR'), 'Erreur')
  assert.equal(statusLabel('UNKNOWN'), 'UNKNOWN')
})

await scenario('buildFeedItems() folds the known event types', () => {
  const items = buildFeedItems([
    {
      id: 'm1',
      type: 'MessageEvent',
      actor: { role: 'AGENT', displayName: 'Analyst' },
      content: [{ content: 'hello' }],
    },
    { id: 't1', type: 'ToolRequestEvent', toolName: 'grep' },
    { id: 't2', type: 'ToolResponseEvent', toolName: 'grep', durationMs: 1500 },
    { id: 'i1', type: 'IntentionGeneratedEvent', toolName: 'edit', intention: 'change config' },
    { id: 'a1', type: 'AgentRunningEvent', agentName: 'analyst' },
    { id: 'x1', type: 'UnknownEvent' },
  ])
  assert.deepEqual(
    items.map((item) => item.kind),
    ['message', 'tool', 'tool', 'intention', 'status']
  )
  assert.equal(items[0].role, 'Analyst')
  assert.equal(items[0].text, 'hello')
  assert.equal(items[2].duration, '1.5s')
  assert.ok(items[3].text.includes('change config'))
})

await scenario('renderForgeActivity() escapes hostile content', () => {
  const html = renderForgeActivity({
    events: [
      { id: 'm1', type: 'MessageEvent', actor: { role: 'USER' }, content: [{ content: '<script>alert(1)</script>' }] },
    ],
    caseStatus: 'RUNNING',
    connected: true,
  })
  assert.ok(html.includes('data-forge-activity="true"'))
  assert.ok(html.includes('&lt;script&gt;alert(1)&lt;/script&gt;'))
  assert.ok(!html.includes('<script>alert(1)</script>'))
  assert.ok(html.includes('data-activity-status="RUNNING"'))
})

await scenario('ForgeActivityStream folds TextChunk, status and events', () => {
  const stream = new ForgeActivityStream({ EventSource: MockEventSource })
  stream.connect('case-1')
  const mock = MockEventSource.instances.at(-1)

  mock.dispatch('TextChunkEvent', JSON.stringify({ type: 'TextChunkEvent', id: 't1', chunk: 'Hello' }))
  mock.dispatch('TextChunkEvent', JSON.stringify({ type: 'TextChunkEvent', id: 't2', chunk: ' world' }))
  mock.dispatch('CaseStatusEvent', JSON.stringify({ type: 'CaseStatusEvent', id: 's1', status: 'RUNNING' }))
  mock.dispatch('MessageEvent', JSON.stringify({ type: 'MessageEvent', id: 'm1', content: [{ content: 'hi' }] }))
  mock.dispatch('ToolRequestEvent', JSON.stringify({ type: 'ToolRequestEvent', id: 'tr1', toolName: 'grep' }))
  mock.dispatch(
    'IntentionGeneratedEvent',
    JSON.stringify({ type: 'IntentionGeneratedEvent', id: 'i1', toolName: 'edit' })
  )

  const state = stream.getState()
  assert.equal(state.activeCaseId, 'case-1')
  assert.equal(state.caseStatus, 'RUNNING')
  assert.equal(state.streamingText, 'Hello world')
  assert.equal(state.connected, true)
  assert.deepEqual(
    state.events.map((event) => event.type),
    ['CaseStatusEvent', 'MessageEvent', 'ToolRequestEvent', 'IntentionGeneratedEvent']
  )

  stream.close()
  assert.equal(mock.closed, true)
})

await scenario('ForgeActivityStream clears streaming text on AgentFinishedEvent', () => {
  const stream = new ForgeActivityStream({ EventSource: MockEventSource })
  stream.connect('case-finish')
  const mock = MockEventSource.instances.at(-1)
  mock.dispatch('TextChunkEvent', JSON.stringify({ type: 'TextChunkEvent', id: 't1', chunk: 'partial' }))
  assert.equal(stream.getState().streamingText, 'partial')
  mock.dispatch('AgentFinishedEvent', JSON.stringify({ type: 'AgentFinishedEvent', id: 'a1', agentName: 'A' }))
  assert.equal(stream.getState().streamingText, '')
  stream.close()
})

await scenario('ForgeActivityStream deduplicates events by id', () => {
  const stream = new ForgeActivityStream({ EventSource: MockEventSource })
  stream.connect('case-dedup')
  const mock = MockEventSource.instances.at(-1)
  mock.dispatch('MessageEvent', JSON.stringify({ type: 'MessageEvent', id: 'dup', content: [{ content: 'a' }] }))
  mock.dispatch('MessageEvent', JSON.stringify({ type: 'MessageEvent', id: 'dup', content: [{ content: 'b' }] }))
  assert.equal(stream.getState().events.filter((event) => event.id === 'dup').length, 1)
  stream.close()
})

await scenario('ForgeActivityStream closes on a terminal CaseStatusEvent', () => {
  const stream = new ForgeActivityStream({ EventSource: MockEventSource })
  stream.connect('case-killed')
  const mock = MockEventSource.instances.at(-1)
  mock.dispatch('CaseStatusEvent', JSON.stringify({ type: 'CaseStatusEvent', id: 's1', status: 'KILLED' }))
  assert.equal(stream.getState().caseStatus, 'KILLED')
  assert.equal(stream.getState().connected, false)
  assert.equal(mock.closed, true)
  assert.equal(mock.listenerCount(), 0)
  stream.close()
})

await scenario('ForgeActivityStream reconnects to a new case without leaking', () => {
  const stream = new ForgeActivityStream({ EventSource: MockEventSource })
  stream.connect('case-a')
  const first = MockEventSource.instances.at(-1)
  stream.connect('case-b')
  assert.equal(first.closed, true)
  assert.equal(first.listenerCount(), 0)
  assert.equal(stream.getState().activeCaseId, 'case-b')
  stream.close()
})

await scenario('disconnect() is idempotent and resets state', () => {
  const stream = new ForgeActivityStream({ EventSource: MockEventSource })
  stream.connect('case-reset')
  const mock = MockEventSource.instances.at(-1)
  mock.dispatch('MessageEvent', JSON.stringify({ type: 'MessageEvent', id: 'm1', content: [{ content: 'x' }] }))
  stream.disconnect()
  stream.disconnect()
  const state = stream.getState()
  assert.equal(mock.closed, true)
  assert.equal(mock.listenerCount(), 0)
  assert.equal(state.activeCaseId, null)
  assert.deepEqual(state.events, [])
  assert.equal(state.caseStatus, 'IDLE')
  assert.equal(state.streamingText, '')
  assert.equal(state.sseListeners, 0)
  stream.close()
})

await scenario('mountForgeActivity() renders, streams and unmounts leak-free', () => {
  const host = new FakeElement()
  const feed = mountForgeActivity(host, { caseId: 'case-1', EventSource: MockEventSource })
  const mock = MockEventSource.instances.at(-1)

  assert.ok(host.innerHTML.includes('data-forge-activity="true"'))
  mock.dispatch('TextChunkEvent', JSON.stringify({ type: 'TextChunkEvent', id: 't1', chunk: 'Bonjour' }))
  mock.dispatch('ToolRequestEvent', JSON.stringify({ type: 'ToolRequestEvent', id: 'tr1', toolName: 'grep' }))
  assert.equal(feed.getStatus(), 'PENDING')
  assert.ok(host.innerHTML.includes('Bonjour'))
  assert.ok(host.innerHTML.includes('grep'))

  feed.unmount()
  assert.equal(mock.closed, true)
  assert.equal(mock.listenerCount(), 0)
  assert.equal(host.listenerCount(), 0)
  assert.equal(host.innerHTML, '')
})

await scenario('mountForgeActivity() validates its inputs', () => {
  assert.throws(() => mountForgeActivity(null, { caseId: 'x' }), TypeError)
  assert.throws(() => mountForgeActivity(new FakeElement(), {}), TypeError)
})

// ---------------------------------------------------------------------------
// B. delivery-panel.mjs
// ---------------------------------------------------------------------------

console.log('\ndelivery-panel.mjs')

await scenario('DELIVERY_STAGES / DELIVERY_OPERATIONS are the documented sets', () => {
  assert.deepEqual(
    [...DELIVERY_STAGES],
    ['implementation-ready', 'artifact-ready', 'release-approved', 'deployed', 'production-verified']
  )
  assert.deepEqual([...DELIVERY_OPERATIONS], ['checkpoint', 'push', 'pull-request', 'promote'])
})

await scenario('trustedUrl() accepts only https GitHub URLs', () => {
  assert.equal(trustedUrl('https://github.com/acme/repo/pull/1'), 'https://github.com/acme/repo/pull/1')
  assert.equal(trustedUrl('https://www.github.com/acme/repo/pull/1'), 'https://www.github.com/acme/repo/pull/1')
  assert.equal(trustedUrl('http://github.com/acme/repo/pull/1'), null)
  assert.equal(trustedUrl('https://evil.com/acme/repo/pull/1'), null)
  assert.equal(trustedUrl('https://github.com.evil.com/x'), null)
  assert.equal(trustedUrl('javascript:alert(1)'), null)
  assert.equal(trustedUrl('not-a-url'), null)
  assert.equal(trustedUrl(null), null)
})

await scenario('stageIndex()/stageItems() compute the forward lifecycle', () => {
  assert.equal(stageIndex('implementation-ready'), 0)
  assert.equal(stageIndex('production-verified'), 4)
  assert.equal(stageIndex('unknown'), -1)
  const items = stageItems({ stage: 'release-approved' })
  assert.deepEqual(
    items.map((item) => (item.current ? 'current' : item.done ? 'done' : 'pending')),
    ['done', 'done', 'current', 'pending', 'pending']
  )
})

await scenario('renderDeliveryPanel() renders the lifecycle, operations and rollbacks', () => {
  const html = renderDeliveryPanel(deliverySnapshot)
  assert.ok(html.includes('data-delivery-panel="true"'))
  assert.ok(html.includes('data-delivery-stage="release-approved"'))
  assert.ok(html.includes('data-delivery-timeline="true"'))
  assert.ok(html.includes('data-operation-id="op-1"'))
  assert.ok(html.includes('data-rollback-id="rb-1"'))
  assert.ok(html.includes('data-delivery-pr="trusted"'))
  assert.ok(html.includes('href="https://github.com/acme/repo/pull/42"'))
  assert.ok(html.includes('data-delivery-op="checkpoint"'))
  assert.ok(html.includes('data-delivery-op="promote"'))
})

await scenario('renderDeliveryPanel() refuses an untrusted pull-request link', () => {
  const snapshot = clone(deliverySnapshot)
  snapshot.git.pullRequest.url = 'http://evil.com/pr/1'
  const html = renderDeliveryPanel(snapshot)
  assert.ok(html.includes('data-delivery-pr="untrusted"'))
  assert.ok(!html.includes('href="http://evil.com'))
})

await scenario('renderDeliveryPanel() flags unresolved indeterminate operations', () => {
  const snapshot = clone(deliverySnapshot)
  snapshot.unresolvedIndeterminate = [{ operationId: 'op-9', kind: 'deployment', state: 'indeterminate' }]
  const html = renderDeliveryPanel(snapshot)
  assert.ok(html.includes('data-delivery-indeterminate="1"'))
  assert.ok(html.includes('Reconciliation requise'))
})

await scenario('renderDeliveryPanel() escapes hostile values and shows empty/loading states', () => {
  const snapshot = clone(deliverySnapshot)
  snapshot.branch = '<script>alert(1)</script>'
  snapshot.deliveryOperations[0].kind = '<img src=x onerror=alert(1)>'
  const html = renderDeliveryPanel(snapshot)
  assert.ok(!html.includes('<script>alert(1)</script>'))
  assert.ok(html.includes('&lt;script&gt;alert(1)&lt;/script&gt;'))
  assert.ok(!html.includes('<img src=x'))
  assert.ok(html.includes('&lt;img src=x onerror=alert(1)&gt;'))

  assert.ok(renderDeliveryPanel(null, { loading: true }).includes('data-delivery-loading="true"'))
  assert.ok(renderDeliveryPanel(null, { error: 'boom' }).includes('data-delivery-error="true"'))
  assert.ok(renderDeliveryPanel(null).includes('data-delivery-empty="true"'))
})

await scenario('mountDeliveryPanel() fetches with attribution and triggers operations', async () => {
  const host = new FakeElement()
  const api = createForgeApi()
  const panel = mountDeliveryPanel(host, {
    workflowId: 'epic-run-1',
    namespaceId: NS,
    caseId: 'case-123',
    apiClient: api,
  })
  await panel.ready

  assert.ok(host.innerHTML.includes('data-delivery-panel="true"'))
  assert.equal(api.calls.get[0].path, '/api/factory/workflows/epic-run-1/delivery')
  assert.deepEqual(api.calls.get[0].options.attribution, { namespaceId: NS, caseId: 'case-123' })

  const result = await panel.trigger('checkpoint')
  assert.equal(result.ok, true)
  assert.equal(api.calls.post[0].path, '/api/factory/workflows/epic-run-1/delivery/checkpoint')
  assert.deepEqual(api.calls.post[0].options.attribution, { namespaceId: NS, caseId: 'case-123' })

  const unknown = await panel.trigger('nope')
  assert.equal(unknown.ok, false)
  assert.equal(unknown.error, 'UNKNOWN_OPERATION')

  panel.unmount()
  assert.equal(host.innerHTML, '')
  assert.equal(host.listenerCount(), 0)
})

await scenario('mountDeliveryPanel() delegated click triggers the operation', async () => {
  const host = new FakeElement()
  const api = createForgeApi()
  const panel = mountDeliveryPanel(host, { workflowId: 'epic-run-1', namespaceId: NS, apiClient: api })
  await panel.ready

  host.dispatch('click', { target: closestTarget({ '[data-delivery-op]': { deliveryOp: 'push' } }) })
  await tick()
  assert.ok(api.calls.post.some((call) => call.path === '/api/factory/workflows/epic-run-1/delivery/push'))
  panel.unmount()
})

await scenario('mountDeliveryPanel() validates its inputs', () => {
  assert.throws(() => mountDeliveryPanel(null, {}), TypeError)
  assert.throws(
    () => mountDeliveryPanel(new FakeElement(), { namespaceId: NS, apiClient: createForgeApi() }),
    TypeError
  )
  assert.throws(
    () => mountDeliveryPanel(new FakeElement(), { workflowId: 'w', apiClient: createForgeApi() }),
    TypeError
  )
  assert.throws(() => mountDeliveryPanel(new FakeElement(), { workflowId: 'w', namespaceId: NS }), TypeError)
})

// ---------------------------------------------------------------------------
// C. forge-cockpit.mjs
// ---------------------------------------------------------------------------

console.log('\nforge-cockpit.mjs')

await scenario('STEPS / US_STEPS keep the 10 / 9 workflow vocabulary', () => {
  assert.equal(STEPS.length, 10)
  assert.deepEqual(
    STEPS.map((step) => step.key),
    ['discovery', 'grooming', 'g1', 'spec', 'g2', 'code', 'g3', 'deploy', 'g4', 'merge']
  )
  assert.equal(US_STEPS.length, 9)
  assert.ok(!US_STEPS.some((step) => step.key === 'discovery'))
  assert.ok(TONES.done && TONES.failed && TONES.human)
})

await scenario('deriveScreen() orders story > epic > workstream > streams', () => {
  assert.equal(deriveScreen({}), 'streams')
  assert.equal(deriveScreen({ ws: 'w' }), 'workstream')
  assert.equal(deriveScreen({ ws: 'w', epic: 'e' }), 'epic')
  assert.equal(deriveScreen({ ws: 'w', epic: 'e', story: 's' }), 'story')
})

await scenario('stateOf()/headOf() read and correct a story position', () => {
  const story = { head: 'code', states: { g1: 'done', spec: 'done', g2: 'done', code: 'running', g3: 'na' } }
  assert.equal(stateOf(story, 'g1'), 'done')
  assert.equal(stateOf(story, 'g3'), 'na')
  assert.equal(stateOf(story, 'unknown'), 'pending')
  assert.equal(headOf(story), 'code')
  const overridden = { head: 'g3', states: { g1: 'done', spec: 'done', g2: 'done', code: 'done', g3: 'na' } }
  assert.equal(headOf(overridden), 'code')
})

await scenario('mapForgeRunToEpicRun()/mapStoryToStoryRun() project the ledger', () => {
  const epic = mapForgeRunToEpicRun(runDto)
  assert.equal(epic.key, 'EPIC-1')
  assert.equal(epic.runId, 'epic-run-1')
  assert.equal(epic.stories.length, 1)
  const story = epic.stories[0]
  assert.equal(story.key, 'US-1')
  assert.equal(story.head, 'g1')
  assert.equal(story.states.g1, 'human')
  assert.equal(story.states.spec, 'done')
  assert.equal(story.states.g2, 'pending')
  assert.equal(story.executions[0].caseId, 'case-123')

  const direct = mapStoryToStoryRun(runDto.stories[0], 'approved')
  assert.equal(direct.states.g1, 'done')
  assert.equal(direct.states.g2, 'done')
  assert.equal(direct.head, 'spec')
})

await scenario('buildG1DecisionBody() never carries actorId/authorityId', () => {
  const body = buildG1DecisionBody({ evidenceSetHash: 'sha256:abc' })
  assert.deepEqual(body, {
    gate: 'G1',
    attempt: 1,
    policyVersion: 'forge-g1-human-v1',
    evidenceSetHash: 'sha256:abc',
    outcome: 'approved',
    reasonCode: 'intent_confirmed',
  })
  assert.ok(!('actorId' in body))
  assert.ok(!('authorityId' in body))
})

await scenario('controller navigates between the four screens', async () => {
  const controller = new ForgeCockpitController({ apiClient: createForgeApi(), namespaceId: NS })
  assert.equal(controller.getState().screen, 'streams')

  controller.openWorkstream('ws-1')
  assert.equal(controller.getState().screen, 'workstream')
  assert.equal(controller.getState().params.ws, 'ws-1')

  controller.openEpic('EPIC-1')
  assert.equal(controller.getState().screen, 'epic')
  assert.equal(controller.getState().params.epic, 'EPIC-1')
  assert.equal(controller.getState().params.story, null)

  controller.openStory('US-1', 'code')
  assert.equal(controller.getState().screen, 'story')
  assert.equal(controller.getState().params.story, 'US-1')
  assert.equal(controller.getState().stepKey, 'code')

  controller.goStreams()
  assert.equal(controller.getState().screen, 'streams')
  assert.deepEqual(controller.getState().params, { ws: null, epic: null, story: null })
  controller.teardown()
})

await scenario('controller loads runs + workstreams and resolves the story case', async () => {
  const api = createForgeApi()
  const controller = new ForgeCockpitController({ apiClient: api, namespaceId: NS })
  await controller.loadRuns()
  await controller.loadWorkstreams()
  controller.openEpic('EPIC-1')
  controller.openStory('US-1')

  const state = controller.getState()
  assert.equal(state.epicRuns.length, 1)
  assert.equal(state.workstream.slug, 'ws-1')
  assert.equal(state.story.key, 'US-1')
  assert.equal(state.storyCaseId, 'case-123')
  assert.equal(state.deliveryWorkflowId, 'epic-run-1')
  assert.equal(state.g1.waitingHuman, true)
  assert.equal(state.g1.evidenceSetHash, 'sha256:abc')
  controller.teardown()
})

await scenario('controller.approveG1() posts the exact G1 body without identity', async () => {
  const api = createForgeApi()
  const controller = new ForgeCockpitController({ apiClient: api, namespaceId: NS })
  await controller.loadRuns()
  controller.openEpic('EPIC-1')

  const result = await controller.approveG1()
  assert.equal(result.ok, true)
  assert.equal(api.calls.post.length, 1)
  const { path, body, options } = api.calls.post[0]
  assert.equal(path, `/api/factory/forge/runs/epic-run-1/gates/G1/decision?namespaceId=${NS}`)
  assert.deepEqual(body, {
    gate: 'G1',
    attempt: 1,
    policyVersion: 'forge-g1-human-v1',
    evidenceSetHash: 'sha256:abc',
    outcome: 'approved',
    reasonCode: 'intent_confirmed',
  })
  assert.ok(!('actorId' in body))
  assert.ok(!('authorityId' in body))
  assert.deepEqual(options.attribution, { namespaceId: NS })
  controller.teardown()
})

await scenario('renderForgeCockpit() escapes hostile run content and renders G1', async () => {
  const hostile = clone(runDto)
  hostile.workflow = '<script>alert(1)</script>'
  const api = createForgeApi({ run: hostile })
  const controller = new ForgeCockpitController({ apiClient: api, namespaceId: NS })
  await controller.loadRuns()
  await controller.loadWorkstreams()
  controller.openEpic('EPIC-1')
  controller.setScreen('workstream', { ws: 'ws-1' })

  const html = renderForgeCockpit(controller.getState())
  assert.ok(!html.includes('<script>alert(1)</script>'))
  assert.ok(html.includes('&lt;script&gt;alert(1)&lt;/script&gt;'))

  controller.openEpic('EPIC-1')
  const epicHtml = renderForgeCockpit(controller.getState())
  assert.ok(epicHtml.includes('data-g1-panel="true"'))
  assert.ok(epicHtml.includes('data-g1-approve="true"'))
  controller.teardown()
})

await scenario('mount() wires the screens, sub-components and unmounts cleanly', async () => {
  const container = new FakeContainer()
  const api = createForgeApi()
  const activityMounts = []
  const activityHandles = []
  const deliveryMounts = []
  const deliveryHandles = []

  const handle = mount(container, {
    namespaceId: NS,
    apiClient: api,
    mountActivity: (host, options) => {
      activityMounts.push(options)
      const h = {
        unmounted: false,
        unmount() {
          this.unmounted = true
          host.innerHTML = ''
        },
      }
      activityHandles.push(h)
      return h
    },
    mountDelivery: (host, options) => {
      deliveryMounts.push(options)
      const h = {
        unmounted: false,
        unmount() {
          this.unmounted = true
          host.innerHTML = ''
        },
      }
      deliveryHandles.push(h)
      return h
    },
  })
  await handle.ready

  assert.ok(container.innerHTML.includes('data-forge-cockpit="true"'))
  assert.ok(container.innerHTML.includes('data-forge-screen="streams"'))
  assert.equal(container.listenerCount(), 1)

  // Delegated click → workstream screen.
  container.dispatch('click', { target: closestTarget({ '[data-ws]': { ws: 'ws-1' } }) })
  assert.equal(handle.getState().screen, 'workstream')

  // Navigate to the story screen: both sub-components mount.
  handle.controller.openEpic('EPIC-1')
  handle.controller.openStory('US-1')
  assert.equal(activityMounts.length, 1)
  assert.equal(activityMounts[0].caseId, 'case-123')
  assert.equal(deliveryMounts.length, 1)
  assert.equal(deliveryMounts[0].workflowId, 'epic-run-1')

  handle.unmount()
  assert.equal(handle.isMounted(), false)
  assert.equal(activityHandles[0].unmounted, true)
  assert.equal(deliveryHandles[0].unmounted, true)
  assert.equal(container.listenerCount(), 0)
  assert.equal(container.innerHTML, '')

  // Idempotent and inert after unmount.
  handle.unmount()
  container.dispatch('click', { target: closestTarget({ '[data-ws]': { ws: 'ws-1' } }) })
  assert.equal(container.innerHTML, '')
})

await scenario('mount() closes the real activity SSE on unmount (zero leak)', async () => {
  const container = new FakeContainer()
  const api = createForgeApi()
  const handle = mount(container, { namespaceId: NS, apiClient: api, EventSource: MockEventSource })
  await handle.ready

  handle.controller.openStory('US-1')
  await tick()

  const mock = MockEventSource.instances.at(-1)
  assert.ok(mock, 'an EventSource should have been opened')
  assert.equal(mock.url, '/api/cases/case-123/events')
  assert.ok(mock.listenerCount() > 0)

  handle.unmount()
  assert.equal(mock.closed, true)
  assert.equal(mock.listenerCount(), 0)
  assert.equal(container.listenerCount(), 0)
  assert.equal(container.innerHTML, '')
})

await scenario('mount() validates its inputs', () => {
  assert.throws(() => mount(null, {}), TypeError)
  assert.throws(() => mount(new FakeContainer(), { apiClient: createForgeApi() }), TypeError)
  assert.throws(() => mount(new FakeContainer(), { namespaceId: NS }), TypeError)
  assert.throws(() => mount(new FakeContainer(), { namespaceId: NS, apiClient: { get: () => {} } }), TypeError)
})

// ---------------------------------------------------------------------------
// D. app.mjs wiring
// ---------------------------------------------------------------------------

console.log('\napp.mjs wiring')

await scenario('resolveNamespaceId() reads ?ns= from search or hash', () => {
  assert.equal(resolveNamespaceId({ location: { search: '?ns=abc', hash: '#/forge' } }), 'abc')
  assert.equal(resolveNamespaceId({ location: { search: '?namespaceId=xyz', hash: '' } }), 'xyz')
  assert.equal(resolveNamespaceId({ location: { search: '', hash: '#/forge?ns=hash-ns' } }), 'hash-ns')
  assert.equal(resolveNamespaceId({ location: { search: '', hash: '#/forge' } }), null)
  assert.equal(resolveNamespaceId(null), null)
})

await scenario('createRouter() invokes the additive onMount hook and tears it down', () => {
  const sections = ['view-runs', 'view-forge'].map((id) => ({ id, classList: { toggle() {} } }))
  const doc = {
    querySelectorAll: (selector) => (selector === '.cockpit-view' ? sections : []),
    getElementById: () => null,
  }
  const listeners = new Map()
  const win = {
    location: { hash: '#/forge', search: '' },
    history: { replaceState() {} },
    addEventListener: (type, handler) => listeners.set(type, handler),
  }

  const mounted = []
  let tornDown = false
  const router = createRouter(win, doc, {
    onMount: (route, ctx) => {
      mounted.push(route)
      if (route !== '/forge') return
      ctx.registerTeardown(() => {
        tornDown = true
      })
    },
  })

  router.mount('/forge')
  assert.deepEqual(mounted, ['/forge'])
  assert.equal(router.getTeardownCount(), 1)
  assert.equal(router.getActiveSectionId(), 'view-forge')

  router.mount('/runs')
  assert.equal(tornDown, true)
  assert.equal(router.getTeardownCount(), 0)
})

// ---------------------------------------------------------------------------

console.log(`\nResult: ${passed} passed, ${failed} failed`)
process.exit(failed ? 1 : 0)
