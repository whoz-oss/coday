import { CaseEvent } from '@whoz-oss/agentos-api-client'
import { describeDelegationActivity } from './delegation-activity.models'
import { DelegationPresentation, DelegationStatus } from './delegation.models'

const delegation: DelegationPresentation = {
  delegationId: 'd-1', toolRequestId: 't-1', subCaseId: 'c-1', agentName: 'Research',
  task: 'Find facts', resumed: false, status: 'WORKING',
}

function event(type: CaseEvent['type'], fields: Record<string, unknown> = {}): CaseEvent {
  return { id: type, type, ...fields } as CaseEvent
}

function summary(events: CaseEvent[], status: DelegationStatus = 'WORKING') {
  return describeDelegationActivity({ ...delegation, status }, events)
}

describe('describeDelegationActivity', () => {
  it('defaults to Thinking only while working', () => {
    expect(summary([])).toEqual({ label: 'Thinking', waitingForUser: false })
    for (const status of ['SUCCESS', 'ERROR', 'TIMEOUT', 'KILLED'] as const) {
      expect(summary([event('QuestionEvent', { question: 'Continue?' })], status)).toBeNull()
    }
  })

  it.each<[CaseEvent, string]>([
    [event('ToolRequestEvent', { toolName: 'FILES__readFile', args: '{"filePath":"README.md"}' }), 'Reading README.md'],
    [event('TextChunkEvent'), 'Writing the answer'],
    [event('ToolSelectedEvent', { toolName: 'FILES__readFile' }), 'Reading a file'],
    [event('IntentionGeneratedEvent', { toolName: 'FILES__listFiles' }), 'Listing files'],
    [event('ToolResponseEvent'), 'Planning the next step'],
  ])('reuses rich activity labels for %j', (activityEvent, label) => {
    expect(summary([activityEvent, event('CaseStatusEvent')])?.label).toBe(label)
  })

  it('describes nested delegations', () => {
    expect(summary([event('SubCaseStartedEvent', {
      delegationId: 'nested', toolRequestId: 'nested-tool', agentName: 'Search', subCaseId: 'nested-case',
    })])?.label).toBe('Delegating to Search')
  })

  it('prefers the parent pending question while waiting', () => {
    expect(describeDelegationActivity({ ...delegation, status: 'WAITING_USER', result: {
      delegationId: 'd-1', toolRequestId: 't-1', pendingQuestion: 'Which branch?',
    } }, [event('QuestionEvent', { question: 'Older question' })])).toEqual({
      label: 'Question: Which branch?', waitingForUser: true,
    })
    expect(summary([], 'WAITING_USER')?.label).toBe('Waiting for answer')
  })

  it('preserves unanswered questions across status, finished and diagnostic events', () => {
    const events = [event('QuestionEvent', { id: 'q-1', question: 'Which branch?' }),
      event('AgentFinishedEvent'), event('CaseStatusEvent'), event('WarnEvent')]
    for (const status of ['WORKING', 'WAITING_USER'] as const) {
      expect(summary(events, status)).toEqual({ label: 'Question: Which branch?', waitingForUser: true })
    }
    expect(summary([...events, event('AnswerEvent', { questionId: 'other' })])?.waitingForUser).toBe(true)
    expect(summary([...events, event('AnswerEvent', { questionId: 'q-1' })])?.waitingForUser).toBe(false)
  })

  it('keeps confirmations pending until their matching resolution', () => {
    const events = [event('PendingConfirmationEvent', {
      id: 'p-1', toolName: 'FILES__readFile', inputJson: '{"filePath":"README.md"}',
    }), event('CaseStatusEvent')]
    expect(summary(events)).toEqual({ label: 'Confirmation needed: Reading README.md', waitingForUser: true })
    expect(summary([...events, event('ConfirmationResolvedEvent', { pendingEventId: 'other' })])?.waitingForUser).toBe(true)
    expect(summary([...events, event('ConfirmationResolvedEvent', { pendingEventId: 'p-1' })])?.waitingForUser).toBe(false)
  })

  it('shows the newest unresolved interaction', () => {
    expect(summary([
      event('QuestionEvent', { question: 'Which branch?' }),
      event('PendingConfirmationEvent', { toolName: 'GIT__git_push' }),
    ])?.label).toBe('Confirmation needed: Pushing the branch')
  })
})
