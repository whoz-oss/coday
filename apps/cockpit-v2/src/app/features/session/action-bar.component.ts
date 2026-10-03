import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'
import { AllowedAction, HumanInteraction, WorkflowBlocker } from '../../core/models'

export interface ReplyIntent {
  interactionId: string
  actionId?: string
  text?: string
  expectedRevision?: number
}

export interface RetryIntent {
  stepId: string
  expectedRevision?: number
  reasonCode?: string
}

export interface CancelIntent {
  attemptId: string
  expectedRevision?: number
  reason?: string
}

export interface ContinueCostIntent {
  expectedThreshold?: number
}

/**
 * Backend-driven action bar.
 *
 * INVARIANT: every button rendered here exists ONLY because the corresponding
 * action is present in `allowedActions` (the backend's single authority). The
 * component never derives an action, an identity or a revision on its own: it
 * echoes the target ids/revisions carried by the action, and emits intents for
 * the page/store to execute.
 */
@Component({
  selector: 'sf-action-bar',
  imports: [MatButtonModule, MatIconModule],
  templateUrl: './action-bar.component.html',
  styleUrl: './action-bar.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ActionBarComponent {
  readonly allowedActions = input<AllowedAction[]>([])
  readonly blockers = input<WorkflowBlocker[]>([])
  readonly interactions = input<HumanInteraction[]>([])

  readonly reply = output<ReplyIntent>()
  readonly retryRequested = output<RetryIntent>()
  readonly cancelAttempt = output<CancelIntent>()
  readonly continueCost = output<ContinueCostIntent>()
  readonly stopCost = output<void>()

  protected readonly replyActions = computed(() => this.allowedActions().filter((action) => action.type === 'reply'))
  protected readonly retryActions = computed(() => this.allowedActions().filter((action) => action.type === 'retry'))
  protected readonly cancelActions = computed(() =>
    this.allowedActions().filter((action) => action.type === 'cancel_attempt')
  )
  protected readonly continueCostActions = computed(() =>
    this.allowedActions().filter((action) => action.type === 'continue_cost')
  )
  protected readonly stopCostActions = computed(() =>
    this.allowedActions().filter((action) => action.type === 'stop_cost')
  )

  protected readonly hasActions = computed(() => this.allowedActions().length > 0)
  protected readonly hasBlockers = computed(() => this.blockers().length > 0)

  /** Draft reply texts, keyed by interaction id (only used when a field shows). */
  private readonly drafts = signal<Record<string, string>>({})

  protected draftFor(interactionId: string): string {
    return this.drafts()[interactionId] ?? ''
  }

  protected setDraft(interactionId: string, value: string): void {
    this.drafts.update((current) => ({ ...current, [interactionId]: value }))
  }

  protected interactionFor(action: AllowedAction): HumanInteraction | undefined {
    if (!action.interactionId) return undefined
    return this.interactions().find((interaction) => interaction.interactionId === action.interactionId)
  }

  protected blockerKind(blocker: WorkflowBlocker): 'waiting' | 'blocked' | 'cost' | 'unknown' {
    switch (blocker.code) {
      case 'WAITING_HUMAN_INTERACTION':
        return 'waiting'
      case 'STEP_BLOCKED':
      case 'ATTEMPT_FAILED':
      case 'VERIFICATION_FAILED':
        return 'blocked'
      case 'REAL_COST_PAUSED':
        return 'cost'
      default:
        return 'unknown'
    }
  }

  protected blockerIcon(blocker: WorkflowBlocker): string {
    switch (this.blockerKind(blocker)) {
      case 'waiting':
        return 'person_alert'
      case 'blocked':
        return 'block'
      case 'cost':
        return 'paid'
      default:
        return 'help'
    }
  }

  protected submitReply(action: AllowedAction, actionId?: string): void {
    if (!action.interactionId) return
    const text = this.draftFor(action.interactionId).trim()
    const intent: ReplyIntent = { interactionId: action.interactionId }
    if (actionId) intent.actionId = actionId
    if (text) intent.text = text
    if (action.expectedRevision !== undefined) intent.expectedRevision = action.expectedRevision
    this.reply.emit(intent)
  }

  protected requestRetry(action: AllowedAction): void {
    if (!action.stepId) return
    const intent: RetryIntent = { stepId: action.stepId, reasonCode: 'human_retry' }
    if (action.expectedRevision !== undefined) intent.expectedRevision = action.expectedRevision
    this.retryRequested.emit(intent)
  }

  protected requestCancel(action: AllowedAction): void {
    if (!action.attemptId) return
    const intent: CancelIntent = { attemptId: action.attemptId }
    if (action.expectedRevision !== undefined) intent.expectedRevision = action.expectedRevision
    this.cancelAttempt.emit(intent)
  }

  protected triggerContinueCost(): void {
    this.continueCost.emit({})
  }

  protected triggerStopCost(): void {
    this.stopCost.emit()
  }
}
