import {
  ActorRoleEnum,
  AgentFinishedEvent,
  AgentRunningEvent,
  CaseEvent,
  EntityMetadata,
  IntentionGeneratedEvent,
  MessageEvent,
  QuestionEvent,
  QuestionEventQuestionTypeEnum,
  SubCaseStartedEvent,
  TextChunkEvent,
  ToolRequestEvent,
  ToolResponseEvent,
  ToolSelectedEvent,
  WarnEvent,
} from '@whoz-oss/agentos-api-client'
import { DelegationPresentation } from '../delegation/models/delegation.models'
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

function intention(id: string, toolName: string, isFailedIntention = false): IntentionGeneratedEvent {
  return {
    ...base(id),
    type: 'IntentionGeneratedEvent',
    toolName,
    agentId: 'a-1',
    intention: 'A long rationale that never reaches the line.',
    isFailedIntention,
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

function agentFinished(id: string): AgentFinishedEvent {
  return { ...base(id), type: 'AgentFinishedEvent', agentId: 'a-1', agentName: 'Astra' }
}

function subCaseStarted(
  id: string,
  delegationId: string,
  agentName: string,
  toolRequestId = 'd-1'
): SubCaseStartedEvent {
  return {
    ...base(id),
    type: 'SubCaseStartedEvent',
    delegationId,
    agentName,
    subCaseId: `sub-${delegationId}`,
    task: 'Investigate the stall',
    toolRequestId,
    resumed: false,
  }
}

function delegation(
  delegationId: string,
  agentName: string,
  status: DelegationPresentation['status'],
  toolRequestId = 'd-1'
): DelegationPresentation {
  return {
    delegationId,
    toolRequestId,
    subCaseId: `sub-${delegationId}`,
    agentName,
    task: 'Investigate the stall',
    resumed: false,
    status,
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

  it('reads the prefix up to the first delimiter, as the backend does', () => {
    // Splitting on the last one would describe a Notion deletion as a file removal.
    expect(toolActivityLabel('MCP__notion__remove', '{"pageId":"abc"}')).toBe('Running notion remove')
  })

  it('counts every target, however many there are', () => {
    const edits = Array.from({ length: 30 }, (_, index) => ({ operation: 'write', path: `file-${index}.ts` }))
    expect(toolActivityLabel('FILES__editFiles', JSON.stringify({ edits }))).toBe('Editing file-0.ts +29')
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

  it('cuts a long command at its end, keeping the program it runs', () => {
    const command = 'pnpm nx run-many --targets=lint,test --projects=agentos-ui && node ./scripts/check.js'
    const label = toolActivityLabel('bash', JSON.stringify({ command }))
    expect(label.startsWith('Running bash: pnpm nx run-many')).toBe(true)
    expect(label.endsWith('…')).toBe(true)
  })
})

describe('describeAgentActivity', () => {
  it('says the agent is thinking when nothing has happened yet', () => {
    expect(describeAgentActivity([]).label).toBe('Thinking')
    expect(describeAgentActivity([userMessage('m1'), agentRunning('a1')]).label).toBe('Thinking')
  })

  it('announces the tool call that is still running', () => {
    const events = [userMessage('m1'), toolRequest('r1', 'FILES__readFile', '{"filePath":"README.md"}')]
    expect(describeAgentActivity(events)).toMatchObject({ label: 'Reading README.md', detail: 'FILES__readFile' })
  })

  it('announces the chosen tool before its arguments exist', () => {
    expect(describeAgentActivity([intention('i1', 'FILES__listFiles')]).label).toBe('Listing files')
    expect(describeAgentActivity([toolSelected('s1', 'FILES__listFiles')]).label).toBe('Listing files')
  })

  it('moves to the next step once a tool has answered', () => {
    const events = [
      toolRequest('r1', 'FILES__readFile', '{"filePath":"README.md"}'),
      toolResponse('r1', 'FILES__readFile'),
    ]
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

  it('does not carry the previous turn over into the next one', () => {
    // The line is shown again as soon as the user sends a message, before any event of the new
    // turn has arrived: what the previous turn ended on is not what is happening.
    const answered: CaseEvent[] = [question('q1'), agentFinished('f1')]
    expect(describeAgentActivity(answered).label).toBe('Thinking')
    const streamed: CaseEvent[] = [textChunk('t1'), agentFinished('f1')]
    expect(describeAgentActivity(streamed).label).toBe('Thinking')
  })

  it('says nothing of a step the backend has given up on', () => {
    // args = null after the parameter generation failed: the tool is never called.
    expect(describeAgentActivity([toolRequest('r1', 'FILES__readFile')]).label).toBe('Planning the next step')
    expect(describeAgentActivity([intention('i1', 'FILES__readFile', true)]).label).toBe('Thinking')
  })

  it('names the delegate while the sub-case runs, and counts them beyond one', () => {
    const events = [toolRequest('d-1', 'DELEGATE__delegate'), subCaseStarted('s1', 'd1', 'Leo')]
    const one = [delegation('d1', 'Leo', 'WORKING')]
    expect(describeAgentActivity(events, one).label).toBe('Delegating to Leo')

    const both = [...events, subCaseStarted('s2', 'd2', 'Astra')]
    const two = [...one, delegation('d2', 'Astra', 'WORKING')]
    expect(describeAgentActivity(both, two).label).toBe('Delegating to 2 agents')
  })

  it('leaves the delegating tool call to speak once every sub-case is over', () => {
    const events = [
      toolRequest('d-1', 'DELEGATE__delegate', '{"delegations":[{"agentName":"Leo","task":"t"}]}'),
      subCaseStarted('s1', 'd1', 'Leo'),
    ]
    const done = [delegation('d1', 'Leo', 'SUCCESS')]
    expect(describeAgentActivity(events, done).label).toBe('Delegating to Leo')
  })

  it('ignores a sub-case left open by an earlier delegating call', () => {
    // A lost SubCaseFinishedEvent used to make every later delegation read "2 agents".
    const events = [toolRequest('d-2', 'DELEGATE__delegate'), subCaseStarted('s2', 'd2', 'Astra', 'd-2')]
    const delegations = [delegation('d1', 'Leo', 'WORKING', 'd-1'), delegation('d2', 'Astra', 'WORKING', 'd-2')]
    expect(describeAgentActivity(events, delegations).label).toBe('Delegating to Astra')
  })

  it('counts from the event that named the step', () => {
    const request = toolRequest('r1', 'FILES__readFile', '{"filePath":"README.md"}')
    expect(describeAgentActivity([request]).since).toBe(request.timestamp)
  })
})
