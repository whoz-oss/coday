/**
 * Workstream routes — GET/POST /api/factory/workstreams
 *
 * Transport only: validation and response mapping live here, while reading and
 * writing forge/bmad/workstreams.toml lives in `workstream-service.mjs`.
 *
 * Returns true when the request was handled, false otherwise.
 */

import { sendError } from './http-utils.mjs'
import { listWorkstreams, createWorkstream, readWorkstreams } from './workstream-service.mjs'

export { readWorkstreams }

/**
 * @param {{ method: string, path: string, url: URL, readBody: () => Promise<object>, send: (status: number, body: unknown) => void, proxy: object, log?: Console }} ctx
 * @returns {Promise<boolean>}
 */
export async function handleWorkstreamRequest({ method, path, url, readBody, send, proxy, log = console }) {
  if (path !== '/api/factory/workstreams') return false

  // GET /api/factory/workstreams?namespaceId=<uuid>
  if (method === 'GET') {
    const result = await listWorkstreams({ proxy, namespaceId: url.searchParams.get('namespaceId') })
    return dispatch(result, send, 200, log), true
  }

  // POST /api/factory/workstreams  Body: { namespaceId, slug, name, status }
  if (method === 'POST') {
    const { namespaceId, slug, name, status } = await readBody()
    const result = await createWorkstream({ proxy, namespaceId, slug, name, status })
    return dispatch(result, send, 201, log), true
  }

  return false
}

function dispatch(result, send, successStatus, log) {
  if (result.ok) return send(successStatus, result.data)
  if (result.status >= 500) log.error?.('workstreams failure', { code: result.code, message: result.message })
  return sendError(send, result.status, result.code, result.message)
}
