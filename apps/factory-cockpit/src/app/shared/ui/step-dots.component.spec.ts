import { TestBed } from '@angular/core/testing'
import { By } from '@angular/platform-browser'
import { MatTooltip } from '@angular/material/tooltip'
import { PhaseSegment } from '../../core/models'
import { StepDotsComponent } from './step-dots.component'

const skipped: PhaseSegment = { key: 'backend', label: 'Backend branch', ratio: 1, tone: 'green', status: 'skipped' }

describe('StepDotsComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [StepDotsComponent] }).compileComponents()
  })

  it('renders skipped as a muted dot with the same truthful tooltip and accessible label', () => {
    const fixture = TestBed.createComponent(StepDotsComponent)
    fixture.componentRef.setInput('phases', [skipped])
    fixture.detectChanges()

    const dot = fixture.debugElement.query(By.directive(MatTooltip))
    const element = dot.nativeElement as HTMLElement
    expect(fixture.componentInstance.dotClass(skipped)).toBe('dot--skipped')
    expect(element.classList.contains('dot--skipped')).toBe(true)
    expect(element.classList.contains('dot--done')).toBe(false)
    expect(element.classList.contains('dot--failed')).toBe(false)
    expect(element.getAttribute('tabindex')).toBe('0')
    expect(element.getAttribute('aria-label')).toBe('Backend branch: skipped, not executed')
    expect(dot.injector.get(MatTooltip).message).toBe('Backend branch: skipped, not executed')
    expect(fixture.componentInstance.ariaLabel()).toBe('Steps: Backend branch: skipped, not executed')
  })

  it('uses the step key when a skipped step has no readable label', () => {
    const fixture = TestBed.createComponent(StepDotsComponent)
    expect(fixture.componentInstance.dotLabel({ ...skipped, label: undefined })).toBe('backend: skipped, not executed')
  })
})
