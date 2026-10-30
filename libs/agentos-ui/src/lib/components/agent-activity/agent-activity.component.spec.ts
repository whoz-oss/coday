import { ComponentFixture, TestBed } from '@angular/core/testing'
import { AgentActivityComponent } from './agent-activity.component'
import { AgentActivity } from './agent-activity.models'

describe('AgentActivityComponent', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [AgentActivityComponent] })
  })

  function render(activity: AgentActivity): ComponentFixture<AgentActivityComponent> {
    const fixture = TestBed.createComponent(AgentActivityComponent)
    fixture.componentRef.setInput('activity', activity)
    fixture.detectChanges()
    return fixture
  }

  function textOf(fixture: ComponentFixture<AgentActivityComponent>, selector: string): string {
    const element = (fixture.nativeElement as HTMLElement).querySelector(selector)
    return element?.textContent?.trim() ?? ''
  }

  /** An ISO timestamp this many seconds in the past, as the server would have stamped it. */
  function secondsAgo(seconds: number): string {
    return new Date(Date.now() - seconds * 1000).toISOString()
  }

  it('shows the sentence of the current step, and the tool behind it as a tooltip', () => {
    const fixture = render({ label: 'Reading README.md', detail: 'FILES__readFile' })
    expect(textOf(fixture, '.agent-activity__label')).toBe('Reading README.md')
    const line = (fixture.nativeElement as HTMLElement).querySelector('.agent-activity')
    expect(line?.getAttribute('title')).toBe('FILES__readFile')
  })

  it('replaces the sentence when the step changes', () => {
    const fixture = render({ label: 'Reading README.md' })
    fixture.componentRef.setInput('activity', { label: 'Planning the next step' })
    fixture.detectChanges()
    expect(textOf(fixture, '.agent-activity__label')).toBe('Planning the next step')
  })

  it('announces the sentence from a region that the counter never touches', () => {
    const fixture = render({ label: 'Running bash: pnpm lint', since: secondsAgo(90) })
    const announce = (fixture.nativeElement as HTMLElement).querySelector('.agent-activity__announce')
    expect(announce?.getAttribute('aria-live')).toBe('polite')
    expect(announce?.textContent?.trim()).toBe('Running bash: pnpm lint')
    expect((fixture.nativeElement as HTMLElement).querySelector('.agent-activity__elapsed')).not.toBeNull()
    expect(announce?.contains((fixture.nativeElement as HTMLElement).querySelector('.agent-activity__elapsed'))).toBe(
      false
    )
  })

  it('counts from the timestamp of the step, not from its own first render', () => {
    const fixture = render({ label: 'Running bash: pnpm test', since: secondsAgo(90) })
    expect(textOf(fixture, '.agent-activity__elapsed')).toBe('1m 30s')
  })

  it('stays quiet about the time until the step is worth timing', () => {
    const fixture = render({ label: 'Reading README.md', since: secondsAgo(2) })
    expect(textOf(fixture, '.agent-activity__elapsed')).toBe('')
  })

  it('falls back to its own clock when no event timestamps the step', () => {
    const fixture = render({ label: 'Thinking' })
    expect(textOf(fixture, '.agent-activity__elapsed')).toBe('')
  })

  it('restarts the counter on the next step', () => {
    const fixture = render({ label: 'Running bash: pnpm test', since: secondsAgo(90) })
    expect(textOf(fixture, '.agent-activity__elapsed')).toBe('1m 30s')

    fixture.componentRef.setInput('activity', { label: 'Planning the next step', since: secondsAgo(1) })
    fixture.detectChanges()
    expect(textOf(fixture, '.agent-activity__elapsed')).toBe('')
  })
})
