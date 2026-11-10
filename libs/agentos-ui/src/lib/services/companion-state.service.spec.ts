import { signal } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { Case, CaseControllerService, CaseEvent } from '@whoz-oss/agentos-api-client'
import { Subject, of, throwError } from 'rxjs'
import { BrowserNotificationService } from './browser-notification.service'
import { CaseStateService } from './case-state.service'
import { CompanionPipService } from './companion-pip.service'
import { CompanionStateService } from './companion-state.service'
import { GlobalCaseEventSseService } from './global-case-event-sse.service'
import { TabCoordinatorService } from './tab-coordinator.service'

describe('CompanionStateService', () => {
  let events$: Subject<CaseEvent>
  let casesSignal: ReturnType<typeof signal<Case[]>>
  let isOpenSignal: ReturnType<typeof signal<boolean>>
  let getByIdCase: jest.Mock
  let playAlertBeep: jest.Mock
  let browserNotify: jest.Mock
  let browserClose: jest.Mock
  let browserCloseMany: jest.Mock
  let service: CompanionStateService
  let connect: jest.Mock
  let broadcastCaseEvent: jest.Mock
  let relayEvent: (event: CaseEvent) => void
  let becomeStreamOwner: () => void

  /**
   * @param coordinated whether the browser can elect a stream owner and relay its events.
   *   False is the degraded single-tab mode, where the stream opens straight away.
   */
  function configure(coordinated: boolean): void {
    events$ = new Subject<CaseEvent>()
    casesSignal = signal<Case[]>([])
    isOpenSignal = signal<boolean>(false)
    getByIdCase = jest.fn()
    playAlertBeep = jest.fn()
    browserNotify = jest.fn()
    browserClose = jest.fn()
    browserCloseMany = jest.fn()
    connect = jest.fn(() => events$.asObservable())
    broadcastCaseEvent = jest.fn()
    const ownership = new Promise<void>((resolve) => (becomeStreamOwner = resolve))

    TestBed.configureTestingModule({
      providers: [
        CompanionStateService,
        { provide: GlobalCaseEventSseService, useValue: { connect } },
        { provide: CaseStateService, useValue: { cases: casesSignal } },
        { provide: CaseControllerService, useValue: { getByIdCase } },
        { provide: CompanionPipService, useValue: { playAlertBeep, isOpen: isOpenSignal } },
        {
          provide: BrowserNotificationService,
          useValue: { notify: browserNotify, close: browserClose, closeMany: browserCloseMany },
        },
        {
          provide: TabCoordinatorService,
          useValue: {
            coordinated,
            whenStreamOwner: () => ownership,
            broadcastCaseEvent,
            onCaseEvent: (handler: (event: CaseEvent) => void) => (relayEvent = handler),
          },
        },
      ],
    })

    service = TestBed.inject(CompanionStateService)
    service.startSse()
  }

  beforeEach(() => configure(false))

  afterEach(() => TestBed.resetTestingModule())

  it('groups notifications by root case, triggers browser notification when PiP is closed, and clears on AnswerEvent', () => {
    casesSignal.set([{ id: 'case-1', namespaceId: 'ns-1', title: 'Main Case', status: 'RUNNING' } as Case])

    events$.next({
      id: 'q-1',
      type: 'QuestionEvent',
      caseId: 'case-1',
      namespaceId: 'ns-1',
      agentName: 'Archay',
      question: 'Proceed?',
      questionType: 'OPEN_CHOICE',
      options: ['Yes', 'No'],
    } as unknown as CaseEvent)

    expect(playAlertBeep).toHaveBeenCalledTimes(1)
    expect(browserNotify).toHaveBeenCalledWith(
      'case-1',
      'ns-1',
      'Main Case',
      expect.objectContaining({ id: 'q-1', kind: 'question', question: 'Proceed?' })
    )
    expect(service.groups()).toEqual([
      {
        caseId: 'case-1',
        namespaceId: 'ns-1',
        caseTitle: 'Main Case',
        notifications: [
          {
            id: 'q-1',
            caseId: 'case-1',
            namespaceId: 'ns-1',
            kind: 'question',
            blocking: true,
            agentName: 'Archay',
            question: 'Proceed?',
            options: ['Yes', 'No'],
          },
        ],
      },
    ])

    // AnswerEvent removes the matching question notification and closes the browser notification
    events$.next({
      id: 'a-1',
      type: 'AnswerEvent',
      caseId: 'case-1',
      namespaceId: 'ns-1',
      questionId: 'q-1',
    } as unknown as CaseEvent)

    expect(browserClose).toHaveBeenCalledWith('q-1')
    expect(service.groups()).toEqual([])
  })

  it('does not trigger browser notification when Companion PiP is open', () => {
    isOpenSignal.set(true)
    casesSignal.set([{ id: 'case-1', namespaceId: 'ns-1', title: 'Main Case', status: 'RUNNING' } as Case])

    events$.next({
      id: 'f-1',
      type: 'AgentFinishedEvent',
      caseId: 'case-1',
      namespaceId: 'ns-1',
      agentName: 'Archay',
    } as unknown as CaseEvent)

    expect(browserNotify).not.toHaveBeenCalled()
    expect(service.groups()).toHaveLength(1)
  })

  it('ignores non-blocking notifications from sub-cases', () => {
    casesSignal.set([
      { id: 'root-1', namespaceId: 'ns-1', title: 'Main Case', status: 'RUNNING' } as Case,
      { id: 'sub-1', namespaceId: 'ns-1', title: 'Sub Case', parentCaseId: 'root-1', status: 'RUNNING' } as Case,
    ])

    events$.next({
      id: 'f-1',
      type: 'AgentFinishedEvent',
      caseId: 'sub-1',
      namespaceId: 'ns-1',
      agentName: 'Sway',
    } as unknown as CaseEvent)

    events$.next({
      id: 'e-1',
      type: 'ErrorEvent',
      caseId: 'sub-1',
      namespaceId: 'ns-1',
      message: 'Sub-case error',
    } as unknown as CaseEvent)

    expect(service.groups()).toEqual([])
    expect(browserNotify).not.toHaveBeenCalled()
  })

  it('attaches blocking notifications from a sub-case to its parent root case group and clears them when resolved', () => {
    casesSignal.set([{ id: 'root-1', namespaceId: 'ns-1', title: 'Main Case', status: 'RUNNING' } as Case])
    getByIdCase.mockReturnValueOnce(
      of({ id: 'sub-1', namespaceId: 'ns-1', title: 'Sub Case', parentCaseId: 'root-1', status: 'RUNNING' } as Case)
    )

    events$.next({
      id: 'q-sub-1',
      type: 'QuestionEvent',
      caseId: 'sub-1',
      namespaceId: 'ns-1',
      agentName: 'Sway',
      question: 'Which branch should I use?',
      questionType: 'SINGLE_CHOICE',
      options: ['master', 'workstream/forge/master'],
    } as unknown as CaseEvent)

    expect(getByIdCase).toHaveBeenCalledWith('sub-1')
    expect(browserNotify).toHaveBeenCalledWith(
      'root-1',
      'ns-1',
      'Main Case',
      expect.objectContaining({ id: 'q-sub-1', caseId: 'sub-1', kind: 'question' })
    )
    expect(service.groups()).toEqual([
      {
        caseId: 'root-1',
        namespaceId: 'ns-1',
        caseTitle: 'Main Case',
        notifications: [
          {
            id: 'q-sub-1',
            caseId: 'sub-1',
            namespaceId: 'ns-1',
            kind: 'question',
            blocking: true,
            agentName: 'Sway',
            question: 'Which branch should I use?',
            options: ['master', 'workstream/forge/master'],
          },
        ],
      },
    ])

    // Answering on the sub-case removes the notification from the parent's group
    events$.next({
      id: 'a-sub-1',
      type: 'AnswerEvent',
      caseId: 'sub-1',
      namespaceId: 'ns-1',
      questionId: 'q-sub-1',
    } as unknown as CaseEvent)

    expect(browserClose).toHaveBeenCalledWith('q-sub-1')
    expect(service.groups()).toEqual([])
  })

  it('buffers notifications while fetching unknown cases from CaseControllerService', () => {
    getByIdCase.mockReturnValueOnce(
      of({ id: 'remote-1', namespaceId: 'ns-1', title: 'Remote Case', status: 'RUNNING' } as Case)
    )

    events$.next({
      id: 'c-1',
      type: 'PendingConfirmationEvent',
      caseId: 'remote-1',
      namespaceId: 'ns-1',
      toolName: 'GIT__git_push',
      inputJson: '{"forceWithLease":false}',
    } as unknown as CaseEvent)

    expect(getByIdCase).toHaveBeenCalledWith('remote-1')
    expect(service.groups()).toHaveLength(1)
    expect(service.groups()[0]?.caseTitle).toBe('Remote Case')

    // ConfirmationResolvedEvent removes the pending confirmation
    events$.next({
      id: 'cr-1',
      type: 'ConfirmationResolvedEvent',
      caseId: 'remote-1',
      namespaceId: 'ns-1',
      pendingEventId: 'c-1',
    } as unknown as CaseEvent)

    expect(service.groups()).toEqual([])
  })

  it('falls back to short caseId when CaseControllerService.getByIdCase fails', () => {
    getByIdCase.mockReturnValueOnce(throwError(() => new Error('network error')))

    events$.next({
      id: 'err-1',
      type: 'ErrorEvent',
      caseId: '12345678-aaaa-bbbb-cccc-ddddeeeeffff',
      namespaceId: 'ns-1',
      message: 'Failed to connect',
    } as unknown as CaseEvent)

    expect(service.groups()).toHaveLength(1)
    expect(service.groups()[0]?.caseTitle).toBe('12345678')
  })

  // ---------------------------------------------------------------------------
  // Stream ownership — one connection per browser, not per tab (#1616)
  // ---------------------------------------------------------------------------

  const errorEvent = {
    id: 'err-relay',
    type: 'ErrorEvent',
    caseId: 'case-1',
    namespaceId: 'ns-1',
    message: 'Boom',
  } as unknown as CaseEvent

  it('relays the events of the stream it owns to the other tabs', async () => {
    TestBed.resetTestingModule()
    configure(true)
    becomeStreamOwner()
    await Promise.resolve()
    casesSignal.set([{ id: 'case-1', namespaceId: 'ns-1', title: 'Main Case', status: 'RUNNING' } as Case])

    events$.next(errorEvent)

    expect(connect).toHaveBeenCalledTimes(1)
    expect(broadcastCaseEvent).toHaveBeenCalledWith(errorEvent)
    expect(service.groups()).toHaveLength(1)
  })

  it('consumes the relay of the owning tab without opening a stream of its own', () => {
    TestBed.resetTestingModule()
    configure(true)
    casesSignal.set([{ id: 'case-1', namespaceId: 'ns-1', title: 'Main Case', status: 'RUNNING' } as Case])

    expect(connect).not.toHaveBeenCalled()

    relayEvent(errorEvent)

    expect(service.groups()).toHaveLength(1)
    expect(service.groups()[0]?.notifications[0]?.id).toBe('err-relay')
    // A relayed event must not be relayed again: that is the owner's job alone.
    expect(broadcastCaseEvent).not.toHaveBeenCalled()
  })

  it('opens the stream as soon as this tab becomes the owner', async () => {
    TestBed.resetTestingModule()
    configure(true)
    expect(connect).not.toHaveBeenCalled()

    becomeStreamOwner()
    await Promise.resolve()

    expect(connect).toHaveBeenCalledTimes(1)
  })
})
