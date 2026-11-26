import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'

import { StepFeedback } from '../../../core/step-command-feedback'

/** Maximum length of the text of a step command. */
export const STEP_CONTEXT_MAX = 4000

/**
 * Presentational form of a governed step command that takes free text (retry of a failed step, resumption of a
 * research step): a textarea with its counter, a submit button, the feedback of the last attempt and the in-flight
 * state. It owns only the draft; the command itself is decided by the parent, which receives the trimmed text.
 */
@Component({
  selector: 'sf-step-context-form',
  imports: [MatButtonModule, MatIconModule],
  templateUrl: './step-context-form.component.html',
  styleUrl: './step-context-form.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StepContextFormComponent {
  readonly stepId = input.required<string>()
  readonly cardClass = input<string>('')
  readonly icon = input<string>('edit')
  readonly title = input.required<string>()
  readonly help = input<string>('')
  readonly fieldLabel = input.required<string>()
  readonly submitLabel = input.required<string>()
  readonly busyLabel = input.required<string>()
  /** Whether the text is mandatory (1 to 4000 characters) or optional (0 to 4000). */
  readonly required = input<boolean>(false)
  readonly rows = input<number>(4)
  /** Step whose command is in flight, or null. Any in-flight command disables every form. */
  readonly inFlightStepId = input<string | null>(null)
  readonly feedback = input<StepFeedback | null>(null)

  readonly contextSubmitted = output<string>()

  protected readonly maxLength = STEP_CONTEXT_MAX
  protected readonly draft = signal('')
  private readonly touched = signal(false)

  protected readonly fieldId = computed(() => `step-context-${this.stepId()}`)
  protected readonly helpId = computed(() => `step-context-help-${this.stepId()}`)
  protected readonly disabled = computed(() => this.inFlightStepId() !== null)
  protected readonly busy = computed(() => this.inFlightStepId() === this.stepId())
  protected readonly valid = computed(() => {
    const length = this.draft().trim().length
    return length <= STEP_CONTEXT_MAX && (!this.required() || length >= 1)
  })
  protected readonly showInvalid = computed(() => !this.valid() && (this.touched() || this.draft().length > 0))
  protected readonly invalidMessage = computed(() =>
    this.required() ? `Enter between 1 and ${STEP_CONTEXT_MAX} characters.` : `Enter at most ${STEP_CONTEXT_MAX} characters.`
  )
  protected readonly stepFeedback = computed(() => {
    const feedback = this.feedback()
    return feedback && feedback.stepId === this.stepId() ? feedback : null
  })

  protected setDraft(value: string): void {
    this.draft.set(value)
  }

  protected markTouched(): void {
    this.touched.set(true)
  }

  protected submit(): void {
    if (this.disabled()) return
    this.touched.set(true)
    if (!this.valid()) return
    this.contextSubmitted.emit(this.draft().trim())
  }
}
