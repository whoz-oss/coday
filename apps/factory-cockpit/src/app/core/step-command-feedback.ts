import { FactoryApiError } from './factory-api.service'

/** What went wrong with a governed step command (retry, research resumption). */
export type StepFeedbackKind = 'conflict' | 'collision' | 'error'

/** One feedback line shown under the form of the step it concerns. */
export interface StepFeedback {
  stepId: string
  kind: StepFeedbackKind
  message: string
}

/** The idempotency key was already used for a different request (HTTP 409, machine code of the backend). */
export function isIdempotencyKeyCollision(error: FactoryApiError): boolean {
  return error.code === 'IDEMPOTENCY_KEY_COLLISION'
}

/**
 * Classify a failed step command: a key collision, a stale/conflicting state (any other 409) or a plain error.
 * `subject` completes the collision sentence ("The retry was not resent.").
 */
export function stepCommandFeedback(stepId: string, error: FactoryApiError, subject: string): StepFeedback {
  if (isIdempotencyKeyCollision(error)) {
    return {
      stepId,
      kind: 'collision',
      message: `The submission key already exists with different content. The ${subject} was not resent.`,
    }
  }
  if (error.status === 409) {
    return {
      stepId,
      kind: 'conflict',
      message: 'The workflow has changed. Allowed actions have been refreshed; review the new state.',
    }
  }
  return { stepId, kind: 'error', message: error.message }
}
