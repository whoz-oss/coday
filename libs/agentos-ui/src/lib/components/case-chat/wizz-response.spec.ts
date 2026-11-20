import { WizzResponse } from './wizz-response'

const metadata = { id: '', created: '', modified: '', removed: false }
const base = { caseId: 'case-1', namespaceId: 'namespace-1', timestamp: '', metadata }
const event = (type: string, id: string, fields: object = {}) => ({ type, id, ...base, ...fields }) as never
const agentMessage = (id: string, text = 'Done', agentId = 'agent-1') =>
  event('MessageEvent', id, { actor: { id: agentId, role: 'AGENT' }, content: [{ type: 'Text', content: text }] })

describe('WizzResponse', () => {
  it('emits once only when live activity, an agent answer and its matching finish occur', () => {
    const wizz = new WizzResponse()
    wizz.setEnabled(true)

    expect(wizz.consume(event('AgentRunningEvent', 'run-1', { agentId: 'agent-1' }))).toBe(false)
    expect(wizz.consume(event('ThinkingEvent', 'thinking-1'))).toBe(false)
    expect(wizz.consume(event('TextChunkEvent', 'chunk-1'))).toBe(false)
    expect(wizz.consume(agentMessage('message-1'), 'Done')).toBe(false)
    expect(wizz.consume(event('AgentFinishedEvent', 'finish-1', { agentId: 'agent-1' }))).toBe(true)
    expect(wizz.consume(event('AgentFinishedEvent', 'finish-1', { agentId: 'agent-1' }))).toBe(false)
  })

  it('rejects durable-only, empty, mismatched, technical and user-event sequences', () => {
    const wizz = new WizzResponse()
    wizz.setEnabled(true)
    expect(wizz.consume(agentMessage('durable-message'), 'Done')).toBe(false)
    expect(wizz.consume(event('AgentFinishedEvent', 'durable-finish', { agentId: 'agent-1' }))).toBe(false)

    wizz.consume(event('ThinkingEvent', 'thinking'))
    wizz.consume(agentMessage('empty-message', '   '), '   ')
    expect(wizz.consume(event('AgentFinishedEvent', 'empty-finish', { agentId: 'agent-1' }))).toBe(false)

    wizz.consume(event('ThinkingEvent', 'thinking-2'))
    wizz.consume(agentMessage('agent-message', 'Done', 'agent-1'), 'Done')
    expect(wizz.consume(event('ToolResponseEvent', 'tool-1'))).toBe(false)
    expect(wizz.consume(event('AgentFinishedEvent', 'wrong-finish', { agentId: 'agent-2' }))).toBe(false)
  })

  it('clears an in-flight candidate for disable, errors, terminal statuses and new runs', () => {
    const wizz = new WizzResponse()
    wizz.setEnabled(true)
    wizz.consume(event('ThinkingEvent', 'thinking-1'))
    wizz.consume(agentMessage('message-1'), 'Done')
    wizz.setEnabled(false)
    expect(wizz.consume(event('AgentFinishedEvent', 'finish-1', { agentId: 'agent-1' }))).toBe(false)

    wizz.setEnabled(true)
    wizz.consume(event('ThinkingEvent', 'thinking-2'))
    wizz.consume(agentMessage('message-2'), 'Done')
    wizz.consume(event('ErrorEvent', 'error-1'))
    expect(wizz.consume(event('AgentFinishedEvent', 'finish-2', { agentId: 'agent-1' }))).toBe(false)

    wizz.consume(event('ThinkingEvent', 'thinking-3'))
    wizz.consume(agentMessage('message-3'), 'Done')
    wizz.consume(event('CaseStatusEvent', 'status-idle', { status: 'IDLE' }))
    expect(wizz.consume(event('AgentFinishedEvent', 'finish-3', { agentId: 'agent-1' }))).toBe(false)

    wizz.consume(event('ThinkingEvent', 'thinking-4'))
    wizz.consume(agentMessage('message-4'), 'Done')
    wizz.consume(event('AgentRunningEvent', 'run-2', { agentId: 'agent-1' }))
    expect(wizz.consume(event('AgentFinishedEvent', 'finish-4', { agentId: 'agent-1' }))).toBe(false)
  })
})
