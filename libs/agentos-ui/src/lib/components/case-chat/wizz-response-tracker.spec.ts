import { WizzResponseTracker } from './wizz-response-tracker'

const metadata = { id: '', created: '', modified: '', removed: false }
const event = (type: string, id: string, fields: object = {}) =>
  ({ type, id, caseId: 'case-1', namespaceId: 'ns-1', timestamp: '', metadata, ...fields }) as never
const agentMessage = (id: string, agentId = 'agent-1') =>
  event('MessageEvent', id, { actor: { id: agentId, role: 'AGENT' } }) as never

const consume = (tracker: WizzResponseTracker, item: ReturnType<typeof event>, text?: string) => tracker.consume(item, text)

describe('WizzResponseTracker', () => {
  it('qualifies one completed response after transient evidence and a non-empty agent message', () => {
    const tracker = new WizzResponseTracker()
    expect(consume(tracker, event('ThinkingEvent', 'thinking'))).toBe(false)
    expect(consume(tracker, agentMessage('message'), 'Done')).toBe(false)
    expect(consume(tracker, event('AgentFinishedEvent', 'finished', { agentId: 'AGENT-1' }))).toBe(true)
    expect(consume(tracker, event('AgentFinishedEvent', 'finished-again', { agentId: 'agent-1' }))).toBe(false)
  })

  it('also accepts text chunks, but never technical events or a completion before the message', () => {
    const tracker = new WizzResponseTracker()
    consume(tracker, event('ToolResponseEvent', 'tool'))
    expect(consume(tracker, event('AgentFinishedEvent', 'finished', { agentId: 'agent-1' }))).toBe(false)
    consume(tracker, event('TextChunkEvent', 'chunk', { chunk: 'Hello' }))
    expect(consume(tracker, event('AgentFinishedEvent', 'finished-2', { agentId: 'agent-1' }))).toBe(false)
    consume(tracker, agentMessage('message'), 'Hello')
    expect(consume(tracker, event('AgentFinishedEvent', 'finished-3', { agentId: 'agent-1' }))).toBe(true)
  })

  it('rejects empty, user, and mismatched agent messages', () => {
    const tracker = new WizzResponseTracker()
    consume(tracker, event('ThinkingEvent', 'thinking'))
    consume(tracker, agentMessage('empty'), '   ')
    expect(consume(tracker, event('AgentFinishedEvent', 'finished', { agentId: 'agent-1' }))).toBe(false)

    consume(tracker, event('ThinkingEvent', 'thinking-2'))
    consume(tracker, event('MessageEvent', 'user', { actor: { id: 'user-1', role: 'USER' } }), 'Question')
    consume(tracker, agentMessage('agent'), 'Answer')
    expect(consume(tracker, event('AgentFinishedEvent', 'mismatch', { agentId: 'agent-2' }))).toBe(false)
  })

  it('drops evidence at turn and terminal boundaries so it cannot leak into another response', () => {
    const tracker = new WizzResponseTracker()
    consume(tracker, event('TextChunkEvent', 'chunk', { chunk: 'partial' }))
    consume(tracker, event('AgentRunningEvent', 'next-run'))
    consume(tracker, agentMessage('message'), 'Answer')
    expect(consume(tracker, event('AgentFinishedEvent', 'finished', { agentId: 'agent-1' }))).toBe(false)

    consume(tracker, event('ThinkingEvent', 'thinking'))
    consume(tracker, event('CaseStatusEvent', 'idle', { status: 'IDLE' }))
    consume(tracker, agentMessage('message-2'), 'Answer')
    expect(consume(tracker, event('AgentFinishedEvent', 'finished-2', { agentId: 'agent-1' }))).toBe(false)
  })
})
