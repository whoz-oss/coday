/**
 * Contract & unit tests for the AgentOS runtime adapter and the
 * AgentRuntimeGateway isolation (Migration Phase 2).
 *
 * Everything runs against the generated operational bundle
 * (`factory/runtime/factory-operational.mjs`), with a fake `fetch` standing in
 * for AgentOS. No network, no AgentOS instance.
 *
 * Usage : node factory/tests/test-agentos-runtime-adapter.mjs
 * Exit code : 0 = all cases pass, 1 = at least one failure.
 */

import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { fileURLToPath, pathToFileURL } from 'node:url'

const bundle = await import(pathToFileURL(fileURLToPath(new URL('../runtime/factory-operational.mjs', import.meta.url))).href)

let passed = 0
let failed = 0

async function test(name, fn) {
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

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

let _id = 0
const ev = (type, extra = {}) => ({ id: `e${++_id}`, type, ...extra })
const status = (s) => ev('CaseStatusEvent', { status: s })

function jsonResponse(body, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
    text: async () => (typeof body === 'string' ? body : JSON.stringify(body)),
  }
}

/**
 * Fake AgentOS transport. `eventSnapshots` are returned in order by successive
 * `GET /api/case-events/by-parentId/...` calls, clamped to the last snapshot.
 */
function makeFakeRuntime({
  agents = [],
  integrations = [],
  eventSnapshots = [],
  caseId = 'case-1',
  agentsError = null,
  eventsError = null,
  messageGate = null,
} = {}) {
  const calls = []
  let eventIndex = 0

  const fetchImpl = async (url, init = {}) => {
    const method = (init.method ?? 'GET').toUpperCase()
    const u = String(url)
    const body = init.body ? JSON.parse(init.body) : undefined
    calls.push({ method, url: u, body, headers: init.headers ?? {} })

    if (method === 'POST' && u.endsWith('/api/cases')) {
      return jsonResponse({ id: caseId, namespaceId: body?.namespaceId, title: body?.title })
    }
    if (method === 'POST' && /\/api\/cases\/[^/]+\/messages$/.test(u)) {
      if (messageGate) await messageGate
      return jsonResponse({}, 200)
    }
    if (method === 'POST' && /\/api\/cases\/[^/]+\/kill$/.test(u)) return jsonResponse({}, 200)
    if (method === 'POST' && /\/api\/cases\/[^/]+\/interrupt$/.test(u)) return jsonResponse({}, 200)
    if (method === 'PUT' && /\/internal\/factory\/cases\/[^/]+\/step-result-binding$/.test(u)) {
      return jsonResponse({}, 200)
    }
    if (method === 'GET' && u.includes('/api/case-events/by-parentId/')) {
      if (eventsError) throw new Error(eventsError)
      const snapshot = eventSnapshots.length
        ? eventSnapshots[Math.min(eventIndex, eventSnapshots.length - 1)]
        : []
      eventIndex++
      return jsonResponse(snapshot)
    }
    if (method === 'GET' && u.includes('/api/agent-configs/by-parentId/')) {
      if (agentsError) throw new Error(agentsError)
      return jsonResponse(agents)
    }
    if (method === 'GET' && u.includes('/api/integration-configs')) return jsonResponse(integrations)
    if (method === 'GET' && /\/api\/cases\/[^/]+$/.test(u)) return jsonResponse({ id: caseId })
    throw new Error(`unexpected request: ${method} ${u}`)
  }

  return { fetchImpl, calls }
}

function makeAdapter(fake, overrides = {}) {
  return bundle.createAgentOsRuntimeAdapter({
    baseUrl: 'http://fake-agentos',
    userId: 'tester',
    bindingSecret: 'shh',
    fetchImpl: fake.fetchImpl,
    pollIntervalMs: 1,
    sleep: async () => {},
    ...overrides,
  })
}

const messageEvent = (text) => ev('MessageEvent', { actor: { role: 'AGENT' }, content: [{ content: text }] })
const toolOk = (name) => ev('ToolResponseEvent', { toolName: name, success: true })
const toolFail = (name) => ev('ToolResponseEvent', { toolName: name, success: false })

// ---------------------------------------------------------------------------
// A. Bundle exports and adapter shape
// ---------------------------------------------------------------------------

await test('A1 — bundle exports the port factory and adapter factory', () => {
  assert.equal(typeof bundle.createAgentOsRuntimeAdapter, 'function')
  assert.equal(typeof bundle.createAgentOsHttpClient, 'function')
  assert.equal(typeof bundle.getAgentOsRuntimeAdapter, 'function')
})

await test('A2 — adapter implements the AgentRuntimeGateway surface', () => {
  const adapter = makeAdapter(makeFakeRuntime())
  for (const method of [
    'inspectWorker',
    'startExecution',
    'observeExecution',
    'bindResultChannel',
    'answerQuestion',
    'interruptExecution',
    'terminateExecution',
  ]) {
    assert.equal(typeof adapter[method], 'function', `missing port method ${method}`)
  }
  for (const legacy of [
    'createCase',
    'postMessage',
    'bindFactoryStepResult',
    'getCase',
    'listEvents',
    'killCase',
    'listAgents',
    'preflightAgent',
    'listIntegrations',
    'preflightWorkspace',
    'preflightWritableWorkspace',
    'preflightReadOnlyWorkspace',
    'runAgentTurn',
  ]) {
    assert.equal(typeof adapter[legacy], 'function', `missing legacy method ${legacy}`)
  }
})

// ---------------------------------------------------------------------------
// B. Capability inspection
// ---------------------------------------------------------------------------

const agentFixtures = [
  { name: 'Worker', enabled: true, subAgents: [], integrations: { FACTORY: ['submit_step_result'] } },
  { name: 'Off', enabled: false, subAgents: [] },
  { name: 'Boss', enabled: true, subAgents: ['A', 'B'] },
]

await test('B1 — inspectWorker accepts an enabled worker without subAgents', async () => {
  const adapter = makeAdapter(makeFakeRuntime({ agents: agentFixtures }))
  const result = await adapter.inspectWorker('ns', 'Worker')
  assert.equal(result.ok, true)
  assert.equal(result.reason, null)
  assert.equal(result.worker?.name, 'Worker')
})

await test('B2 — inspectWorker rejects missing, disabled and delegating workers', async () => {
  const adapter = makeAdapter(makeFakeRuntime({ agents: agentFixtures }))
  const missing = await adapter.inspectWorker('ns', 'Ghost')
  assert.equal(missing.ok, false)
  assert.match(missing.reason, /introuvable/)
  const disabled = await adapter.inspectWorker('ns', 'Off')
  assert.equal(disabled.ok, false)
  assert.match(disabled.reason, /désactivé/)
  const delegating = await adapter.inspectWorker('ns', 'Boss')
  assert.equal(delegating.ok, false)
  assert.match(delegating.reason, /subAgents=\[A, B\]/)
})

await test('B3 — inspectWorker fails closed when the agent list errors', async () => {
  const adapter = makeAdapter(makeFakeRuntime({ agentsError: 'boom' }))
  const result = await adapter.inspectWorker('ns', 'Worker')
  assert.equal(result.ok, false)
  assert.match(result.reason, /Impossible de lister les agents/)
})

await test('B4 — legacy preflightAgent maps worker to agent', async () => {
  const adapter = makeAdapter(makeFakeRuntime({ agents: agentFixtures }))
  const result = await adapter.preflightAgent('ns', 'Worker')
  assert.equal(result.ok, true)
  assert.equal(result.agent?.name, 'Worker')
  assert.equal('worker' in result, false)
})

// ---------------------------------------------------------------------------
// C. Workspace colocalization
// ---------------------------------------------------------------------------

const workspaceAgent = {
  name: 'Editor',
  integrations: { FACTORY: ['submit_step_result'], FILE_ACCESS: null },
}
const fileAccess = [
  { name: 'FILE_ACCESS', integrationType: 'FILE_ACCESS', parameters: { rootPath: '/repo', readOnly: false } },
]

await test('C1 — preflightWorkspace accepts a colocalized FILE_ACCESS', async () => {
  const adapter = makeAdapter(makeFakeRuntime({ integrations: fileAccess }))
  const result = await adapter.preflightWorkspace('ns', workspaceAgent, '/repo')
  assert.equal(result.ok, true)
  assert.equal(result.rootPath, '/repo')
})

await test('C2 — preflightWorkspace rejects broken colocalization', async () => {
  const adapter = makeAdapter(makeFakeRuntime({ integrations: fileAccess }))
  const result = await adapter.preflightWorkspace('ns', workspaceAgent, '/other')
  assert.equal(result.ok, false)
  assert.match(result.reason, /Colocalisation rompue/)
})

await test('C3 — preflightWorkspace fails closed on REST-invisible integrations', async () => {
  const adapter = makeAdapter(makeFakeRuntime({ integrations: [] }))
  const agent = {
    name: 'Editor',
    integrations: { FACTORY: ['submit_step_result'], DISK: { rootPath: '/x' } },
  }
  const result = await adapter.preflightWorkspace('ns', agent, '/x')
  assert.equal(result.ok, false)
  assert.match(result.reason, /absente\(s\) de l'API/)
})

await test('C4 — preflightWritableWorkspace enforces canonical writable root', async () => {
  const adapter = makeAdapter(makeFakeRuntime({ integrations: fileAccess }), { realpath: (p) => p })
  const agent = {
    name: 'Editor',
    integrations: { QUERY_USER: [], FACTORY: ['submit_step_result'], FILE_ACCESS: null },
  }
  const ok = await adapter.preflightWritableWorkspace('ns', agent, '/repo')
  assert.equal(ok.ok, true)
  assert.equal(ok.integration?.name, 'FILE_ACCESS')

  const readOnly = [
    { name: 'FILE_ACCESS', integrationType: 'FILE_ACCESS', parameters: { rootPath: '/repo', readOnly: true } },
  ]
  const bad = await makeAdapter(makeFakeRuntime({ integrations: readOnly }), { realpath: (p) => p })
    .preflightWritableWorkspace('ns', agent, '/repo')
  assert.equal(bad.ok, false)
})

await test('C5 — preflightReadOnlyWorkspace enforces readOnly:true', async () => {
  const readOnly = [
    { name: 'FILE_ACCESS', integrationType: 'FILE_ACCESS', parameters: { rootPath: '/repo', readOnly: true } },
  ]
  const agent = {
    name: 'Analyst',
    integrations: { QUERY_USER: [], FACTORY: ['submit_step_result'], FILE_ACCESS: null },
  }
  const ok = await makeAdapter(makeFakeRuntime({ integrations: readOnly }), { realpath: (p) => p })
    .preflightReadOnlyWorkspace('ns', agent, '/repo')
  assert.equal(ok.ok, true)

  const bad = await makeAdapter(makeFakeRuntime({ integrations: fileAccess }), { realpath: (p) => p })
    .preflightReadOnlyWorkspace('ns', agent, '/repo')
  assert.equal(bad.ok, false)
})

// ---------------------------------------------------------------------------
// D. Observation: F7, questions, statuses
// ---------------------------------------------------------------------------

await test('D1 — F7 anchors quiescence on the LAST RUNNING', async () => {
  const f7 = [
    status('RUNNING'),
    ev('AgentFinishedEvent'),
    status('IDLE'), // intermediate — must NOT be the verdict
    status('RUNNING'),
    toolOk('FILES__edit'),
    ev('AgentFinishedEvent'),
    messageEvent('done'),
    status('IDLE'),
  ]
  const fake = makeFakeRuntime({ eventSnapshots: [f7] })
  const adapter = makeAdapter(fake)
  const observation = await adapter.observeExecution('case-1', { baselineId: null })
  assert.equal(observation.status, 'finished')
  assert.equal(observation.agentTurns, 2)
  assert.equal(observation.toolCallCount, 1)
  assert.equal(observation.events.length, 8)
  assert.equal(observation.events[1].kind, 'worker_finished')
})

await test('D2 — unanswered question yields pending_question', async () => {
  const events = [status('RUNNING'), ev('QuestionEvent', { question: 'Which repo?' }), status('IDLE')]
  const adapter = makeAdapter(makeFakeRuntime({ eventSnapshots: [events] }))
  const observation = await adapter.observeExecution('case-1', { baselineId: null })
  assert.equal(observation.status, 'pending_question')
  assert.equal(observation.message, 'Which repo?')
})

await test('D3 — answered question yields finished', async () => {
  const question = ev('QuestionEvent', { question: 'Which repo?' })
  const events = [status('RUNNING'), question, ev('AnswerEvent', { questionId: question.id }), status('IDLE')]
  const adapter = makeAdapter(makeFakeRuntime({ eventSnapshots: [events] }))
  const observation = await adapter.observeExecution('case-1', { baselineId: null })
  assert.equal(observation.status, 'finished')
})

await test('D4 — KILLED and ERROR statuses map to killed/case_error', async () => {
  const killed = await makeAdapter(makeFakeRuntime({ eventSnapshots: [[status('RUNNING'), status('KILLED')]] }))
    .observeExecution('case-1', { baselineId: null })
  assert.equal(killed.status, 'killed')
  assert.equal(killed.caseStatus, 'KILLED')
  const errored = await makeAdapter(makeFakeRuntime({ eventSnapshots: [[status('RUNNING'), status('ERROR')]] }))
    .observeExecution('case-1', { baselineId: null })
  assert.equal(errored.status, 'case_error')
})

await test('D5 — failed tools and LLM models are collected', async () => {
  const events = [
    status('RUNNING'),
    ev('AgentRunningEvent', { agentName: 'Worker', llmProvider: 'openai', llmModel: 'gpt-4' }),
    toolOk('FILES__read'),
    toolFail('BASH'),
    toolFail('BASH'),
    ev('AgentFinishedEvent', { agentName: 'Worker', llmProvider: 'openai', llmModel: 'gpt-4' }),
    status('IDLE'),
  ]
  const adapter = makeAdapter(makeFakeRuntime({ eventSnapshots: [events] }))
  const observation = await adapter.observeExecution('case-1', { baselineId: null })
  assert.deepEqual(observation.failedToolCalls, { BASH: 2 })
  assert.equal(observation.toolCallCount, 3)
  assert.deepEqual(observation.llmModels, [{ agentName: 'Worker', llmProvider: 'openai', llmModel: 'gpt-4' }])
})

await test('D6 — start timeout kills the execution', async () => {
  let clock = 0
  const fake = makeFakeRuntime({ eventSnapshots: [[]] })
  const adapter = makeAdapter(fake, { now: () => clock, sleep: async () => { clock += 1000 } })
  const observation = await adapter.observeExecution('case-1', {
    baselineId: null,
    startTimeoutMs: 100,
    workTimeoutMs: 100,
  })
  assert.equal(observation.status, 'start_timeout')
  assert.equal(observation.killedByBudget, true)
  assert.equal(fake.calls.some((c) => /\/kill$/.test(c.url)), true)
})

await test('D7 — work timeout kills the execution and keeps turn metrics', async () => {
  let clock = 0
  const fake = makeFakeRuntime({ eventSnapshots: [[status('RUNNING'), toolFail('BASH')]] })
  const adapter = makeAdapter(fake, { now: () => clock, sleep: async () => { clock += 1000 } })
  const observation = await adapter.observeExecution('case-1', {
    baselineId: null,
    startTimeoutMs: 100,
    workTimeoutMs: 100,
  })
  assert.equal(observation.status, 'work_timeout')
  assert.equal(observation.killedByBudget, true)
  assert.deepEqual(observation.failedToolCalls, { BASH: 1 })
  assert.equal(fake.calls.some((c) => /\/kill$/.test(c.url)), true)
})

await test('D8 — listEvents error surfaces as error', async () => {
  const adapter = makeAdapter(makeFakeRuntime({ eventsError: 'network down' }))
  const observation = await adapter.observeExecution('case-1', { baselineId: null, startTimeoutMs: 100 })
  assert.equal(observation.status, 'error')
  assert.match(observation.message, /network down/)
})

await test('D9 — slicing anchors on the baseline event id', async () => {
  const baseline = status('IDLE')
  const events = [baseline, status('RUNNING'), status('IDLE')]
  const adapter = makeAdapter(makeFakeRuntime({ eventSnapshots: [events] }))
  const observation = await adapter.observeExecution('case-1', { baselineId: baseline.id })
  assert.equal(observation.anchored, true)
  assert.equal(observation.events.length, 2)
})

await test('D10 — unanchored baseline is reported, not ignored', async () => {
  const adapter = makeAdapter(makeFakeRuntime({ eventSnapshots: [[status('RUNNING'), status('IDLE')]] }))
  const observation = await adapter.observeExecution('case-1', { baselineId: 'missing-id' })
  assert.equal(observation.anchored, false)
})

// ---------------------------------------------------------------------------
// E. runAgentTurn lifecycle (A5)
// ---------------------------------------------------------------------------

await test('E1 — runAgentTurn posts then observes, publishing the active case', async () => {
  const turn = [
    status('RUNNING'),
    toolOk('FILES__read'),
    toolFail('BASH'),
    ev('AgentSelectedEvent', { agentName: 'Worker' }),
    ev('AgentFinishedEvent', { agentName: 'Worker' }),
    messageEvent('All done.'),
    status('IDLE'),
  ]
  let release
  const gate = new Promise((resolve) => { release = resolve })
  // Hold the message POST so we can observe the active-case publication.
  const gated = makeFakeRuntime({
    eventSnapshots: [[status('IDLE')], turn],
    messageGate: gate,
  })
  const gatedAdapter = makeAdapter(gated)

  const pending = gatedAdapter.runAgentTurn('case-1', 'Worker', 'do it')
  await new Promise((resolve) => setImmediate(resolve))
  assert.equal(bundle.getActiveCaseIds().includes('case-1'), true, 'case must be active before postMessage returns')
  release()
  const observation = await pending
  assert.equal(bundle.getActiveCaseIds().includes('case-1'), false, 'case must be released on exit')

  assert.equal(observation.status, 'finished')
  assert.equal(observation.caseStatus, 'IDLE')
  assert.equal(observation.message, 'All done.')
  assert.deepEqual(observation.agentsSelected, ['Worker'])
  assert.equal(observation.agentTurns, 1)
  assert.equal(observation.toolCallCount, 2)
  assert.deepEqual(observation.failedToolCalls, { BASH: 1 })

  const post = gated.calls.find((c) => /\/messages$/.test(c.url))
  assert.equal(post?.body?.content, '@Worker do it')
})

await test('E2 — runAgentTurn refuses a busy case without posting', async () => {
  const fake = makeFakeRuntime({ eventSnapshots: [[status('RUNNING')]] })
  const adapter = makeAdapter(fake)
  const observation = await adapter.runAgentTurn('case-1', 'Worker', 'do it')
  assert.equal(observation.status, 'case_busy')
  assert.equal(fake.calls.some((c) => /\/messages$/.test(c.url)), false)
})

await test('E3 — postMessage failure kills the case and releases it', async () => {
  const fake = makeFakeRuntime({ eventSnapshots: [[status('IDLE')]] })
  const original = fake.fetchImpl
  const failing = {
    fetchImpl: async (url, init) => {
      if (String(url).endsWith('/messages')) throw new Error('post failed')
      return original(url, init)
    },
    calls: fake.calls,
  }
  const adapter = makeAdapter(failing)
  const observation = await adapter.runAgentTurn('case-1', 'Worker', 'do it')
  assert.equal(observation.status, 'error')
  assert.match(observation.message, /post failed/)
  assert.equal(failing.calls.some((c) => /\/kill$/.test(c.url)), true)
  assert.equal(bundle.getActiveCaseIds().includes('case-1'), false)
})

// ---------------------------------------------------------------------------
// F. Gateway execution methods
// ---------------------------------------------------------------------------

await test('F1 — startExecution creates a case, posts and returns its id', async () => {
  const fake = makeFakeRuntime({ caseId: 'case-new' })
  const adapter = makeAdapter(fake)
  const executionId = await adapter.startExecution({ namespaceId: 'ns', workerName: 'Worker', brief: 'go' })
  assert.equal(executionId, 'case-new')
  assert.equal(bundle.getActiveCaseIds().includes('case-new'), true)
  assert.equal(fake.calls.some((c) => c.method === 'POST' && c.url.endsWith('/api/cases')), true)
  const post = fake.calls.find((c) => /\/messages$/.test(c.url))
  assert.equal(post?.body?.content, '@Worker go')
})

await test('F2 — startExecution reuses a provided caseId', async () => {
  const fake = makeFakeRuntime()
  const adapter = makeAdapter(fake)
  const executionId = await adapter.startExecution({
    namespaceId: 'ns',
    workerName: 'Worker',
    brief: 'go',
    caseId: 'existing',
  })
  assert.equal(executionId, 'existing')
  assert.equal(fake.calls.some((c) => c.method === 'POST' && c.url.endsWith('/api/cases')), false)
})

await test('F3 — terminateExecution kills and clears the active case', async () => {
  const fake = makeFakeRuntime({ caseId: 'case-term' })
  const adapter = makeAdapter(fake)
  await adapter.startExecution({ namespaceId: 'ns', workerName: 'Worker', brief: 'go' })
  await adapter.terminateExecution('case-term')
  assert.equal(fake.calls.some((c) => /\/kill$/.test(c.url)), true)
  assert.equal(bundle.getActiveCaseIds().includes('case-term'), false)
})

await test('F4 — answerQuestion posts an answer linked to the question', async () => {
  const fake = makeFakeRuntime()
  const adapter = makeAdapter(fake)
  await adapter.answerQuestion('case-1', 'q-42', 'use the main repo')
  const post = fake.calls.find((c) => /\/messages$/.test(c.url))
  assert.equal(post?.body?.answerToEventId, 'q-42')
  assert.equal(post?.body?.content, 'use the main repo')
})

await test('F5 — interruptExecution posts the interrupt endpoint', async () => {
  const fake = makeFakeRuntime()
  const adapter = makeAdapter(fake)
  await adapter.interruptExecution('case-1')
  assert.equal(fake.calls.some((c) => /\/interrupt$/.test(c.url)), true)
})

await test('F6 — bindResultChannel requires secret and PUTs the binding', async () => {
  const fake = makeFakeRuntime()
  const adapter = makeAdapter(fake)
  await adapter.bindResultChannel('case-1', { attemptId: 'a', token: 't' })
  const put = fake.calls.find((c) => c.method === 'PUT')
  assert.match(put?.url ?? '', /\/internal\/factory\/cases\/case-1\/step-result-binding$/)
  assert.equal(put?.headers['x-factory-agentos-secret'], 'shh')

  const noSecret = makeAdapter(makeFakeRuntime(), { bindingSecret: '' })
  await assert.rejects(() => noSecret.bindResultChannel('case-1', {}), /FACTORY_AGENTOS_BINDING_SECRET is required/)
})

await test('F7 — HTTP non-2xx is surfaced with the endpoint', async () => {
  const failing = { fetchImpl: async () => jsonResponse('nope', 500), calls: [] }
  const adapter = makeAdapter(failing)
  await assert.rejects(() => adapter.getCase('case-1'), /HTTP 500/)
})

// ---------------------------------------------------------------------------
// G. Legacy binding & isolation
// ---------------------------------------------------------------------------

await test('G1 — lib/agentos.mjs delegates to the operational bundle', async () => {
  const facade = await import(pathToFileURL(fileURLToPath(new URL('../lib/agentos.mjs', import.meta.url))).href)
  for (const name of [
    'createCase',
    'postMessage',
    'bindFactoryStepResult',
    'getCase',
    'listEvents',
    'killCase',
    'listAgents',
    'preflightAgent',
    'listIntegrations',
    'preflightWorkspace',
    'preflightWritableWorkspace',
    'preflightReadOnlyWorkspace',
    'runAgentTurn',
  ]) {
    assert.equal(facade[name], bundle[name], `facade ${name} must be the bundle function identity`)
  }
})

await test('G2 — the facade is stateless (no endpoint, no DTO, no logic)', async () => {
  const source = await readFile(new URL('../lib/agentos.mjs', import.meta.url), 'utf8')
  assert.doesNotMatch(source, /\/api\//)
  assert.doesNotMatch(source, /\bCaseEvent\b|\bAgentFinishedEvent\b|\bQuestionEvent\b|\bAgentConfig\b|\bIntegrationConfig\b/)
  assert.doesNotMatch(source, /\bfetch\s*\(/)
  assert.match(source, /from '\.\.\/runtime\/factory-operational\.mjs'/)
})

await test('G3 — no AgentOS DTO or endpoint leaks outside adapters/agentos', async () => {
  const files = [
    '../src/entrypoints/factory-operational.ts',
    '../src/ports/agent-runtime-gateway.ts',
    '../src/lib/active-case.ts',
    '../src/lib/registry.ts',
    '../src/application/shutdown.ts',
    '../src/adapters/process-shutdown.ts',
    '../src/ports/case-terminator.ts',
  ]
  for (const file of files) {
    const source = await readFile(new URL(file, import.meta.url), 'utf8')
    assert.doesNotMatch(source, /\/api\/cases|\/api\/case-events|\/internal\/factory/)
    assert.doesNotMatch(source, /\bCaseEvent\b|\bAgentFinishedEvent\b|\bQuestionEvent\b|\bAgentConfig\b|\bIntegrationConfig\b/)
  }
})

console.log(`\nRésultat : ${passed} passé(s), ${failed} échoué(s)`)
process.exit(failed > 0 ? 1 : 0)
