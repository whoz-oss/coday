import { provideHttpClient } from '@angular/common/http'
import { provideHttpClientTesting } from '@angular/common/http/testing'
import { ComponentFixture, TestBed } from '@angular/core/testing'
import { AllowedAction, HumanInteraction, WorkflowBlocker } from '../../core/models'
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
      providers: [provideHttpClient(), provideHttpClientTesting()],
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
    expect(buttons(host)[0]?.textContent).toContain("Relancer l'étape")
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
