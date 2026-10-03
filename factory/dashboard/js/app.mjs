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
import { mountArtifactAdminView } from './views/artifact-admin.mjs'
import { mountProjectionView } from './views/projection.mjs'
import { mount as mountRunDetailView } from './views/run-detail.mjs'
import { mountRunLaunchView } from './views/run-launch.mjs'

export const ROUTES = Object.freeze({
  '/runs': { id: 'view-runs', label: 'Runs' },
  '/launch': { id: 'view-launch', label: 'Lancer' },
  '/detail': { id: 'view-detail', label: 'Détail' },
  // Legacy alias of the runs list (the former standalone `Projection` tab).
  // Resolves to the same list view so old links keep working without a second
  // placeholder screen.
  '/projection': { id: 'view-runs', label: 'Projection' },
  '/admin': { id: 'view-admin', label: 'Admin' },
})

/**
 * Route → view mounter registry. Only routes with a real view are listed; the
 * remaining routes keep their static placeholder sections. Strictly additive:
 * a missing mounter is a no-op so existing routes keep working unchanged.
 */
export const VIEW_MOUNTERS = Object.freeze({
  '/launch': mountRunLaunchView,
  '/admin': mountArtifactAdminView,
})

export const DEFAULT_ROUTE = '/runs'

/**
 * Resolve the active namespace from the URL (`?ns=` / `?namespaceId=`), read
 * either from the querystring or from the hash route. Returns `null` when the
 * caller has not supplied one. Pure and import-safe in Node.
 */
export function resolveNamespaceId(win = globalThis.window) {
  if (!win?.location) return null
  const hash = typeof win.location.hash === 'string' ? win.location.hash : ''
  const sources = [win.location.search ?? '', hash.includes('?') ? hash.slice(hash.indexOf('?')) : '']
  for (const source of sources) {
    let params
    try {
      params = new URLSearchParams(source)
    } catch {
      continue
    }
    const value = params.get('ns') ?? params.get('namespaceId')
    if (value) return value
  }
  return null
}

/**
 * Read a named query parameter from the querystring or the hash route. Returns
 * `null` when absent. Pure and import-safe in Node.
 */
export function resolveRouteParam(win, name) {
  if (!win?.location || !name) return null
  const hash = typeof win.location.hash === 'string' ? win.location.hash : ''
  const sources = [win.location.search ?? '', hash.includes('?') ? hash.slice(hash.indexOf('?')) : '']
  for (const source of sources) {
    let params
    try {
      params = new URLSearchParams(source)
    } catch {
      continue
    }
    const value = params.get(name)
    if (value) return value
  }
  return null
}

/** Resolve the workflow a `/detail` route should mount, or `null`. */
export function resolveWorkflowId(win = globalThis.window) {
  return resolveRouteParam(win, 'workflowId') ?? resolveRouteParam(win, 'id')
}

/**
 * Build the stable identity of a route transition: the normalized route path
 * plus its full query string. Two navigations to the same path with different
 * params (`#/detail?workflowId=A` vs `#/detail?workflowId=B`) therefore yield
 * different identities and force an unmount/remount of the view with its new
 * arguments, instead of a silent no-op. Pure and import-safe in Node.
 */
export function buildRouteIdentity(route, win = globalThis.window) {
  const hash = typeof win?.location?.hash === 'string' ? win.location.hash : ''
  const raw = hash.replace(/^#/, '')
  const queryIndex = raw.indexOf('?')
  if (queryIndex < 0) return route
  const query = raw.slice(queryIndex + 1)
  return query ? `${route}?${query}` : route
}

/** Build the namespace-scoped workflow SSE stream URL (param omitted when absent). */
export function workflowStreamUrl(namespaceId) {
  return namespaceId
    ? `/api/factory/workflows/stream?namespaceId=${encodeURIComponent(namespaceId)}`
    : `/api/factory/workflows/stream`
}

/** Routes that consume the shared workflow SSE stream. */
export const SSE_ROUTES = Object.freeze(['/runs', '/projection', '/detail'])

/** Build `#route?params` and navigate to it (shared by the view mounters). */
export function navigateTo(win, route, params) {
  const query = params && Object.keys(params).length > 0 ? `?${new URLSearchParams(params).toString()}` : ''
  if (win?.location) win.location.hash = `#${route}${query}`
}

/** True when the hash's path (query excluded) names a registered route. */
function isKnownHash(hash) {
  const raw = typeof hash === 'string' ? hash.replace(/^#/, '') : ''
  const path = raw.split('?')[0].replace(/\/+$/, '') || '/'
  const normalized = path.startsWith('/') ? path : `/${path}`
  return Object.prototype.hasOwnProperty.call(ROUTES, normalized)
}

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
 *
 * `options.mounters` maps a route to its view mounter (defaults to
 * {@link VIEW_MOUNTERS}); `options.onMount(route, { registerTeardown, doc, win })`
 * is an additive, optional view-mount hook that runs after the section classes
 * are toggled so a view can register its own teardown. `options.onMountOwnedRoutes`
 * lists the routes whose mounting is owned by `onMount`, so the generic mounter
 * is skipped for them. All are optional and none changes the route table.
 */
export function createRouter(win = globalThis.window, doc = globalThis.document, options = {}) {
  let currentRoute = null
  let currentRouteIdentity = null
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
  // Routes whose mounting is owned by the `onMount` hook (they need options the
  // generic registry cannot supply, e.g. a freshly resolved namespace). The
  // generic mounter is skipped for them so a view never mounts twice.
  const onMountOwnedRoutes = new Set(options.onMountOwnedRoutes ?? [])
  const sseRoutes = new Set(options.sseRoutes ?? SSE_ROUTES)
  const SseClientImpl = options.SseClient ?? SseClient

  // Resolve the ACTIVE namespace on every transition (never frozen at
  // bootstrap) so the shared stream and every view stay scoped to the current
  // view's namespace.
  const resolveNamespace =
    typeof options.resolveNamespace === 'function' ? options.resolveNamespace : () => options.namespaceId ?? null

  // A single shared SSE stream per namespace: recreated (not frozen) whenever
  // the active namespace changes, so subscriptions always follow the view.
  let activeSseClient = options.sseClient ?? null
  let activeSseNamespace

  const releaseSseClient = () => {
    if (options.sseClient || !activeSseClient) return
    if (typeof activeSseClient.close === 'function') {
      try {
        activeSseClient.close()
      } catch {
        // close() must stay idempotent.
      }
    }
    activeSseClient = null
    activeSseNamespace = undefined
  }

  const getSseClient = (namespaceId) => {
    const ns = namespaceId ?? null
    if (options.sseClient) return options.sseClient
    if (typeof SseClientImpl !== 'function') return null
    if (activeSseClient && activeSseNamespace === ns) return activeSseClient
    releaseSseClient()
    activeSseClient = new SseClientImpl(workflowStreamUrl(ns))
    activeSseNamespace = ns
    activeSseClient.connect?.()
    return activeSseClient
  }

  const sseClientForRoute = (route, namespaceId) => {
    if (options.sseClient) return options.sseClient
    if (!sseRoutes.has(route)) {
      releaseSseClient()
      return null
    }
    return getSseClient(namespaceId)
  }

  // Default navigation: canonicalize into a hash so the browser router picks it
  // up. Callers may inject `onNavigate` (tests, embedded hosts).
  const defaultNavigate = (route, params) => {
    const query = params && Object.keys(params).length > 0 ? `?${new URLSearchParams(params).toString()}` : ''
    if (win?.location) win.location.hash = `#${route}${query}`
  }

  const mountView = (route, namespaceId, sseClient) => {
    if (onMountOwnedRoutes.has(route)) return
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
        sseClient,
        namespaceId,
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

  const mount = (route, identity = route) => {
    if (identity === currentRouteIdentity) return
    runTeardowns()
    currentRoute = route
    currentRouteIdentity = identity
    const activeId = ROUTES[route].id
    for (const section of doc.querySelectorAll('.cockpit-view')) {
      section.classList.toggle('active', section.id === activeId)
    }
    updateNav(route)
    setLiveIndicator(doc, 'online')
    const namespaceId = resolveNamespace()
    const sseClient = sseClientForRoute(route, namespaceId)
    mountView(route, namespaceId, sseClient)
    if (typeof options.onMount === 'function') {
      try {
        options.onMount(route, { registerTeardown, doc, win, namespaceId, sseClient })
      } catch {
        // A failing view mount must never break the shell navigation.
      }
    }
  }

  const applyHash = () => {
    const route = parseHash(win.location.hash)
    if (!isKnownHash(win.location.hash)) {
      // Canonicalize an empty/unknown hash without adding a history entry. A
      // known route keeps its query string (e.g. `#/detail?workflowId=…`).
      win.history?.replaceState?.(null, '', `#${route}`)
    }
    // The identity carries the query string so `#/detail?workflowId=A` and
    // `#/detail?workflowId=B` are distinct transitions (teardown + remount).
    mount(route, buildRouteIdentity(route, win))
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
    getCurrentRouteIdentity: () => currentRouteIdentity,
    getSseClient,
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
  const namespaceId = resolveNamespaceId(win)
  const streamQuery = namespaceId ? `?namespaceId=${encodeURIComponent(namespaceId)}` : ''
  const sseClient = new SseClient(`/api/factory/workflows/stream${streamQuery}`, {
    onOpen: () => setLiveIndicator(doc, 'online'),
    onError: () => setLiveIndicator(doc, 'reconnecting'),
  }).connect()
  const router = createRouter(win, doc, {
    apiClient: api,
    sseClient,
    mounters: VIEW_MOUNTERS,
    onMountOwnedRoutes: ['/launch'],
    resolveNamespace: () => resolveNamespaceId(win),
    onMount: (route, ctx) => {
      const namespaceId = ctx.namespaceId ?? resolveNamespaceId(win)
      if (route === '/launch') {
        // Governed run launch: pick a definition, resolve the namespace (and
        // optional ticket / FACTORY_ROOT) then start + run the workflow. The
        // rich options (resolved namespace, cockpit navigation) require this
        // hook rather than the generic `VIEW_MOUNTERS` registration.
        const container = doc.getElementById('view-launch')
        if (!container) return
        mountRunLaunchView(container, {
          apiClient: api,
          namespaceId,
          onNavigate: (target, params) => navigateTo(win, target, params),
          registerTeardown: ctx.registerTeardown,
        })
        return
      }
      if (route === '/runs' || route === '/projection') {
        // Home view: the workflow (run) list. Each card renders its
        // human / agent / code swimlanes and clicking one opens its detail
        // timeline. Live-updated by the named `/api/factory/workflows/stream`
        // SSE events (once connected; the namespace param is optional).
        const listContainer = doc.getElementById('view-runs')
        if (!listContainer) return
        mountProjectionView(listContainer, {
          api,
          namespaceId,
          sse: ctx.sseClient,
          registerTeardown: ctx.registerTeardown,
          onNavigate: (target, params) => navigateTo(win, target, params),
          showModal: (content) => showModal(content, doc),
          closeModal: () => closeModal(doc),
        })
        return
      }
      if (route === '/detail') {
        // Dedicated run timeline: reuses the governed projection + swimlanes.
        const container = doc.getElementById('view-detail')
        const workflowId = resolveWorkflowId(win)
        if (!container || !workflowId) return
        let handle = null
        let unmounted = false
        ctx.registerTeardown(() => {
          unmounted = true
          if (typeof handle?.unmount === 'function') handle.unmount()
          handle = null
        })
        Promise.resolve(
          mountRunDetailView(container, {
            workflowId,
            namespaceId,
            apiClient: api,
            sseClient: ctx.sseClient,
            agentosUrl: win.location.origin,
          }),
        )
          .then((resolved) => {
            if (unmounted) {
              if (typeof resolved?.unmount === 'function') resolved.unmount()
              return
            }
            handle = resolved
          })
          .catch(() => {})
        return
      }
    },
  })
  const start = () => router.start()
  if (doc.readyState === 'loading') doc.addEventListener('DOMContentLoaded', start, { once: true })
  else start()
  win.addEventListener?.('beforeunload', () => sseClient.close(), { once: true })
  return { api, router, sseClient, SseClient, showModal: (c) => showModal(c, doc), closeModal: () => closeModal(doc) }
}

if (typeof window !== 'undefined' && typeof document !== 'undefined') {
  bootstrapCockpit()
}

export default bootstrapCockpit
