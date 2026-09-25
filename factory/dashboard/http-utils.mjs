/**
 * HTTP utilities — transport-level primitives shared across route modules.
 *
 * These functions have no dependency on any store, service, or configuration.
 * They centralise the three transport concerns the Factory HTTP boundary owns:
 *
 *   1. Correlation IDs — every response carries `X-Correlation-Id`; an inbound
 *      header is propagated, otherwise a trace id is generated.
 *   2. A single error contract — `{ "error": { "code", "message", "details" } }`,
 *      produced by `sendError`/`errorBody` and enforced by `send` for legacy
 *      string payloads still emitted by older route modules.
 *   3. TrustContext extraction — identity headers and the loopback/remote bind
 *      policy are resolved once, at the edge, and passed to handlers.
 */

import { randomUUID } from 'node:crypto'

/** Canonical header used for request/response correlation. */
export const CORRELATION_ID_HEADER = 'x-correlation-id'

/** Deterministic fallback error codes so a string error is never opaque. */
const ERROR_CODE_BY_STATUS = Object.freeze({
  400: 'BAD_REQUEST',
  401: 'UNAUTHENTICATED',
  403: 'FORBIDDEN',
  404: 'NOT_FOUND',
  405: 'METHOD_NOT_ALLOWED',
  409: 'CONFLICT',
  410: 'GONE',
  415: 'UNSUPPORTED_MEDIA_TYPE',
  422: 'UNPROCESSABLE_ENTITY',
  429: 'TOO_MANY_REQUESTS',
  500: 'INTERNAL_ERROR',
  501: 'NOT_IMPLEMENTED',
  502: 'BAD_GATEWAY',
  503: 'SERVICE_UNAVAILABLE',
})

/** Generate a correlation id when the caller supplied none. */
export function generateCorrelationId() {
  return `coday-corr-${randomUUID()}`
}

/**
 * Resolve the correlation id for a request: propagate the inbound header when
 * present, otherwise mint one. Never throws on malformed headers.
 *
 * @param {import('node:http').IncomingMessage} req
 * @returns {string}
 */
export function resolveCorrelationId(req) {
  const raw = req?.headers?.[CORRELATION_ID_HEADER]
  const value = Array.isArray(raw) ? raw[0] : raw
  return typeof value === 'string' && value.trim() ? value.trim().slice(0, 256) : generateCorrelationId()
}

/**
 * Build the standardized error envelope.
 *
 * `details` is intentionally omitted when not supplied so a route module's
 * direct (unit-tested) return value stays minimal; the transport `send`
 * normalizer fills `details: null` at the HTTP boundary, so every real
 * response still conforms to `{ error: { code, message, details } }`.
 *
 * @param {string} code
 * @param {string} [message]
 * @param {any} [details]
 * @returns {{ error: { code: string, message: string, details?: any } }}
 */
export function errorBody(code, message, details) {
  const error = { code, message: message ?? code }
  if (details !== undefined) error.details = details
  return { error }
}

/**
 * Upgrade legacy error payloads (`{ error: 'message' }` or a partial object) to
 * the canonical envelope. Non-error bodies are returned untouched.
 *
 * @param {number} status
 * @param {any} body
 * @returns {any}
 */
export function normalizeErrorBody(status, body) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) return body
  if (!Object.hasOwn(body, 'error')) return body
  const fallback = ERROR_CODE_BY_STATUS[status] ?? 'ERROR'
  if (typeof body.error === 'string') {
    return { ...body, error: { code: fallback, message: body.error, details: null } }
  }
  if (body.error && typeof body.error === 'object') {
    const code = typeof body.error.code === 'string' ? body.error.code : fallback
    return {
      ...body,
      error: { ...body.error, code, message: body.error.message ?? code, details: body.error.details ?? null },
    }
  }
  return body
}

/**
 * Send a JSON (or plain-text) HTTP response with CORS + correlation headers.
 *
 * `res.correlationId` is set by the composition root per request; when present
 * it is echoed on every response so a caller can trace one request end-to-end.
 *
 * @param {import('node:http').ServerResponse} res
 * @param {number} status
 * @param {object|string|null} body
 * @param {string} [ct]
 * @param {Record<string, string>} [extraHeaders]
 */
export function send(res, status, body, ct = 'application/json', extraHeaders = {}) {
  const normalized = normalizeErrorBody(status, body)
  const data = typeof normalized === 'string' ? normalized : JSON.stringify(normalized)
  const headers = { 'Content-Type': ct, 'Access-Control-Allow-Origin': '*', ...extraHeaders }
  if (res?.correlationId) headers[CORRELATION_ID_HEADER] = res.correlationId
  res.writeHead(status, headers)
  res.end(data)
}

/**
 * Emit a standardized error response through a route's `send` delegate.
 *
 * @param {(status: number, body: unknown) => void} send
 * @param {number} status
 * @param {string} code
 * @param {string} [message]
 * @param {any} [details]
 */
export function sendError(send, status, code, message, details) {
  return send(status, errorBody(code, message, details))
}

/**
 * Read and JSON-parse the request body.
 * Returns {} on empty or invalid JSON (never throws).
 *
 * @param {import('node:http').IncomingMessage} req
 * @returns {Promise<object>}
 */
export function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = ''
    req.on('data', (c) => { data += c })
    req.on('end', () => { try { resolve(JSON.parse(data || '{}')) } catch { resolve({}) } })
    req.on('error', reject)
  })
}

/**
 * Validate that a Story-edit request body contains only allowed fields.
 *
 * @param {unknown} body
 * @returns {boolean}
 */
export function isAllowedStoryEditRequestBody(body) {
  return (
    !!body &&
    typeof body === 'object' &&
    !Array.isArray(body) &&
    Object.keys(body).every((key) =>
      ['analysisExecutionId', 'namespaceId', 'agentName', 'expectedSpecHash', 'supplement'].includes(key),
    )
  )
}

const LOOPBACK_ADDRESSES = new Set(['127.0.0.1', '::1', '::ffff:127.0.0.1'])

/** True when a socket address is a loopback address (or unavailable). */
export function isLoopbackAddress(address) {
  if (typeof address !== 'string') return true
  return LOOPBACK_ADDRESSES.has(address) || address.startsWith('127.')
}

/**
 * Extract the trusted identity + security context at the HTTP boundary.
 *
 * Identity is never inferred from the message body or the working directory;
 * it comes only from the headers a trusted controller sets. The bind policy
 * (loopback-only by default) is threaded through so downstream handlers can
 * reason about the transport trust level.
 *
 * @param {import('node:http').IncomingMessage} req
 * @param {{ trustMode?: string }} [bindPolicy]
 * @returns {{
 *   namespaceId: string|null, caseId: string|null, actorId: string|null,
 *   authorityId: string|null, runtimeId: string|null, agentId: string|null,
 *   threadId: string|null, correlationId: string|null,
 *   trustMode: string, loopback: boolean,
 * }}
 */
export function extractTrustContext(req, bindPolicy = {}) {
  const header = (name) => {
    const raw = req?.headers?.[name]
    const value = Array.isArray(raw) ? raw[0] : raw
    return typeof value === 'string' && value.length > 0 ? value : null
  }
  return {
    namespaceId: header('x-factory-namespace-id'),
    caseId: header('x-factory-case-id'),
    actorId: header('x-factory-actor-id'),
    authorityId: header('x-factory-authority-id'),
    runtimeId: header('x-factory-runtime-id'),
    agentId: header('x-factory-agent-id'),
    threadId: header('x-factory-thread-id'),
    correlationId: header(CORRELATION_ID_HEADER),
    trustMode: bindPolicy?.trustMode ?? 'loopback-only',
    loopback: isLoopbackAddress(req?.socket?.remoteAddress),
  }
}
