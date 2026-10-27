import {
  ActorRoleEnum,
  EntityMetadata,
  AgentRunningEvent,
  CaseEvent,
  ConfirmationResolvedEvent,
  IntentionGeneratedEvent,
  MessageEvent,
  PendingConfirmationEvent,
  QuestionEvent,
  QuestionEventQuestionTypeEnum,
  SubCaseFinishedEvent,
  SubCaseFinishedEventOutcomeEnum,
  SubCaseStartedEvent,
  TextChunkEvent,
  ToolRequestEvent,
  ToolResponseEvent,
  ToolSelectedEvent,
  WarnEvent,
} from '@whoz-oss/agentos-api-client'
import { describeAgentActivity, toolActivityLabel } from './agent-activity.models'

const metadata: EntityMetadata = { id: '', created: '', modified: '', removed: false }

interface EventBase {
  id: string
  caseId: string
  namespaceId: string
  timestamp: string
  metadata: EntityMetadata
}

function base(id: string): EventBase {
  return { id, caseId: 'c-1', namespaceId: 'ns-1', timestamp: '2026-01-01T00:00:00Z', metadata }
}

function toolRequest(id: string, toolName: string, args?: string): ToolRequestEvent {
  return { ...base(id), type: 'ToolRequestEvent', toolName, toolRequestId: id, args }
}

function toolResponse(id: string, toolName: string): ToolResponseEvent {
  return {
    ...base(id),
    type: 'ToolResponseEvent',
    toolName,
    toolRequestId: id,
    images: [],
    output: {},
    success: true,
    toolMetadata: {},
  }
}

function toolSelected(id: string, toolName: string): ToolSelectedEvent {
  return { ...base(id), type: 'ToolSelectedEvent', toolName, agentId: 'a-1' }
}

function intention(id: string, toolName: string): IntentionGeneratedEvent {
  return {
    ...base(id),
    type: 'IntentionGeneratedEvent',
    toolName,
    agentId: 'a-1',
    intention: 'A long rationale that never reaches the line.',
    isFailedIntention: false,
  }
}

function userMessage(id: string): MessageEvent {
  return {
    ...base(id),
    type: 'MessageEvent',
    actor: { id: 'u-1', role: ActorRoleEnum.USER, displayName: 'Selim' },
    content: [],
  }
}

function agentRunning(id: string): AgentRunningEvent {
  return { ...base(id), type: 'AgentRunningEvent', agentId: 'a-1', agentName: 'Astra' }
}

function warn(id: string): WarnEvent {
  return { ...base(id), type: 'WarnEvent', message: 'Something to know about' }
}

function textChunk(id: string): TextChunkEvent {
  return { ...base(id), type: 'TextChunkEvent', chunk: 'Hel' }
}

function question(id: string): QuestionEvent {
  return {
    ...base(id),
    type: 'QuestionEvent',
    agentId: 'a-1',
    agentName: 'Astra',
    question: 'Which branch?',
    questionType: QuestionEventQuestionTypeEnum.FREE_TEXT,
  }
}

function pendingConfirmation(id: string, toolName: string, inputJson: string): PendingConfirmationEvent {
  return {
    ...base(id),
    type: 'PendingConfirmationEvent',
    toolName,
    toolRequestId: 'r-1',
    inputJson,
    toolConfirmationInstructions: 'Confirm before writing',
  }
}

function confirmationResolved(id: string, pendingEventId: string): ConfirmationResolvedEvent {
  return { ...base(id), type: 'ConfirmationResolvedEvent', pendingEventId, confirmed: true, resultText: 'ok' }
}

function subCaseStarted(id: string, delegationId: string, agentName: string): SubCaseStartedEvent {
  return {
    ...base(id),
    type: 'SubCaseStartedEvent',
    delegationId,
    agentName,
    subCaseId: `sub-${delegationId}`,
    task: 'Investigate the stall',
    toolRequestId: 'd-1',
    resumed: false,
  }
}

function subCaseFinished(id: string, delegationId: string, agentName: string): SubCaseFinishedEvent {
  return {
    ...base(id),
    type: 'SubCaseFinishedEvent',
    delegationId,
    agentName,
    subCaseId: `sub-${delegationId}`,
    toolRequestId: 'd-1',
    outcome: SubCaseFinishedEventOutcomeEnum.SUCCESS,
  }
}

describe('toolActivityLabel', () => {
  it('names the file a read is about', () => {
    expect(toolActivityLabel('FILES__readFile', '{"filePath":"src/app/main.ts"}')).toBe('Reading src/app/main.ts')
  })

  it('falls back to the plain sentence before the arguments are generated', () => {
    expect(toolActivityLabel('FILES__readFile')).toBe('Reading a file')
  })

  it('reads a path nested in the arguments and counts the other ones', () => {
    const args = '{"edits":[{"operation":"write","path":"a.ts"},{"operation":"patch","path":"b.ts"}]}'
    expect(toolActivityLabel('case-exchange__editFiles', args)).toBe('Editing a.ts +1')
  })

  it('prefers the key that best describes the tool over the first one in the tree', () => {
    const args = '{"path":"src","fileName":"*.spec.ts"}'
    expect(toolActivityLabel('FILES__searchFiles', args)).toBe('Searching files for *.spec.ts')
  })

  it('names the delegate of a delegation', () => {
    const args = '{"delegations":[{"agentName":"Leo","task":"Investigate the stall"}]}'
    expect(toolActivityLabel('DELEGATE__delegate', args)).toBe('Delegating to Leo')
  })

  it('phrases a snake_case action of the catalogue', () => {
    expect(toolActivityLabel('GIT__git_status')).toBe('Checking the Git status')
  })

  it('builds a sentence from the name of a tool it does not know', () => {
    expect(toolActivityLabel('JIRA__createIssue', '{"title":"Fix the login"}')).toBe('Creating issue: Fix the login')
  })

  it('still says what it runs when the name opens on no known verb', () => {
    expect(toolActivityLabel('bash', '{"command":"pnpm lint"}')).toBe('Running bash: pnpm lint')
  })

  it('ignores arguments that are not JSON', () => {
    expect(toolActivityLabel('FILES__readFile', 'src/app/main.ts')).toBe('Reading a file')
  })

  it('elides a long path from its left so the file name stays readable', () => {
    const long = `${'nested/'.repeat(12)}case-chat.component.ts`
    const label = toolActivityLabel('FILES__readFile', JSON.stringify({ filePath: long }))
    expect(label.startsWith('Reading …')).toBe(true)
    expect(label.endsWith('case-chat.component.ts')).toBe(true)
    expect(label.length).toBeLessThanOrEqual('Reading '.length + 48)
  })
})

describe('describeAgentActivity', () => {
  it('says the agent is thinking when nothing has happened yet', () => {
    expect(describeAgentActivity([]).label).toBe('Thinking')
    expect(describeAgentActivity([userMessage('m1'), agentRunning('a1')]).label).toBe('Thinking')
  })

  it('announces the tool call that is still running', () => {
    const events = [userMessage('m1'), toolRequest('r1', 'FILES__readFile', '{"filePath":"README.md"}')]
    expect(describeAgentActivity(events)).toEqual({ label: 'Reading README.md', detail: 'FILES__readFile' })
  })

  it('announces the chosen tool before its arguments exist', () => {
    expect(describeAgentActivity([intention('i1', 'FILES__listFiles')]).label).toBe('Listing files')
    expect(describeAgentActivity([toolSelected('s1', 'FILES__listFiles')]).label).toBe('Listing files')
  })

  it('moves to the next step once a tool has answered', () => {
    const events = [toolRequest('r1', 'FILES__readFile'), toolResponse('r1', 'FILES__readFile')]
    expect(describeAgentActivity(events).label).toBe('Planning the next step')
  })

  it('walks past the events that say nothing about the current step', () => {
    const events = [toolRequest('r1', 'FILES__readFile', '{"filePath":"README.md"}'), warn('w1')]
    expect(describeAgentActivity(events).label).toBe('Reading README.md')
  })

  it('reports the answer being written', () => {
    const events = [toolResponse('r1', 'FILES__readFile'), textChunk('t1')]
    expect(describeAgentActivity(events).label).toBe('Writing the answer')
  })

  it('reports what the turn waits for', () => {
    expect(describeAgentActivity([question('q1')]).label).toBe('Waiting for your answer')
    const pending = pendingConfirmation('p1', 'FILES__remove', '{"path":"a.ts"}')
    expect(describeAgentActivity([pending]).label).toBe('Waiting for your confirmation')
  })

  it('announces the confirmed tool once the user has resolved the confirmation', () => {
    const pending = pendingConfirmation('p1', 'FILES__remove', '{"path":"a.ts"}')
    const events: CaseEvent[] = [pending, confirmationResolved('c1', 'p1')]
    expect(describeAgentActivity(events)).toEqual({ label: 'Removing a.ts', detail: 'FILES__remove' })
  })

  it('names the delegate while the sub-case runs, and counts them beyond one', () => {
    const delegating = toolRequest('d-1', 'DELEGATE__delegate')
    expect(describeAgentActivity([delegating, subCaseStarted('s1', 'd1', 'Leo')]).label).toBe('Delegating to Leo')
    const two = [delegating, subCaseStarted('s1', 'd1', 'Leo'), subCaseStarted('s2', 'd2', 'Astra')]
    expect(describeAgentActivity(two).label).toBe('Delegating to 2 agents')
  })

  it('leaves the delegating tool call to speak once every sub-case is over', () => {
    const events: CaseEvent[] = [
      toolRequest('d-1', 'DELEGATE__delegate', '{"delegations":[{"agentName":"Leo","task":"t"}]}'),
      subCaseStarted('s1', 'd1', 'Leo'),
      subCaseFinished('f1', 'd1', 'Leo'),
    ]
    expect(describeAgentActivity(events).label).toBe('Delegating to Leo')
  })
})
