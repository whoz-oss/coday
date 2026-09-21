/**
 * HTTP utilities — send, readBody, and body validation helpers.
 *
 * These functions are transport-level primitives shared across all route modules.
 * They have no dependency on any store, service, or configuration.
 */

/**
 * Send a JSON (or plain-text) HTTP response with CORS headers.
 *
 * @param {import('node:http').ServerResponse} res
 * @param {number} status
 * @param {object|string} body
 * @param {string} [ct]
 */
export function send(res, status, body, ct = 'application/json') {
  const data = typeof body === 'string' ? body : JSON.stringify(body)
  res.writeHead(status, {
    'Content-Type': ct,
    'Access-Control-Allow-Origin': '*',
  })
  res.end(data)
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
