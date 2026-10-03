/**
 * Factory Cockpit — REST API client.
 *
 * Vanilla ESM, zero dependencies, zero build step. Thin wrapper around the
 * native `fetch` that owns three cross-cutting concerns for every cockpit call:
 *
 *   1. attribution headers (`X-Factory-*`) — for traceability ONLY, NEVER for
 *      authorization. The server trusts identity exclusively from verified
 *      credentials resolved at the HTTP boundary; these headers are descriptive.
 *   2. correlation id propagation (`X-Correlation-Id`) — echoed inbound when
 *      present, otherwise minted once so one call is traceable end-to-end.
 *   3. a single response contract — `{ data }` envelopes are unwrapped, raw
 *      payloads pass through untouched, and every failure is normalized into a
 *      structured {@link ApiClientError}.
 */

/** Structured error thrown by {@link ApiClient} for every non-2xx response. */
export class ApiClientError extends Error {
  /**
   * @param {string} message
   * @param {{ code?: string|null, status?: number|null, details?: any }} [meta]
   */
  constructor(message, meta = {}) {
    super(message)
    this.name = 'ApiClientError'
    this.code = meta.code ?? null
    this.status = meta.status ?? null
    this.details = meta.details ?? null
  }
}

/** Maps the camelCase attribution fields to their wire headers. */
const ATTRIBUTION_HEADERS = {
  namespaceId: 'X-Factory-Namespace-Id',
  caseId: 'X-Factory-Case-Id',
  actorId: 'X-Factory-Actor-Id',
}

const CORRELATION_ID_HEADER = 'X-Correlation-Id'

const hasOwn = (object, key) => Object.prototype.hasOwnProperty.call(object, key)

/** Mint a correlation id, preferring the platform `crypto.randomUUID`. */
function generateCorrelationId() {
  const crypto = globalThis.crypto
  if (crypto && typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  return `corr-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

/** Case-insensitively look up a header key. */
function findHeaderKey(headers, name) {
  const target = name.toLowerCase()
  return Object.keys(headers).find((key) => key.toLowerCase() === target) ?? null
}

/** True when the value should be serialized as a JSON body. */
function isJsonSerializable(value) {
  if (value === null || typeof value !== 'object') return false
  if (Array.isArray(value)) return true
  if (typeof FormData !== 'undefined' && value instanceof FormData) return false
  if (typeof Blob !== 'undefined' && value instanceof Blob) return false
  if (typeof ArrayBuffer !== 'undefined' && ArrayBuffer.isView(value)) return false
  if (typeof URLSearchParams !== 'undefined' && value instanceof URLSearchParams) return false
  return true
}

export class ApiClient {
  /**
   * @param {{ baseUrl?: string, defaultHeaders?: Record<string, string>, headers?: Record<string, string> }} [options]
   */
  constructor(options = {}) {
    this.baseUrl = options.baseUrl ?? ''
    this.defaultHeaders = { ...(options.defaultHeaders ?? {}), ...(options.headers ?? {}) }
  }

  /**
   * Build the absolute URL. Relative paths are joined to `baseUrl`; absolute
   * URLs are passed through unchanged.
   *
   * @param {string} path
   * @returns {string}
   */
  buildUrl(path) {
    if (/^https?:\/\//i.test(path)) return path
    if (!this.baseUrl) return path
    const base = this.baseUrl.replace(/\/+$/, '')
    return `${base}${path.startsWith('/') ? '' : '/'}${path}`
  }

  /**
   * Resolve the correlation id: explicit option, then an inbound header, then a
   * freshly minted one. Never returns an empty string.
   *
   * @param {{ correlationId?: string, headers?: Record<string, string> }} options
   * @returns {string}
   */
  resolveCorrelationId(options) {
    const headers = options.headers ?? {}
    const explicitKey = findHeaderKey(headers, CORRELATION_ID_HEADER)
    const provided =
      options.correlationId ?? (explicitKey ? headers[explicitKey] : undefined)
    return typeof provided === 'string' && provided.trim() ? provided.trim() : generateCorrelationId()
  }

  /**
   * Perform a REST call and normalize the response.
   *
   * @param {string} path
   * @param {{ method?: string, headers?: Record<string, string>, body?: any,
   *   correlationId?: string, attribution?: { namespaceId?: string, caseId?: string, actorId?: string },
   *   namespaceId?: string, caseId?: string, actorId?: string, signal?: AbortSignal }} [options]
   * @returns {Promise<any>} the unwrapped `data` payload, or the raw payload.
   */
  async request(path, options = {}) {
    const method = (options.method ?? 'GET').toUpperCase()
    const headers = { ...this.defaultHeaders, ...(options.headers ?? {}) }

    // Attribution only — NEVER an authorization signal (see module header).
    const attribution = options.attribution ?? {}
    for (const [field, header] of Object.entries(ATTRIBUTION_HEADERS)) {
      const value = attribution[field] ?? options[field]
      if (value === undefined || value === null || value === '') continue
      if (findHeaderKey(headers, header) === null) headers[header] = String(value)
    }

    // Correlation id is always set, case-insensitively normalized to one key.
    const correlationKey = findHeaderKey(headers, CORRELATION_ID_HEADER)
    if (correlationKey && correlationKey !== CORRELATION_ID_HEADER) delete headers[correlationKey]
    headers[CORRELATION_ID_HEADER] = this.resolveCorrelationId(options)

    let body = options.body
    if (body !== undefined && isJsonSerializable(body)) {
      if (findHeaderKey(headers, 'Content-Type') === null) headers['Content-Type'] = 'application/json'
      body = JSON.stringify(body)
    }

    const response = await fetch(this.buildUrl(path), { method, headers, body, signal: options.signal })
    return this.handleResponse(response)
  }

  /**
   * Normalize a `fetch` response into a payload or a thrown {@link ApiClientError}.
   *
   * @param {Response} response
   * @returns {Promise<any>}
   */
  async handleResponse(response) {
    const status = response.status
    const statusText = response.statusText ?? ''
    const text = typeof response.text === 'function' ? await response.text() : ''

    let payload = null
    let parsed = false
    if (text.length > 0) {
      try {
        payload = JSON.parse(text)
        parsed = true
      } catch {
        parsed = false
      }
    }

    const isObjectPayload = parsed && payload !== null && typeof payload === 'object' && !Array.isArray(payload)
    const hasErrorField = isObjectPayload && payload.error != null

    if (!response.ok || hasErrorField) {
      throw this.normalizeError(status, statusText, payload, text)
    }

    if (isObjectPayload && hasOwn(payload, 'data')) return payload.data
    if (parsed) return payload
    if (text.length === 0) return null
    return text
  }

  /**
   * Normalize any error shape into an {@link ApiClientError}.
   *
   *   A. `{ error: { code, message, ... } }`  → uses `code`/`message`
   *   B. `{ error: "message" }`               → code `HTTP_<status>`
   *   C. plain text / no body                 → code `HTTP_<status>`
   *
   * @param {number} status
   * @param {string} statusText
   * @param {any} payload parsed body when it was JSON, otherwise null
   * @param {string} rawText raw body
   * @returns {ApiClientError}
   */
  normalizeError(status, statusText, payload, rawText) {
    const fallbackCode = `HTTP_${status}`

    if (payload !== null && typeof payload === 'object' && !Array.isArray(payload) && payload.error != null) {
      const error = payload.error
      if (typeof error === 'object' && !Array.isArray(error)) {
        const code = typeof error.code === 'string' && error.code ? error.code : String(status)
        const message = typeof error.message === 'string' && error.message ? error.message : code
        return new ApiClientError(message, { code, status, details: error.details ?? error })
      }
      return new ApiClientError(String(error), { code: fallbackCode, status, details: payload })
    }

    const fallbackMessage = statusText && statusText.trim()
      ? statusText.trim()
      : rawText && rawText.trim()
        ? rawText.trim()
        : `HTTP ${status}`
    return new ApiClientError(fallbackMessage, { code: fallbackCode, status, details: payload })
  }

  /** @param {string} path @param {object} [options] */
  get(path, options = {}) {
    return this.request(path, { ...options, method: 'GET' })
  }

  /** @param {string} path @param {any} [body] @param {object} [options] */
  post(path, body, options = {}) {
    return this.request(path, { ...options, method: 'POST', body })
  }

  /** @param {string} path @param {any} [body] @param {object} [options] */
  put(path, body, options = {}) {
    return this.request(path, { ...options, method: 'PUT', body })
  }

  /**
   * DELETE with either body or options in second position:
   *   `delete(path)`, `delete(path, options)`, `delete(path, body, options)`.
   *
   * @param {string} path
   * @param {any} [bodyOrOptions]
   * @param {object} [maybeOptions]
   */
  delete(path, bodyOrOptions, maybeOptions) {
    if (maybeOptions !== undefined) {
      return this.request(path, { ...maybeOptions, method: 'DELETE', body: bodyOrOptions })
    }
    if (bodyOrOptions === undefined || looksLikeOptions(bodyOrOptions)) {
      return this.request(path, { ...(bodyOrOptions ?? {}), method: 'DELETE' })
    }
    return this.request(path, { method: 'DELETE', body: bodyOrOptions })
  }
}

/** Heuristic: does this object look like a request-options bag? */
function looksLikeOptions(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  return ['headers', 'method', 'correlationId', 'attribution', 'signal', 'namespaceId', 'caseId', 'actorId'].some(
    (key) => hasOwn(value, key),
  )
}

export default ApiClient
