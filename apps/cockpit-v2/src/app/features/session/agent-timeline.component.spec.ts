import { ComponentFixture, TestBed } from '@angular/core/testing'
import { RunStatus, TimelineLane } from '../../core/models'
import { AgentTimelineComponent } from './agent-timeline.component'

/** Structural view over the component's protected signals for assertions. */
interface TimelineInternals {
  effectiveNowSec: () => number
  effectiveLanes: () => TimelineLane[]
}

function lane(overrides: Partial<TimelineLane> = {}): TimelineLane {
  return {
    id: 'agent:builder',
    label: 'builder',
    subtitle: 'builder',
    kind: 'agent',
    tone: 'violet',
    blocks: [],
    ...overrides,
  }
}

describe('AgentTimelineComponent', () => {
  let fixture: ComponentFixture<AgentTimelineComponent>

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [AgentTimelineComponent] }).compileComponents()
    // The clock is frozen before the component is created so its internal
    // `liveNowMs` anchor starts from the fake instant.
    jest.useFakeTimers()
    jest.setSystemTime(new Date('2026-10-03T10:00:00.000Z'))
    fixture = TestBed.createComponent(AgentTimelineComponent)
  })

  afterEach(() => {
    jest.useRealTimers()
  })

  function render(inputs: {
    lanes: TimelineLane[]
    nowSec: number
    status: RunStatus
    startedAt?: string
  }): TimelineInternals {
    fixture.componentRef.setInput('lanes', inputs.lanes)
    fixture.componentRef.setInput('nowSec', inputs.nowSec)
    fixture.componentRef.setInput('status', inputs.status)
    if (inputs.startedAt) fixture.componentRef.setInput('startedAt', inputs.startedAt)
    fixture.detectChanges()
    return fixture.componentInstance as unknown as TimelineInternals
  }

  it('advances the effective now while running and freezes once terminal', () => {
    const startedAt = new Date('2026-10-03T09:59:30.000Z').toISOString() // 30s ago

    const component = render({ lanes: [lane()], nowSec: 5, status: 'running', startedAt })
    expect(component.effectiveNowSec()).toBeCloseTo(30, 0)

    jest.advanceTimersByTime(4000)
    fixture.detectChanges()
    const running = component.effectiveNowSec()
    expect(running).toBeGreaterThanOrEqual(34)

    // Terminal status: the local clock stops ticking and the value stays frozen.
    fixture.componentRef.setInput('status', 'succeeded')
    fixture.detectChanges()
    const frozen = component.effectiveNowSec()

    jest.advanceTimersByTime(10000)
    fixture.detectChanges()
    expect(component.effectiveNowSec()).toBe(frozen)
  })

  it('does not advance the effective now for a terminal run', () => {
    const startedAt = new Date('2026-10-03T09:59:30.000Z').toISOString()

    const component = render({ lanes: [lane()], nowSec: 42, status: 'succeeded', startedAt })
    // Any elapsed-based value would be ~30s; the input now wins as base.
    expect(component.effectiveNowSec()).toBe(42)

    jest.advanceTimersByTime(5000)
    fixture.detectChanges()
    expect(component.effectiveNowSec()).toBe(42)
  })

  it('extends running blocks up to the effective now and leaves terminal blocks untouched', () => {
    const startedAt = new Date('2026-10-03T09:59:00.000Z').toISOString() // 60s ago

    const running = lane({ blocks: [{ label: 'build', startSec: 0, endSec: 10, status: 'running' }] })
    const done = lane({
      id: 'code',
      blocks: [{ label: 'plan', startSec: 0, endSec: 5, status: 'completed' }],
    })

    const component = render({ lanes: [running, done], nowSec: 12, status: 'running', startedAt })
    const effective = component.effectiveLanes()

    expect(effective[0]?.blocks[0]?.endSec).toBeGreaterThanOrEqual(60)
    expect(effective[1]?.blocks[0]?.endSec).toBe(5)
    // The original input lanes are never mutated.
    expect(running.blocks[0]?.endSec).toBe(10)
  })

  it('keeps a planned Thor lane empty while Tony failed remains truthfully visible', () => {
    const tony = lane({
      id: 'agent:Tony_Starck',
      label: 'Tony_Starck',
      blocks: [{ label: 'Tony failed', startSec: 2, endSec: 4, status: 'failed' }],
    })
    const thor = lane({
      id: 'agent:Thor',
      label: 'Thor',
      subtitle: 'Thor',
      blocks: [
        { label: 'Thor pending', startSec: 4, endSec: 6, status: 'pending' },
        { label: 'Thor ready', startSec: 6, endSec: 8, status: 'ready' },
      ],
    })
    const component = render({ lanes: [tony, thor], nowSec: 60, status: 'running' })

    expect(component.effectiveLanes().map((candidate) => [candidate.label, candidate.blocks.length])).toEqual([
      ['Tony_Starck', 1],
      ['Thor', 0],
    ])
    expect(fixture.nativeElement.querySelectorAll('.row.lane')).toHaveLength(2)
    expect(fixture.nativeElement.textContent).toContain('Thor')
    const labels = [...fixture.nativeElement.querySelectorAll('button.block')].map((button: Element) =>
      button.getAttribute('aria-label')
    )
    expect(labels).toEqual(expect.arrayContaining([expect.stringContaining('Tony failed, échoué')]))
    expect(labels.some((label: string | null) => label?.startsWith('Thor'))).toBe(false)
  })
})
