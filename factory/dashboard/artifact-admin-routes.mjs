/**
 * Explicit admin HTTP routes for factory-artifact governance (B5-T2b).
 *
 * Three commands — purge, legal hold and garbage collection — are exposed under
 * `/api/factory/admin/artifacts/*`. Every one of them funnels through the same
 * explicit authorization point, {@link requireAdminRole}, before any use case
 * runs: a non-admin caller gets a `403 FORBIDDEN_ADMIN_REQUIRED` and the store
 * is never touched.
 *
 * The guard inspects the already-resolved `TrustContext` (never client headers).
 * B6 will replace the guard's body with a real entitlement lookup; the single
 * invocation site below is the seam that is expected to stay stable.
 *
 * The handler is transport-only: it validates the route, authorizes, reads the
 * body and delegates to the application use cases. The artifact store and the
 * object-storage client are injected by the composition root.
 */

import { errorBody, requireAdminRole, sendError } from './http-utils.mjs'
import { collectAndAuditGarbage, purgeArtifactAdmin, setLegalHoldAdmin } from '../lib/artifact-admin-use-cases.mjs'

const PURGE_ROUTE = /^\/api\/factory\/admin\/artifacts\/([^/]+)\/purge$/
const LEGAL_HOLD_ROUTE = /^\/api\/factory\/admin\/artifacts\/([^/]+)\/legal-hold$/
const GC_ROUTE = /^\/api\/factory\/admin\/artifacts\/gc$/

/**
 * Handle an admin artifact-governance request.
 *
 * @param {object} context
 * @param {string} context.method
 * @param {string} context.path
 * @param {object} context.trustContext            resolved TrustContext
 * @param {() => Promise<object>} context.readBody
 * @param {(status: number, body: unknown) => void} context.send
 * @param {object} [context.store]                 ArtifactStore port
 * @param {object} [context.blobClient]            ArtifactBlobClient
 * @param {(() => Promise<object[]>) | undefined} [context.listMetadata]
 * @param {object} [context.log]
 * @returns {Promise<boolean>} whether the request was handled
 */
export async function handleArtifactAdminRequest({
  method,
  path,
  trustContext,
  readBody,
  send,
  store,
  blobClient,
  listMetadata,
  log = console,
}) {
  const purgeMatch = path.match(PURGE_ROUTE)
  const legalHoldMatch = path.match(LEGAL_HOLD_ROUTE)
  const gcMatch = GC_ROUTE.test(path)
  if (!purgeMatch && !legalHoldMatch && !gcMatch) return false

  // --- Explicit admin authorization point (invoked for every admin command) ---
  try {
    requireAdminRole(trustContext)
  } catch (error) {
    send(error.statusCode ?? 403, errorBody(error.code ?? 'FORBIDDEN_ADMIN_REQUIRED', error.message, error.reason))
    return true
  }

  if (method !== 'POST') {
    sendError(send, 405, 'METHOD_NOT_ALLOWED', 'Admin artifact commands require POST')
    return true
  }

  try {
    if (gcMatch) {
      const report = await collectAndAuditGarbage(store, blobClient, listMetadata ? { listMetadata } : {})
      send(200, { data: report })
      return true
    }

    const artifactId = decodeURIComponent((purgeMatch ?? legalHoldMatch)[1])

    if (purgeMatch) {
      const body = await readBody()
      const reason = typeof body?.reason === 'string' && body.reason.trim() ? body.reason : 'admin-purge'
      const result = await purgeArtifactAdmin(store, artifactId, reason)
      if (result.success) {
        send(200, { data: result })
      } else {
        const status = result.status === 'NOT_FOUND' ? 404 : 409
        sendError(send, status, result.status, `Admin purge refused: ${result.status}`)
      }
      return true
    }

    const body = await readBody()
    if (typeof body?.legalHold !== 'boolean') {
      sendError(send, 400, 'INVALID_LEGAL_HOLD', '`legalHold` must be a boolean')
      return true
    }
    const reason = typeof body.reason === 'string' && body.reason.trim() ? body.reason : undefined
    const metadata = await setLegalHoldAdmin(store, artifactId, body.legalHold, reason)
    send(200, { data: metadata })
    return true
  } catch (error) {
    log.error?.('Admin artifact command failure', { code: error?.code ?? 'UNEXPECTED' })
    sendError(
      send,
      error?.statusCode ?? 500,
      error?.code ?? 'ARTIFACT_ADMIN_FAILURE',
      error?.message ?? 'Admin artifact command failed'
    )
    return true
  }
}
