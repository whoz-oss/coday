import {
  Actor,
  ActorRoleEnum,
  AnswerEvent,
  CaseEvent,
  CaseStatusEventStatusEnum,
  MessagingPlatform,
  QuestionEvent,
} from '@whoz-oss/agentos-api-client'
import { messagingPlatformName } from '../messaging-accounts/messaging-platforms'

/**
 * Label above a turn of the conversation, for messages and question answers alike.
 *
 * - an agent turn: its display name, 'Agent' when it has none;
 * - a human turn from the current user: 'You';
 * - a human turn from someone else (a collaborator on a shared case): their display name;
 * - no display name (legacy events), or current user not loaded yet: the name when there is one, 'You' otherwise.
 *
 * Ids are compared regardless of the UUID letter case, like the respondent check of [findLegitimateAnswer].
 */
export function senderLabel(actor: Actor, currentUserId: string | null | undefined): string {
  if (actor.role !== ActorRoleEnum.USER) return actor.displayName || 'Agent'
  if (currentUserId && actor.id?.toLowerCase() === currentUserId.toLowerCase()) return 'You'
  return actor.displayName || 'You'
}

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

/**
 * The messaging application a user message was sent from, read from the `messaging` entry the backend
 * writes in its session context (`SessionContextKeys.MESSAGING`). Null for a message typed in the UI.
 */
export function messagingPlatformLabel(sessionContext: { [key: string]: unknown } | undefined): string | null {
  const messaging = sessionContext?.['messaging'] as { platform?: unknown } | undefined
  const platform = messaging?.platform
  return typeof platform === 'string' && (Object.values(MessagingPlatform) as string[]).includes(platform)
    ? messagingPlatformName(platform as MessagingPlatform)
    : null
}

/**
 * Whether the case runs after [event], or null when the event does not tell.
 *
 * Mirrors the backend's own run-state reader (`CaseRuntime.closeAgentLeftOpenByCurrentTurn`), which
 * scans back for the same three orchestration events and groups `AgentSelectedEvent` with
 * `AgentRunningEvent`: an agent selected but not yet started still leaves the turn open. A turn can
 * change agent without the case changing status - a redirect, a command-queue drain, a question
 * resumption each emit `AgentFinishedEvent`, then `AgentSelectedEvent`, then `AgentRunningEvent` -
 * so only the whole triple tells the truth about a handoff.
 *
 * PENDING counts as running: the launch gate has taken the turn and will admit it once the case's
 * workspace is ready, so the case is no more idle than a RUNNING one. Reading it as idle cancelled
 * the optimistic flag the composer sets on submit, and let a second message be posted into the
 * queued turn - which then runs with two consecutive user messages, a shape providers reject.
 *
 * An unrecognised status returns null rather than false, leaving the previous telling event to
 * stand: frames are parsed without validation, and a status this UI does not know must never
 * unlock the composer on a case that may be mid-run.
 */
export function runningAfter(event: CaseEvent): boolean | null {
  switch (event.type) {
    case 'CaseStatusEvent':
      switch (event.status) {
        case CaseStatusEventStatusEnum.RUNNING:
        case CaseStatusEventStatusEnum.PENDING:
          return true
        case CaseStatusEventStatusEnum.CREATED:
        case CaseStatusEventStatusEnum.IDLE:
        case CaseStatusEventStatusEnum.KILLED:
        case CaseStatusEventStatusEnum.ERROR:
          return false
        default:
          return null
      }
    case 'AgentRunningEvent':
    case 'AgentSelectedEvent':
      return true
    case 'AgentFinishedEvent':
      return false
    default:
      return null
  }
}
