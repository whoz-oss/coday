import { signal } from '@angular/core'
import { ComponentFixture, fakeAsync, TestBed, tick } from '@angular/core/testing'
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
import { CaseUsageComponent } from './case-usage.component'

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

/**
 * The usage strip is a read-only readout of the state the chat provides: the polling is the state service's, driven here
 * through `bind` like the chat does. The cost decision itself is covered by the cost pause card spec.
 */
describe('CaseUsageComponent', () => {
  let costs: { getRunCost: jest.Mock; continueCostRunRunCost: jest.Mock; stopCostRunRunCost: jest.Mock }
  let usage: { aggregateByCaseTreeUsageRecord: jest.Mock }
  let agentMessageEvents: Subject<MessageEvent>
  let configuration: { getUsageConfiguration: jest.Mock }
  beforeEach(() => {
    configuration = { getUsageConfiguration: jest.fn().mockReturnValue(of({ enabled: true })) }
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
        CaseRunCostStateService,
        { provide: UsageConfigurationControllerService, useValue: configuration },
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
    const runCost = TestBed.inject(CaseRunCostStateService)
    runCost.bind('case-a', canWrite, false)
    const fixture = TestBed.createComponent(CaseUsageComponent)
    fixture.detectChanges()
    tick(0)
    fixture.detectChanges()
    return fixture
  }

  /** What the chat does when the case, the rights or the activity change. */
  function rebind(fixture: ComponentFixture<CaseUsageComponent>, caseId: string, running: boolean, canWrite = true) {
    TestBed.inject(CaseRunCostStateService).bind(caseId, canWrite, running)
    fixture.detectChanges()
  }

  const runCostState = () => TestBed.inject(CaseRunCostStateService)

  it('waits for startup settings before fetching usage or polling an active run', fakeAsync(() => {
    const settings = new Subject<{ enabled: boolean }>()
    configuration.getUsageConfiguration.mockReturnValue(settings)
    const fixture = render()
    rebind(fixture, 'case-a', true)
    tick(10000)
    expect(fixture.nativeElement.textContent).toContain('Loading usage settings')
    expect(costs.getRunCost).not.toHaveBeenCalled()
    expect(usage.aggregateByCaseTreeUsageRecord).not.toHaveBeenCalled()
    runCostState().continue(state().pausedCases[0])
    runCostState().stop()
    expect(costs.continueCostRunRunCost).not.toHaveBeenCalled()
    expect(costs.stopCostRunRunCost).not.toHaveBeenCalled()

    settings.next({ enabled: true })
    settings.complete()
    fixture.detectChanges()
    expect(costs.getRunCost).toHaveBeenCalledTimes(1)
    expect(usage.aggregateByCaseTreeUsageRecord).toHaveBeenCalledTimes(1)
    expect(fixture.nativeElement.textContent).toContain('12.00')
    tick(2000)
    expect(costs.getRunCost).toHaveBeenCalledTimes(2)
    fixture.destroy()
  }))

  it('hides consumption and blocks requests, polling and cost decisions when disabled', fakeAsync(() => {
    configuration.getUsageConfiguration.mockReturnValue(of({ enabled: false }))
    const fixture = render()
    rebind(fixture, 'case-a', true)
    tick(10000)
    agentMessageEvents.next({ id: 'answer-1', caseId: 'case-a' } as MessageEvent)
    runCostState().continue(state().pausedCases[0])
    runCostState().stop()
    expect(fixture.nativeElement.textContent.trim()).toBe('')
    expect(fixture.nativeElement.querySelector('button')).toBeNull()
    expect(costs.getRunCost).not.toHaveBeenCalled()
    expect(usage.aggregateByCaseTreeUsageRecord).not.toHaveBeenCalled()
    expect(costs.continueCostRunRunCost).not.toHaveBeenCalled()
    expect(costs.stopCostRunRunCost).not.toHaveBeenCalled()
    expect(runCostState().paused()).toBe(false)
    fixture.destroy()
  }))

  it('shows a configuration failure and retries before starting usage requests', fakeAsync(() => {
    configuration.getUsageConfiguration.mockReturnValueOnce(throwError(() => new Error('offline')))
    const fixture = render()
    expect(fixture.nativeElement.textContent).toContain('Could not load usage settings')
    expect(fixture.nativeElement.textContent).not.toContain('0.00')
    expect(costs.getRunCost).not.toHaveBeenCalled()
    expect(usage.aggregateByCaseTreeUsageRecord).not.toHaveBeenCalled()
    tick(10000)
    expect(configuration.getUsageConfiguration).toHaveBeenCalledTimes(1)
    fixture.nativeElement.querySelector('button').click()
    fixture.detectChanges()
    expect(configuration.getUsageConfiguration).toHaveBeenCalledTimes(2)
    expect(fixture.nativeElement.textContent).toContain('12.00')
    fixture.destroy()
  }))

  it('is a readout when the run is paused: the title and figures, no decision, not forced open', fakeAsync(() => {
    const fixture = render()
    const text = fixture.nativeElement.textContent.replace(/\s+/g, ' ')

    expect(text).toContain('Paused · cost threshold reached')
    expect(text).toContain('12.00')
    expect(fixture.nativeElement.querySelector('.usage--paused')).not.toBeNull()
    expect(fixture.nativeElement.querySelector('details').open).toBe(false)
    // The decision is asked in the composer (cost pause card), nowhere else.
    expect(fixture.nativeElement.querySelector('button')).toBeNull()
    expect(text).not.toContain('double threshold')
    expect(text).not.toContain('Stop')
    expect(costs.continueCostRunRunCost).not.toHaveBeenCalled()
    expect(costs.stopCostRunRunCost).not.toHaveBeenCalled()
    fixture.destroy()
  }))

  it('never shows an unknown amount as a complete estimate', fakeAsync(() => {
    costs.getRunCost.mockReturnValue(of({ ...state(), unknownCostCount: 1 }))
    const fixture = render()
    // A partial total is marked as a lower bound by the sign, not by a prose prefix.
    expect(fixture.nativeElement.textContent).toContain('\u2265')
    expect(fixture.nativeElement.textContent).toContain('lower bound')
    fixture.destroy()
  }))

  it('reports no amount at all when nothing in the run was priced', fakeAsync(() => {
    // A namespace whose models carry no pricing produces this on every run: the sum of known
    // costs is 0 and the count of unknowns is not. "At least 0.00" read as a free run.
    costs.getRunCost.mockReturnValue(of({ ...state(), cost: 0, unknownCostCount: 2 }))
    const fixture = render()
    const text = fixture.nativeElement.textContent as string
    expect(text).toContain('\u2014')
    expect(text).not.toContain('0.00 this run')
    expect(text).toContain('lower bound')
    fixture.destroy()
  }))

  it('puts the conversation cost and token total in the summary', fakeAsync(() => {
    const fixture = render()
    // The two figures the panel is opened for must be readable without opening it, each under the
    // word that says what it is.
    const summary = fixture.nativeElement.querySelector('summary')
    const labels = Array.from(summary.querySelectorAll('.usage__figure-label')).map((el) =>
      (el as HTMLElement).textContent?.trim()
    )
    expect(labels).toEqual(['this run', 'threshold', 'conversation', 'tokens'])
    expect(summary.textContent).toContain('150')
    // `summary` takes phrasing content: a definition list there is non-conforming.
    expect(summary.querySelector('dl')).toBeNull()
    fixture.destroy()
  }))

  /** The value of the summary figure carrying [label], so an assertion names what it reads. */
  function figure(fixture: ComponentFixture<CaseUsageComponent>, label: string): string | undefined {
    const pair = Array.from(fixture.nativeElement.querySelectorAll('.usage__figure')).find(
      (row) => (row as HTMLElement).querySelector('.usage__figure-label')?.textContent?.trim() === label
    )
    return (pair as HTMLElement | undefined)?.querySelector('.usage__figure-value')?.textContent?.trim()
  }

  it('shows the live threshold of a parent pause while the child run is on display', fakeAsync(() => {
    costs.getRunCost.mockReturnValue(of(parentPause()))
    const fixture = render()
    const text = fixture.nativeElement.textContent.replace(/\s+/g, ' ')

    expect(text).toContain('Paused · cost threshold reached')
    expect(fixture.nativeElement.querySelector('details').open).toBe(false)
    expect(figure(fixture, 'threshold')).toBe('100.00')
    expect(fixture.nativeElement.querySelector('button')).toBeNull()
    fixture.destroy()
  }))

  it('reads a hidden linked pause as paused without inventing parent details', fakeAsync(() => {
    costs.getRunCost.mockReturnValue(of({ ...parentPause(), cost: 2, pausedCases: [] }))
    usage.aggregateByCaseTreeUsageRecord.mockReturnValue(of({ ...totals, cost: 2 }))
    const fixture = render()
    const text = fixture.nativeElement.textContent.replace(/\s+/g, ' ')

    expect(text).toContain('Paused · cost threshold reached')
    expect(fixture.nativeElement.querySelector('details').open).toBe(false)
    expect(text).not.toContain('parent-case')
    expect(text).not.toContain('double threshold')
    expect(fixture.nativeElement.querySelector('button')).toBeNull()
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
    rebind(fixture, 'case-b', false)
    tick(0)
    fixture.detectChanges()
    pending.next(state('case-a'))
    pending.complete()
    expect(runCostState().run()?.caseId).toBe('case-b')
    fixture.destroy()
    TestBed.resetTestingModule()
    const calls = costs.getRunCost.mock.calls.length
    agentMessageEvents.next({ id: 'answer-after-destroy', caseId: 'case-b' } as MessageEvent)
    expect(costs.getRunCost).toHaveBeenCalledTimes(calls)
  }))

  it('keeps an API failure visible instead of showing zero consumption', fakeAsync(() => {
    costs.getRunCost.mockReturnValue(throwError(() => new Error('offline')))
    const fixture = render()
    expect(fixture.nativeElement.textContent).toContain('could not be refreshed')
    expect(runCostState().run()).toBeNull()
    fixture.destroy()
  }))

  it('discovers a pause when a new run starts in an already open idle conversation', fakeAsync(() => {
    const idle = { ...state(), active: false, paused: false, pausedCases: [], cost: 0 }
    costs.getRunCost.mockReturnValue(of(idle))
    const fixture = render()
    expect(runCostState().paused()).toBe(false)
    tick(10000)
    expect(costs.getRunCost).toHaveBeenCalledTimes(1)

    // The chat starts the activity signal before the runtime necessarily exists.
    rebind(fixture, 'case-a', true)
    tick(0)
    expect(runCostState().run()?.active).toBe(false)
    const aggregateCalls = usage.aggregateByCaseTreeUsageRecord.mock.calls.length
    costs.getRunCost.mockReturnValue(of(state()))
    // No final AGENT message is emitted while the cost gate waits for a decision.
    tick(2000)
    fixture.detectChanges()

    expect(runCostState().paused()).toBe(true)
    expect(fixture.nativeElement.textContent).toContain('Paused · cost threshold reached')
    expect(fixture.nativeElement.querySelector('details').open).toBe(false)
    expect(usage.aggregateByCaseTreeUsageRecord).toHaveBeenCalledTimes(aggregateCalls)
    fixture.destroy()
  }))

  it('waits for a slow run-cost response and recovers after a failed poll', fakeAsync(() => {
    const fixture = render()
    const pending = new Subject<RunCostDto>()
    costs.getRunCost.mockReturnValueOnce(pending)
    rebind(fixture, 'case-a', true)
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
    rebind(fixture, 'case-a', true)
    tick(2000)
    const finished = { ...state(), active: false, paused: false, pausedCases: [], cost: 20 }
    costs.getRunCost.mockReturnValue(of(finished))
    usage.aggregateByCaseTreeUsageRecord.mockReturnValue(of({ ...totals, cost: 20 }))
    rebind(fixture, 'case-a', false)
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
    rebind(fixture, 'case-a', true)
    tick(0)
    const pending = new Subject<RunCostDto>()
    costs.getRunCost.mockReturnValueOnce(pending)
    tick(2000)
    rebind(fixture, 'case-b', false)
    tick(0)
    pending.next(state('case-a'))
    pending.complete()
    fixture.detectChanges()
    expect(runCostState().run()?.caseId).toBe('case-b')
    const idleCalls = costs.getRunCost.mock.calls.length
    tick(2000)
    expect(costs.getRunCost).toHaveBeenCalledTimes(idleCalls)
    rebind(fixture, 'case-b', true)
    tick(2000)
    expect(costs.getRunCost).toHaveBeenLastCalledWith('case-b')
    fixture.destroy()
    TestBed.resetTestingModule()
    const calls = costs.getRunCost.mock.calls.length
    tick(10000)
    expect(costs.getRunCost).toHaveBeenCalledTimes(calls)
  }))

  it('keeps the persisted header threshold separate from the live run threshold', fakeAsync(() => {
    const cases = TestBed.inject(CaseStateService).cases
    cases.set([{ id: 'case-a', runCostThreshold: 30 } as Case])
    const fixture = render()
    expect(cases()[0].runCostThreshold).toBe(30)
    expect(runCostState().run()?.runCostThreshold).toBe(10)
    fixture.destroy()
  }))
})
