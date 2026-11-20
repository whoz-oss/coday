import { NgZone } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { Configuration } from '../lib/configuration'
import { CaseEventSseService } from './case-event-sse.service'

describe('CaseEventSseService', () => {
  let sources: Array<EventTarget & { close: jest.Mock }>
  const original = globalThis.EventSource

  beforeEach(() => {
    sources = []
    globalThis.EventSource = jest.fn(() => {
      const source = Object.assign(new EventTarget(), { close: jest.fn() })
      sources.push(source)
      return source
    }) as unknown as typeof EventSource
    TestBed.configureTestingModule({
      providers: [CaseEventSseService, { provide: Configuration, useValue: { basePath: '/api/agentos' } }, { provide: NgZone, useValue: { run: (fn: () => void) => fn(), runOutsideAngular: (fn: () => unknown) => fn() } }],
    })
  })

  afterEach(() => {
    TestBed.resetTestingModule()
    globalThis.EventSource = original
  })

  it('keeps the default endpoint and closes that source on unsubscribe', () => {
    const subscription = TestBed.inject(CaseEventSseService).connect('case-1').subscribe()
    expect(globalThis.EventSource).toHaveBeenCalledWith('/api/agentos/api/cases/case-1/events')
    subscription.unsubscribe()
    expect(sources[0]!.close).toHaveBeenCalledTimes(1)
  })

  it('uses the live-only query only when explicitly requested and parses the case-event channel', () => {
    const received: unknown[] = []
    TestBed.inject(CaseEventSseService).connect('case-1', { includePreviousEvents: false }).subscribe((event) => received.push(event))
    expect(globalThis.EventSource).toHaveBeenCalledWith('/api/agentos/api/cases/case-1/events?includePreviousEvents=false')
    sources[0]!.dispatchEvent(new MessageEvent('case-event', { data: JSON.stringify({ id: 'f-1', type: 'AgentFinishedEvent' }) }))
    expect(received).toEqual([{ id: 'f-1', type: 'AgentFinishedEvent' }])
  })
})
