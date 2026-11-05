import { signal } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { Case, CaseControllerService, CaseEvent } from '@whoz-oss/agentos-api-client'
import { Subject, of, throwError } from 'rxjs'
import { CaseStateService } from './case-state.service'
import { CompanionPipService } from './companion-pip.service'
import { CompanionStateService } from './companion-state.service'
import { GlobalCaseEventSseService } from './global-case-event-sse.service'

describe('CompanionStateService', () => {
  let events$: Subject<CaseEvent>
  let casesSignal: ReturnType<typeof signal<Case[]>>
  let getByIdCase: jest.Mock
  let playAlertBeep: jest.Mock
  let service: CompanionStateService

  beforeEach(() => {
    events$ = new Subject<CaseEvent>()
    casesSignal = signal<Case[]>([])
    getByIdCase = jest.fn()
    playAlertBeep = jest.fn()

    TestBed.configureTestingModule({
      providers: [
        CompanionStateService,
        { provide: GlobalCaseEventSseService, useValue: { connect: jest.fn(() => events$.asObservable()) } },
        { provide: CaseStateService, useValue: { cases: casesSignal } },
        { provide: CaseControllerService, useValue: { getByIdCase } },
        { provide: CompanionPipService, useValue: { playAlertBeep } },
      ],
    })

    service = TestBed.inject(CompanionStateService)
    service.startSse()
  })

  afterEach(() => TestBed.resetTestingModule())

  it('groups notifications by root case and resolves titles from CaseStateService', () => {
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

    // AnswerEvent removes the matching question notification and cleans up the empty group
    events$.next({
      id: 'a-1',
      type: 'AnswerEvent',
      caseId: 'case-1',
      namespaceId: 'ns-1',
      questionId: 'q-1',
    } as unknown as CaseEvent)

    expect(service.groups()).toEqual([])
  })

  it('filters out sub-cases that have a parentCaseId', () => {
    casesSignal.set([
      { id: 'sub-1', namespaceId: 'ns-1', title: 'Sub Case', parentCaseId: 'root-1', status: 'RUNNING' } as Case,
    ])

    events$.next({
      id: 'f-1',
      type: 'AgentFinishedEvent',
      caseId: 'sub-1',
      namespaceId: 'ns-1',
      agentName: 'Sway',
    } as unknown as CaseEvent)

    expect(service.groups()).toEqual([])
  })

  it('buffers notifications while fetching unknown cases from CaseControllerService and drops sub-cases', () => {
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
})
