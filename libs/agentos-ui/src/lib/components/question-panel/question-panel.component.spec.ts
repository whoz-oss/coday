import { TestBed } from '@angular/core/testing'
import { QuestionEvent, QuestionEventQuestionTypeEnum } from '@whoz-oss/agentos-api-client'
import { OAuthAgentosService } from '../../services/oauth-agentos.service'
import { QuestionPanelComponent } from './question-panel.component'

const question = (overrides: Partial<QuestionEvent> = {}): QuestionEvent => ({
  id: 'q-1',
  type: 'QuestionEvent',
  caseId: 'c-1',
  namespaceId: 'ns-1',
  timestamp: '2026-01-01T00:00:00Z',
  metadata: { id: 'q-1', created: '', modified: '', removed: false },
  agentId: 'agent-1',
  agentName: 'Agent',
  question: 'Which color?',
  questionType: QuestionEventQuestionTypeEnum.SINGLE_CHOICE,
  options: ['red', 'blue'],
  ...overrides,
})

describe('QuestionPanelComponent', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [QuestionPanelComponent],
      providers: [{ provide: OAuthAgentosService, useValue: { openPopup: jest.fn(), cancelRequest: jest.fn() } }],
    })
  })

  function render(event: QuestionEvent, inputs: { variant?: 'panel' | 'inline'; disabled?: boolean } = {}) {
    const fixture = TestBed.createComponent(QuestionPanelComponent)
    fixture.componentRef.setInput('questionEvent', event)
    for (const [key, value] of Object.entries(inputs)) fixture.componentRef.setInput(key, value)
    fixture.detectChanges()
    return fixture
  }

  const el = (fixture: { nativeElement: unknown }) => fixture.nativeElement as HTMLElement

  it('shows the agent and the question in the default panel variant', () => {
    const text = el(render(question())).textContent
    expect(text).toContain('Agent')
    expect(text).toContain('Which color?')
  })

  it('does not render the question text again in the inline variant', () => {
    const root = el(render(question(), { variant: 'inline' }))
    expect(root.textContent).not.toContain('Which color?')
    expect(root.querySelector('.question-panel__header')).toBeNull()
  })

  it('renders options as native buttons inside a group labelled by the question element', () => {
    const root = el(render(question(), { variant: 'inline' }))
    const group = root.querySelector('[role="group"]')
    expect(group?.getAttribute('aria-labelledby')).toBe('question-q-1')
    const buttons = Array.from(group!.querySelectorAll('button'))
    expect(buttons.map((b) => b.textContent?.trim())).toEqual(['red', 'blue'])
    expect(buttons.every((b) => b.type === 'button')).toBe(true)
  })

  it('emits the option when it is clicked', () => {
    const fixture = render(question(), { variant: 'inline' })
    const answered = jest.fn()
    fixture.componentInstance.answered.subscribe(answered)

    el(fixture).querySelectorAll('button')[1]!.click()

    expect(answered).toHaveBeenCalledWith('blue')
  })

  it('labels the free-text input and only enables Submit once something is typed', () => {
    const fixture = render(question({ questionType: QuestionEventQuestionTypeEnum.FREE_TEXT, options: null }), {
      variant: 'inline',
    })
    const root = el(fixture)
    const input = root.querySelector('input')!
    const submit = root.querySelector<HTMLButtonElement>('button')!
    expect(input.getAttribute('aria-label')).toBe('Your answer')
    expect(submit.disabled).toBe(true)

    const answered = jest.fn()
    fixture.componentInstance.answered.subscribe(answered)
    input.value = ' green '
    input.dispatchEvent(new Event('input'))
    fixture.detectChanges()
    expect(submit.disabled).toBe(false)
    submit.click()

    expect(answered).toHaveBeenCalledWith('green')
  })

  it('disables every control and emits nothing while disabled', () => {
    const fixture = render(question({ questionType: QuestionEventQuestionTypeEnum.OPEN_CHOICE }), {
      variant: 'inline',
      disabled: true,
    })
    const root = el(fixture)
    const answered = jest.fn()
    fixture.componentInstance.answered.subscribe(answered)

    const controls = Array.from(root.querySelectorAll<HTMLButtonElement | HTMLInputElement>('button, input'))
    expect(controls.length).toBeGreaterThan(0)
    expect(controls.every((control) => control.disabled)).toBe(true)
    root.querySelector('button')!.click()
    expect(answered).not.toHaveBeenCalled()
  })

  it('keeps the OAuth Authorize and Cancel buttons as native buttons in a labelled group', () => {
    const root = el(
      render(question({ questionType: QuestionEventQuestionTypeEnum.OAUTH_AUTHORIZE, options: null }), {
        variant: 'inline',
      })
    )
    const group = root.querySelector('[role="group"]')
    expect(group?.getAttribute('aria-labelledby')).toBe('question-q-1')
    expect(Array.from(group!.querySelectorAll('button')).map((b) => b.textContent?.trim())).toEqual([
      'Authorize',
      'Cancel',
    ])
  })
})
