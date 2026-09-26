/**
 * Factory Cockpit — hash-based SPA router & view lifecycle manager.
 *
 * Vanilla ESM, zero dependencies, zero build step. Owns:
 *
 *   - routing on `window.location.hash` (`#/runs`, `#/detail`, …);
 *   - deterministic view mounting/unmounting: every transition first runs the
 *     previous view's teardown hooks (timers, SSE clients, listeners);
 *   - topbar active-link state and the live connectivity dot;
 *   - a native `<dialog>` modal helper.
 *
 * The module is import-safe outside the browser: nothing touches `window` or
 * `document` until {@link bootstrapCockpit} runs.
 */

import { SseClient } from './services/sse-client.mjs'
import { ApiClient } from './services/api-client.mjs'
import { mountRunLaunchView } from './views/run-launch.mjs'

export const ROUTES = Object.freeze({
  '/runs': { id: 'view-runs', label: 'Runs' },
  '/launch': { id: 'view-launch', label: 'Lancer' },
  '/detail': { id: 'view-detail', label: 'Détail' },
  '/projection': { id: 'view-projection', label: 'Projection' },
  '/forge': { id: 'view-forge', label: 'Forge' },
  '/admin': { id: 'view-admin', label: 'Admin' },
})

/**
 * Route → view mounter registry. Only routes with a real view are listed; the
 * remaining routes keep their static placeholder sections. Strictly additive:
 * a missing mounter is a no-op so existing routes keep working unchanged.
 */
export const VIEW_MOUNTERS = Object.freeze({
  '/launch': mountRunLaunchView,
})

export const DEFAULT_ROUTE = '/runs'

/** Normalize any hash (`#/x`, `#x`, ``, `#/unknown`) into a known route. */
export function parseHash(hash) {
  const raw = typeof hash === 'string' ? hash.replace(/^#/, '') : ''
  const path = raw.startsWith('/') ? raw : `/${raw}`
  const clean = path.split('?')[0].replace(/\/+$/, '') || '/'
  return Object.prototype.hasOwnProperty.call(ROUTES, clean) ? clean : DEFAULT_ROUTE
}

/** Update the topbar live indicator and its label. */
export function setLiveIndicator(doc, state) {
  const el = doc.getElementById('cockpit-live-indicator')
  if (!el) return
  el.dataset.state = state
  const label = el.querySelector('.cockpit-live-label')
  if (label) label.textContent = state
}

/** Open the native modal with the supplied content (string or Node). */
export function showModal(content, doc = globalThis.document) {
  if (!doc) return null
  const dialog = doc.getElementById('cockpit-dialog')
  const host = doc.getElementById('cockpit-dialog-content')
  if (!dialog || !host) return null
  if (typeof content === 'string') host.innerHTML = content
  else if (content) host.replaceChildren(content)
  if (!dialog.open) dialog.showModal()
  return dialog
}

/** Close the native modal, defensively. */
export function closeModal(doc = globalThis.document) {
  if (!doc) return null
  const dialog = doc.getElementById('cockpit-dialog')
  if (dialog?.open) dialog.close()
  return dialog
}

/**
 * Create the router bound to a window/document pair. Returns handles used by
 * the auto-bootstrap and by tests.
 */
export function createRouter(win = globalThis.window, doc = globalThis.document, options = {}) {
  let currentRoute = null
  let teardownHooks = []
  let viewGeneration = 0

  const registerTeardown = (fn) => {
    if (typeof fn === 'function') teardownHooks.push(fn)
    return fn
  }

  const runTeardowns = () => {
    viewGeneration++
    const hooks = teardownHooks
    teardownHooks = []
    for (const hook of hooks) {
      try {
        hook()
      } catch {
        // A broken teardown must not block the next view from mounting.
      }
    }
  }

  const mounters = options.mounters ?? VIEW_MOUNTERS

  // Default navigation: canonicalize into a hash so the browser router picks it
  // up. Callers may inject `onNavigate` (tests, embedded hosts).
  const defaultNavigate = (route, params) => {
    const query = params && Object.keys(params).length > 0 ? `?${new URLSearchParams(params).toString()}` : ''
    if (win?.location) win.location.hash = `#${route}${query}`
  }

  const mountView = (route) => {
    const mounter = mounters[route]
    if (typeof mounter !== 'function') return
    const host = doc?.getElementById?.(ROUTES[route].id)
    if (!host) return
    const generation = viewGeneration
    const adopt = (handle) => {
      const unmount = typeof handle === 'function' ? handle : handle?.unmount
      if (generation !== viewGeneration) {
        // The user navigated away before the view finished mounting.
        if (typeof unmount === 'function') unmount()
        return
      }
      if (typeof unmount === 'function') registerTeardown(unmount)
    }
    try {
      const handle = mounter(host, {
        apiClient: options.apiClient,
        sseClient: options.sseClient,
        namespaceId: options.namespaceId,
        onNavigate: options.onNavigate ?? defaultNavigate,
        registerTeardown,
      })
      if (handle && typeof handle.then === 'function') handle.then(adopt, () => {})
      else adopt(handle)
    } catch {
      // A failing view must never break navigation to the next route.
    }
  }

  const updateNav = (route) => {
    for (const link of doc.querySelectorAll('.cockpit-nav a')) {
      const target = link.dataset.route ?? parseHash(link.getAttribute('href'))
      link.classList.toggle('active', target === route)
    }
  }

  const mount = (route) => {
    if (route === currentRoute) return
    runTeardowns()
    currentRoute = route
    const activeId = ROUTES[route].id
    for (const section of doc.querySelectorAll('.cockpit-view')) {
      section.classList.toggle('active', section.id === activeId)
    }
    updateNav(route)
    setLiveIndicator(doc, 'online')
    mountView(route)
  }

  const applyHash = () => {
    const route = parseHash(win.location.hash)
    if (win.location.hash !== `#${route}`) {
      // Canonicalize an empty/unknown hash without adding a history entry.
      win.history?.replaceState?.(null, '', `#${route}`)
    }
    mount(route)
  }

  const start = () => {
    win.addEventListener('hashchange', applyHash)
    applyHash()
  }

  return {
    start,
    mount,
    applyHash,
    registerTeardown,
    runTeardowns,
    getCurrentRoute: () => currentRoute,
    getTeardownCount: () => teardownHooks.length,
    getActiveSectionId: () => ROUTES[currentRoute ?? DEFAULT_ROUTE].id,
  }
}

/**
 * Browser bootstrap: wire the router and the service singletons. Import-safe in
 * Node because it is only invoked when a real DOM is present.
 */
export function bootstrapCockpit(win = globalThis.window, doc = globalThis.document) {
  if (!win || !doc) return null
  const api = new ApiClient({ baseUrl: '' })
  const router = createRouter(win, doc, { apiClient: api, mounters: VIEW_MOUNTERS })
  const start = () => router.start()
  if (doc.readyState === 'loading') doc.addEventListener('DOMContentLoaded', start, { once: true })
  else start()
  return { api, router, SseClient, showModal: (c) => showModal(c, doc), closeModal: () => closeModal(doc) }
}

if (typeof window !== 'undefined' && typeof document !== 'undefined') {
  bootstrapCockpit()
}

export default bootstrapCockpit
