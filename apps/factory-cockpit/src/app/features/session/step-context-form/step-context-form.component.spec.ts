import { ComponentFixture, TestBed } from '@angular/core/testing'
import { StepContextFormComponent } from './step-context-form.component'

describe('StepContextFormComponent', () => {
  let fixture: ComponentFixture<StepContextFormComponent>
  const submitted: string[] = []

  beforeEach(async () => {
    submitted.length = 0
    await TestBed.configureTestingModule({ imports: [StepContextFormComponent] }).compileComponents()
    fixture = TestBed.createComponent(StepContextFormComponent)
    fixture.componentInstance.contextSubmitted.subscribe((value) => submitted.push(value))
    fixture.componentRef.setInput('stepId', 'build')
    fixture.componentRef.setInput('title', 'Retry failed step')
    fixture.componentRef.setInput('fieldLabel', 'Additional information (optional)')
    fixture.componentRef.setInput('submitLabel', 'Retry step')
    fixture.componentRef.setInput('busyLabel', 'Retrying…')
  })

  function host(): HTMLElement {
    fixture.detectChanges()
    return fixture.nativeElement as HTMLElement
  }
  function type(value: string): void {
    const textarea = host().querySelector('textarea') as HTMLTextAreaElement
    textarea.value = value
    textarea.dispatchEvent(new Event('input'))
    fixture.detectChanges()
  }
  function submit(): HTMLButtonElement {
    return host().querySelector('button[type="submit"]') as HTMLButtonElement
  }

  it('submits the trimmed text and counts the characters', () => {
    type('  Use staging  ')

    expect(host().textContent).toContain('15 / 4000 characters')
    submit().click()

    expect(submitted).toEqual(['Use staging'])
  })

  it('accepts an empty text when the field is optional and refuses it when required', () => {
    expect(submit().disabled).toBe(false)
    submit().click()
    expect(submitted).toEqual([''])

    fixture.componentRef.setInput('required', true)
    fixture.detectChanges()
    expect(submit().disabled).toBe(true)
    expect((host().querySelector('textarea') as HTMLTextAreaElement).required).toBe(true)
    submit().click()
    expect(submitted).toEqual([''])
  })

  it('shows the bound message once the text is invalid', () => {
    fixture.componentRef.setInput('required', true)
    type('   ')
    ;(host().querySelector('textarea') as HTMLTextAreaElement).dispatchEvent(new Event('blur'))
    fixture.detectChanges()

    expect(host().textContent).toContain('Enter between 1 and 4000 characters.')
  })

  it('disables every form while any command is in flight and relabels only the busy one', () => {
    fixture.componentRef.setInput('inFlightStepId', 'deploy')
    expect((host().querySelector('textarea') as HTMLTextAreaElement).disabled).toBe(true)
    expect(submit().disabled).toBe(true)
    expect(submit().textContent).toContain('Retry step')
    host().querySelector('form')?.dispatchEvent(new Event('submit'))
    expect(submitted).toEqual([])

    fixture.componentRef.setInput('inFlightStepId', 'build')
    fixture.detectChanges()
    expect(submit().textContent).toContain('Retrying')
  })

  it('shows the feedback of its own step only', () => {
    fixture.componentRef.setInput('feedback', { stepId: 'deploy', kind: 'conflict', message: 'Other step' })
    expect(host().textContent).not.toContain('Other step')

    fixture.componentRef.setInput('feedback', { stepId: 'build', kind: 'conflict', message: 'The workflow has changed.' })
    fixture.detectChanges()
    expect(host().textContent).toContain('The workflow has changed.')
    expect(host().querySelector('.action-feedback--conflict')).not.toBeNull()
  })
})
