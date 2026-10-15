import { TestBed } from '@angular/core/testing'
import { Subject, BehaviorSubject } from 'rxjs'
import { CodayService } from './coday.service'
import { EventStreamService } from './event-stream.service'
import { MessageApiService } from './message-api.service'
import { UserService } from './user.service'
import { CodayEvent, DelegationEvent, MessageEvent } from '@coday/model'
import { ChatMessage } from '../../components/chat-message/chat-message.component'

function setupTestBed() {
  const eventsSubject = new Subject<CodayEvent>()
  const statusSubject = new BehaviorSubject({ connected: false, reconnectAttempts: 0, maxAttempts: 3 })
  const eventStreamMock = {
    events$: eventsSubject.asObservable(),
    connectionStatus$: statusSubject.asObservable(),
    connectToThread: jest.fn(),
    disconnect: jest.fn(),
    _emit: (event: CodayEvent) => eventsSubject.next(event),
  }
  TestBed.configureTestingModule({
    providers: [
      CodayService,
      { provide: EventStreamService, useValue: eventStreamMock },
      { provide: MessageApiService, useValue: {} },
      { provide: UserService, useValue: { getUsername: () => 'alice' } },
    ],
  })
  return { service: TestBed.inject(CodayService), eventStreamMock }
}

describe('CodayService.handleDelegationEvent', () => {
  afterEach(() => TestBed.resetTestingModule())

  it('creates one block for a single DelegationEvent with open window', () => {
    const { service, eventStreamMock } = setupTestBed()
    const event = new DelegationEvent({
      subThreadId: 'sub-1',
      agentName: 'Searchay',
      timestamp: '2026-01-01T00:00:00.000Z-aaaaa',
    })
    eventStreamMock._emit(event)
    let messages: ChatMessage[] = []
    service.messages$.subscribe((msgs) => (messages = msgs))
    expect(messages).toHaveLength(1)
    expect(messages[0].type).toBe('delegation')
    expect(messages[0].subThreadId).toBe('sub-1')
    expect(messages[0].windowStart).toBe(event.timestamp)
    expect(messages[0].windowEnd).toBeUndefined()
  })

  it('is idempotent: re-delivering the same DelegationEvent is a no-op', () => {
    // Covers dual-channel delivery (REST + SSE replay, debt #343):
    // second delivery must not close the window, which would collapse it to [ts, ts)
    // and hide all content after page reload.
    const { service, eventStreamMock } = setupTestBed()
    const ts = '2026-01-01T00:00:00.000Z-aaaaa'
    const event = new DelegationEvent({ subThreadId: 'sub-1', agentName: 'Searchay', timestamp: ts })
    eventStreamMock._emit(event)
    eventStreamMock._emit(event)
    let messages: ChatMessage[] = []
    service.messages$.subscribe((msgs) => (messages = msgs))
    expect(messages).toHaveLength(1)
    expect(messages[0].windowEnd).toBeUndefined()
  })

  it('creates two blocks for two occurrences with correct windows', () => {
    const { service, eventStreamMock } = setupTestBed()
    const ts1 = '2026-01-01T00:00:00.000Z-aaaaa'
    const ts2 = '2026-01-01T01:00:00.000Z-bbbbb'
    eventStreamMock._emit(new DelegationEvent({ subThreadId: 'sub-1', agentName: 'Searchay', timestamp: ts1 }))
    eventStreamMock._emit(new DelegationEvent({ subThreadId: 'sub-1', agentName: 'Searchay', timestamp: ts2 }))
    let messages: ChatMessage[] = []
    service.messages$.subscribe((msgs) => (messages = msgs))
    expect(messages).toHaveLength(2)
    expect(messages[0].id).toBe(ts1)
    expect(messages[0].windowStart).toBe(ts1)
    expect(messages[0].windowEnd).toBe(ts2)
    expect(messages[1].id).toBe(ts2)
    expect(messages[1].windowStart).toBe(ts2)
    expect(messages[1].windowEnd).toBeUndefined()
  })

  it('idempotent via loadHistoryFromRest: double pass yields identical state', () => {
    const { service } = setupTestBed()
    const ts1 = '2026-01-01T00:00:00.000Z-aaaaa'
    const ts2 = '2026-01-01T01:00:00.000Z-bbbbb'
    const rawEvents = [
      { type: 'delegation', subThreadId: 'sub-1', agentName: 'Searchay', timestamp: ts1 },
      { type: 'delegation', subThreadId: 'sub-1', agentName: 'Searchay', timestamp: ts2 },
    ]
    service.loadHistoryFromRest(rawEvents)
    service.loadHistoryFromRest(rawEvents)
    let messages: ChatMessage[] = []
    service.messages$.subscribe((msgs) => (messages = msgs))
    expect(messages).toHaveLength(2)
    expect(messages[0].windowEnd).toBe(ts2)
    expect(messages[1].windowEnd).toBeUndefined()
  })
})

describe('CodayService.handleMessageEvent', () => {
  afterEach(() => TestBed.resetTestingModule())

  it('adds a MessageEvent to messages$', () => {
    const { service, eventStreamMock } = setupTestBed()
    eventStreamMock._emit(
      new MessageEvent({ role: 'user', name: 'alice', content: [{ type: 'text', content: 'hello' }] })
    )
    let messages: ChatMessage[] = []
    service.messages$.subscribe((msgs) => (messages = msgs))
    expect(messages).toHaveLength(1)
    expect(messages[0].speaker).toBe('alice')
  })

  it('adds a MessageEvent replayed via loadHistoryFromRest', () => {
    const { service } = setupTestBed()
    service.loadHistoryFromRest([
      {
        type: 'message',
        role: 'assistant',
        name: 'agent',
        content: [{ type: 'text', content: 'response' }],
        timestamp: new Date().toISOString(),
      },
    ])
    let messages: ChatMessage[] = []
    service.messages$.subscribe((msgs) => (messages = msgs))
    expect(messages).toHaveLength(1)
  })
})
