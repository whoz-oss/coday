import { signal } from '@angular/core'
import { fakeAsync, TestBed, tick } from '@angular/core/testing'
import { Case, RunCostControllerService, RunCostDto, UsageRecordControllerService } from '@whoz-oss/agentos-api-client'
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
  beforeEach(() => {
    costs = {
      getRunCost: jest.fn().mockImplementation((id) => of(state(id))),
      continueCostRunRunCost: jest.fn(),
      stopCostRunRunCost: jest.fn().mockReturnValue(of(undefined)),
    }
    usage = { aggregateByCaseTreeUsageRecord: jest.fn().mockReturnValue(of(totals)) }
    TestBed.configureTestingModule({
      imports: [CaseUsageComponent],
      providers: [
        { provide: RunCostControllerService, useValue: costs },
        { provide: UsageRecordControllerService, useValue: usage },
        { provide: CaseStateService, useValue: { cases: signal<Case[]>([]), refreshCaseThreshold: jest.fn() } },
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

  it('discards the old case response and cancels polling when destroyed', fakeAsync(() => {
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
    tick(6000)
    expect(costs.getRunCost).toHaveBeenCalledTimes(calls)
  }))

  it('keeps an API failure visible instead of showing zero consumption', fakeAsync(() => {
    costs.getRunCost.mockReturnValue(throwError(() => new Error('offline')))
    const fixture = render()
    expect(fixture.nativeElement.textContent).toContain('could not be refreshed')
    expect(fixture.componentInstance.run()).toBeNull()
    fixture.destroy()
  }))
  it('keeps the persisted header threshold separate from the live run threshold', fakeAsync(() => {
    const cases = TestBed.inject(CaseStateService).cases
    cases.set([{ id: 'case-a', runCostThreshold: 30 } as Case])
    const fixture = render()
    tick(3000)
    expect(cases()[0].runCostThreshold).toBe(30)
    expect(fixture.componentInstance.run()?.runCostThreshold).toBe(10)
    fixture.destroy()
  }))
})
