import { NgZone } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { Configuration } from '../lib/configuration'
import { CaseEventSseService } from './case-event-sse.service'

describe('CaseEventSseService', () => {
  const sources: Array<EventTarget & { close: jest.Mock }> = []
  const originalEventSource = globalThis.EventSource

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [CaseEventSseService, { provide: Configuration, useValue: { basePath: '/agentos' } }, NgZone],
    })
    globalThis.EventSource = jest.fn(() => {
      const source = Object.assign(new EventTarget(), { close: jest.fn(), onerror: null })
      sources.push(source)
      return source
    }) as unknown as typeof EventSource
  })

  afterEach(() => {
    TestBed.resetTestingModule()
    sources.length = 0
    globalThis.EventSource = originalEventSource
  })

  it('keeps the default history-bearing URL and adds the live-only query only when requested', () => {
    const service = TestBed.inject(CaseEventSseService)
    const normal = service.connect('case-1').subscribe()
    const live = service.connect('case-1', { includePreviousEvents: false }).subscribe()

    expect(globalThis.EventSource).toHaveBeenNthCalledWith(1, '/agentos/api/cases/case-1/events')
    expect(globalThis.EventSource).toHaveBeenNthCalledWith(2, '/agentos/api/cases/case-1/events?includePreviousEvents=false')

    normal.unsubscribe()
    live.unsubscribe()
    expect(sources.map((source) => source.close)).toEqual([expect.any(Function), expect.any(Function)])
    expect(sources.every((source) => source.close.mock.calls.length === 1)).toBe(true)
  })

  it('parses case-event frames on an independent connection', () => {
    const received: unknown[] = []
    const subscription = TestBed.inject(CaseEventSseService)
      .connect('case-1', { includePreviousEvents: false })
      .subscribe((event) => received.push(event))

    sources[0]!.dispatchEvent(new MessageEvent('case-event', { data: JSON.stringify({ id: 'event-1', type: 'WarnEvent' }) }))

    expect(received).toEqual([{ id: 'event-1', type: 'WarnEvent' }])
    subscription.unsubscribe()
  })
})
