/**
 * Active-run routes — GET/POST/DELETE /api/factory/active-run
 *
 * Transport only: it parses the request, delegates to the active-run service,
 * and maps the service result to a standardized response. Business logic lives
 * in `active-run-service.mjs`.
 *
 * Returns true when the request was handled (matched route), false otherwise.
 */

import { sendError } from './http-utils.mjs'
import { readActiveRun, writeActiveRun, clearActiveRun } from './active-run-service.mjs'

/**
 * @param {{ method: string, path: string, url: URL, readBody: () => Promise<object>, send: (status: number, body: unknown) => void, proxy: object, log?: Console }} ctx
 * @returns {Promise<boolean>}
 */
export async function handleActiveRunRequest({ method, path, url, readBody, send, proxy, log = console }) {
  if (path !== '/api/factory/active-run') return false

  // GET /api/factory/active-run?namespaceId=<uuid>
  if (method === 'GET') {
    const result = await readActiveRun({ proxy, namespaceId: url.searchParams.get('namespaceId') })
    return dispatch(result, send, 200, log), true
  }

  // POST /api/factory/active-run  Body: { namespaceId, caseId, ticketId }
  if (method === 'POST') {
    const body = await readBody()
    const result = await writeActiveRun({
      proxy,
      namespaceId: body.namespaceId,
      caseId: body.caseId,
      ticketId: body.ticketId,
    })
    return dispatch(result, send, 201, log), true
  }

  // DELETE /api/factory/active-run?namespaceId=<uuid>
  if (method === 'DELETE') {
    const result = await clearActiveRun({ proxy, namespaceId: url.searchParams.get('namespaceId') })
    if (!result.ok) return dispatch(result, send, 200, log), true
    return send(204, ''), true
  }

  return false
}

function dispatch(result, send, successStatus, log) {
  if (result.ok) return send(successStatus, result.data)
  if (result.status >= 500) log.error?.('active-run failure', { code: result.code, message: result.message })
  return sendError(send, result.status, result.code, result.message)
}
