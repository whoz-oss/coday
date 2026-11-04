import { Actor, AnswerEvent, CaseEvent, MessagingPlatform, QuestionEvent } from '@whoz-oss/agentos-api-client'

/**
 * Finds the legitimate answer of a question, mirroring the backend rule
 * (`CaseRuntime.findLegitimateAnswerIndex`): the FIRST AnswerEvent, in event order, paired by
 * `questionId` AND coming from the right respondent.
 *
 * - question without `userId` (unaddressed): any respondent qualifies;
 * - question with a `userId`: only an answer whose `actor.id` equals it qualifies.
 *
 * The respondent check must stay part of the search predicate: testing it after picking the
 * first paired answer would let a wrong respondent's answer mask the legitimate one.
 */
export function findLegitimateAnswer(question: QuestionEvent, events: readonly CaseEvent[]): AnswerEvent | undefined {
  return events.find(
    (event): event is AnswerEvent =>
      event.type === 'AnswerEvent' &&
      event.questionId === question.id &&
      (!question.userId || event.actor.id.toLowerCase() === question.userId.toLowerCase())
  )
}

/**
 * The label above a message: "You" for the current user's own messages, the author's name for anyone
 * else's, so that a case shared with others or fed from a chat tells who wrote what. Without a known
 * current user, every user message reads "You", as before cases had several authors.
 */
export function messageAuthorLabel(actor: Actor, currentUserId: string | undefined): string {
  if (actor.role === 'AGENT') return actor.displayName || 'Agent'
  if (!currentUserId || actor.id.toLowerCase() === currentUserId.toLowerCase()) return 'You'
  return actor.displayName || 'Someone'
}

const MESSAGING_PLATFORM_LABELS: Record<MessagingPlatform, string> = {
  [MessagingPlatform.TELEGRAM]: 'Telegram',
}

/**
 * The messaging application a user message was sent from, read from the `messaging` entry the backend
 * writes in its session context (`SessionContextKeys.MESSAGING`). Null for a message typed in the UI.
 */
export function messagingPlatformLabel(sessionContext: { [key: string]: unknown } | undefined): string | null {
  const messaging = sessionContext?.['messaging'] as { platform?: unknown } | undefined
  const platform = messaging?.platform
  return typeof platform === 'string' && platform in MESSAGING_PLATFORM_LABELS
    ? MESSAGING_PLATFORM_LABELS[platform as MessagingPlatform]
    : null
}
