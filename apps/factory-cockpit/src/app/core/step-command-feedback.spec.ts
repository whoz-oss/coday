import { FactoryApiError } from './factory-api.service'
import { isIdempotencyKeyCollision, stepCommandFeedback } from './step-command-feedback'

function apiError(code: string, status: number, message = 'boom'): FactoryApiError {
  return { code, status, message, raw: null }
}

describe('step command feedback', () => {
  it('detects a key collision by its machine code, never by the message', () => {
    expect(isIdempotencyKeyCollision(apiError('IDEMPOTENCY_KEY_COLLISION', 409))).toBe(true)
    expect(isIdempotencyKeyCollision(apiError('REVISION_CONFLICT', 409, 'idempotency key reused'))).toBe(false)
    expect(isIdempotencyKeyCollision(apiError('HTTP_500', 500, 'IDEMPOTENCY_KEY_COLLISION'))).toBe(false)
  })

  it('words the collision with the subject of the command', () => {
    expect(stepCommandFeedback('build', apiError('IDEMPOTENCY_KEY_COLLISION', 409), 'retry')).toEqual({
      stepId: 'build',
      kind: 'collision',
      message: 'The submission key already exists with different content. The retry was not resent.',
    })
    expect(stepCommandFeedback('build', apiError('IDEMPOTENCY_KEY_COLLISION', 409), 'resumption').message).toContain(
      'The resumption was not resent.'
    )
  })

  it('reports any other 409 as a conflict and the rest as the backend error', () => {
    expect(stepCommandFeedback('build', apiError('REVISION_CONFLICT', 409), 'retry')).toEqual({
      stepId: 'build',
      kind: 'conflict',
      message: 'The workflow has changed. Allowed actions have been refreshed; review the new state.',
    })
    expect(stepCommandFeedback('build', apiError('INVALID_REQUEST', 400, 'Bad input'), 'retry')).toEqual({
      stepId: 'build',
      kind: 'error',
      message: 'Bad input',
    })
  })
})
