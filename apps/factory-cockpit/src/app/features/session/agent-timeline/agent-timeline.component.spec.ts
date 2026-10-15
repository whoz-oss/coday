import { ComponentFixture, TestBed } from '@angular/core/testing'
import { TimelineBlock, TimelineLane } from '../../../core/models'
import { AgentTimelineComponent } from './agent-timeline.component'

/** Structural view over the component's protected signals for assertions. */
interface TimelineInternals {
  effectiveNowSec: () => number
  axisEnd: () => number
  effectiveLanes: () => TimelineLane[]
  blockTooltip: (block: TimelineBlock) => string
  isSelected: (block: TimelineBlock) => boolean
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
    // Freeze wall time before creating the component clock signal.
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
    activelyRunning: boolean
    timelineOriginAt?: string
    selected?: string
  }): TimelineInternals {
    fixture.componentRef.setInput('lanes', inputs.lanes)
    fixture.componentRef.setInput('nowSec', inputs.nowSec)
    fixture.componentRef.setInput('activelyRunning', inputs.activelyRunning)
    if (inputs.timelineOriginAt) fixture.componentRef.setInput('timelineOriginAt', inputs.timelineOriginAt)
    if (inputs.selected !== undefined) fixture.componentRef.setInput('selected', inputs.selected)
    fixture.detectChanges()
    return fixture.componentInstance as unknown as TimelineInternals
  }

  it('advances the effective now while running and freezes once terminal', () => {
    const timelineOriginAt = new Date('2026-10-03T09:59:30.000Z').toISOString() // 30s ago

    const component = render({ lanes: [lane()], nowSec: 5, activelyRunning: true, timelineOriginAt })
    expect(component.effectiveNowSec()).toBe(30)

    jest.advanceTimersByTime(4000)
    fixture.detectChanges()
    const running = component.effectiveNowSec()
    expect(running).toBeGreaterThanOrEqual(34)

    // Suspended status: the local active clock stops and stays frozen.
    fixture.componentRef.setInput('activelyRunning', false)
    fixture.detectChanges()
    const frozen = component.effectiveNowSec()

    jest.advanceTimersByTime(10000)
    fixture.detectChanges()
    expect(component.effectiveNowSec()).toBe(frozen)
  })

  it('does not regress when an older running snapshot arrives over SSE', () => {
    const timelineOriginAt = new Date('2026-10-03T09:59:30.000Z').toISOString()
    const component = render({ lanes: [lane()], nowSec: 28, activelyRunning: true, timelineOriginAt })
    expect(component.effectiveNowSec()).toBe(30)

    fixture.componentRef.setInput('nowSec', 20)
    fixture.detectChanges()
    expect(component.effectiveNowSec()).toBe(30)
  })

  it('keeps a non-zero axis for an empty snapshot', () => {
    const component = render({ lanes: [lane()], nowSec: 0, activelyRunning: false })
    expect(component.axisEnd()).toBeGreaterThan(0)
  })

  it('does not advance the effective now for a terminal run', () => {
    const timelineOriginAt = new Date('2026-10-03T09:59:30.000Z').toISOString()

    const component = render({ lanes: [lane()], nowSec: 42, activelyRunning: false, timelineOriginAt })
    // Any elapsed-based value would be ~30s; the input now wins as base.
    expect(component.effectiveNowSec()).toBe(42)

    jest.advanceTimersByTime(5000)
    fixture.detectChanges()
    expect(component.effectiveNowSec()).toBe(42)
  })

  it('extends running blocks up to the effective now and leaves terminal blocks untouched', () => {
    const timelineOriginAt = new Date('2026-10-03T09:59:00.000Z').toISOString() // 60s ago

    const running = lane({ blocks: [{ label: 'build', startSec: 0, endSec: 10, status: 'running' }] })
    const done = lane({
      id: 'code',
      blocks: [{ label: 'plan', startSec: 0, endSec: 5, status: 'completed' }],
    })

    const component = render({ lanes: [running, done], nowSec: 12, activelyRunning: true, timelineOriginAt })
    const effective = component.effectiveLanes()

    expect(effective[0]?.blocks[0]?.endSec).toBeGreaterThanOrEqual(12)
    expect(effective[1]?.blocks[0]?.endSec).toBe(5)
    // The original input lanes are never mutated.
    expect(running.blocks[0]?.endSec).toBe(10)
  })

  it('does not advance or extend a waiting-human block while suspended', () => {
    const architect = lane({
      label: 'Architect',
      blocks: [{ label: 'technical-design', startSec: 5, endSec: 20, status: 'waiting_human' }],
    })
    const component = render({ lanes: [architect], nowSec: 20, activelyRunning: false })

    jest.advanceTimersByTime(10000)
    fixture.detectChanges()

    expect(component.effectiveNowSec()).toBe(20)
    expect(component.effectiveLanes()[0]?.blocks[0]?.endSec).toBe(20)
    expect(fixture.nativeElement.querySelector('.block--waiting')).not.toBeNull()
  })

  describe('blockTooltip', () => {
    it('builds a minimal tooltip for a plain block', () => {
      const component = render({ lanes: [lane()], nowSec: 10, activelyRunning: false })
      const block: TimelineBlock = { label: 'build', startSec: 0, endSec: 10, status: 'completed' }
      expect(component.blockTooltip(block)).toBe('build \u00b7 completed')
    })

    it('includes stepId in the tooltip when it differs from the label', () => {
      const component = render({ lanes: [lane()], nowSec: 10, activelyRunning: false })
      const block: TimelineBlock = {
        label: 'Build \u00b7 attempt 1',
        stepId: 'build',
        startSec: 0,
        endSec: 10,
        status: 'failed',
      }
      const tooltip = component.blockTooltip(block)
      expect(tooltip).toContain('step: build')
      expect(tooltip).toContain('failed')
    })

    it('includes agentName, attemptNumber, caseId and failureCode when present', () => {
      const component = render({ lanes: [lane()], nowSec: 10, activelyRunning: false })
      const block: TimelineBlock = {
        label: 'Build \u00b7 attempt 1',
        stepId: 'build',
        startSec: 0,
        endSec: 10,
        status: 'failed',
        agentName: 'builder',
        attemptNumber: 1,
        caseId: 'case-1',
        failureCode: 'VERIFICATION_FAILED',
      }
      const tooltip = component.blockTooltip(block)
      expect(tooltip).toContain('agent: builder')
      expect(tooltip).toContain('attempt #1')
      expect(tooltip).toContain('case: case-1')
      expect(tooltip).toContain('failure: VERIFICATION_FAILED')
    })
  })

  describe('isSelected', () => {
    it('returns false when nothing is selected', () => {
      const component = render({ lanes: [lane()], nowSec: 10, activelyRunning: false })
      const block: TimelineBlock = { label: 'build', stepId: 'build', startSec: 0, endSec: 10, status: 'completed' }
      expect(component.isSelected(block)).toBe(false)
    })

    it('matches by stepId when present', () => {
      const component = render({ lanes: [lane()], nowSec: 10, activelyRunning: false, selected: 'build' })
      const block: TimelineBlock = {
        label: 'Build \u00b7 attempt 1',
        stepId: 'build',
        startSec: 0,
        endSec: 10,
        status: 'completed',
      }
      expect(component.isSelected(block)).toBe(true)
    })

    it('falls back to label matching when stepId is absent', () => {
      const component = render({ lanes: [lane()], nowSec: 10, activelyRunning: false, selected: 'request' })
      const block: TimelineBlock = { label: 'request', startSec: 0, endSec: 5, status: 'completed' }
      expect(component.isSelected(block)).toBe(true)
    })

    it('does not match a block with a different stepId', () => {
      const component = render({ lanes: [lane()], nowSec: 10, activelyRunning: false, selected: 'plan' })
      const block: TimelineBlock = { label: 'build', stepId: 'build', startSec: 0, endSec: 10, status: 'completed' }
      expect(component.isSelected(block)).toBe(false)
    })

    it('remains stable when the block label changes (SSE refresh scenario)', () => {
      // Simulate: user clicked on attempt 1, then SSE refresh changes label to attempt 2
      const component = render({ lanes: [lane()], nowSec: 10, activelyRunning: false, selected: 'build' })
      // Attempt 1 block (before refresh)
      const attempt1: TimelineBlock = {
        label: 'Build \u00b7 attempt 1',
        stepId: 'build',
        startSec: 0,
        endSec: 10,
        status: 'failed',
      }
      // Attempt 2 block (after refresh - label changed but stepId is stable)
      const attempt2: TimelineBlock = {
        label: 'Build \u00b7 attempt 2',
        stepId: 'build',
        startSec: 15,
        endSec: 30,
        status: 'running',
      }
      // Both should be selected because they share the same stepId
      expect(component.isSelected(attempt1)).toBe(true)
      expect(component.isSelected(attempt2)).toBe(true)
    })
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
    const component = render({ lanes: [tony, thor], nowSec: 60, activelyRunning: true })

    expect(component.effectiveLanes().map((candidate) => [candidate.label, candidate.blocks.length])).toEqual([
      ['Tony_Starck', 1],
      ['Thor', 0],
    ])
    expect(fixture.nativeElement.querySelectorAll('.row.lane')).toHaveLength(2)
    expect(fixture.nativeElement.textContent).toContain('Thor')
    const labels = [...fixture.nativeElement.querySelectorAll('button.block')].map((button: Element) =>
      button.getAttribute('aria-label')
    )
    expect(labels).toEqual(expect.arrayContaining([expect.stringContaining('Tony failed, failed')]))
    expect(labels.some((label: string | null) => label?.startsWith('Thor'))).toBe(false)
  })
})
