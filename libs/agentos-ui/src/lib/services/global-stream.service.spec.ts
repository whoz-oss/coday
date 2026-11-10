import { fakeAsync, TestBed, tick } from '@angular/core/testing'
import { CaseEvent } from '@whoz-oss/agentos-api-client'
import { Subject, defer } from 'rxjs'
import { GlobalCaseEventSseService, GlobalStreamMessage } from './global-case-event-sse.service'
import { GlobalStreamService } from './global-stream.service'
import { TabCoordinatorService } from './tab-coordinator.service'

describe('GlobalStreamService', () => {
  let messages$: Subject<GlobalStreamMessage>
  let connect: jest.Mock
  /** How many streams were actually opened: `connect` is called once, then resubscribed on retry. */
  let streamOpens: number
  let broadcastGlobalMessage: jest.Mock
  let relay: (message: GlobalStreamMessage) => void
  let becomeStreamOwner: () => void
  let service: GlobalStreamService

  const caseEvent = { id: 'e-1', type: 'ErrorEvent', caseId: 'case-1' } as unknown as CaseEvent
  const caseEventMessage: GlobalStreamMessage = { kind: 'case-event', event: caseEvent }
  const invalidation: GlobalStreamMessage = { kind: 'case-list-invalidated', namespaceId: 'ns-1' }

  /**
   * @param coordinated whether the browser can elect a stream owner and relay its messages.
   *   False is the degraded mode, where every tab opens a stream of its own straight away.
   */
  function configure(coordinated: boolean): void {
    TestBed.resetTestingModule()
    messages$ = new Subject<GlobalStreamMessage>()
    streamOpens = 0
    // Cold, like the real client: each subscription opens its own EventSource, so a retry lands
    // on a fresh stream rather than on the errored one.
    connect = jest.fn(() =>
      defer(() => {
        streamOpens++
        messages$ = new Subject<GlobalStreamMessage>()
        return messages$.asObservable()
      })
    )
    broadcastGlobalMessage = jest.fn()
    const ownership = new Promise<void>((resolve) => (becomeStreamOwner = resolve))

    TestBed.configureTestingModule({
      providers: [
        GlobalStreamService,
        { provide: GlobalCaseEventSseService, useValue: { connect } },
        {
          provide: TabCoordinatorService,
          useValue: {
            coordinated,
            whenStreamOwner: () => ownership,
            broadcastGlobalMessage,
            onGlobalMessage: (handler: (message: GlobalStreamMessage) => void) => (relay = handler),
          },
        },
      ],
    })

    service = TestBed.inject(GlobalStreamService)
  }

  afterEach(() => TestBed.resetTestingModule())

  it('splits the stream into case events and case-list invalidations', () => {
    configure(false)
    const events: CaseEvent[] = []
    const invalidated: string[] = []
    service.caseEvents$.subscribe((event) => events.push(event))
    service.caseListInvalidations$.subscribe((namespaceId) => invalidated.push(namespaceId))
    service.start()

    messages$.next(caseEventMessage)
    messages$.next(invalidation)

    expect(events).toEqual([caseEvent])
    expect(invalidated).toEqual(['ns-1'])
  })

  it('relays what it reads to the other tabs', async () => {
    configure(true)
    service.start()
    becomeStreamOwner()
    await Promise.resolve()

    messages$.next(invalidation)

    expect(streamOpens).toBe(1)
    expect(broadcastGlobalMessage).toHaveBeenCalledWith(invalidation)
  })

  it('consumes the relay of the owning tab without opening a stream of its own', () => {
    configure(true)
    const invalidated: string[] = []
    service.caseListInvalidations$.subscribe((namespaceId) => invalidated.push(namespaceId))
    service.start()

    expect(connect).not.toHaveBeenCalled()

    relay(invalidation)

    expect(invalidated).toEqual(['ns-1'])
    // A relayed message must not be relayed again: that is the owner's job alone.
    expect(broadcastGlobalMessage).not.toHaveBeenCalled()
  })

  it('ignores the relay while it reads the stream itself', () => {
    configure(false)
    const invalidated: string[] = []
    service.caseListInvalidations$.subscribe((namespaceId) => invalidated.push(namespaceId))
    service.start()

    // Degraded mode: this tab has its own stream. Handling the relay on top would process every
    // message once per open tab.
    relay(invalidation)

    expect(invalidated).toEqual([])
  })

  it('opens the stream as soon as this tab becomes the owner', async () => {
    configure(true)
    service.start()
    expect(connect).not.toHaveBeenCalled()

    becomeStreamOwner()
    await Promise.resolve()

    expect(streamOpens).toBe(1)
  })

  // fakeAsync, not jest's fake timers: the retry delay goes through a timer zone.js patched
  // before jest could replace it, so advanceTimersByTime never fires it.
  it('reopens the stream after the browser gives up on it', fakeAsync(() => {
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => undefined)
    try {
      configure(false)
      const invalidated: string[] = []
      service.caseListInvalidations$.subscribe((namespaceId) => invalidated.push(namespaceId))
      service.start()

      // What a restarting backend or an expired session produces: the browser stops retrying.
      messages$.error(new Error('Global event stream closed'))
      tick(5_000)

      expect(streamOpens).toBe(2)
      expect(warn).toHaveBeenCalled()

      messages$.next(invalidation)
      expect(invalidated).toEqual(['ns-1'])
    } finally {
      warn.mockRestore()
    }
  }))
})
