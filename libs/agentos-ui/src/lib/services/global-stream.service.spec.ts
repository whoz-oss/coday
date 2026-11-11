import { HttpErrorResponse } from '@angular/common/http'
import { signal } from '@angular/core'
import { fakeAsync, TestBed, tick } from '@angular/core/testing'
import { CaseEvent, User } from '@whoz-oss/agentos-api-client'
import { Observable, Subject, defer, of, throwError } from 'rxjs'
import { GlobalCaseEventSseService, GlobalStreamMessage } from './global-case-event-sse.service'
import { GlobalStreamService } from './global-stream.service'
import { TabCoordinatorService } from './tab-coordinator.service'
import { UserStateService } from './user-state.service'

describe('GlobalStreamService', () => {
  let messages$: Subject<GlobalStreamMessage>
  let connect: jest.Mock
  /** How many streams were actually opened: `connect` is called once, then resubscribed on retry. */
  let streamOpens: number
  let broadcastGlobalMessage: jest.Mock
  let whenStreamOwner: jest.Mock
  let relay: (message: GlobalStreamMessage, userId: string) => void
  let becomeStreamOwner: () => void
  let loadMe: jest.Mock<Observable<User>>
  let service: GlobalStreamService

  const signedIn = { id: 'u-1' } as User

  const caseEvent = { id: 'e-1', type: 'ErrorEvent', caseId: 'case-1' } as unknown as CaseEvent
  const caseEventMessage: GlobalStreamMessage = { kind: 'case-event', event: caseEvent }
  const invalidation: GlobalStreamMessage = { kind: 'case-list-invalidated', namespaceId: 'ns-1' }

  /**
   * @param coordinated whether the browser can elect a stream owner and relay its messages.
   *   False is the degraded mode, where every tab opens a stream of its own straight away.
   * @param currentUser the user already loaded at startup, or null when it is still to be asked for.
   */
  function configure(coordinated: boolean, currentUser: User | null = signedIn): void {
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
    whenStreamOwner = jest.fn(() => ownership)
    loadMe = jest.fn(() => of(signedIn))

    TestBed.configureTestingModule({
      providers: [
        GlobalStreamService,
        { provide: GlobalCaseEventSseService, useValue: { connect } },
        {
          provide: TabCoordinatorService,
          useValue: {
            coordinated,
            whenStreamOwner,
            broadcastGlobalMessage,
            onGlobalMessage: (handler: (message: GlobalStreamMessage, userId: string) => void) => (relay = handler),
          },
        },
        { provide: UserStateService, useValue: { currentUser: signal(currentUser), loadMe } },
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
    expect(broadcastGlobalMessage).toHaveBeenCalledWith(invalidation, 'u-1')
  })

  it('consumes the relay of the owning tab without opening a stream of its own', () => {
    configure(true)
    const invalidated: string[] = []
    service.caseListInvalidations$.subscribe((namespaceId) => invalidated.push(namespaceId))
    service.start()

    expect(connect).not.toHaveBeenCalled()

    relay(invalidation, 'u-1')

    expect(invalidated).toEqual(['ns-1'])
    // A relayed message must not be relayed again: that is the owner's job alone.
    expect(broadcastGlobalMessage).not.toHaveBeenCalled()
  })

  it("drops the relay of another user's stream", () => {
    configure(true)
    const invalidated: string[] = []
    service.caseListInvalidations$.subscribe((namespaceId) => invalidated.push(namespaceId))
    service.start()

    // A tab still open from before someone else signed in on this browser, its stream still
    // authenticated as the previous user.
    relay(invalidation, 'u-previous')

    expect(invalidated).toEqual([])
  })

  it('elects one owner per user', () => {
    configure(true)
    service.start()

    expect(whenStreamOwner).toHaveBeenCalledWith('u-1')
  })

  it('asks who is signed in when the startup load has not answered yet', () => {
    configure(true, null)
    service.start()

    expect(loadMe).toHaveBeenCalledTimes(1)
    expect(whenStreamOwner).toHaveBeenCalledWith('u-1')
  })

  it('opens no stream and claims no ownership when nobody is signed in', () => {
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => undefined)
    try {
      configure(false, null)
      loadMe.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 401 })))
      service.start()

      expect(connect).not.toHaveBeenCalled()
      expect(whenStreamOwner).not.toHaveBeenCalled()
    } finally {
      warn.mockRestore()
    }
  })

  it('ignores the relay while it reads the stream itself', () => {
    configure(false)
    const invalidated: string[] = []
    service.caseListInvalidations$.subscribe((namespaceId) => invalidated.push(namespaceId))
    service.start()

    // Degraded mode: this tab has its own stream. Handling the relay on top would process every
    // message once per open tab.
    relay(invalidation, 'u-1')

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

  it('waits longer after each failure in a row, and starts over once a stream opens', fakeAsync(() => {
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => undefined)
    try {
      configure(false)
      service.start()

      messages$.error(new Error('Global event stream closed'))
      tick(5_000)
      expect(streamOpens).toBe(2)

      // Failing again straight away: the second wait is twice the first.
      messages$.error(new Error('Global event stream closed'))
      tick(5_000)
      expect(streamOpens).toBe(2)
      tick(5_000)
      expect(streamOpens).toBe(3)

      // It opens, then drops: back to the shortest wait.
      messages$.next({ kind: 'connected' })
      messages$.error(new Error('Global event stream closed'))
      tick(5_000)
      expect(streamOpens).toBe(4)
    } finally {
      warn.mockRestore()
    }
  }))

  it('stops retrying once the session is refused', fakeAsync(() => {
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => undefined)
    try {
      configure(false)
      service.start()

      loadMe.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 401 })))
      messages$.error(new Error('Global event stream closed'))
      tick(5_000)
      tick(60_000)

      expect(streamOpens).toBe(1)
    } finally {
      warn.mockRestore()
    }
  }))

  it('keeps retrying while the backend itself is down', fakeAsync(() => {
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => undefined)
    try {
      configure(false)
      service.start()

      loadMe.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 502 })))
      messages$.error(new Error('Global event stream closed'))
      tick(5_000)

      expect(streamOpens).toBe(2)
    } finally {
      warn.mockRestore()
    }
  }))

  it('stops retrying once another user has signed in on this browser', fakeAsync(() => {
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => undefined)
    try {
      configure(false)
      service.start()

      // This tab's lock and relays are the previous user's: the new user's tabs elect their own.
      loadMe.mockReturnValue(of({ id: 'u-2' } as User))
      messages$.error(new Error('Global event stream closed'))
      tick(5_000)
      tick(60_000)

      expect(streamOpens).toBe(1)
    } finally {
      warn.mockRestore()
    }
  }))
})
