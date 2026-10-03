/**
 * Factory Cockpit — run-launch launch contract unit tests (vanilla, Node
 * built-in test runner, zero dependencies, zero build step).
 *
 *   node --test factory/dashboard/js/views/run-launch.test.mjs
 *
 * The view is exercised against a minimal fake container (only the DOM surface
 * run-launch touches: `innerHTML` and the delegated listeners) and a recording
 * fake ApiClient. The tests pin the two-step governed contract — materialize a
 * fresh instance with `POST /{id}/start`, then trigger `POST /{id}/run` with
 * `{ namespaceId, ticket, repoRoot }` — plus field validation and navigation.
 */

import test from 'node:test'
import assert from 'node:assert/strict'

import { mountRunLaunchView, buildStartUrl, buildRunUrl, generateWorkflowId } from './run-launch.mjs'

/** Flush the pending microtask queue (async render chains). */
function flush() {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

/** Minimal DOM-ish container with the `addEventListener` surface the view uses. */
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
  }
}

/** Recording fake ApiClient: definitions/namespaces GETs succeed, POSTs are captured. */
function fakeApiClient({ definitions = [{ workflowType: 'feature-session', title: 'Feature session' }], namespaces = [{ id: 'ns-1', name: 'Engineering' }], postError = null, postErrors = {} } = {}) {
  const calls = { get: [], post: [] }
  return {
    calls,
    get(path) {
      calls.get.push(path)
      if (path.startsWith('/api/factory/workflow-definitions')) return Promise.resolve({ items: definitions })
      if (path === '/api/namespaces') return Promise.resolve({ items: namespaces })
      return Promise.resolve(null)
    },
    post(path, body, options) {
      calls.post.push({ path, body, options })
      const error = postErrors[path.endsWith('/start') ? 'start' : path.endsWith('/run') ? 'run' : 'other'] ?? postError
      if (error) return Promise.reject(error)
      return Promise.resolve({ status: 'ACCEPTED' })
    },
  }
}

async function mountWith(container, apiClient, options = {}) {
  return mountRunLaunchView(container, { apiClient, ...options })
}

// ------------------------------------------------------------- URL builders

test('buildStartUrl / buildRunUrl encode the workflow id', () => {
  assert.equal(buildStartUrl('wf-1'), '/api/factory/workflows/wf-1/start')
  assert.equal(buildRunUrl('wf-1'), '/api/factory/workflows/wf-1/run')
  assert.equal(buildStartUrl('a/b'), '/api/factory/workflows/a%2Fb/start')
})

test('generateWorkflowId returns a unique wf- prefixed id', () => {
  const first = generateWorkflowId()
  const second = generateWorkflowId()
  assert.match(first, /^wf-\d+-[a-z0-9]+$/)
  assert.notEqual(first, second)
})

// ------------------------------------------------------------- happy path

test('launching starts a fresh instance then runs it with ticket and repoRoot', async () => {
  const container = fakeContainer()
  const apiClient = fakeApiClient()
  const nav = []
  const handle = await mountWith(container, apiClient, {
    namespaceId: 'ns-1',
    factoryRoot: '/repo',
    ticket: 'JIRA-1',
    controllerRequest: 'Build the requested feature safely.',
    onNavigate: (route, params) => nav.push([route, params]),
  })

  await handle.submit()
  await flush()

  assert.equal(apiClient.calls.post.length, 2, 'expected exactly a start then a run call')
  const [start, run] = apiClient.calls.post
  assert.match(start.path, /^\/api\/factory\/workflows\/wf-[^/]+\/start$/)
  assert.equal(start.body.workflow.workflowType, 'feature-session')
  assert.equal(start.body.workflow.title, 'Run feature-session')
  assert.equal(start.body.workflow.ticket, 'JIRA-1')
  assert.equal(start.body.workflow.workflowId, start.path.split('/')[4])
  assert.equal(start.body.controllerRequest, 'Build the requested feature safely.')
  assert.deepEqual(start.body.execution, {
    namespaceId: 'ns-1',
    runtimeId: 'factory-dashboard',
    kind: 'agentos',
    agentId: 'factory-agent',
  })

  assert.match(run.path, /^\/api\/factory\/workflows\/wf-[^/]+\/run$/)
  // The run targets the same instance materialized by the start call.
  assert.equal(run.path.replace('/run', '/start'), start.path)
  assert.deepEqual(run.body, { namespaceId: 'ns-1', ticket: 'JIRA-1', repoRoot: '/repo' })

  assert.equal(nav.length, 1, 'expected a single navigation to the detail view')
  assert.equal(nav[0][0], '/detail')
  assert.equal(nav[0][1].namespaceId, 'ns-1')
  assert.equal(nav[0][1].workflowId, start.body.workflow.workflowId)
  handle.unmount()
})

test('omitting ticket and repoRoot keeps those fields off the run payload', async () => {
  const container = fakeContainer()
  const apiClient = fakeApiClient()
  const handle = await mountWith(container, apiClient, { namespaceId: 'ns-1', controllerRequest: 'Do the work.' })

  await handle.submit()
  await flush()

  const [start, run] = apiClient.calls.post
  assert.equal('ticket' in start.body.workflow, false)
  assert.deepEqual(run.body, { namespaceId: 'ns-1' })
  handle.unmount()
})

test('an already-existing instance still proceeds to the run call', async () => {
  const container = fakeContainer()
  const conflict = Object.assign(new Error('exists'), { code: 'WORKFLOW_IDENTITY_CONFLICT', status: 409 })
  const apiClient = fakeApiClient({ postErrors: { start: conflict } })
  const handle = await mountWith(container, apiClient, { namespaceId: 'ns-1', controllerRequest: 'Do the work.' })

  await handle.submit()
  await flush()

  assert.equal(apiClient.calls.post.length, 2, 'expected the run call to still happen')
  assert.ok(apiClient.calls.post[1].path.endsWith('/run'))
  handle.unmount()
})

// ------------------------------------------------------------- validation

test('submitting without a selected definition reports an error and does not post', async () => {
  const container = fakeContainer()
  const apiClient = fakeApiClient({ definitions: [] })
  const handle = await mountWith(container, apiClient, { namespaceId: 'ns-1', controllerRequest: 'Do the work.' })

  await handle.submit()
  await flush()

  assert.equal(apiClient.calls.post.length, 0)
  assert.equal(handle.getState().submitError, 'Sélectionnez une définition de workflow.')
  handle.unmount()
})

test('submitting without a namespace reports an error and does not post', async () => {
  const container = fakeContainer()
  const apiClient = fakeApiClient()
  const handle = await mountWith(container, apiClient, { namespaceId: '', controllerRequest: 'Do the work.' })

  await handle.submit()
  await flush()

  assert.equal(apiClient.calls.post.length, 0)
  assert.equal(handle.getState().submitError, 'Le namespace est requis.')
  handle.unmount()
})

test('submitting without an engineer request reports an error and does not post', async () => {
  const container = fakeContainer()
  const apiClient = fakeApiClient()
  const handle = await mountWith(container, apiClient, { namespaceId: 'ns-1', controllerRequest: '   ' })

  await handle.submit()
  await flush()

  assert.equal(apiClient.calls.post.length, 0)
  assert.equal(handle.getState().submitError, 'La demande de l’ingénieur est requise.')
  handle.unmount()
})

// ------------------------------------------------------------- failures

test('a start failure other than an identity conflict is surfaced and skips the run', async () => {
  const container = fakeContainer()
  const failure = Object.assign(new Error('boom'), { code: 'WORKFLOW_DEFINITION_NOT_FOUND', status: 404 })
  const apiClient = fakeApiClient({ postErrors: { start: failure } })
  const handle = await mountWith(container, apiClient, { namespaceId: 'ns-1', controllerRequest: 'Do the work.' })

  await handle.submit()
  await flush()

  assert.equal(apiClient.calls.post.length, 1, 'the run must not be attempted after a start failure')
  assert.ok(handle.getState().submitError)
  assert.equal(handle.getState().submitting, false)
  handle.unmount()
})

test('a run failure is surfaced and leaves the view mounted', async () => {
  const container = fakeContainer()
  const failure = Object.assign(new Error('conflict'), { code: 'REVISION_CONFLICT', status: 409 })
  const apiClient = fakeApiClient({ postErrors: { run: failure } })
  const handle = await mountWith(container, apiClient, { namespaceId: 'ns-1', controllerRequest: 'Do the work.' })

  await handle.submit()
  await flush()

  assert.equal(apiClient.calls.post.length, 2)
  assert.ok(handle.getState().submitError)
  assert.equal(handle.isMounted(), true)
  handle.unmount()
})

// ------------------------------------------------------------- teardown

test('registerTeardown receives the unmount hook', async () => {
  const container = fakeContainer()
  const teardowns = []
  const handle = await mountWith(container, fakeApiClient(), {
    namespaceId: 'ns-1',
    controllerRequest: 'Do the work.',
    registerTeardown: (fn) => teardowns.push(fn),
  })

  assert.equal(teardowns.length, 1)
  assert.equal(typeof teardowns[0], 'function')
  teardowns[0]()
  assert.equal(handle.isMounted(), false)
})
