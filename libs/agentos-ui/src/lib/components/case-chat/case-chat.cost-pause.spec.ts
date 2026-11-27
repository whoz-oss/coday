import { JsonPipe, NgTemplateOutlet } from '@angular/common'
import { HttpClient } from '@angular/common/http'
import { NO_ERRORS_SCHEMA, signal } from '@angular/core'
import { ComponentFixture, TestBed } from '@angular/core/testing'
import { ActivatedRoute } from '@angular/router'
import {
  Case,
  Configuration,
  MessageEvent as CaseMessageEvent,
  RunCostControllerService,
  RunCostDto,
  UsageConfigurationControllerService,
  UsageRecordControllerService,
  User,
  VoiceMessageControllerService,
} from '@whoz-oss/agentos-api-client'
import { of, Subject } from 'rxjs'
import { CostPipe } from '../../pipes/cost.pipe'
import { TokenCountPipe } from '../../pipes/token-count.pipe'
import { AiModelStateService } from '../../services/ai-model-state.service'
import { CaseStateService } from '../../services/case-state.service'
import { CaseMemberStateService } from '../../services/case-member-state.service'
import { ExchangeStateService } from '../../services/exchange-state.service'
import { PromptStateService } from '../../services/prompt-state.service'
import { USER_PREFERENCES_PORT } from '../../services/user-preferences.service'
import { UserStateService } from '../../services/user-state.service'
import { CostPauseCardComponent } from '../cost-pause-card/cost-pause-card.component'
import { CaseChatComponent } from './case-chat.component'

const CASE_ID = 'c-1'

const notPaused = (): RunCostDto => ({
  caseId: CASE_ID,
  since: '2026-09-22T10:00:00Z',
  cost: 3,
  unknownCostCount: 0,
  runCostThreshold: 10,
  paused: false,
  active: true,
  liveTokens: 0,
  pausedCases: [],
})

const paused = (): RunCostDto => ({
  ...notPaused(),
  cost: 12,
  paused: true,
  pausedCases: [{ caseId: CASE_ID, cost: 12, threshold: 10, ancestor: false, canContinue: true }],
})

const totals = {
  recordCount: 1,
  inputTokens: 100,
  outputTokens: 50,
  cacheReadTokens: 0,
  cacheWriteTokens: 0,
  totalTokens: 150,
  cost: 12,
  unknownCostCount: 0,
}

/**
 * Where the cost pause card sits in the chat, and what the chat does with the keyboard focus when a run pauses.
 *
 * Unlike case-chat.component.spec.ts, which never renders the template, this spec renders the REAL chat template with
 * the REAL card and the REAL run cost state, driven through mocked run-cost endpoints: removing the card from the
 * template, or the focus effect from the chat, fails it. Only the heavy children that need their own providers (drawer,
 * exchange shell, autocompletes, recorder...) are dropped, as unknown elements that keep their projected content.
 */
describe('CaseChatComponent — cost pause in the composer (rendered)', () => {
  let fixture: ComponentFixture<CaseChatComponent>
  let costs: { getRunCost: jest.Mock; continueCostRunRunCost: jest.Mock; stopCostRunRunCost: jest.Mock }
  let answers: Subject<CaseMessageEvent>
  /** One subject per run-cost request, in call order: a test answers the request it is waiting for. */
  let requests: Subject<RunCostDto>[]
  let originalEventSource: typeof EventSource
  let outsideInput: HTMLInputElement | null

  beforeEach(() => {
    requests = []
    outsideInput = null
    answers = new Subject<CaseMessageEvent>()
    costs = {
      getRunCost: jest.fn().mockImplementation(() => {
        const request = new Subject<RunCostDto>()
        requests.push(request)
        return request
      }),
      continueCostRunRunCost: jest.fn(),
      stopCostRunRunCost: jest.fn().mockReturnValue(of(undefined)),
    }

    // ngOnInit opens the event stream.
    originalEventSource = globalThis.EventSource
    const source = Object.assign(new EventTarget(), { close: jest.fn(), onerror: null, readyState: 0 })
    globalThis.EventSource = jest.fn(() => source) as unknown as typeof EventSource

    TestBed.configureTestingModule({
      providers: [
        {
          provide: HttpClient,
          useValue: { post: jest.fn().mockReturnValue(of({})), get: jest.fn().mockReturnValue(of({})) },
        },
        { provide: Configuration, useValue: { basePath: '' } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParams: { case: CASE_ID, ns: 'ns-1' } }, queryParams: of({}) },
        },
        {
          provide: ExchangeStateService,
          useValue: {
            uploadFile: jest.fn(),
            canWriteCase: signal(true),
            canWriteNamespace: signal(false),
            fileCount: signal(0),
            refreshManifest: jest.fn(),
            refreshCase: jest.fn(),
            refreshNamespace: jest.fn(),
          },
        },
        {
          provide: CaseStateService,
          useValue: {
            cases: signal<Case[]>([]),
            addCase: jest.fn(),
            updateCaseTitle: jest.fn(),
            updateCaseStatus: jest.fn(),
            notifyAgentMessageEvent: jest.fn(),
            setReasoningEffort: jest.fn().mockReturnValue(of({})),
            agentMessageEvent$: answers.asObservable(),
            refreshCaseThreshold: jest.fn(),
          },
        },
        { provide: PromptStateService, useValue: { listEffective: jest.fn().mockReturnValue(of([])) } },
        // ngOnInit loads the models for the /model command: without this mock the real API client runs on the
        // bare Configuration above and throws (selectHeaderAccept is not a function).
        { provide: AiModelStateService, useValue: { listAvailable: jest.fn().mockReturnValue(of([])) } },
        // ngOnInit reads who the case is shared with, which decides the team chat: a case on its own here.
        { provide: CaseMemberStateService, useValue: { listMembers: jest.fn().mockReturnValue(of([])) } },
        { provide: UserStateService, useValue: { currentUser: signal<User | null>(null) } },
        {
          provide: USER_PREFERENCES_PORT,
          useValue: {
            shouldSend: jest.fn().mockReturnValue(false),
            composerHint: () => 'hint',
            showTechnical: signal(false),
            enterKeyBehavior: signal('send'),
          },
        },
        {
          provide: VoiceMessageControllerService,
          useValue: { capabilityVoiceMessage: jest.fn().mockReturnValue(of({ available: false })) },
        },
        {
          provide: UsageConfigurationControllerService,
          useValue: { getUsageConfiguration: jest.fn().mockReturnValue(of({ enabled: true })) },
        },
        { provide: RunCostControllerService, useValue: costs },
        {
          provide: UsageRecordControllerService,
          useValue: { aggregateByCaseTreeUsageRecord: jest.fn().mockReturnValue(of(totals)) },
        },
      ],
    })
    TestBed.overrideComponent(CaseChatComponent, {
      set: {
        imports: [NgTemplateOutlet, JsonPipe, CostPipe, TokenCountPipe, CostPauseCardComponent],
        schemas: [NO_ERRORS_SCHEMA],
      },
    })
  })

  afterEach(() => {
    jest.useRealTimers()
    outsideInput?.remove()
    TestBed.resetTestingModule()
    globalThis.EventSource = originalEventSource
  })

  /** Lets the effects, the renders and the after-render hooks of the chat run. */
  async function settle(): Promise<void> {
    for (let pass = 0; pass < 3; pass++) {
      fixture.detectChanges()
      await fixture.whenStable()
    }
  }

  async function openChat(running = false): Promise<void> {
    fixture = TestBed.createComponent(CaseChatComponent)
    if (running) fixture.componentInstance['isRunning'].set(true)
    await settle()
  }

  /** Answers the run-cost request in flight. */
  async function respond(state: RunCostDto): Promise<void> {
    const request = requests[requests.length - 1]!
    request.next(state)
    request.complete()
    await settle()
  }

  /** An agent answer makes the state read the run cost again, like it does in production. */
  async function refreshAfterAnswer(id: string): Promise<void> {
    answers.next({ id, caseId: CASE_ID } as CaseMessageEvent)
    await settle()
  }

  const root = () => fixture.nativeElement as HTMLElement
  const controls = () => root().querySelector('[data-cost-pause-controls]')
  const continueButton = () =>
    Array.from(root().querySelectorAll<HTMLButtonElement>('[data-cost-pause-controls] button')).find((button) =>
      (button.textContent ?? '').includes('Continue')
    )

  function focusOutsideInput(): HTMLInputElement {
    outsideInput = document.createElement('input')
    document.body.appendChild(outsideInput)
    outsideInput.focus()
    return outsideInput
  }

  it('shows the decision inside the composer when the run is paused', async () => {
    await openChat()
    await respond(paused())

    const card = root().querySelector('.case-chat__composer agentos-cost-pause-card [data-cost-pause-controls]')
    expect(card).not.toBeNull()
    expect(card?.querySelector('[role="alert"]')?.textContent).toContain('Cost threshold reached')
    expect((continueButton()?.textContent ?? '').replace(/\s+/g, ' ').trim()).toBe(
      'Continue · double threshold to 20.00'
    )
  })

  it('replaces the input of the composer while the run is paused, and gives it back with its draft', async () => {
    await openChat()
    await respond(notPaused())
    fixture.componentInstance['inputValue'].set('my draft')
    await settle()
    expect(root().querySelector('.case-chat__composer textarea')).not.toBeNull()

    await refreshAfterAnswer('answer-1')
    await respond(paused())

    const composer = root().querySelector('.case-chat__composer')
    expect(composer?.classList).toContain('case-chat__composer--paused')
    expect(composer?.querySelector('textarea')).toBeNull()
    expect(composer?.querySelector('.case-chat__actions')).toBeNull()

    await refreshAfterAnswer('answer-2')
    await respond(notPaused())

    const input = root().querySelector<HTMLTextAreaElement>('.case-chat__composer textarea')
    expect(root().querySelector('.case-chat__composer')?.classList).not.toContain('case-chat__composer--paused')
    expect(input?.value).toBe('my draft')
    expect(controls()).toBeNull()
  })

  it('shows no decision anywhere while the run is not paused', async () => {
    await openChat()
    await respond(notPaused())

    expect(root().querySelector('.case-chat__composer')).not.toBeNull()
    expect(controls()).toBeNull()
    expect(document.querySelector('[data-cost-pause-controls]')).toBeNull()
  })

  it('moves the focus to Continue when the pause appears', async () => {
    await openChat()
    await respond(notPaused())
    expect(controls()).toBeNull()

    await refreshAfterAnswer('answer-1')
    await respond(paused())

    expect(continueButton()).toBeDefined()
    expect(document.activeElement).toBe(continueButton())
  })

  it('leaves the focus alone when the user is typing in another field', async () => {
    await openChat()
    await respond(notPaused())
    const typing = focusOutsideInput()

    await refreshAfterAnswer('answer-1')
    await respond(paused())

    expect(continueButton()).toBeDefined()
    expect(document.activeElement).toBe(typing)
  })

  it('moves the focus once per pause, not at every poll of the same pause', async () => {
    await openChat()
    await respond(notPaused())
    await refreshAfterAnswer('answer-1')
    await respond(paused())
    expect(document.activeElement).toBe(continueButton())

    // The user clicks away; the next poll still reports the same pause.
    ;(document.activeElement as HTMLElement).blur()
    await refreshAfterAnswer('answer-2')
    await respond(paused())

    expect(controls()).not.toBeNull()
    expect(document.activeElement).not.toBe(continueButton())
  })

  it('stops polling the run cost when the chat is destroyed', async () => {
    jest.useFakeTimers({ doNotFake: ['nextTick', 'setImmediate', 'queueMicrotask'] })
    await openChat(true)
    await respond(notPaused())

    // A running case is polled every two seconds: this is what makes the check below meaningful.
    jest.advanceTimersByTime(2000)
    expect(costs.getRunCost).toHaveBeenCalledTimes(2)
    await respond(notPaused())

    fixture.destroy()
    jest.advanceTimersByTime(10_000)

    expect(costs.getRunCost).toHaveBeenCalledTimes(2)
  })
})
