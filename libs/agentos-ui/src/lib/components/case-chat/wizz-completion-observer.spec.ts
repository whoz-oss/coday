import { WizzCompletionObserver, WizzEventSource } from './wizz-completion-observer'

class FakeEventSource extends EventTarget implements WizzEventSource {
  onerror: ((event: Event) => void) | null = null
  readonly close = jest.fn()
}

describe('WizzCompletionObserver', () => {
  let sources: FakeEventSource[]
  let observer: WizzCompletionObserver
  let completed: jest.Mock
  let openedUrl: string | undefined

  beforeEach(() => {
    sources = []
    completed = jest.fn()
    observer = new WizzCompletionObserver((url) => {
      openedUrl = url
      const source = new FakeEventSource()
      sources.push(source)
      return source
    })
  })

  const emit = (source: FakeEventSource, event: object): void =>
    source.dispatchEvent(new MessageEvent('case-event', { data: JSON.stringify(event) }))
  const answer = (source: FakeEventSource, id: string, agentId = 'agent-1'): void =>
    emit(source, {
      id,
      type: 'MessageEvent',
      caseId: 'case-1',
      actor: { id: agentId, role: 'AGENT' },
      content: [{ type: 'Text', content: 'Done' }],
    })
  const finish = (source: FakeEventSource, id: string, agentId = 'agent-1'): void =>
    emit(source, { id, type: 'AgentFinishedEvent', caseId: 'case-1', agentId })

  it('opens only the live-only stream and completes a replayed pair once', () => {
    observer.start('/api/agentos', 'case-1', completed)
    const source = sources[0]!
    answer(source, 'message-1')
    finish(source, 'finished-1')
    answer(source, 'message-1')
    finish(source, 'finished-1')

    expect(openedUrl).toBe('/api/agentos/api/cases/case-1/events?includePreviousEvents=false')
    expect(completed).toHaveBeenCalledTimes(1)
  })

  it('waits for a matching finish after chunks, tools and intermediate messages', () => {
    observer.start('', 'case-1', completed)
    const source = sources[0]!
    emit(source, { id: 'chunk', type: 'TextChunkEvent', caseId: 'case-1' })
    answer(source, 'intermediate')
    emit(source, { id: 'tool', type: 'ToolResponseEvent', caseId: 'case-1' })
    answer(source, 'final')
    expect(completed).not.toHaveBeenCalled()
    finish(source, 'finished')
    expect(completed).toHaveBeenCalledTimes(1)
  })

  it('clears stale candidates on execution resets, errors, statuses and transport errors', () => {
    observer.start('', 'case-1', completed)
    const source = sources[0]!
    for (const [index, event] of [
      { type: 'AgentRunningEvent' },
      { type: 'AgentSelectedEvent' },
      { type: 'ErrorEvent' },
      { type: 'CaseStatusEvent', status: 'IDLE' },
      { type: 'CaseStatusEvent', status: 'KILLED' },
    ].entries()) {
      answer(source, `message-${index}`)
      emit(source, { id: `reset-${index}`, caseId: 'case-1', ...event })
      finish(source, `finished-${index}`)
    }
    answer(source, 'message-error')
    source.onerror!(new Event('error'))
    finish(source, 'finished-error')
    expect(completed).not.toHaveBeenCalled()
  })

  it('requires matching agents, ignores invalid events, and supports two answering agents', () => {
    observer.start('', 'case-1', completed)
    const source = sources[0]!
    source.dispatchEvent(new MessageEvent('case-event', { data: '{bad json' }))
    emit(source, { id: 'wrong-case', type: 'MessageEvent', caseId: 'other' })
    emit(source, { id: 'invalid-message', type: 'MessageEvent', caseId: 'case-1', actor: null, content: null })
    answer(source, 'message-one', 'agent-1')
    finish(source, 'wrong-finish', 'agent-2')
    answer(source, 'message-two', 'agent-1')
    finish(source, 'finish-two', 'agent-1')
    answer(source, 'message-three', 'agent-2')
    finish(source, 'finish-three', 'agent-2')

    expect(completed).toHaveBeenCalledTimes(2)
  })

  it('closes and rejects captured late handlers after stop or a replacement start', () => {
    observer.start('', 'case-1', completed)
    const oldSource = sources[0]!
    observer.start('', 'case-1', completed)
    const source = sources[1]!
    answer(oldSource, 'old-message')
    finish(oldSource, 'old-finished')
    answer(source, 'message')
    finish(source, 'finished')
    observer.stop()
    answer(source, 'late-message')
    finish(source, 'late-finished')

    expect(oldSource.close).toHaveBeenCalledTimes(1)
    expect(source.close).toHaveBeenCalledTimes(1)
    expect(completed).toHaveBeenCalledTimes(1)
  })
})
