import { sendError } from './http-utils.mjs'

const status = {
  RESULT_SCHEMA_INVALID: 400,
  RESULT_CAPABILITY_INVALID: 401,
  RESULT_IDENTITY_MISMATCH: 403,
  RESULT_CAPABILITY_EXPIRED: 410,
  RESULT_SEMANTIC_COLLISION: 409,
}
export async function handleAgentStepResultRequest({
  method,
  path,
  headers,
  readBody,
  send,
  resultStore,
  log = console,
}) {
  if (path !== '/api/factory/agent-step-results') return false
  if (method !== 'POST') {
    sendError(send, 405, 'METHOD_NOT_ALLOWED')
    return true
  }
  try {
    const authorization = headers.authorization,
      token = typeof authorization === 'string' && authorization.startsWith('Bearer ') ? authorization.slice(7) : null,
      body = await readBody(),
      allowed = new Set(['attemptId', 'result'])
    if (
      !body ||
      typeof body !== 'object' ||
      Array.isArray(body) ||
      Object.keys(body).some((key) => !allowed.has(key)) ||
      typeof body.attemptId !== 'string'
    ) {
      sendError(send, 400, 'INVALID_RESULT_REQUEST')
      return true
    }
    const result = await resultStore.submit(token, body.result, {
      attemptId: body.attemptId,
      caseId: headers['x-agentos-case-id'],
      agentName: headers['x-agentos-agent-name'],
    })
    if (!result.ok) {
      log.warn?.('Structured step result rejected', {
        attemptId: body.attemptId,
        code: result.code,
        status: status[result.code] ?? 409,
      })
      sendError(send, status[result.code] ?? 409, result.code)
      return true
    }
    send(result.idempotent ? 200 : 201, {
      data: { resultId: result.result.resultId, idempotent: result.idempotent, resultHash: result.result.resultHash },
    })
  } catch (cause) {
    log.error?.('Structured step result rejected', { code: cause?.code ?? 'STEP_RESULT_SUBMISSION_FAILED' })
    sendError(send, 500, 'STEP_RESULT_SUBMISSION_FAILED')
  }
  return true
}
