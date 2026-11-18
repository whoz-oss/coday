import { ActorRoleEnum, CaseEvent, MessageEvent } from '@whoz-oss/agentos-api-client'
import { WizzResponseTracker } from './wizz-response-tracker'

const base = { caseId: 'case', namespaceId: 'namespace', timestamp: '', metadata: { id: '', created: '', modified: '', removed: false } }
const event = (type: string, id: string, fields: object = {}): CaseEvent => ({ type, id, ...base, ...fields }) as CaseEvent
const agentMessage = (id: string, agentId = 'agent-1', text = 'Done'): MessageEvent =>
  event('MessageEvent', id, { actor: { id: agentId, role: ActorRoleEnum.AGENT }, content: [{ type: 'Text', content: text }] }) as MessageEvent

describe('WizzResponseTracker', () => {
  it('qualifies exactly one matching completion after live evidence and a non-empty agent message', () => {
    const tracker = new WizzResponseTracker()
    expect(tracker.consume(event('ThinkingEvent', 'thinking'))).toBe(false)
    expect(tracker.consume(agentMessage('message', 'AGENT-1'))).toBe(false)
    expect(tracker.consume(event('AgentFinishedEvent', 'finished', { agentId: 'agent-1' }))).toBe(true)
    expect(tracker.consume(event('AgentFinishedEvent', 'finished-again', { agentId: 'agent-1' }))).toBe(false)
  })

  it('suppresses history-like, empty, mismatched and user-message sequences', () => {
    const tracker = new WizzResponseTracker()
    expect(tracker.consume(agentMessage('history'))).toBe(false)
    expect(tracker.consume(event('AgentFinishedEvent', 'history-finished', { agentId: 'agent-1' }))).toBe(false)

    tracker.consume(event('TextChunkEvent', 'chunk'))
    tracker.consume(agentMessage('empty', 'agent-1', '  '))
    expect(tracker.consume(event('AgentFinishedEvent', 'empty-finished', { agentId: 'agent-1' }))).toBe(false)

    tracker.consume(event('ThinkingEvent', 'thinking-2'))
    tracker.consume(agentMessage('message-2'))
    expect(tracker.consume(event('AgentFinishedEvent', 'wrong-agent', { agentId: 'agent-2' }))).toBe(false)

    tracker.consume(event('ThinkingEvent', 'thinking-3'))
    tracker.consume(event('MessageEvent', 'user', { actor: { id: 'user', role: ActorRoleEnum.USER }, content: [{ type: 'Text', content: 'Hi' }] }))
    expect(tracker.consume(event('AgentFinishedEvent', 'after-user', { agentId: 'agent-1' }))).toBe(false)
  })

  it('forgets pending evidence at lifecycle and terminal boundaries', () => {
    const tracker = new WizzResponseTracker()
    tracker.consume(event('ThinkingEvent', 'thinking'))
    tracker.reset()
    tracker.consume(agentMessage('after-reset'))
    expect(tracker.consume(event('AgentFinishedEvent', 'after-reset-finished', { agentId: 'agent-1' }))).toBe(false)

    tracker.consume(event('ThinkingEvent', 'thinking-2'))
    tracker.consume(agentMessage('candidate'))
    tracker.consume(event('CaseStatusEvent', 'idle', { status: 'IDLE' }))
    expect(tracker.consume(event('AgentFinishedEvent', 'after-idle', { agentId: 'agent-1' }))).toBe(false)
  })
})
