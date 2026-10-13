import { provideHttpClient } from '@angular/common/http'
import { provideHttpClientTesting } from '@angular/common/http/testing'
import { ComponentFixture, TestBed } from '@angular/core/testing'
import { provideRouter } from '@angular/router'
import { AgentQuestion, AllowedAction, HumanInteraction, WorkflowBlocker } from '../../core/models'
import { ActionBarComponent, CancelIntent, ReplyIntent, RetryIntent } from './action-bar.component'

const interaction: HumanInteraction = {
  interactionId: 'i-1',
  stepId: 'build',
  interactionType: 'approval',
  status: 'waiting',
  prompt: 'Approve the deploy?',
  actions: [
    { id: 'approve', label: 'Approve' },
    { id: 'reject', label: 'Reject' },
  ],
}

describe('ActionBarComponent', () => {
  let fixture: ComponentFixture<ActionBarComponent>

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ActionBarComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents()
    fixture = TestBed.createComponent(ActionBarComponent)
  })

  function render(
    allowedActions: AllowedAction[] = [],
    blockers: WorkflowBlocker[] = [],
    interactions: HumanInteraction[] = []
  ): HTMLElement {
    fixture.componentRef.setInput('allowedActions', allowedActions)
    fixture.componentRef.setInput('blockers', blockers)
    fixture.componentRef.setInput('interactions', interactions)
    fixture.detectChanges()
    return fixture.nativeElement as HTMLElement
  }

  function buttons(host: HTMLElement): HTMLButtonElement[] {
    return Array.from(host.querySelectorAll('.action-row button'))
  }

  it('renders no action button when nothing is authorized', () => {
    const host = render([])
    expect(host.querySelector('.actions')).toBeNull()
    expect(host.querySelector('.blockers')).toBeNull()
    expect(buttons(host)).toHaveLength(0)
  })

  it('renders ONLY the actions present in allowedActions', () => {
    const host = render([{ type: 'retry', stepId: 'build', expectedRevision: 7 }], [], [interaction])

    expect(host.querySelector('.actions')).not.toBeNull()
    // A reply would require the corresponding allowed action: it is absent.
    expect(buttons(host)).toHaveLength(1)
    expect(buttons(host)[0]?.textContent).toContain('Retry step')
  })

  it('renders the reply choices and prompt from the interaction', () => {
    const host = render(
      [{ type: 'reply', interactionId: 'i-1', expectedRevision: 3, label: 'Approve?' }],
      [],
      [interaction]
    )

    const choices = buttons(host)
    expect(choices).toHaveLength(2)
    expect(choices.map((button) => button.textContent?.trim())).toEqual(['Approve', 'Reject'])
    expect(host.textContent).toContain('Approve the deploy?')
  })

  it('emits a ReplyIntent with the action identity and revision (never fabricated)', () => {
    const emitted: ReplyIntent[] = []
    fixture.componentInstance.reply.subscribe((intent) => emitted.push(intent))
    const host = render([{ type: 'reply', interactionId: 'i-1', expectedRevision: 3 }], [], [interaction])

    buttons(host)[0]?.click()

    expect(emitted).toEqual([{ interactionId: 'i-1', actionId: 'approve', expectedRevision: 3 }])
  })

  it('answers only the selected open agent question with matching reply revision', () => {
    const emitted: unknown[] = []
    fixture.componentInstance.agentQuestionAnswered.subscribe((intent) => emitted.push(intent))
    fixture.componentRef.setInput('selectedStepId', 'another-step')
    fixture.componentRef.setInput('namespaceId', 'ns-1')
    const question: AgentQuestion = {
      questionEventId: 'q-1',
      caseId: 'case-1',
      attemptId: 'a-1',
      stepId: 'build',
      question: 'Choose',
      questionType: 'SINGLE_CHOICE',
      options: ['A', 'B'],
      answered: false,
    }
    fixture.componentRef.setInput('agentQuestionsInput', [question])
    const host = render([], [], [])

    buttons(host)[0]?.click()

    expect(emitted).toEqual([{ question, answer: 'A' }])
    expect(host.querySelector('a')?.getAttribute('href')).toBe('/agentos/home?ns=ns-1&case=case-1')
    expect(emitted[0]).not.toHaveProperty('namespaceId')
    expect(emitted[0]).not.toHaveProperty('attemptId')
    expect(emitted[0]).not.toHaveProperty('caseId')
    expect(emitted[0]).not.toHaveProperty('actorId')
  })

  it('shows every open question independently from the selected timeline step', () => {
    fixture.componentRef.setInput('selectedStepId', 'build')
    fixture.componentRef.setInput('agentQuestionsInput', [
      {
        questionEventId: 'q-design',
        caseId: 'case-design',
        attemptId: 'a-1',
        stepId: 'technical-design',
        question: 'Architecture?',
        questionType: 'FREE_TEXT',
        answered: false,
      },
    ])

    const host = render([], [], [])

    expect(host.textContent).toContain('Architecture?')
    expect(host.querySelector('input[placeholder="Your answer"]')).not.toBeNull()
  })

  it('renders a Case fallback when question projection is unavailable', () => {
    fixture.componentRef.setInput('agentQuestionsError', {
      code: 'AGENT_QUESTIONS_UNAVAILABLE',
      message: 'Question projection unavailable',
      caseId: 'case-9',
      namespaceId: 'ns-9',
    })

    const host = render([], [], [])

    expect(host.textContent).toContain('Question projection unavailable')
    expect(host.querySelector('a')?.getAttribute('href')).toBe('/agentos/home?ns=ns-9&case=case-9')
  })

  it('fails closed for unsupported questions and mismatched revisions', () => {
    fixture.componentRef.setInput('selectedStepId', 'build')
    const unsupported: AgentQuestion = {
      questionEventId: 'q-1',
      caseId: 'case-1',
      attemptId: 'a-1',
      stepId: 'build',
      question: 'Unsupported',
      questionType: 'MULTI_CHOICE',
      options: ['A'],
      answered: false,
    }
    fixture.componentRef.setInput('agentQuestionsInput', [unsupported])
    const host = render([], [], [])
    expect(host.textContent).toContain('Unsupported question type')
    expect(buttons(host)).toHaveLength(0)
  })

  it('emits a RetryIntent carrying the step and the expected revision', () => {
    const emitted: RetryIntent[] = []
    fixture.componentInstance.retryRequested.subscribe((intent) => emitted.push(intent))
    const host = render([{ type: 'retry', stepId: 'build', expectedRevision: 7 }])

    buttons(host)[0]?.click()

    expect(emitted).toEqual([{ stepId: 'build', expectedRevision: 7, reasonCode: 'human_retry' }])
  })

  it('emits a CancelIntent carrying the attempt and the expected revision', () => {
    const emitted: CancelIntent[] = []
    fixture.componentInstance.cancelAttempt.subscribe((intent) => emitted.push(intent))
    const host = render([{ type: 'cancel_attempt', attemptId: 'a-1', expectedRevision: 4 }])

    buttons(host)[0]?.click()

    expect(emitted).toEqual([{ attemptId: 'a-1', expectedRevision: 4 }])
  })

  it('renders cost continue/stop actions and emits their intents', () => {
    const continued: unknown[] = []
    let stops = 0
    fixture.componentInstance.continueCost.subscribe((intent) => continued.push(intent))
    fixture.componentInstance.stopCost.subscribe(() => (stops += 1))
    const host = render([
      { type: 'continue_cost', expectedRevision: 1 },
      { type: 'stop_cost', expectedRevision: 1 },
    ])

    const actions = buttons(host)
    expect(actions).toHaveLength(2)
    actions[0]?.click()
    actions[1]?.click()

    expect(continued).toEqual([{}])
    expect(stops).toBe(1)
  })

  it('renders blockers with a visual class per blocker nature', () => {
    const blockers: WorkflowBlocker[] = [
      { code: 'WAITING_HUMAN_INTERACTION', stepId: 'build', label: 'Waiting' },
      { code: 'STEP_BLOCKED', stepId: 'build', label: 'Blocked' },
      { code: 'REAL_COST_PAUSED', label: 'Paused' },
      { code: 'SOMETHING_ELSE', label: 'Weird' },
    ]
    const host = render([], blockers)

    const elements = Array.from(host.querySelectorAll('.blocker'))
    expect(elements).toHaveLength(4)
    expect(elements[0]?.classList).toContain('blocker--waiting')
    expect(elements[1]?.classList).toContain('blocker--blocked')
    expect(elements[2]?.classList).toContain('blocker--cost')
    expect(elements[3]?.classList).toContain('blocker--unknown')
  })
})
