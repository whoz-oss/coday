import { ActorRoleEnum, CaseEvent } from '@whoz-oss/agentos-api-client'
import { WizzResponseTracker } from './wizz-response-tracker'

const metadata = { id: '', created: '', modified: '', removed: false }
const event = (type: string, id: string, fields: object = {}): CaseEvent =>
  ({ id, type, caseId: 'case-1', namespaceId: 'namespace-1', timestamp: '', metadata, ...fields }) as CaseEvent
const agentMessage = (id: string, agentId = 'agent-1', content = 'Answer'): CaseEvent =>
  event('MessageEvent', id, {
    actor: { id: agentId, role: ActorRoleEnum.AGENT },
    content: [{ type: 'Text', content }],
  })

describe('WizzResponseTracker', () => {
  it('qualifies one live agent answer only when its matching agent finishes', () => {
    const tracker = new WizzResponseTracker()

    expect(tracker.observe(event('ThinkingEvent', 'thinking-1'))).toBeNull()
    expect(tracker.observe(agentMessage('message-1'))).toBeNull()
    expect(tracker.observe(event('AgentFinishedEvent', 'finish-1', { agentId: 'agent-1' }))).toBe('message-1')
    expect(tracker.observe(event('AgentFinishedEvent', 'finish-2', { agentId: 'agent-1' }))).toBeNull()
  })

  it.each([
    ['persisted replay', [agentMessage('message-1'), event('AgentFinishedEvent', 'finish-1', { agentId: 'agent-1' })]],
    ['empty agent message', [event('ThinkingEvent', 'thinking-1'), agentMessage('message-1', 'agent-1', '  '), event('AgentFinishedEvent', 'finish-1', { agentId: 'agent-1' })]],
    ['mismatched agent', [event('TextChunkEvent', 'chunk-1', { chunk: 'live' }), agentMessage('message-1'), event('AgentFinishedEvent', 'finish-1', { agentId: 'agent-2' })]],
  ])('does not qualify %s', (_name, events) => {
    const tracker = new WizzResponseTracker()
    expect(events.map((candidate) => tracker.observe(candidate))).toEqual(events.map(() => null))
  })

  it('clears stale eligibility at a new run boundary and transport reset', () => {
    const tracker = new WizzResponseTracker()
    tracker.observe(event('ThinkingEvent', 'thinking-1'))
    tracker.observe(agentMessage('message-1'))
    tracker.observe(event('AgentRunningEvent', 'running-2'))
    expect(tracker.observe(event('AgentFinishedEvent', 'finish-1', { agentId: 'agent-1' }))).toBeNull()

    tracker.observe(event('TextChunkEvent', 'chunk-2', { chunk: 'live' }))
    tracker.observe(agentMessage('message-2'))
    tracker.reset()
    expect(tracker.observe(event('AgentFinishedEvent', 'finish-2', { agentId: 'agent-1' }))).toBeNull()
  })

  it('allows successive observed responses from the same agent', () => {
    const tracker = new WizzResponseTracker()
    const observeRun = (suffix: string): string | null => {
      tracker.observe(event('TextChunkEvent', `chunk-${suffix}`, { chunk: 'live' }))
      tracker.observe(agentMessage(`message-${suffix}`))
      return tracker.observe(event('AgentFinishedEvent', `finish-${suffix}`, { agentId: 'agent-1' }))
    }

    expect(observeRun('1')).toBe('message-1')
    expect(observeRun('2')).toBe('message-2')
  })
})
