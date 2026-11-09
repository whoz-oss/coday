import { AnswerEvent, CaseEvent, CaseStatusEventStatusEnum, QuestionEvent } from '@whoz-oss/agentos-api-client'

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
 * Whether the case runs after [event], or null when the event does not tell.
 *
 * Mirrors the backend's own run-state reader (`CaseRuntime.closeAgentLeftOpenByCurrentTurn`), which
 * scans back for the same three orchestration events and groups `AgentSelectedEvent` with
 * `AgentRunningEvent`: an agent selected but not yet started still leaves the turn open. A turn can
 * change agent without the case changing status - a redirect, a command-queue drain, a question
 * resumption each emit `AgentFinishedEvent`, then `AgentSelectedEvent`, then `AgentRunningEvent` -
 * so only the whole triple tells the truth about a handoff.
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
          return true
        case CaseStatusEventStatusEnum.CREATED:
        case CaseStatusEventStatusEnum.PENDING:
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
