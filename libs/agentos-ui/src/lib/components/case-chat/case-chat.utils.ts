import {
  Actor,
  ActorRoleEnum,
  AnswerEvent,
  CaseEvent,
  CaseStatusEventStatusEnum,
  MessageEvent as CaseMessageEvent,
  MessageEventAllOfContent,
  MessagingPlatform,
  QuestionEvent,
} from '@whoz-oss/agentos-api-client'
import { messagingPlatformName } from '../messaging-accounts/messaging-platforms'

/**
 * Id of the message a user message re-runs, or null when the event is not a re-run.
 *
 * Only a USER message can re-run another one, like on the server: an agent message carrying a `rerunOf`
 * is not a re-run and cuts nothing.
 */
export function rerunTargetOf(event: CaseEvent): string | null {
  return event.type === 'MessageEvent' && event.actor.role === ActorRoleEnum.USER ? (event.rerunOf ?? null) : null
}

/**
 * The conversation as it effectively stands, computed from the ordered event log of a case. Mirror of the
 * server's `effectiveConversation` (agentos-service, `EffectiveConversation.kt`): both must keep the same rule.
 *
 * The log is append-only: a user who re-runs a message deletes nothing, the new message carries `rerunOf = X`.
 * Walking the log in order, such a message removes from the result built so far X and everything that follows
 * it (the answers, the tool calls, the questions, the sub-cases, the orchestration events...), then takes its
 * place. It works for chained re-runs, since each step only looks at the result built so far.
 *
 * When X is not in the result built so far (unknown id, or already cut by an earlier re-run), nothing is
 * removed and the message is simply added.
 *
 * Returns the argument itself when no message re-runs another one (the common case, no copy), and never
 * modifies it: the SSE stream and the REST listing always return the whole log.
 */
export function effectiveConversation(events: CaseEvent[]): CaseEvent[] {
  if (!events.some((event) => rerunTargetOf(event) !== null)) return events
  const conversation: CaseEvent[] = []
  for (const event of events) {
    const target = rerunTargetOf(event)
    if (target !== null) {
      const index = conversation.findLastIndex((candidate) => candidate.id === target)
      if (index >= 0) conversation.length = index
    }
    conversation.push(event)
  }
  return conversation
}

/**
 * The text of a message, as the server sees it when the message is re-run: its Text parts only, in order.
 * The other parts (audio, images) are not text and are kept as they are by a re-run.
 */
export function textOfMessage(event: CaseMessageEvent): string {
  return (event.content ?? [])
    .filter((part) => (part as { type?: string }).type === 'Text')
    .map((part) => (part as { content?: string }).content ?? '')
    .join('')
}

/**
 * The message with [text] as its text, the way the server builds the re-run of a message: the text replaces
 * the first text part of the original, the other text parts are dropped, and the parts that are not text
 * (audio, images) stay where they were. A message without any text part gets it first.
 *
 * Used to show the new text of a message while its re-run is on its way; the event itself is not modified.
 */
export function withText(event: CaseMessageEvent, text: string): CaseMessageEvent {
  const content: MessageEventAllOfContent[] = []
  let placed = false
  for (const part of event.content ?? []) {
    if ((part as { type?: string }).type !== 'Text') {
      content.push(part)
    } else if (!placed) {
      placed = true
      content.push({ type: 'Text', content: text })
    }
  }
  if (!placed) content.unshift({ type: 'Text', content: text })
  return { ...event, content }
}

/** What the user is told when the server refuses a re-run, by the HTTP status it answered. */
export function rerunErrorMessage(status: number | undefined): string {
  switch (status) {
    case 400:
      return 'This message cannot be re-run.'
    case 403:
      return 'Only the author of a message can re-run it.'
    case 409:
      return 'A run is in progress, or this message is no longer part of the conversation. Try again once the case is idle.'
    default:
      return 'The message could not be sent. Try again.'
  }
}

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
 * Whether [actor] is the human [currentUserId]: a user turn whose id is the current user's, regardless of the
 * UUID letter case. The server holds the same rule for a re-run (only the author of a message can re-run it);
 * this one only decides whether the button is offered. False while the current user is not loaded yet.
 */
export function isAuthorOf(actor: Actor, currentUserId: string | null | undefined): boolean {
  return actor.role === ActorRoleEnum.USER && !!currentUserId && actor.id?.toLowerCase() === currentUserId.toLowerCase()
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
