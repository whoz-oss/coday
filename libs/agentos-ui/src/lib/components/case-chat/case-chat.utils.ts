import { AnswerEvent, CaseEvent, QuestionEvent } from '@whoz-oss/agentos-api-client'

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
