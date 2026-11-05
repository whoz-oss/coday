import { CaseEvent } from '@whoz-oss/agentos-api-client'
import { describeAgentActivity, toolActivityLabel } from '../../agent-activity/agent-activity.models'
import { buildDelegations, DelegationPresentation } from './delegation.models'

export interface DelegationActivity {
  label: string
  waitingForUser: boolean
}

/** Pending interactions survive diagnostic/status events until their matching resolution arrives. */
export function describeDelegationActivity(
  delegation: DelegationPresentation,
  events: readonly CaseEvent[]
): DelegationActivity | null {
  if (delegation.status !== 'WORKING' && delegation.status !== 'WAITING_USER') return null

  if (delegation.status === 'WAITING_USER' && delegation.result?.pendingQuestion) {
    return { label: `Question: ${delegation.result.pendingQuestion}`, waitingForUser: true }
  }

  const answered = new Set<string>()
  const resolved = new Set<string>()
  for (let index = events.length - 1; index >= 0; index--) {
    const event = events[index]!
    if (event.type === 'AnswerEvent') answered.add(event.questionId)
    if (event.type === 'ConfirmationResolvedEvent') resolved.add(event.pendingEventId)
    if (event.type === 'QuestionEvent' && !answered.has(event.id)) {
      return { label: `Question: ${event.question}`, waitingForUser: true }
    }
    if (event.type === 'PendingConfirmationEvent' && !resolved.has(event.id)) {
      return {
        label: `Confirmation needed: ${toolActivityLabel(event.toolName, event.inputJson)}`,
        waitingForUser: true,
      }
    }
  }

  if (delegation.status === 'WAITING_USER') return { label: 'Waiting for answer', waitingForUser: true }

  // Status bookkeeping should not hide the live step that immediately preceded it.
  const activityEvents = events.filter((event) => event.type !== 'CaseStatusEvent')
  return {
    label: describeAgentActivity(activityEvents, buildDelegations(activityEvents)).label,
    waitingForUser: false,
  }
}
