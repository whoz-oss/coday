/**
 * Factory Cockpit — workflow-definition admin section unit tests (vanilla,
 * Node built-in test runner, zero dependencies, zero build step).
 *
 *   node --test factory/dashboard/js/views/artifact-admin.test.mjs
 *
 * The mounted view is exercised against a minimal fake container (only the DOM
 * surface the view actually touches: `innerHTML` and delegated listeners) and a
 * recording fake `apiClient`. The tests pin the exact endpoint, body type and
 * refresh behaviour of the workflow-definition upload/list/delete section, plus
 * the server-owned admin gating (a 403 disables every mutation).
 */

import test from 'node:test'
import assert from 'node:assert/strict'

import {
  createAdminState,
  mountArtifactAdminView,
  renderArtifactAdmin,
  buildDefinitionPath,
  WORKFLOW_DEFINITIONS_PATH,
  WORKFLOW_DEFINITIONS_UPLOAD_PATH,
} from './artifact-admin.mjs'

/** Flush the pending microtask/macrotask queue (async render chains). */
async function settle(rounds = 6) {
  for (let index = 0; index < rounds; index++) await new Promise((resolve) => setTimeout(resolve, 0))
}

/** Minimal DOM-ish container with delegated-listener firing. */
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
      const handler = listeners.get(type)
      if (handler) return handler(event)
      return undefined
    },
  }
}

class FakeApiError extends Error {
  constructor(message, { code = null, status = null } = {}) {
    super(message)
    this.name = 'ApiClientError'
    this.code = code
    this.status = status
  }
}

/** Recording fake ApiClient with per-test behaviours. */
function fakeApi(overrides = {}) {
  const calls = { get: [], post: [], delete: [] }
  const api = {
    calls,
    async get(path) {
      calls.get.push({ path })
      if (typeof overrides.get === 'function') return overrides.get(path)
      return { items: [] }
    },
    async post(path, body) {
      calls.post.push({ path, body })
      if (typeof overrides.post === 'function') return overrides.post(path, body)
      return { workflowType: 'wf-demo', version: '1.0.0' }
    },
    async delete(path) {
      calls.delete.push({ path })
      if (typeof overrides.delete === 'function') return overrides.delete(path)
      return { deleted: true }
    },
  }
  return api
}

const DEFINITION = { workflowType: 'wf-demo', version: '1.0.0', definitionHash: 'a'.repeat(64) }

test('mounting fetches workflow definitions and renders them in the table', async () => {
  const container = fakeContainer()
  const api = fakeApi({ get: () => ({ items: [DEFINITION] }) })
  const handle = mountArtifactAdminView(container, { apiClient: api })
  await settle()

  assert.deepEqual(api.calls.get[0], { path: WORKFLOW_DEFINITIONS_PATH })
  assert.match(container.innerHTML, /data-admin-definitions="true"/)
  assert.match(container.innerHTML, /wf-demo/)
  assert.match(container.innerHTML, /1\.0\.0/)
  assert.match(container.innerHTML, new RegExp('a{64}'))
  assert.equal(handle.getState().definitions.items.length, 1)
  handle.unmount()
})

test('renderArtifactAdmin exposes the workflow-definitions section and escapes values', () => {
  const state = createAdminState({
    definitions: {
      items: [{ workflowType: '<img src=x onerror=alert(1)>', version: '9.9.9', definitionHash: 'h' }],
      loading: false,
      uploading: false,
      file: null,
      deletingKey: null,
      error: null,
    },
  })
  const html = renderArtifactAdmin(state)
  assert.match(html, /data-admin-definitions="true"/)
  assert.match(html, /data-admin-definition-file="true"/)
  assert.match(html, /data-admin-definition-upload="true"/)
  assert.match(html, /data-admin-definition-delete="true"/)
  assert.doesNotMatch(html, /<img src=x/)
  assert.match(html, /&lt;img src=x/)
})

test('selecting a file stores it and uploading posts FormData to the upload route', async () => {
  const container = fakeContainer()
  let uploaded = 0
  const api = fakeApi({
    get: () => ({ items: [] }),
    post: () => {
      uploaded += 1
      return { workflowType: 'wf-demo', version: '1.0.0' }
    },
  })
  const handle = mountArtifactAdminView(container, { apiClient: api })
  await settle()

  const file = new File(['{"workflowType":"wf-demo"}'], 'wf-demo.json', { type: 'application/json' })
  container.fire('change', { target: { dataset: { adminDefinitionFile: 'true' }, files: [file] } })
  assert.equal(handle.getState().definitions.file, file)

  await handle.uploadDefinition({ file })
  await settle()

  assert.equal(api.calls.post.length, 1)
  assert.equal(api.calls.post[0].path, WORKFLOW_DEFINITIONS_UPLOAD_PATH)
  assert.ok(api.calls.post[0].body instanceof FormData)
  assert.equal(api.calls.post[0].body.get('file').name, 'wf-demo.json')
  assert.equal(uploaded, 1)
  handle.unmount()
})

test('an invalid definition rejection renders an escaped error banner', async () => {
  const container = fakeContainer()
  const api = fakeApi({
    get: () => ({ items: [] }),
    post: () => {
      throw new FakeApiError('Invalid JSON <script>alert(1)</script>', { code: 'INVALID_DEFINITION', status: 400 })
    },
  })
  const handle = mountArtifactAdminView(container, { apiClient: api })
  await settle()

  const file = new File(['not json'], 'broken.json', { type: 'application/json' })
  await handle.uploadDefinition({ file })
  await settle()

  assert.match(container.innerHTML, /data-admin-definition-error="true"/)
  assert.match(container.innerHTML, /&lt;script&gt;/)
  assert.doesNotMatch(container.innerHTML, /<script>alert/)
  assert.equal(handle.getState().definitions.error.code, 'INVALID_DEFINITION')
  handle.unmount()
})

test('delete asks for confirmation then calls the definition DELETE route and refreshes', async () => {
  const container = fakeContainer()
  const confirmations = []
  const api = fakeApi({ get: () => ({ items: [DEFINITION] }) })
  const handle = mountArtifactAdminView(container, {
    apiClient: api,
    confirm: (request) => {
      confirmations.push(request)
      return true
    },
  })
  await settle()

  const getsBefore = api.calls.get.length
  await handle.deleteDefinition({ workflowType: 'wf-demo', version: '1.0.0' })
  await settle()

  assert.equal(confirmations.length, 1)
  assert.equal(confirmations[0].action, 'delete-definition')
  assert.equal(api.calls.delete.length, 1)
  assert.equal(api.calls.delete[0].path, buildDefinitionPath('wf-demo', '1.0.0'))
  // The list is refreshed after a successful deletion.
  assert.ok(api.calls.get.length > getsBefore)
  handle.unmount()
})

test('a declined confirmation never sends the delete request', async () => {
  const container = fakeContainer()
  const api = fakeApi({ get: () => ({ items: [DEFINITION] }) })
  const handle = mountArtifactAdminView(container, { apiClient: api, confirm: () => false })
  await settle()

  await handle.deleteDefinition({ workflowType: 'wf-demo', version: '1.0.0' })
  await settle()

  assert.equal(api.calls.delete.length, 0)
  handle.unmount()
})

test('a 403 FORBIDDEN_ADMIN_REQUIRED disables the definition mutations', async () => {
  const container = fakeContainer()
  const api = fakeApi({
    get: () => {
      throw new FakeApiError('Admin authorization required', { code: 'FORBIDDEN_ADMIN_REQUIRED', status: 403 })
    },
  })
  const handle = mountArtifactAdminView(container, { apiClient: api })
  await settle()

  const state = handle.getState()
  assert.equal(state.adminEntitled, false)
  assert.equal(state.error.code, 'FORBIDDEN_ADMIN_REQUIRED')
  assert.match(container.innerHTML, /data-admin-status="denied"/)
  assert.match(container.innerHTML, /data-admin-definition-upload="true" disabled/)
  handle.unmount()
})

test('unmount is idempotent and clears the definitions state', async () => {
  const container = fakeContainer()
  const api = fakeApi({ get: () => ({ items: [DEFINITION] }) })
  const handle = mountArtifactAdminView(container, { apiClient: api })
  await settle()

  handle.unmount()
  handle.unmount()
  assert.equal(handle.isMounted(), false)
  assert.equal(handle.getState().definitions.items.length, 0)
  assert.equal(container.innerHTML, '')
})
