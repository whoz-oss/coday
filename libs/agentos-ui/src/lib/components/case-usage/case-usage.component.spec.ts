import { signal } from '@angular/core'
import { fakeAsync, TestBed, tick } from '@angular/core/testing'
import {
  Case,
  MessageEvent,
  RunCostControllerService,
  RunCostDto,
  UsageRecordControllerService,
} from '@whoz-oss/agentos-api-client'
import { of, Subject, throwError } from 'rxjs'
import { CaseStateService } from '../../services/case-state.service'
import { CaseUsageComponent } from './case-usage.component'

const state = (caseId = 'case-a'): RunCostDto => ({
  caseId,
  since: '2026-09-22T10:00:00Z',
  cost: 12,
  unknownCostCount: 0,
  runCostThreshold: 10,
  paused: true,
  active: true,
  liveTokens: 150,
  pausedCases: [{ caseId, cost: 12, threshold: 10 }],
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

describe('CaseUsageComponent', () => {
  let costs: { getRunCost: jest.Mock; continueCostRunRunCost: jest.Mock; stopCostRunRunCost: jest.Mock }
  let usage: { aggregateByCaseTreeUsageRecord: jest.Mock }
  let agentMessageEvents: Subject<MessageEvent>
  beforeEach(() => {
    costs = {
      getRunCost: jest.fn().mockImplementation((id) => of(state(id))),
      continueCostRunRunCost: jest.fn(),
      stopCostRunRunCost: jest.fn().mockReturnValue(of(undefined)),
    }
    usage = { aggregateByCaseTreeUsageRecord: jest.fn().mockReturnValue(of(totals)) }
    agentMessageEvents = new Subject<MessageEvent>()
    TestBed.configureTestingModule({
      imports: [CaseUsageComponent],
      providers: [
        { provide: RunCostControllerService, useValue: costs },
        { provide: UsageRecordControllerService, useValue: usage },
        {
          provide: CaseStateService,
          useValue: {
            cases: signal<Case[]>([]),
            agentMessageEvent$: agentMessageEvents.asObservable(),
            refreshCaseThreshold: jest.fn(),
          },
        },
      ],
    })
  })

  function render(canWrite = true) {
    const fixture = TestBed.createComponent(CaseUsageComponent)
    fixture.componentRef.setInput('caseId', 'case-a')
    fixture.componentRef.setInput('canWrite', canWrite)
    fixture.detectChanges()
    tick(0)
    fixture.detectChanges()
    return fixture
  }

  it('renders consumption and the exact doubled threshold before confirmation', fakeAsync(() => {
    const fixture = render()
    const text = fixture.nativeElement.textContent
    expect(text).toContain('Paused')
    expect(text).toContain('12.00')
    expect(text).toContain('double threshold to 20.00')
    expect(costs.continueCostRunRunCost).not.toHaveBeenCalled()
    fixture.destroy()
  }))

  it('submits one explicit confirmation while the request is pending', fakeAsync(() => {
    const response = new Subject<RunCostDto>()
    costs.continueCostRunRunCost.mockReturnValue(response)
    const fixture = render()
    fixture.componentInstance.continue(state().pausedCases[0])
    fixture.componentInstance.continue(state().pausedCases[0])
    expect(costs.continueCostRunRunCost).toHaveBeenCalledTimes(1)
    expect(costs.continueCostRunRunCost).toHaveBeenCalledWith('case-a', { expectedThreshold: 10 })
    costs.getRunCost.mockReturnValue(of({ ...state(), paused: false, pausedCases: [], runCostThreshold: 20 }))
    response.next({ ...state(), runCostThreshold: 20 })
    response.complete()
    fixture.detectChanges()
    expect(fixture.nativeElement.textContent).not.toContain('double threshold')
    fixture.destroy()
  }))

  it('never shows an unknown amount as a complete estimate', fakeAsync(() => {
    costs.getRunCost.mockReturnValue(of({ ...state(), unknownCostCount: 1 }))
    const fixture = render()
    expect(fixture.nativeElement.textContent).toContain('At least')
    expect(fixture.nativeElement.textContent).toContain('lower bound')
    fixture.destroy()
  }))

  it('does not offer confirmation controls to read-only viewers', fakeAsync(() => {
    const fixture = render(false)
    expect(fixture.nativeElement.querySelector('button')).toBeNull()
    fixture.componentInstance.continue(state().pausedCases[0])
    expect(costs.continueCostRunRunCost).not.toHaveBeenCalled()
    fixture.destroy()
  }))

  it('refreshes once per distinct agent answer for the active case only', fakeAsync(() => {
    const fixture = render()
    costs.getRunCost.mockClear()
    usage.aggregateByCaseTreeUsageRecord.mockClear()

    agentMessageEvents.next({ id: 'answer-other', caseId: 'other-case' } as MessageEvent)
    expect(costs.getRunCost).not.toHaveBeenCalled()

    const answer = { id: 'answer-1', caseId: 'case-a' } as MessageEvent
    agentMessageEvents.next(answer)
    agentMessageEvents.next(answer)
    expect(costs.getRunCost).toHaveBeenCalledTimes(1)
    expect(costs.getRunCost).toHaveBeenCalledWith('case-a')
    expect(usage.aggregateByCaseTreeUsageRecord).toHaveBeenCalledTimes(1)
    expect(usage.aggregateByCaseTreeUsageRecord).toHaveBeenCalledWith('case-a')
    fixture.destroy()
  }))

  it('discards the old case response and unsubscribes from status events on destroy', fakeAsync(() => {
    const pending = new Subject<RunCostDto>()
    costs.getRunCost.mockReturnValueOnce(pending)
    const fixture = render()
    fixture.componentRef.setInput('caseId', 'case-b')
    fixture.detectChanges()
    tick(0)
    fixture.detectChanges()
    pending.next(state('case-a'))
    pending.complete()
    expect(fixture.componentInstance.run()?.caseId).toBe('case-b')
    fixture.destroy()
    const calls = costs.getRunCost.mock.calls.length
    agentMessageEvents.next({ id: 'answer-after-destroy', caseId: 'case-b' } as MessageEvent)
    expect(costs.getRunCost).toHaveBeenCalledTimes(calls)
  }))

  it('keeps an API failure visible instead of showing zero consumption', fakeAsync(() => {
    costs.getRunCost.mockReturnValue(throwError(() => new Error('offline')))
    const fixture = render()
    expect(fixture.nativeElement.textContent).toContain('could not be refreshed')
    expect(fixture.componentInstance.run()).toBeNull()
    fixture.destroy()
  }))
  it('discovers a pause when a new run starts in an already open idle conversation', fakeAsync(() => {
    const idle = { ...state(), active: false, paused: false, pausedCases: [], cost: 0 }
    costs.getRunCost.mockReturnValue(of(idle))
    const fixture = render()
    expect(fixture.nativeElement.querySelector('button')).toBeNull()
    tick(10000)
    expect(costs.getRunCost).toHaveBeenCalledTimes(1)

    // The chat starts the activity signal before the runtime necessarily exists.
    fixture.componentRef.setInput('running', true)
    fixture.detectChanges()
    tick(0)
    expect(fixture.componentInstance.run()?.active).toBe(false)
    const aggregateCalls = usage.aggregateByCaseTreeUsageRecord.mock.calls.length
    costs.getRunCost.mockReturnValue(of(state()))
    // No final AGENT message is emitted while the cost gate waits for a decision.
    tick(2000)
    fixture.detectChanges()

    expect(fixture.nativeElement.textContent).toContain('Paused · cost threshold reached')
    expect(fixture.nativeElement.querySelector('details').open).toBe(true)
    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[]
    expect(buttons.map((button) => button.textContent?.trim())).toEqual([
      'Continue · double threshold to 20.00',
      'Stop',
    ])
    expect(buttons.every((button) => !button.disabled)).toBe(true)
    expect(usage.aggregateByCaseTreeUsageRecord).toHaveBeenCalledTimes(aggregateCalls)
    fixture.destroy()
  }))

  it('waits for a slow run-cost response and recovers after a failed poll', fakeAsync(() => {
    const fixture = render()
    const pending = new Subject<RunCostDto>()
    costs.getRunCost.mockReturnValueOnce(pending)
    fixture.componentRef.setInput('running', true)
    fixture.detectChanges()
    tick(0)
    const pendingCalls = costs.getRunCost.mock.calls.length
    tick(6000)
    expect(costs.getRunCost).toHaveBeenCalledTimes(pendingCalls)
    pending.next({ ...state(), cost: 15 })
    pending.complete()
    fixture.detectChanges()
    expect(fixture.nativeElement.textContent).toContain('15.00')

    costs.getRunCost.mockReturnValueOnce(throwError(() => new Error('offline')))
    tick(2000)
    fixture.detectChanges()
    expect(fixture.nativeElement.textContent).toContain('could not be refreshed')
    costs.getRunCost.mockReturnValue(of({ ...state(), cost: 18 }))
    tick(2000)
    fixture.detectChanges()
    expect(fixture.nativeElement.textContent).toContain('18.00')
    expect(fixture.nativeElement.textContent).not.toContain('could not be refreshed')
    fixture.destroy()
  }))

  it('refreshes on completion and stops polling until another run starts', fakeAsync(() => {
    const fixture = render()
    fixture.componentRef.setInput('running', true)
    fixture.detectChanges()
    tick(2000)
    const finished = { ...state(), active: false, paused: false, pausedCases: [], cost: 20 }
    costs.getRunCost.mockReturnValue(of(finished))
    usage.aggregateByCaseTreeUsageRecord.mockReturnValue(of({ ...totals, cost: 20 }))
    fixture.componentRef.setInput('running', false)
    fixture.detectChanges()
    tick(0)
    fixture.detectChanges()
    expect(fixture.nativeElement.textContent).toContain('20.00 recorded')
    expect(fixture.nativeElement.querySelector('button')).toBeNull()
    const calls = costs.getRunCost.mock.calls.length
    tick(10000)
    expect(costs.getRunCost).toHaveBeenCalledTimes(calls)
    fixture.destroy()
  }))

  it('cancels an active poll on navigation and removes the timer on destroy', fakeAsync(() => {
    const fixture = render()
    fixture.componentRef.setInput('running', true)
    fixture.detectChanges()
    tick(0)
    const pending = new Subject<RunCostDto>()
    costs.getRunCost.mockReturnValueOnce(pending)
    tick(2000)
    fixture.componentRef.setInput('caseId', 'case-b')
    fixture.componentRef.setInput('running', false)
    fixture.detectChanges()
    tick(0)
    pending.next(state('case-a'))
    pending.complete()
    fixture.detectChanges()
    expect(fixture.componentInstance.run()?.caseId).toBe('case-b')
    const idleCalls = costs.getRunCost.mock.calls.length
    tick(2000)
    expect(costs.getRunCost).toHaveBeenCalledTimes(idleCalls)
    fixture.componentRef.setInput('running', true)
    fixture.detectChanges()
    tick(2000)
    expect(costs.getRunCost).toHaveBeenLastCalledWith('case-b')
    fixture.destroy()
    const calls = costs.getRunCost.mock.calls.length
    tick(10000)
    expect(costs.getRunCost).toHaveBeenCalledTimes(calls)
  }))

  it('keeps the persisted header threshold separate from the live run threshold', fakeAsync(() => {
    const cases = TestBed.inject(CaseStateService).cases
    cases.set([{ id: 'case-a', runCostThreshold: 30 } as Case])
    const fixture = render()
    expect(cases()[0].runCostThreshold).toBe(30)
    expect(fixture.componentInstance.run()?.runCostThreshold).toBe(10)
    fixture.destroy()
  }))
})
