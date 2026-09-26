/**
 * Factory Cockpit — case / thread link component (Milestone D, Wave 3).
 *
 * Vanilla ESM, zero dependencies, zero build step. Renders the identity of a
 * workflow `controllerExecution` as either a clickable link or an inert,
 * readable identifier:
 *
 *   - `kind: 'agentos'` + `caseId` → deep link to the trusted AgentOS base URL
 *     (`agentosUrl` from `GET /api/config`) built with {@link buildAgentosCaseUrl}.
 *   - `kind: 'coday-express'` + `threadId` → readable thread identifier. It is
 *     clickable ONLY when the server exposes a trusted `codayExpressUrl` base in
 *     `GET /api/config`; otherwise it stays plain text (`Thread <id>`).
 *
 * STRICT SSRF INVARIANT — a network URL is NEVER composed from user-controlled
 * input. Every clickable target is resolved against a trusted base URL that is
 * parsed with the WHATWG URL parser, restricted to `http(s)`, and rejected when
 * it carries embedded credentials. The variable path segment (case id / thread
 * id) is always `encodeURIComponent`-ed so path or authority injection is inert.
 *
 * The module is import-safe in Node: no `window`/`document` access happens at
 * module evaluation, only inside {@link createCaseLinkElement}.
 */

const ALLOWED_PROTOCOLS = Object.freeze(['http:', 'https:'])

/** Escape a value for safe HTML text interpolation. */
export function escapeHtml(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/** Escape a value for safe HTML attribute interpolation. */
export function escapeAttr(value) {
  return escapeHtml(value)
}

/**
 * Parse a trusted base URL. Returns `null` when the input is missing, malformed,
 * not `http(s)`, or carries embedded credentials — the single choke point that
 * makes every link target safe.
 *
 * @param {string} baseUrl
 * @returns {URL|null}
 */
function parseTrustedBase(baseUrl) {
  if (typeof baseUrl !== 'string' || !baseUrl.trim()) return null
  let base
  try {
    base = new URL(baseUrl)
  } catch {
    return null
  }
  if (!ALLOWED_PROTOCOLS.includes(base.protocol)) return null
  if (!base.hostname) return null
  // Credentials in the base origin are untrusted and must never be forwarded.
  if (base.username || base.password) return null
  return base
}

/**
 * Build a safe absolute AgentOS case URL, or `null` when the base URL is
 * missing, malformed or untrusted.
 *
 * @param {string} caseId
 * @param {string} agentosUrl trusted base URL (from GET /api/config)
 * @returns {string|null}
 */
export function buildAgentosCaseUrl(caseId, agentosUrl) {
  if (typeof caseId !== 'string' || !caseId.trim()) return null
  const base = parseTrustedBase(agentosUrl)
  if (!base) return null
  try {
    // A leading `/` guarantees same-origin resolution; encodeURIComponent makes
    // any path/authority injection in `caseId` inert.
    return new URL(`/case/${encodeURIComponent(caseId)}`, base).href
  } catch {
    return null
  }
}

/**
 * Build a safe absolute Coday Express thread URL, or `null` when no trusted
 * `codayExpressUrl` base is configured. Only called with a server-provided base
 * from `GET /api/config`; never with arbitrary user input.
 *
 * @param {string} threadId
 * @param {string} baseUrl trusted base URL (from GET /api/config)
 * @returns {string|null}
 */
export function buildCodayExpressThreadUrl(threadId, baseUrl) {
  if (typeof threadId !== 'string' || !threadId.trim()) return null
  const base = parseTrustedBase(baseUrl)
  if (!base) return null
  try {
    return new URL(`/threads/${encodeURIComponent(threadId)}`, base).href
  } catch {
    return null
  }
}

/**
 * Resolve a `controllerExecution` into a DOM-agnostic link descriptor.
 *
 * @param {{ kind?: string, caseId?: string, threadId?: string }|null|undefined} controllerExecution
 * @param {{ agentosUrl?: string, codayExpressUrl?: string, class?: string }} [options]
 * @returns {{ tag: 'a'|'span', href: string|null, label: string, className: string, title: string|null }|null}
 */
export function resolveCaseLink(controllerExecution, options = {}) {
  const execution = controllerExecution && typeof controllerExecution === 'object' ? controllerExecution : {}
  const kind = execution.kind
  const caseId = execution.caseId
  const threadId = execution.threadId
  const extraClass =
    typeof options.class === 'string' && options.class.trim() ? ` ${options.class.trim()}` : ''

  if (kind === 'agentos' && typeof caseId === 'string' && caseId) {
    const label = `Case ${caseId}`
    const href = buildAgentosCaseUrl(caseId, options.agentosUrl)
    if (href) {
      return { tag: 'a', href, label, className: `case-link${extraClass}`, title: 'Ouvrir le case dans AgentOS' }
    }
    // Fallback: no clickable link when the base URL is missing/untrusted.
    return { tag: 'span', href: null, label, className: 'case-id cockpit-id', title: 'Lien AgentOS indisponible' }
  }

  if (typeof threadId === 'string' && threadId) {
    const label = `Thread ${threadId}`
    // Clickable ONLY against a server-provided, trusted base URL.
    const href = buildCodayExpressThreadUrl(threadId, options.codayExpressUrl)
    if (href) {
      return { tag: 'a', href, label, className: `case-link${extraClass}`, title: 'Ouvrir le thread dans Coday Express' }
    }
    return { tag: 'span', href: null, label, className: 'thread-id cockpit-id', title: 'Identifiant de thread Coday' }
  }

  if (typeof caseId === 'string' && caseId) {
    return { tag: 'span', href: null, label: `Case ${caseId}`, className: 'case-id cockpit-id', title: null }
  }

  return null
}

/**
 * Render a `controllerExecution` identity as an escaped HTML string. Returns the
 * empty string when there is nothing to render.
 *
 * @param {{ kind?: string, caseId?: string, threadId?: string }|null|undefined} controllerExecution
 * @param {{ agentosUrl?: string, codayExpressUrl?: string, class?: string }} [options]
 * @returns {string}
 */
export function buildCaseLinkHtml(controllerExecution, options = {}) {
  const link = resolveCaseLink(controllerExecution, options)
  if (!link) return ''
  if (link.tag === 'a') {
    return (
      `<a class="${escapeAttr(link.className)}" href="${escapeAttr(link.href)}" target="_blank" ` +
      `rel="noopener noreferrer" title="${escapeAttr(link.title)}">${escapeHtml(link.label)}</a>`
    )
  }
  const title = link.title ? ` title="${escapeAttr(link.title)}"` : ''
  return `<span class="${escapeAttr(link.className)}"${title}>${escapeHtml(link.label)}</span>`
}

/**
 * Create the identity as a DOM element, without ever touching `innerHTML`.
 * Text is assigned through `textContent`, so untrusted ids stay inert.
 *
 * @param {{ kind?: string, caseId?: string, threadId?: string }|null|undefined} controllerExecution
 * @param {{ agentosUrl?: string, codayExpressUrl?: string, class?: string }} [options]
 * @param {Document|{ createElement: Function }|null} [doc]
 * @returns {Element|null}
 */
export function createCaseLinkElement(controllerExecution, options = {}, doc = globalThis.document) {
  const link = resolveCaseLink(controllerExecution, options)
  if (!link || !doc || typeof doc.createElement !== 'function') return null
  const el = doc.createElement(link.tag)
  el.className = link.className
  el.textContent = link.label
  if (link.title) el.title = link.title
  if (link.tag === 'a') {
    el.setAttribute('href', link.href)
    el.setAttribute('target', '_blank')
    el.setAttribute('rel', 'noopener noreferrer')
  }
  return el
}

export default {
  escapeHtml,
  escapeAttr,
  buildAgentosCaseUrl,
  buildCodayExpressThreadUrl,
  resolveCaseLink,
  buildCaseLinkHtml,
  createCaseLinkElement,
}
