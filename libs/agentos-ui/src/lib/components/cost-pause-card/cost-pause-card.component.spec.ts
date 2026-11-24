import { signal } from '@angular/core'
import { fakeAsync, TestBed, tick } from '@angular/core/testing'
import {
  Case,
  MessageEvent,
  RunCostControllerService,
  RunCostDto,
  UsageConfigurationControllerService,
  UsageRecordControllerService,
} from '@whoz-oss/agentos-api-client'
import { of, Subject, throwError } from 'rxjs'
import { CaseRunCostStateService } from '../../services/case-run-cost-state.service'
import { CaseStateService } from '../../services/case-state.service'
import { CostPauseCardComponent } from './cost-pause-card.component'

const state = (caseId = 'case-a', canContinue = true): RunCostDto => ({
  caseId,
  since: '2026-09-22T10:00:00Z',
  cost: 12,
  unknownCostCount: 0,
  runCostThreshold: 10,
  paused: true,
  active: true,
  liveTokens: 150,
  pausedCases: [{ caseId, cost: 12, threshold: 10, ancestor: false, canContinue }],
})
const parentPause = (canContinue = true): RunCostDto => ({
  ...state(),
  runCostThreshold: 100,
  pausedCases: [{ caseId: 'parent-case', cost: 12, threshold: 10, ancestor: true, canContinue }],
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

describe('CostPauseCardComponent', () => {
  let costs: { getRunCost: jest.Mock; continueCostRunRunCost: jest.Mock; stopCostRunRunCost: jest.Mock }
  let configuration: { getUsageConfiguration: jest.Mock }
  let refreshCaseThreshold: jest.Mock

  beforeEach(() => {
    configuration = { getUsageConfiguration: jest.fn().mockReturnValue(of({ enabled: true })) }
    costs = {
      getRunCost: jest.fn().mockImplementation((id) => of(state(id))),
      continueCostRunRunCost: jest.fn(),
      stopCostRunRunCost: jest.fn().mockReturnValue(of(undefined)),
    }
    refreshCaseThreshold = jest.fn()
    TestBed.configureTestingModule({
      imports: [CostPauseCardComponent],
      providers: [
        CaseRunCostStateService,
        { provide: UsageConfigurationControllerService, useValue: configuration },
        { provide: RunCostControllerService, useValue: costs },
        {
          provide: UsageRecordControllerService,
          useValue: { aggregateByCaseTreeUsageRecord: jest.fn().mockReturnValue(of(totals)) },
        },
        {
          provide: CaseStateService,
          useValue: {
            cases: signal<Case[]>([]),
            agentMessageEvent$: new Subject<MessageEvent>().asObservable(),
            refreshCaseThreshold,
          },
        },
      ],
    })
  })

  /** Renders the card on a case the way the chat binds it, then lets the first poll land. */
  function render(canWrite = true) {
    TestBed.inject(CaseRunCostStateService).bind('case-a', canWrite, false)
    const fixture = TestBed.createComponent(CostPauseCardComponent)
    fixture.detectChanges()
    tick(0)
    fixture.detectChanges()
    return fixture
  }

  const textOf = (element: HTMLElement) => (element.textContent ?? '').replace(/\s+/g, ' ')
  const buttons = (element: HTMLElement) => Array.from(element.querySelectorAll('button')) as HTMLButtonElement[]

  it('asks the decision as an alert, with the exact doubled threshold and a Stop', fakeAsync(() => {
    const fixture = render()
    const root = fixture.nativeElement as HTMLElement

    expect(root.querySelector('[data-cost-pause-controls]')).not.toBeNull()
    expect(root.querySelector('[role="alert"]')?.textContent).toContain(
      'Cost threshold reached: 12.00 consumed. Continue?'
    )
    expect(textOf(root)).toContain('Conversation: 12.00 consumed.')
    expect(buttons(root).map((button) => button.textContent?.trim())).toEqual([
      'Continue · double threshold to 20.00',
      'Stop',
    ])
    expect(costs.continueCostRunRunCost).not.toHaveBeenCalled()
    fixture.destroy()
  }))

  it('renders nothing while the run is not paused', fakeAsync(() => {
    costs.getRunCost.mockReturnValue(of({ ...state(), paused: false, pausedCases: [] }))
    const fixture = render()

    expect(fixture.nativeElement.querySelector('[data-cost-pause-controls]')).toBeNull()
    expect((fixture.nativeElement as HTMLElement).textContent?.trim()).toBe('')
    fixture.destroy()
  }))

  it('renders nothing, and asks nothing, when usage tracking is disabled', fakeAsync(() => {
    configuration.getUsageConfiguration.mockReturnValue(of({ enabled: false }))
    const fixture = render()

    expect((fixture.nativeElement as HTMLElement).textContent?.trim()).toBe('')
    expect(costs.getRunCost).not.toHaveBeenCalled()
    fixture.destroy()
  }))

  it('continues with the threshold of the paused case, once, while the request is pending', fakeAsync(() => {
    const response = new Subject<RunCostDto>()
    costs.continueCostRunRunCost.mockReturnValue(response)
    const fixture = render()
    const [continueButton] = buttons(fixture.nativeElement)

    continueButton.click()
    fixture.detectChanges()
    continueButton.click()
    expect(costs.continueCostRunRunCost).toHaveBeenCalledTimes(1)
    expect(costs.continueCostRunRunCost).toHaveBeenCalledWith('case-a', { expectedThreshold: 10 })
    expect(buttons(fixture.nativeElement).every((button) => button.disabled)).toBe(true)

    costs.getRunCost.mockReturnValue(of({ ...state(), paused: false, pausedCases: [], runCostThreshold: 20 }))
    response.next({ ...state(), runCostThreshold: 20 })
    response.complete()
    fixture.detectChanges()

    expect(refreshCaseThreshold).toHaveBeenCalledWith('case-a')
    expect(fixture.nativeElement.querySelector('[data-cost-pause-controls]')).toBeNull()
    fixture.destroy()
  }))

  it('stops the case on display, never another', fakeAsync(() => {
    const fixture = render()
    // Once stopped, the next poll finds the run no longer paused.
    costs.getRunCost.mockReturnValue(of({ ...state(), paused: false, pausedCases: [] }))
    buttons(fixture.nativeElement)[1].click()
    fixture.detectChanges()

    expect(costs.stopCostRunRunCost).toHaveBeenCalledTimes(1)
    expect(costs.stopCostRunRunCost).toHaveBeenCalledWith('case-a')
    expect(fixture.nativeElement.querySelector('[data-cost-pause-controls]')).toBeNull()
    fixture.destroy()
  }))

  it('keeps the card when the decision is refused, so it can be taken again', fakeAsync(() => {
    costs.continueCostRunRunCost.mockReturnValue(throwError(() => new Error('refused')))
    const fixture = render()

    buttons(fixture.nativeElement)[0].click()
    fixture.detectChanges()

    expect(costs.continueCostRunRunCost).toHaveBeenCalledTimes(1)
    expect(fixture.nativeElement.querySelector('[data-cost-pause-controls]')).not.toBeNull()
    expect(TestBed.inject(CaseRunCostStateService).saving()).toBe(false)
    fixture.destroy()
  }))

  it('identifies a parent pause: parent threshold, no Delegated label, Stop for the writable child', fakeAsync(() => {
    costs.getRunCost.mockReturnValue(of(parentPause()))
    const fixture = render()
    const text = textOf(fixture.nativeElement)

    expect(text).toContain('Parent conversation: 12.00 consumed.')
    expect(text).toContain('Continue · double threshold to 20.00')
    expect(text).not.toContain('double threshold to 200.00')
    expect(text).not.toContain('Delegated run:')
    expect(buttons(fixture.nativeElement).map((button) => button.textContent?.trim())).toEqual([
      'Continue · double threshold to 20.00',
      'Stop',
    ])
    fixture.destroy()
  }))

  it('confirms the parent threshold and refreshes the child after the decision', fakeAsync(() => {
    const pending = new Subject<RunCostDto>()
    costs.getRunCost.mockReturnValue(of(parentPause()))
    costs.continueCostRunRunCost.mockReturnValue(pending)
    const fixture = render()
    costs.getRunCost.mockClear()

    buttons(fixture.nativeElement)[0].click()
    fixture.detectChanges()
    expect(costs.continueCostRunRunCost).toHaveBeenCalledWith('parent-case', { expectedThreshold: 10 })
    expect(costs.getRunCost).not.toHaveBeenCalled()

    costs.getRunCost.mockReturnValue(of({ ...parentPause(), paused: false, pausedCases: [] }))
    pending.next({ ...state('parent-case'), runCostThreshold: 20, paused: false, pausedCases: [] })
    pending.complete()
    fixture.detectChanges()

    expect(costs.getRunCost).toHaveBeenCalledWith('case-a')
    expect(costs.getRunCost).not.toHaveBeenCalledWith('parent-case')
    expect(refreshCaseThreshold).toHaveBeenCalledWith('parent-case')
    expect(fixture.nativeElement.querySelector('[data-cost-pause-controls]')).toBeNull()
    fixture.destroy()
  }))

  it('explains a parent pause the viewer cannot approve, and still lets the writable child stop', fakeAsync(() => {
    costs.getRunCost.mockReturnValue(of(parentPause(false)))
    const fixture = render(true)

    expect(textOf(fixture.nativeElement)).toContain(
      'A member with edit permission on the parent conversation must approve continuation.'
    )
    expect(buttons(fixture.nativeElement).map((button) => button.textContent?.trim())).toEqual(['Stop'])
    TestBed.inject(CaseRunCostStateService).continue(parentPause(false).pausedCases[0])
    expect(costs.continueCostRunRunCost).not.toHaveBeenCalled()
    fixture.destroy()
  }))

  it('lets a read-only viewer approve the parent pause but never offers a child Stop', fakeAsync(() => {
    costs.getRunCost.mockReturnValue(of(parentPause()))
    costs.continueCostRunRunCost.mockReturnValue(of({ ...state('parent-case'), runCostThreshold: 20 }))
    const fixture = render(false)

    expect(buttons(fixture.nativeElement).map((button) => button.textContent?.trim())).toEqual([
      'Continue · double threshold to 20.00',
    ])
    TestBed.inject(CaseRunCostStateService).stop()
    expect(costs.stopCostRunRunCost).not.toHaveBeenCalled()
    fixture.destroy()
  }))

  it('offers nothing to a read-only viewer when the pause cannot be continued by them', fakeAsync(() => {
    costs.getRunCost.mockReturnValue(of(state('case-a', false)))
    const fixture = render(false)

    expect(buttons(fixture.nativeElement)).toEqual([])
    expect(textOf(fixture.nativeElement)).toContain('A member with edit permission on this conversation must approve')
    TestBed.inject(CaseRunCostStateService).continue(state('case-a', false).pausedCases[0])
    expect(costs.continueCostRunRunCost).not.toHaveBeenCalled()
    fixture.destroy()
  }))

  it('explains a hidden linked pause without inventing parent details or a continue action', fakeAsync(() => {
    costs.getRunCost.mockReturnValue(of({ ...parentPause(), cost: 2, pausedCases: [] }))
    const fixture = render()
    const text = textOf(fixture.nativeElement)

    expect(text).toContain('This run is waiting for confirmation in a linked conversation you cannot access.')
    expect(text).not.toContain('parent-case')
    expect(text).not.toContain('double threshold')
    expect(buttons(fixture.nativeElement).map((button) => button.textContent?.trim())).toEqual(['Stop'])
    expect(costs.continueCostRunRunCost).not.toHaveBeenCalled()
    fixture.destroy()
  }))

  it('asks for a positive threshold before continuing when the paused case has none', fakeAsync(() => {
    const noThreshold = state()
    noThreshold.pausedCases = [{ ...noThreshold.pausedCases[0], threshold: 0 }]
    costs.getRunCost.mockReturnValue(of(noThreshold))
    const fixture = render()

    expect(textOf(fixture.nativeElement)).toContain(
      'Set a positive threshold in this conversation settings before continuing.'
    )
    expect(buttons(fixture.nativeElement).map((button) => button.textContent?.trim())).toEqual([
      'Continue with updated threshold',
      'Stop',
    ])
    fixture.destroy()
  }))
})
