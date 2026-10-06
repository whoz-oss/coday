import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'
import { AllowedActionDto, StepAttemptDto } from '../../../core/models/workstream.model'
import { attemptBadgeClass } from '../workstream-badges'

/** Retry intent emitted by the view — derived strictly from DTO fields. */
export interface StepRetryRequest {
  workflowId: string
  stepId: string
  expectedRevision: number
}

/**
 * Step attempts view: durable attempt details for the selected step.
 *
 * The only action exposed is `request_agent_retry` (§5 capability matrix:
 * control-plane / human). The Retry control is enabled ONLY when the backend
 * `allowedActions` read authorizes a `retry` for the step — never from a
 * hardcoded rule. No worker can be freely launched from here.
 */
@Component({
  selector: 'app-step-attempts',
  standalone: true,
  imports: [MatButtonModule, MatIconModule],
  templateUrl: './step-attempts.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './step-attempts.component.scss',
})
export class StepAttemptsComponent {
  @Input() attempts: StepAttemptDto[] = []
  @Input() workflowId: string | null = null
  @Input() stepId: string | null = null
  @Input() allowedActions: AllowedActionDto[] = []

  @Output() retry = new EventEmitter<StepRetryRequest>()

  protected readonly attemptBadgeClass = attemptBadgeClass

  /**
   * Retry is offered only when the backend authorizes a `retry` action for this
   * step AND the attempt is `failed` / `indeterminate` (a retry from `succeeded`
   * or `running` is never meaningful).
   */
  protected canRetry(attempt: StepAttemptDto): boolean {
    if (attempt.status !== 'failed' && attempt.status !== 'indeterminate') return false
    return this.retryActionFor(attempt.stepId) !== undefined
  }

  protected onRetry(attempt: StepAttemptDto): void {
    if (!this.workflowId || !this.stepId || !this.canRetry(attempt)) return
    const action = this.retryActionFor(attempt.stepId)
    this.retry.emit({
      workflowId: this.workflowId,
      stepId: this.stepId,
      expectedRevision: action?.expectedRevision ?? attempt.revision,
    })
  }

  private retryActionFor(stepId: string): AllowedActionDto | undefined {
    return this.allowedActions.find(
      (action) => actionTypeOf(action) === 'retry' && (!action.stepId || action.stepId === stepId)
    )
  }
}

/** Resolve the effective action type across the accepted DTO shapes. */
function actionTypeOf(action: AllowedActionDto): string | undefined {
  return action.type ?? action.id ?? action.kind
}
