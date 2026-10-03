import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'
import { AttemptStatus, StepAttemptDto } from '../../../core/models/workstream.model'
import { attemptBadgeClass } from '../workstream-badges'

/** Retry intent emitted by the view — derived strictly from DTO fields. */
export interface StepRetryRequest {
  workflowId: string
  stepId: string
  expectedRevision: number
}

/**
 * Step attempts view: durable attempt details for the selected step.
 * The only action exposed is `request_agent_retry` (§5 capability matrix:
 * control-plane / human). No worker can be freely launched from here.
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

  @Output() retry = new EventEmitter<StepRetryRequest>()

  protected readonly attemptBadgeClass = attemptBadgeClass

  /** Retry is only offered on failed / indeterminate attempts (§5 matrix). */
  protected canRetry(status: AttemptStatus): boolean {
    return status === 'failed' || status === 'indeterminate'
  }

  protected onRetry(attempt: StepAttemptDto): void {
    if (!this.workflowId || !this.stepId || !this.canRetry(attempt.status)) return
    this.retry.emit({
      workflowId: this.workflowId,
      stepId: this.stepId,
      expectedRevision: attempt.revision,
    })
  }
}
