import { ComponentRef } from '@angular/core'
import { TestBed, ComponentFixture, fakeAsync, tick, discardPeriodicTasks } from '@angular/core/testing'
import { By } from '@angular/platform-browser'
import { FactoryRunDetail } from '../../services/factory-api.service'
import { FactoryRunDetailComponent } from './factory-run-detail.component'

const STARTED_AT = '2025-01-01T00:00:00.000Z'
const STARTED_AT_MS = Date.parse(STARTED_AT)

const BASE_RUN: FactoryRunDetail = {
  runId: 'run-1',
  namespaceId: 'ns',
  workflow: 'fix-loop',
  status: 'pass',
  startedAt: STARTED_AT,
  endedAt: null,
  durationMs: 5000,
  phaseCount: 0,
  phases: [],
}

const RUNNING_RUN: FactoryRunDetail = { ...BASE_RUN, status: 'running', durationMs: null }

function setup(run: FactoryRunDetail): {
  fixture: ComponentFixture<FactoryRunDetailComponent>
  ref: ComponentRef<FactoryRunDetailComponent>
} {
  TestBed.configureTestingModule({ imports: [FactoryRunDetailComponent] })
  const fixture = TestBed.createComponent(FactoryRunDetailComponent)
  const ref = fixture.componentRef
  ref.setInput('run', run)
  ref.setInput('selectedPhaseIndex', 0)
  return { fixture, ref }
}

describe('FactoryRunDetailComponent — wall-clock tick', () => {
  afterEach(() => TestBed.resetTestingModule())

  it('runningElapsedMs is null for a finished run (no clock needed)', fakeAsync(() => {
    jest.spyOn(Date, 'now').mockReturnValue(STARTED_AT_MS + 10_000)
    const { fixture } = setup(BASE_RUN)
    fixture.detectChanges()

    // Access the protected property via the component instance
    const component = fixture.componentInstance as unknown as { runningElapsedMs: () => number | null }
    expect(component.runningElapsedMs()).toBeNull()
    discardPeriodicTasks()
  }))

  it('runningElapsedMs grows after each 1-second tick for a running run', fakeAsync(() => {
    jest.spyOn(Date, 'now').mockReturnValue(STARTED_AT_MS + 1000)
    const { fixture } = setup(RUNNING_RUN)
    fixture.detectChanges()

    const component = fixture.componentInstance as unknown as { runningElapsedMs: () => number | null }
    const before = component.runningElapsedMs()
    expect(before).toBeGreaterThan(0)

    // Advance clock and simulate time passage
    jest.spyOn(Date, 'now').mockReturnValue(STARTED_AT_MS + 2000)
    tick(1000)
    fixture.detectChanges()

    const after = component.runningElapsedMs()
    expect(after).toBeGreaterThan(before!)
    discardPeriodicTasks()
  }))

  it('timer stops incrementing clockTick when run is no longer running', fakeAsync(() => {
    jest.spyOn(Date, 'now').mockReturnValue(STARTED_AT_MS + 1000)
    const { fixture, ref } = setup(RUNNING_RUN)
    fixture.detectChanges()

    const component = fixture.componentInstance as unknown as { clockTick: () => number }
    tick(1000) // first tick fires
    fixture.detectChanges()
    const tickCountAfterFirstTick = component.clockTick()
    expect(tickCountAfterFirstTick).toBe(1)

    // Transition to terminal state
    ref.setInput('run', { ...BASE_RUN, status: 'pass', durationMs: 2000 })
    fixture.detectChanges()

    tick(3000) // three more ticks — none should increment because status !== 'running'
    fixture.detectChanges()
    expect(component.clockTick()).toBe(1) // unchanged
    discardPeriodicTasks()
  }))

  it('timer is torn down on component destruction (no leaked interval)', fakeAsync(() => {
    jest.spyOn(Date, 'now').mockReturnValue(STARTED_AT_MS + 1000)
    const { fixture } = setup(RUNNING_RUN)
    fixture.detectChanges()

    fixture.destroy()

    // After destruction, ticking the clock should not throw or affect anything
    expect(() => tick(5000)).not.toThrow()
    // No pending tasks remain — discardPeriodicTasks is intentionally omitted here
    // because there should be nothing left to discard after destroy.
  }))

  it('runningElapsedMs is null for a running run with no startedAt', fakeAsync(() => {
    const runNoStart: FactoryRunDetail = { ...RUNNING_RUN, startedAt: null }
    const { fixture } = setup(runNoStart)
    fixture.detectChanges()

    const component = fixture.componentInstance as unknown as { runningElapsedMs: () => number | null }
    tick(1000)
    fixture.detectChanges()
    expect(component.runningElapsedMs()).toBeNull()
    discardPeriodicTasks()
  }))
})
