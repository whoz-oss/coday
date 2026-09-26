/**
 * Factory Cockpit — Milestone D, Wave 3 Step 1 — offline test suite.
 *
 * Exercises, without a browser and without network:
 *
 *   A. `case-link.mjs`         — SSRF-safe case/thread link resolution, XSS
 *                                escaping, DOM element creation.
 *   B. `workflow-card.mjs`     — additive delegation to `case-link.mjs`.
 *   C. `artifact-admin.mjs`    — admin view rendering, GC / purge / legal-hold
 *                                commands, native `<dialog>` confirmation and
 *                                `403 FORBIDDEN_ADMIN_REQUIRED` handling.
 *   D. `app.mjs` router        — additive `/admin` mounter wiring and teardown.
 *
 * No external dependency, no network. Exit code 0 = all pass.
 *
 * Usage: node factory/tests/test-cockpit-admin-and-case-link.mjs
 */

import assert from 'node:assert/strict'

import {
  buildAgentosCaseUrl,
  buildCodayExpressThreadUrl,
  buildCaseLinkHtml,
  resolveCaseLink,
  createCaseLinkElement,
  escapeHtml,
} from '../dashboard/js/components/case-link.mjs'
import { renderWorkflowCard, buildAgentosCaseUrl as buildAgentosCaseUrlFromCard } from '../dashboard/js/components/workflow-card.mjs'
import {
  ADMIN_GC_PATH,
  buildPurgePath,
  buildLegalHoldPath,
  mountArtifactAdminView,
  renderArtifactAdmin,
  createAdminState,
  createArtifactConfirm,
  isAdminEntitled,
  describeAdminError,
  formatBytes,
} from '../dashboard/js/views/artifact-admin.mjs'
import { VIEW_MOUNTERS, ROUTES, createRouter, parseHash, DEFAULT_ROUTE } from '../dashboard/js/app.mjs'
import { mount as mountRunDetail } from '../dashboard/js/views/run-detail.mjs'
import { createProjectionController, renderProjection } from '../dashboard/js/views/projection.mjs'
import { ApiClientError } from '../dashboard/js/services/api-client.mjs'

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

/** Event target whose `closest()` matches exactly one attribute selector. */
function clickTarget(selector) {
  return { closest: (candidate) => (candidate === selector ? {} : null) }
}

/** API double recording POST bodies; `handler(path, body)` may throw. */
function createApiClient(handler) {
  const calls = []
  return {
    calls,
    async post(path, body) {
      calls.push({ method: 'POST', path, body })
      if (typeof handler === 'function') return handler(path, body)
      return { ok: true }
    },
  }
}

const NS = '11111111-1111-4111-8111-111111111111'

// ---------------------------------------------------------------------------
// A. case-link.mjs — SSRF invariant & rendering
// ---------------------------------------------------------------------------

console.log('\ncase-link.mjs')

await scenario('builds a safe absolute agentos case URL from a trusted base', () => {
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

await scenario('refuses missing, malformed or untrusted agentos bases', () => {
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
  const linked = buildCaseLinkHtml({ kind: 'agentos', caseId: 'case-1' }, { agentosUrl: 'https://agentos.example.com' })
  assert.ok(
    linked.includes(
      '<a class="case-link" href="https://agentos.example.com/case/case-1" target="_blank" rel="noopener noreferrer"',
    ),
  )
  assert.ok(linked.includes('Case case-1'))

  const unlinked = buildCaseLinkHtml(
    { kind: 'agentos', caseId: 'case-1' },
    { agentosUrl: 'http://user:pass@evil.example.com' },
  )
  assert.ok(!unlinked.includes('<a '))
  assert.ok(unlinked.includes('<span class="case-id cockpit-id"'))
  assert.ok(unlinked.includes('Case case-1'))
})

await scenario('renders coday-express threads as readable text without a config base', () => {
  const html = buildCaseLinkHtml(
    { kind: 'coday-express', threadId: 't-1' },
    { agentosUrl: 'https://agentos.example.com' },
  )
  assert.ok(!html.includes('<a '))
  assert.ok(html.includes('<span class="thread-id cockpit-id"'))
  assert.ok(html.includes('Thread t-1'))
})

await scenario('makes a coday-express thread clickable only against a trusted config base', () => {
  const href = buildCodayExpressThreadUrl('t-1', 'https://express.example.com')
  assert.equal(href, 'https://express.example.com/threads/t-1')

  const linked = buildCaseLinkHtml(
    { kind: 'coday-express', threadId: 't-1' },
    { codayExpressUrl: 'https://express.example.com' },
  )
  assert.ok(linked.startsWith('<a class="case-link" href="https://express.example.com/threads/t-1"'))
  assert.ok(linked.includes('rel="noopener noreferrer"'))

  // Untrusted bases never become anchors.
  for (const base of ['javascript:alert(1)', '//evil.example.com', 'http://u:p@evil.example.com', 'not a url']) {
    const unsafe = buildCaseLinkHtml({ kind: 'coday-express', threadId: 't-1' }, { codayExpressUrl: base })
    assert.ok(!unsafe.includes('<a '), `base ${base} must not yield an anchor`)
    assert.ok(unsafe.includes('Thread t-1'))
  }
})

await scenario('escapes untrusted ids (no HTML/browser URL injection)', () => {
  const injection = '"><img src=x onerror=alert(1)>'
  const html = buildCaseLinkHtml({ kind: 'agentos', caseId: injection }, { agentosUrl: 'https://agentos.example.com' })
  assert.ok(!html.includes('<img'))
  assert.ok(html.includes('&lt;img'))
  assert.ok(html.includes('&quot;'))

  const thread = buildCaseLinkHtml({ kind: 'coday-express', threadId: '<b>' })
  assert.ok(!thread.includes('<b>'))
  assert.ok(thread.includes('&lt;b&gt;'))
  assert.equal(escapeHtml(injection), '&quot;&gt;&lt;img src=x onerror=alert(1)&gt;')
})

await scenario('resolveCaseLink covers the generic caseId fallback', () => {
  assert.deepEqual(resolveCaseLink({ caseId: 'case-x' }), {
    tag: 'span',
    href: null,
    label: 'Case case-x',
    className: 'case-id cockpit-id',
    title: null,
  })
  assert.equal(resolveCaseLink(null), null)
  assert.equal(buildCaseLinkHtml({}), '')
})

await scenario('createCaseLinkElement never uses innerHTML and sets safe attributes', () => {
  const created = []
  const doc = {
    createElement(tag) {
      const el = { tag, attributes: {}, setAttribute: (k, v) => (el.attributes[k] = v) }
      created.push(el)
      return el
    },
  }
  const el = createCaseLinkElement({ kind: 'agentos', caseId: 'case-1' }, { agentosUrl: 'https://agentos.example.com' }, doc)
  assert.equal(el.tag, 'a')
  assert.equal(el.className, 'case-link')
  assert.equal(el.textContent, 'Case case-1')
  assert.equal(el.attributes.href, 'https://agentos.example.com/case/case-1')
  assert.equal(el.attributes.rel, 'noopener noreferrer')
  assert.equal(el.attributes.target, '_blank')

  // No document → null (import-safe).
  assert.equal(createCaseLinkElement({ caseId: 'x' }, {}, null), null)
})

// ---------------------------------------------------------------------------
// B. workflow-card.mjs integration
// ---------------------------------------------------------------------------

console.log('\nworkflow-card.mjs integration')

const agentosSnapshot = {
  workflowId: 'wf-1',
  revision: 2,
  projection: { title: 'Story', status: 'running', steps: [] },
  controllerExecution: { kind: 'agentos', caseId: 'case-1' },
}

await scenario('renderWorkflowCard delegates identity to case-link.mjs', () => {
  const html = renderWorkflowCard(agentosSnapshot, { agentosUrl: 'https://agentos.example.com' })
  assert.ok(
    html.includes(
      '<a class="case-link" href="https://agentos.example.com/case/case-1" target="_blank" rel="noopener noreferrer"',
    ),
  )
  const unlinked = renderWorkflowCard(agentosSnapshot, { agentosUrl: 'javascript:alert(1)' })
  assert.ok(!unlinked.includes('<a '))
  assert.ok(unlinked.includes('Case case-1'))
})

await scenario('workflow-card re-exports the SSRF choke point unchanged', () => {
  assert.equal(buildAgentosCaseUrlFromCard, buildAgentosCaseUrl)
  assert.equal(buildAgentosCaseUrlFromCard('case-1', 'https://agentos.example.com'), 'https://agentos.example.com/case/case-1')
})

await scenario('workflow-card links a coday-express thread only with a trusted base', () => {
  const snapshot = {
    workflowId: 'wf-2',
    projection: { title: 'Express', status: 'ready', steps: [] },
    controllerExecution: { kind: 'coday-express', threadId: 't-9' },
  }
  const plain = renderWorkflowCard(snapshot, { agentosUrl: 'https://agentos.example.com' })
  assert.ok(!plain.includes('<a '))
  assert.ok(plain.includes('Thread t-9'))

  const linked = renderWorkflowCard(snapshot, { codayExpressUrl: 'https://express.example.com' })
  assert.ok(linked.includes('href="https://express.example.com/threads/t-9"'))
})

await scenario('run-detail renders the controller identity through case-link', async () => {
  const container = createFakeContainer()
  const apiClient = {
    calls: [],
    async get(path) {
      this.calls.push(path)
      if (path.startsWith('/api/factory/workflows/wf-1?')) {
        return {
          state: 'existing',
          workflowId: 'wf-1',
          revision: 1,
          controllerExecution: { kind: 'agentos', caseId: 'case-1' },
          projection: { workflowId: 'wf-1', workflowType: 't', title: 'T', status: 'completed', steps: [] },
        }
      }
      return null
    },
  }
  const handle = await mountRunDetail(container, {
    workflowId: 'wf-1',
    namespaceId: NS,
    apiClient,
    agentosUrl: 'https://agentos.example.com',
  })
  assert.ok(container.innerHTML.includes('data-run-detail-identity="true"'))
  assert.ok(container.innerHTML.includes('href="https://agentos.example.com/case/case-1"'))
  // Without a trusted base the identity stays inert and never breaks the view.
  handle.unmount()

  const inert = createFakeContainer()
  const inertHandle = await mountRunDetail(inert, { workflowId: 'wf-1', namespaceId: NS, apiClient })
  assert.ok(inert.innerHTML.includes('Case case-1'))
  assert.ok(!inert.innerHTML.includes('<a '))
  inertHandle.unmount()
})

await scenario('projection resolves the codayExpressUrl base and links threads', async () => {
  const api = {
    calls: [],
    async get(path) {
      this.calls.push(path)
      if (path === '/api/config') {
        return { agentosUrl: 'https://agentos.example.com', codayExpressUrl: 'https://express.example.com' }
      }
      if (path.startsWith('/api/factory/workflows?')) {
        return {
          items: [
            {
              workflowId: 'wf-x',
              projection: { title: 'X', status: 'ready', steps: [] },
              controllerExecution: { kind: 'coday-express', threadId: 't-7' },
            },
          ],
        }
      }
      return null
    },
  }
  const controller = createProjectionController({ api, namespaceId: NS })
  await controller.resolveAgentosUrl()
  assert.equal(controller.agentosUrl, 'https://agentos.example.com')
  assert.equal(controller.codayExpressUrl, 'https://express.example.com')
  await controller.load('active')
  const html = renderProjection(controller)
  assert.ok(html.includes('href="https://express.example.com/threads/t-7"'))
  assert.ok(html.includes('Thread t-7'))
  controller.teardown()
})

// ---------------------------------------------------------------------------
// C. artifact-admin.mjs — view, commands, 403 handling, lifecycle
// ---------------------------------------------------------------------------

console.log('\nartifact-admin.mjs')

await scenario('renders the admin panel with the expected dockyard sections', () => {
  const html = renderArtifactAdmin(createAdminState())
  assert.ok(html.includes('data-artifact-admin="true"'))
  assert.ok(html.includes('data-admin-head="true"'))
  assert.ok(html.includes('data-admin-gc="true"'))
  assert.ok(html.includes('data-admin-purge="true"'))
  assert.ok(html.includes('data-admin-legal="true"'))
  assert.ok(html.includes('data-admin-status="granted"'))
  assert.ok(html.includes('class="panel"'))
  assert.ok(html.includes('class="btn btn-danger"'))
})

await scenario('isAdminEntitled fails open on unknown and gates on explicit signals', () => {
  assert.equal(isAdminEntitled(null), true)
  assert.equal(isAdminEntitled({}), true)
  assert.equal(isAdminEntitled({ isAdmin: false }), false)
  assert.equal(isAdminEntitled({ roles: ['admin'] }), true)
  assert.equal(isAdminEntitled({ roles: ['member'] }), false)
  assert.equal(isAdminEntitled({ entitlements: ['admin:artifacts'] }), true)
})

await scenario('describeAdminError maps 403/FORBIDDEN_ADMIN_REQUIRED without swallowing the message', () => {
  const described = describeAdminError(
    new ApiClientError('Admin authorization required (no-admin-role)', {
      code: 'FORBIDDEN_ADMIN_REQUIRED',
      status: 403,
    }),
  )
  assert.equal(described.forbidden, true)
  assert.equal(described.code, 'FORBIDDEN_ADMIN_REQUIRED')
  assert.ok(described.message.includes('Admin authorization required (no-admin-role)'))
  assert.ok(described.message.includes('FORBIDDEN_ADMIN_REQUIRED'))
})

await scenario('mount throws without a usable apiClient', () => {
  assert.throws(() => mountArtifactAdminView(createFakeContainer(), {}), TypeError)
  assert.throws(() => mountArtifactAdminView(createFakeContainer(), { apiClient: {} }), TypeError)
  assert.throws(() => mountArtifactAdminView(null, { apiClient: createApiClient() }), TypeError)
})

await scenario('GC command posts to the admin route and renders the report', async () => {
  const container = createFakeContainer()
  const report = {
    reclaimedStagingKeys: ['uploads/a', 'uploads/b'],
    anomalies: [{ type: 'blob_without_pg_row', storageKey: 'objects/x', details: 'd' }],
    scannedBlobKeys: ['objects/x', 'objects/y'],
    scannedMetadataRows: 7,
    timestamp: '2026-09-26T10:00:00.000Z',
  }
  const apiClient = createApiClient((path) => (path === ADMIN_GC_PATH ? report : {}))
  const handle = mountArtifactAdminView(container, { apiClient })

  assert.ok(container.innerHTML.includes('data-artifact-admin="true"'))
  assert.equal(container.listenerCount(), 3)

  container.dispatch('click', { target: clickTarget('[data-admin-gc-run]') })
  await flush()
  await flush()

  assert.equal(apiClient.calls.length, 1)
  assert.deepEqual(apiClient.calls[0], { method: 'POST', path: ADMIN_GC_PATH, body: {} })
  assert.ok(container.innerHTML.includes('data-admin-gc-result="true"'))
  assert.ok(container.innerHTML.includes('data-gc-reclaimed="2"'))
  assert.ok(container.innerHTML.includes('data-gc-anomalies="1"'))
  assert.ok(container.innerHTML.includes('data-gc-rows="7"'))
  assert.equal(handle.getState().gc.report, report)

  handle.unmount()
})

await scenario('GC dry-run flag is forwarded in the request body', async () => {
  const container = createFakeContainer()
  const apiClient = createApiClient(() => ({ reclaimedStagingKeys: [], anomalies: [], scannedBlobKeys: [] }))
  const handle = mountArtifactAdminView(container, { apiClient })

  container.dispatch('change', { target: { dataset: { adminGcDryRun: '' }, checked: true } })
  assert.equal(handle.getState().gc.dryRun, true)
  const report = await handle.runGc()
  assert.ok(report)
  assert.deepEqual(apiClient.calls[0].body, { dryRun: true })

  handle.unmount()
})

await scenario('purge is confirmed through the injected dialog before dispatch', async () => {
  const container = createFakeContainer()
  const confirmations = []
  const apiClient = createApiClient(() => ({
    success: true,
    artifactId: 'a1',
    reason: 'audit',
    status: 'purged',
    metadata: { id: 'a1', size: 2048, availabilityStatus: 'purged' },
  }))
  const handle = mountArtifactAdminView(container, {
    apiClient,
    confirm: async (request) => {
      confirmations.push(request)
      return true
    },
  })

  handle.getState().purge.artifactId = 'a1'
  handle.getState().purge.reason = 'audit'
  container.dispatch('click', { target: clickTarget('[data-admin-purge-submit]') })
  await flush()
  await flush()

  assert.equal(confirmations.length, 1)
  assert.equal(confirmations[0].action, 'purge')
  assert.equal(apiClient.calls.length, 1)
  assert.deepEqual(apiClient.calls[0], { method: 'POST', path: buildPurgePath('a1'), body: { reason: 'audit' } })
  assert.ok(container.innerHTML.includes('data-admin-purge-result="true"'))
  assert.ok(container.innerHTML.includes('data-purge-freed="2048"'))
  assert.ok(container.innerHTML.includes('2.0 Ko'))

  handle.unmount()
})

await scenario('purge is never dispatched when the confirmation is declined', async () => {
  const container = createFakeContainer()
  const apiClient = createApiClient(() => ({}))
  const handle = mountArtifactAdminView(container, { apiClient, confirm: async () => false })
  handle.getState().purge.artifactId = 'a1'

  await handle.purgeArtifact()
  assert.equal(apiClient.calls.length, 0)
  assert.equal(handle.getState().purge.result, null)

  handle.unmount()
})

await scenario('legal hold sends { legalHold, reason } and renders the state', async () => {
  const container = createFakeContainer()
  const apiClient = createApiClient(() => ({ id: 'a2', legalHold: true, legalHoldReason: 'audit' }))
  const handle = mountArtifactAdminView(container, { apiClient })

  handle.getState().legalHold.artifactId = 'a2'
  handle.getState().legalHold.legalHold = true
  handle.getState().legalHold.reason = 'audit'
  await handle.setLegalHold()

  assert.deepEqual(apiClient.calls[0], {
    method: 'POST',
    path: buildLegalHoldPath('a2'),
    body: { legalHold: true, reason: 'audit' },
  })
  assert.ok(container.innerHTML.includes('data-admin-legal-result="true"'))
  assert.ok(container.innerHTML.includes('data-legal-hold="true"'))

  handle.unmount()
})

await scenario('a 403 FORBIDDEN_ADMIN_REQUIRED is rendered escaped and locks the actions', async () => {
  const container = createFakeContainer()
  const apiClient = createApiClient(() => {
    throw new ApiClientError('Admin authorization required (<script>alert(1)</script>)', {
      code: 'FORBIDDEN_ADMIN_REQUIRED',
      status: 403,
    })
  })
  const handle = mountArtifactAdminView(container, { apiClient })

  await handle.runGc()

  const state = handle.getState()
  assert.equal(state.adminEntitled, false)
  assert.equal(state.error.code, 'FORBIDDEN_ADMIN_REQUIRED')
  assert.ok(container.innerHTML.includes('data-admin-error="true"'))
  assert.ok(container.innerHTML.includes('FORBIDDEN_ADMIN_REQUIRED'))
  // The server message is shown, but escaped — never injected as HTML.
  assert.ok(!container.innerHTML.includes('<script>'))
  assert.ok(container.innerHTML.includes('&lt;script&gt;'))
  // Every admin action is disabled once entitlement is denied.
  assert.ok(container.innerHTML.includes('data-admin-gc-run="true" disabled'))
  assert.ok(container.innerHTML.includes('data-admin-purge-submit="true" disabled'))
  assert.ok(container.innerHTML.includes('data-admin-legal-submit="true" disabled'))
  assert.ok(container.innerHTML.includes('data-admin-status="denied"'))

  handle.unmount()
})

await scenario('a non-authorization failure stays scoped to its section (no admin lock)', async () => {
  const container = createFakeContainer()
  const apiClient = createApiClient(() => {
    throw new ApiClientError('Admin purge refused: LEGAL_HOLD_ACTIVE', {
      code: 'LEGAL_HOLD_ACTIVE',
      status: 409,
    })
  })
  const handle = mountArtifactAdminView(container, { apiClient, confirm: async () => true })
  handle.getState().purge.artifactId = 'a1'

  await handle.purgeArtifact({ artifactId: 'a1' })

  assert.equal(handle.getState().adminEntitled, true)
  assert.ok(container.innerHTML.includes('data-admin-purge-error="true"'))
  assert.ok(container.innerHTML.includes('LEGAL_HOLD_ACTIVE'))
  assert.ok(!container.innerHTML.includes('data-admin-error="true"'))

  handle.unmount()
})

await scenario('an explicit non-admin profile gates the view up-front', () => {
  const container = createFakeContainer()
  const apiClient = createApiClient()
  const handle = mountArtifactAdminView(container, { apiClient, profile: { isAdmin: false } })
  assert.equal(handle.getState().adminEntitled, false)
  assert.ok(container.innerHTML.includes('data-admin-status="denied"'))
  assert.equal(apiClient.calls.length, 0)
  handle.unmount()
})

await scenario('unmount() detaches listeners, empties the container and is idempotent', async () => {
  const container = createFakeContainer()
  const apiClient = createApiClient(() => ({}))
  let teardown = null
  const handle = mountArtifactAdminView(container, {
    apiClient,
    registerTeardown: (fn) => {
      teardown = fn
    },
  })
  assert.equal(typeof teardown, 'function')
  assert.equal(container.listenerCount(), 3)

  handle.unmount()
  assert.equal(handle.isMounted(), false)
  assert.equal(container.listenerCount(), 0)
  assert.equal(container.innerHTML, '')

  handle.unmount()
  container.dispatch('click', { target: clickTarget('[data-admin-gc-run]') })
  await flush()
  assert.equal(apiClient.calls.length, 0)
})

await scenario('createArtifactConfirm drives a native dialog and escapes its content', async () => {
  const hostHandlers = new Set()
  const host = {
    innerHTML: '',
    addEventListener: (type, handler) => hostHandlers.add(handler),
    removeEventListener: (type, handler) => hostHandlers.delete(handler),
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
  const confirm = createArtifactConfirm({ doc })
  const pending = confirm({ label: 'Purger', warning: 'irréversible', detail: '<img src=x>' })
  assert.equal(dialog.open, true)
  assert.ok(host.innerHTML.includes('&lt;img src=x&gt;'))

  for (const handler of [...hostHandlers]) {
    handler({ target: { closest: (selector) => (selector === '[data-dialog-action]' ? { dataset: { dialogAction: 'confirm' } } : null) } })
  }
  assert.equal(await pending, true)
  assert.equal(dialog.open, false)
  assert.equal(hostHandlers.size, 0)

  // Without a document the destructive command is never auto-approved.
  const noDoc = createArtifactConfirm({})
  assert.equal(await noDoc({ label: 'x', warning: 'y' }), false)
})

await scenario('formatBytes renders human sizes and rejects invalid input', () => {
  assert.equal(formatBytes(512), '512 o')
  assert.equal(formatBytes(2048), '2.0 Ko')
  assert.equal(formatBytes(5 * 1024 * 1024), '5.0 Mo')
  assert.equal(formatBytes(-1), null)
  assert.equal(formatBytes('nope'), null)
})

// ---------------------------------------------------------------------------
// D. app.mjs router wiring
// ---------------------------------------------------------------------------

console.log('\napp.mjs router wiring')

function createFakeWin(hash) {
  return { location: { hash }, history: { replaceState() {} }, addEventListener() {} }
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
    querySelectorAll: (selector) => (selector === '.cockpit-view' ? Object.values(containers.sections ?? {}) : []),
  }
}

await scenario('/admin is registered without disturbing existing routes', () => {
  assert.equal(ROUTES['/admin'].id, 'view-admin')
  assert.equal(VIEW_MOUNTERS['/admin'], mountArtifactAdminView)
  assert.equal(VIEW_MOUNTERS['/launch'] !== undefined, true)
  assert.equal(parseHash('#/admin'), '/admin')
  assert.equal(parseHash('#/admin?ns=x'), '/admin')
  assert.equal(parseHash('#/unknown'), DEFAULT_ROUTE)
})

await scenario('router mounts the admin view on #/admin and tears it down on exit', async () => {
  const sections = {
    'view-runs': createFakeSection('view-runs'),
    'view-admin': createFakeSection('view-admin'),
  }
  const adminHost = createFakeContainer()
  const win = createFakeWin('#/admin')
  const doc = createFakeDoc({ 'view-admin': adminHost, sections })
  const apiClient = createApiClient(() => ({}))
  const router = createRouter(win, doc, { apiClient, mounters: VIEW_MOUNTERS })

  router.start()
  await flush()

  assert.equal(router.getCurrentRoute(), '/admin')
  assert.ok(adminHost.innerHTML.includes('data-artifact-admin="true"'))
  assert.equal(adminHost.listenerCount(), 3)
  assert.equal(sections['view-admin'].classList.active, true)
  assert.equal(sections['view-runs'].classList.active, false)

  router.mount('/runs')
  assert.equal(router.getCurrentRoute(), '/runs')
  assert.equal(adminHost.innerHTML, '')
  assert.equal(adminHost.listenerCount(), 0)
  assert.equal(sections['view-runs'].classList.active, true)
})

// ---------------------------------------------------------------------------

console.log(`\nResult: ${passed} passed, ${failed} failed`)
process.exit(failed ? 1 : 0)
