import { sendError as error } from './http-utils.mjs'

const bounded = (value) =>
  String(value ?? '')
    .replace(/[\r\n\t]+/g, ' ')
    .replace(/\s+/g, ' ')
    .slice(0, 1000)
export async function handleFactoryFrontendRunRequest({ method, path, readBody, send, runner, log = console }) {
  const match = path.match(/^\/api\/factory\/workflows\/([^/]+)\/(run|continue|retries)$/)
  if (!match) return false
  if (method !== 'POST') {
    error(send, 405, 'METHOD_NOT_ALLOWED')
    return true
  }
  try {
    const body = await readBody()
    if (!body || typeof body !== 'object' || Array.isArray(body) || typeof body.namespaceId !== 'string')
      return (error(send, 400, 'INVALID_RUN_REQUEST'), true)
    if (match[2] === 'retries') {
      const allowed = new Set(['namespaceId', 'stepId', 'expectedRevision', 'reasonCode'])
      if (
        Object.keys(body).some((key) => !allowed.has(key)) ||
        !Number.isSafeInteger(body.expectedRevision) ||
        typeof body.stepId !== 'string' ||
        typeof body.reasonCode !== 'string'
      )
        return (error(send, 400, 'INVALID_RETRY_REQUEST'), true)
      const result = await runner.openRetry({
        workflowId: decodeURIComponent(match[1]),
        namespaceId: body.namespaceId,
        stepId: body.stepId,
        expectedRevision: body.expectedRevision,
        reasonCode: body.reasonCode,
      })
      send(result.status === 'FAILED' ? 409 : 201, { data: result })
      return true
    }
    const result = await runner({
      workflowId: decodeURIComponent(match[1]),
      namespaceId: body.namespaceId,
      ticket: body.ticket ?? null,
    })
    if (result.code === 'AGENT_PREFLIGHT_FAILED')
      log.error?.('Factory frontend agent preflight failed', { code: result.code, details: bounded(result.details) })
    send(result.status === 'FAILED' ? 409 : 200, { data: result })
  } catch (cause) {
    log.error?.('Factory frontend run failed', { code: cause?.code })
    error(send, 500, cause?.code ?? 'FACTORY_FRONTEND_RUN_FAILED')
  }
  return true
}
