import { CaseEvent, MessageEvent as CaseMessageEvent } from '@whoz-oss/agentos-api-client'
import { WizzCompletionTracker } from './wizz-completion'

const event = (type: string, fields: object = {}): CaseEvent => ({ type, ...fields }) as CaseEvent
const text = (value: string): CaseMessageEvent =>
  event('MessageEvent', { actor: { role: 'AGENT', id: 'agent-1' }, content: [{ content: value }] }) as CaseMessageEvent

describe('WizzCompletionTracker', () => {
  it('emits once when observed live activity produces matching agent text then finishes', () => {
    const tracker = new WizzCompletionTracker()
    const content = (message: CaseMessageEvent) => message.content?.map((part) => ('content' in part ? part.content : '')).join('') ?? ''

    expect(tracker.accept(event('ThinkingEvent'), content)).toBe(false)
    expect(tracker.accept(event('TextChunkEvent', { chunk: 'Hello' }), content)).toBe(false)
    expect(tracker.accept(text('Completed answer'), content)).toBe(false)
    expect(tracker.accept(event('AgentFinishedEvent', { agentId: 'agent-1' }), content)).toBe(true)
    expect(tracker.accept(event('AgentFinishedEvent', { agentId: 'agent-1' }), content)).toBe(false)
  })

  it('does not emit for replay, empty answers, mismatched agents, or a new turn', () => {
    const tracker = new WizzCompletionTracker()
    const content = (message: CaseMessageEvent) => message.content?.map((part) => ('content' in part ? part.content : '')).join('') ?? ''

    expect(tracker.accept(text('history'), content)).toBe(false)
    expect(tracker.accept(event('AgentFinishedEvent', { agentId: 'agent-1' }), content)).toBe(false)

    tracker.accept(event('ThinkingEvent'), content)
    tracker.accept(text(''), content)
    expect(tracker.accept(event('AgentFinishedEvent', { agentId: 'agent-1' }), content)).toBe(false)

    tracker.accept(event('ThinkingEvent'), content)
    tracker.accept(text('answer'), content)
    expect(tracker.accept(event('AgentFinishedEvent', { agentId: 'another-agent' }), content)).toBe(false)

    tracker.accept(event('ThinkingEvent'), content)
    tracker.accept(text('old answer'), content)
    tracker.accept(event('AgentRunningEvent'), content)
    expect(tracker.accept(event('AgentFinishedEvent', { agentId: 'agent-1' }), content)).toBe(false)
  })

  it('resets eligibility for questions, errors and terminal statuses', () => {
    const tracker = new WizzCompletionTracker()
    const content = () => 'answer'
    for (const boundary of ['QuestionEvent', 'ErrorEvent', 'CaseStatusEvent']) {
      tracker.accept(event('ThinkingEvent'), content)
      tracker.accept(event('MessageEvent', { actor: { role: 'AGENT', id: 'agent-1' } }), content)
      tracker.accept(event(boundary, boundary === 'CaseStatusEvent' ? { status: 'IDLE' } : {}), content)
      expect(tracker.accept(event('AgentFinishedEvent', { agentId: 'agent-1' }), content)).toBe(false)
    }
  })
})
