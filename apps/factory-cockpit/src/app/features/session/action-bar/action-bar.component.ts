import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'

import { AgentQuestion, AllowedAction, HumanInteraction, WorkflowBlocker } from '../../../core/models'
import { AGENTOS_BASE_URL, buildAgentOsCaseUrl } from '../../../core/factory.store'

export interface ReplyIntent {
  interactionId: string
  actionId?: string
  text?: string
  expectedRevision?: number
}

export interface AgentQuestionAnswerIntent {
  question: AgentQuestion
  answer: string
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
  readonly agentQuestionsInput = input<AgentQuestion[]>([])
  readonly selectedStepId = input<string>('')
  readonly namespaceId = input<string>('')
  readonly agentQuestionsError = input<{
    code: string
    message: string
    caseId?: string
    namespaceId?: string
    workflowId?: string
    stepId?: string
  } | null>(null)
  readonly answeringQuestionId = input<string | null>(null)
  readonly questionFeedback = input<{ interactionId: string; kind: 'conflict' | 'error'; message: string } | null>(null)

  readonly reply = output<ReplyIntent>()
  readonly agentQuestionAnswered = output<AgentQuestionAnswerIntent>()
  readonly retryRequested = output<RetryIntent>()
  readonly cancelAttempt = output<CancelIntent>()
  readonly continueCost = output<ContinueCostIntent>()
  readonly stopCost = output<void>()

  protected readonly replyActions = computed(() =>
    this.allowedActions().filter(
      (action) => action.type === 'reply' && this.interactionFor(action)?.interactionType !== 'agent_question'
    )
  )
  protected readonly agentQuestions = computed(() =>
    this.agentQuestionsInput().filter((question) => !question.answered)
  )
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

  protected readonly hasActions = computed(
    () => this.allowedActions().length > 0 || this.agentQuestions().length > 0 || this.agentQuestionsError() !== null
  )
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

  /**
   * Classify a blocker into a severity/kind bucket.
   *
   * - `failure`: real execution failures (ATTEMPT_FAILED, VERIFICATION_FAILED)
   *   — the step actually failed; operator attention is required.
   * - `consequence`: downstream blocks (STEP_BLOCKED) — a step is blocked as a
   *   consequence of an upstream failure; they are grouped as mitigated.
   * - `waiting`: human gate (WAITING_HUMAN_INTERACTION).
   * - `cost`: budget pause (REAL_COST_PAUSED).
   * - `indeterminate`: unknown runtime state (UNKNOWN_RUNTIME).
   * - `unknown`: anything else.
   */
  protected blockerKind(
    blocker: WorkflowBlocker
  ): 'waiting' | 'failure' | 'consequence' | 'cost' | 'indeterminate' | 'unknown' {
    switch (blocker.code) {
      case 'WAITING_HUMAN_INTERACTION':
        return 'waiting'
      case 'ATTEMPT_FAILED':
      case 'VERIFICATION_FAILED':
        return 'failure'
      case 'STEP_BLOCKED':
        return 'consequence'
      case 'REAL_COST_PAUSED':
        return 'cost'
      case 'UNKNOWN_RUNTIME':
        return 'indeterminate'
      default:
        return 'unknown'
    }
  }

  /**
   * Computed grouping of blockers by kind, preserving ALL root failures (never
   * arbitrarily picking a single first one).
   */
  protected readonly blockerGroups = computed(() => {
    const blockers = this.blockers()
    return {
      failures: blockers.filter((b) => this.blockerKind(b) === 'failure'),
      consequences: blockers.filter((b) => this.blockerKind(b) === 'consequence'),
      waiting: blockers.filter((b) => this.blockerKind(b) === 'waiting'),
      cost: blockers.filter((b) => this.blockerKind(b) === 'cost'),
      indeterminate: blockers.filter((b) => this.blockerKind(b) === 'indeterminate'),
      unknown: blockers.filter(
        (b) =>
          this.blockerKind(b) !== 'failure' &&
          this.blockerKind(b) !== 'consequence' &&
          this.blockerKind(b) !== 'waiting' &&
          this.blockerKind(b) !== 'cost' &&
          this.blockerKind(b) !== 'indeterminate'
      ),
    }
  })

  protected blockerIcon(blocker: WorkflowBlocker): string {
    switch (this.blockerKind(blocker)) {
      case 'waiting':
        return 'person_alert'
      case 'failure':
        return 'error'
      case 'consequence':
        return 'link_off'
      case 'cost':
        return 'paid'
      case 'indeterminate':
        return 'help'
      default:
        return 'warning'
    }
  }

  /** Title for a blocker group header. */
  protected groupTitle(kind: 'failure' | 'consequence' | 'waiting' | 'cost' | 'indeterminate' | 'unknown'): string {
    switch (kind) {
      case 'failure':
        return 'Root failures'
      case 'consequence':
        return 'Blocked steps (consequence)'
      case 'waiting':
        return 'Waiting for human'
      case 'cost':
        return 'Budget pause'
      case 'indeterminate':
        return 'Indeterminate state'
      default:
        return 'Other blockers'
    }
  }

  protected supportedQuestion(question: AgentQuestion): boolean {
    return ['FREE_TEXT', 'SINGLE_CHOICE', 'OPEN_CHOICE'].includes(question.questionType ?? '')
  }

  private readonly agentOsBaseUrl = inject(AGENTOS_BASE_URL)

  protected caseLink(caseId: string, namespaceId?: string): string {
    const namespace = namespaceId || this.namespaceId()
    return buildAgentOsCaseUrl(caseId, namespace || undefined, this.agentOsBaseUrl)
  }

  protected submitAgentQuestion(question: AgentQuestion, choice?: string): void {
    if (!this.supportedQuestion(question) || this.answeringQuestionId()) return
    const answer = (choice ?? this.draftFor(question.questionEventId)).trim()
    if (!answer) return
    if (question.questionType === 'SINGLE_CHOICE' && !question.options?.includes(answer)) return
    this.agentQuestionAnswered.emit({ question, answer })
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
