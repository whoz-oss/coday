import { WizzCompletionObserver, WizzEventSource } from './wizz-completion-observer'

class FakeEventSource extends EventTarget implements WizzEventSource {
  onerror: ((event: Event) => void) | null = null
  readonly close = jest.fn()
}

describe('WizzCompletionObserver', () => {
  let source: FakeEventSource
  let observer: WizzCompletionObserver
  let completed: jest.Mock
  let openedUrl: string | undefined

  beforeEach(() => {
    source = new FakeEventSource()
    completed = jest.fn()
    observer = new WizzCompletionObserver((url) => {
      openedUrl = url
      return source
    })
  })

  const emit = (event: object): void =>
    source.dispatchEvent(new MessageEvent('case-event', { data: JSON.stringify(event) }))

  it('opens only the live-only stream and completes one nonblank agent answer once', () => {
    observer.start('/api/agentos', 'case-1', completed)
    emit({
      id: 'message-1',
      type: 'MessageEvent',
      caseId: 'case-1',
      actor: { id: 'agent-1', role: 'AGENT' },
      content: [{ type: 'Text', content: 'Done' }],
    })
    emit({ id: 'finished-1', type: 'AgentFinishedEvent', caseId: 'case-1', agentId: 'agent-1' })
    emit({ id: 'finished-1', type: 'AgentFinishedEvent', caseId: 'case-1', agentId: 'agent-1' })

    expect(openedUrl).toBe('/api/agentos/api/cases/case-1/events?includePreviousEvents=false')
    expect(completed).toHaveBeenCalledTimes(1)
  })

  it('ignores history-shaped invalid input, chunks, user messages, tools, and a finish without an answer', () => {
    observer.start('', 'case-1', completed)
    source.dispatchEvent(new MessageEvent('case-event', { data: '{bad json' }))
    emit({ id: 'wrong-case', type: 'MessageEvent', caseId: 'case-2', actor: { id: 'agent-1', role: 'AGENT' } })
    emit({ id: 'chunk', type: 'TextChunkEvent', caseId: 'case-1' })
    emit({ id: 'user', type: 'MessageEvent', caseId: 'case-1', actor: { id: 'user-1', role: 'USER' } })
    emit({ id: 'tool', type: 'ToolResponseEvent', caseId: 'case-1' })
    emit({ id: 'finished', type: 'AgentFinishedEvent', caseId: 'case-1', agentId: 'agent-1' })

    expect(completed).not.toHaveBeenCalled()
  })

  it('keeps only the last agent message of an execution and clears candidates on terminal/error events', () => {
    observer.start('', 'case-1', completed)
    emit({ id: 'message-1', type: 'MessageEvent', caseId: 'case-1', actor: { id: 'agent-1', role: 'AGENT' }, content: [{ type: 'Text', content: 'first' }] })
    emit({ id: 'message-2', type: 'MessageEvent', caseId: 'case-1', actor: { id: 'agent-1', role: 'AGENT' }, content: [{ type: 'Text', content: 'final' }] })
    emit({ id: 'error', type: 'ErrorEvent', caseId: 'case-1' })
    emit({ id: 'finished-1', type: 'AgentFinishedEvent', caseId: 'case-1', agentId: 'agent-1' })
    expect(completed).not.toHaveBeenCalled()

    emit({ id: 'message-3', type: 'MessageEvent', caseId: 'case-1', actor: { id: 'agent-2', role: 'AGENT' }, content: [{ type: 'Text', content: 'answer' }] })
    emit({ id: 'finished-2', type: 'AgentFinishedEvent', caseId: 'case-1', agentId: 'agent-2' })
    expect(completed).toHaveBeenCalledTimes(1)
  })

  it('closes and rejects late events after stop', () => {
    observer.start('', 'case-1', completed)
    observer.stop()
    emit({ id: 'message', type: 'MessageEvent', caseId: 'case-1', actor: { id: 'agent-1', role: 'AGENT' }, content: [{ type: 'Text', content: 'answer' }] })
    emit({ id: 'finished', type: 'AgentFinishedEvent', caseId: 'case-1', agentId: 'agent-1' })

    expect(source.close).toHaveBeenCalledTimes(1)
    expect(completed).not.toHaveBeenCalled()
  })
})
