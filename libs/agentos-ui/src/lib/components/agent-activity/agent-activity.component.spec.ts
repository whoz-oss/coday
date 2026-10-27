import { ComponentFixture, TestBed, discardPeriodicTasks, fakeAsync, tick } from '@angular/core/testing'
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

  function text(fixture: ComponentFixture<AgentActivityComponent>): string {
    return (fixture.nativeElement as HTMLElement).textContent?.trim() ?? ''
  }

  it('shows the sentence of the current step, and the tool behind it as a tooltip', () => {
    const fixture = render({ label: 'Reading README.md', detail: 'FILES__readFile' })
    expect(text(fixture)).toBe('Reading README.md')
    const line = (fixture.nativeElement as HTMLElement).querySelector('.agent-activity')
    expect(line?.getAttribute('title')).toBe('FILES__readFile')
  })

  it('replaces the sentence when the step changes', () => {
    const fixture = render({ label: 'Reading README.md' })
    fixture.componentRef.setInput('activity', { label: 'Planning the next step' })
    fixture.detectChanges()
    expect(text(fixture)).toBe('Planning the next step')
  })

  it('stays quiet about the time until the step is worth timing', fakeAsync(() => {
    const fixture = render({ label: 'Running bash: pnpm lint' })

    tick(4000)
    fixture.detectChanges()
    expect(text(fixture)).toBe('Running bash: pnpm lint')

    tick(3000)
    fixture.detectChanges()
    expect(text(fixture)).toContain('7s')

    discardPeriodicTasks()
  }))

  it('restarts the counter on the next step', fakeAsync(() => {
    const fixture = render({ label: 'Running bash: pnpm lint' })
    tick(7000)
    fixture.detectChanges()
    expect(text(fixture)).toContain('7s')

    fixture.componentRef.setInput('activity', { label: 'Planning the next step' })
    fixture.detectChanges()
    expect(text(fixture)).toBe('Planning the next step')

    discardPeriodicTasks()
  }))
})
