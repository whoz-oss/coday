import { HttpErrorResponse } from '@angular/common/http'
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, input, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import {
  ConfigChangeControllerService,
  ConfigChangeReportDto,
  ConfigChangeReportDtoDecisionEnum,
  ConfigChangeViewDto,
  ConfigChangeViewDtoStateEnum,
  ConfigFieldChangeDto,
  ConfigOperationResultDto,
  ConfigOperationResultDtoStatusEnum,
  ConfigOperationViewDto,
  ConfigSecretInputDto,
} from '@whoz-oss/agentos-api-client'

/** Label shown instead of the actions when the plan can no longer be applied. */
const STATE_LABELS: Record<string, string> = {
  [ConfigChangeViewDtoStateEnum.ANSWERED]: 'This plan has been answered.',
  [ConfigChangeViewDtoStateEnum.EXPIRED]: 'This plan has expired. Ask the agent for a new one.',
  [ConfigChangeViewDtoStateEnum.SUPERSEDED]: 'A newer plan replaces this one.',
  [ConfigChangeViewDtoStateEnum.IN_PROGRESS]: 'This plan is being applied.',
}

/** Per-step label once the plan has been applied from this card. */
const RESULT_LABELS: Record<ConfigOperationResultDtoStatusEnum, string> = {
  [ConfigOperationResultDtoStatusEnum.APPLIED]: 'Applied',
  [ConfigOperationResultDtoStatusEnum.ALREADY_APPLIED]: 'Already applied',
  [ConfigOperationResultDtoStatusEnum.DENIED]: 'Not allowed',
  [ConfigOperationResultDtoStatusEnum.NOT_FOUND]: 'Not found',
  [ConfigOperationResultDtoStatusEnum.CONFLICT]: 'Conflict',
  [ConfigOperationResultDtoStatusEnum.INVALID]: 'Invalid value',
  [ConfigOperationResultDtoStatusEnum.FAILED]: 'Failed',
  [ConfigOperationResultDtoStatusEnum.NOT_RUN]: 'Not run',
}

const SUCCESS_RESULTS: ReadonlySet<string> = new Set([
  ConfigOperationResultDtoStatusEnum.APPLIED,
  ConfigOperationResultDtoStatusEnum.ALREADY_APPLIED,
])

const DECISION_LABELS: Record<ConfigChangeReportDtoDecisionEnum, string> = {
  [ConfigChangeReportDtoDecisionEnum.APPLIED]: 'All steps were applied.',
  [ConfigChangeReportDtoDecisionEnum.PARTIAL]: 'Some steps were applied; the first refused one stopped the rest.',
  [ConfigChangeReportDtoDecisionEnum.FAILED]: 'Nothing was applied.',
  [ConfigChangeReportDtoDecisionEnum.REJECTED]: 'The plan was rejected.',
}

/** A field of a created entity, as shown in a step's details. */
interface DisplayField {
  key: string
  text: string
  /** Shown as a preformatted block: objects, lists and multi-line text. Plain text wraps. */
  block: boolean
}

/** An agent's `integrations` entry: null grants every tool of the integration, a list only those. */
const INTEGRATIONS_PATH_PREFIX = '/integrations/'

/** A secret the user must type before applying, with the step it belongs to. */
interface PendingSecret {
  op: ConfigOperationViewDto
  input: ConfigSecretInputDto
}

/**
 * ConfigChangeCardComponent — shows a configuration plan proposed by an agent with
 * `AGENTOS_CONFIG__propose`, and lets the user it was proposed to apply or reject it.
 *
 * The card never writes configuration itself: Apply sends the plan's hash and the secrets the
 * user typed to the server, which applies the frozen plan with that user's rights and answers
 * the agent. Every status shown comes from the server, re-read with the viewer's own rights.
 *
 * Each step is one line whose fields unfold on demand; the secrets to type are gathered next to
 * Apply, which says why it is disabled.
 */
@Component({
  selector: 'agentos-config-change-card',
  templateUrl: './config-change-card.component.html',
  styleUrl: './config-change-card.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConfigChangeCardComponent implements OnInit {
  private readonly configChanges = inject(ConfigChangeControllerService)
  private readonly destroyRef = inject(DestroyRef)

  readonly caseId = input.required<string>()
  readonly questionId = input.required<string>()
  /** Disables Apply and Reject while the agent's turn is still running: an answer sent then would not resume it. */
  readonly disabled = input(false)

  protected readonly view = signal<ConfigChangeViewDto | null>(null)
  protected readonly report = signal<ConfigChangeReportDto | null>(null)
  protected readonly errorMessage = signal<string | null>(null)
  protected readonly busy = signal(false)
  protected readonly secrets = signal<Record<string, string>>({})
  protected readonly rejecting = signal(false)
  protected readonly rejectComment = signal('')

  protected readonly pendingSecrets = computed<PendingSecret[]>(() =>
    (this.view()?.operations ?? [])
      .filter((op) => op.status === 'PENDING')
      .flatMap((op) => op.secretInputs.map((input) => ({ op, input })))
  )
  protected readonly missingSecrets = computed(() =>
    this.pendingSecrets().filter((secret) => !this.secrets()[secret.input.key]?.trim())
  )
  protected readonly canApply = computed(
    () => !!this.view()?.canApply && !this.busy() && !this.disabled() && this.missingSecrets().length === 0
  )
  /** Why Apply is disabled, when the user can do something about it. */
  protected readonly applyHint = computed(() => {
    const missing = this.missingSecrets()
    if (!this.view()?.canApply || this.busy()) return null
    if (this.disabled()) return 'The agent is still working: you can apply once its turn ends.'
    if (missing.length === 0) return null
    return `Type the ${missing.map((secret) => secret.input.label).join(' and the ')} above to apply.`
  })
  protected readonly counts = computed(() => {
    const operations = this.view()?.operations ?? []
    return {
      pending: operations.filter((op) => op.status === 'PENDING').length,
      done: operations.filter((op) => op.status === 'APPLIED' || op.status === 'ALREADY_APPLIED').length,
      blocked: operations.filter((op) => op.status === 'CONFLICT' || op.status === 'NOT_VISIBLE').length,
    }
  })
  protected readonly stateLabel = computed(() => {
    const view = this.view()
    if (!view) return null
    if (view.state !== ConfigChangeViewDtoStateEnum.OPEN) return STATE_LABELS[view.state] ?? null
    return view.canApply ? null : 'Only the person this plan was proposed to can apply or reject it.'
  })

  ngOnInit(): void {
    this.load()
  }

  protected load(): void {
    this.configChanges
      .viewConfigChange(this.caseId(), this.questionId())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (view) => this.view.set(view),
        error: (err: HttpErrorResponse) => this.errorMessage.set(this.describeError(err, 'load')),
      })
  }

  protected onSecretInput(key: string, event: Event): void {
    const value = (event.target as HTMLInputElement).value
    this.secrets.update((current) => ({ ...current, [key]: value }))
  }

  protected apply(): void {
    const view = this.view()
    if (!view || !this.canApply()) return
    this.busy.set(true)
    this.errorMessage.set(null)
    this.configChanges
      .applyConfigChange(this.caseId(), this.questionId(), {
        expectedPlanHash: view.planHash,
        secrets: this.secrets(),
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (report) => {
          this.report.set(report)
          this.secrets.set({})
          this.busy.set(false)
          this.load()
        },
        error: (err: HttpErrorResponse) => {
          this.busy.set(false)
          this.errorMessage.set(this.describeError(err, 'apply'))
          this.load()
        },
      })
  }

  protected startReject(): void {
    this.rejecting.set(true)
  }

  protected cancelReject(): void {
    this.rejecting.set(false)
    this.rejectComment.set('')
  }

  protected onRejectCommentInput(event: Event): void {
    this.rejectComment.set((event.target as HTMLTextAreaElement).value)
  }

  protected confirmReject(): void {
    if (this.busy()) return
    this.busy.set(true)
    this.errorMessage.set(null)
    const comment = this.rejectComment().trim()
    this.configChanges
      .rejectConfigChange(this.caseId(), this.questionId(), { comment: comment || undefined })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.busy.set(false)
          this.rejecting.set(false)
          this.load()
        },
        error: (err: HttpErrorResponse) => {
          this.busy.set(false)
          this.errorMessage.set(this.describeError(err, 'reject'))
          this.load()
        },
      })
  }

  /** e.g. `Create auth setting`. */
  protected actionLabel(op: ConfigOperationViewDto): string {
    const label = `${op.action} ${op.kind.replace('_', ' ')}`.toLowerCase()
    return label.charAt(0).toUpperCase() + label.slice(1)
  }

  protected scopeLabel(op: ConfigOperationViewDto): string {
    switch (op.affects) {
      case 'CALLER_ONLY':
        return 'Only you'
      case 'PLATFORM':
        return 'Whole platform'
      default:
        return op.namespaceName ? `Members of ${op.namespaceName}` : 'Namespace members'
    }
  }

  protected statusLabel(op: ConfigOperationViewDto): string {
    const result = this.result(op.ref)
    if (result) return RESULT_LABELS[result.status]
    switch (op.status) {
      case 'PENDING':
        return 'To apply'
      case 'APPLIED':
      case 'ALREADY_APPLIED':
        return 'Applied'
      case 'CONFLICT':
        return 'Changed since proposed'
      default:
        return 'Not visible to you'
    }
  }

  protected isDone(op: ConfigOperationViewDto): boolean {
    const result = this.result(op.ref)
    return result ? SUCCESS_RESULTS.has(result.status) : op.status === 'APPLIED' || op.status === 'ALREADY_APPLIED'
  }

  protected isProblem(op: ConfigOperationViewDto): boolean {
    const result = this.result(op.ref)
    return result ? !SUCCESS_RESULTS.has(result.status) : op.status === 'CONFLICT' || op.status === 'NOT_VISIBLE'
  }

  protected needsSecret(op: ConfigOperationViewDto): boolean {
    return this.missingSecrets().some((secret) => secret.op.ref === op.ref)
  }

  protected isHighlighted(op: ConfigOperationViewDto): boolean {
    return (
      op.affects === 'PLATFORM' ||
      op.action === 'DEPLOY' ||
      op.changes.some((c) => c.path.startsWith('/instructions') || c.path.startsWith('/integrations')) ||
      (op.kind === 'INTEGRATION' && op.action === 'CREATE')
    )
  }

  protected fields(op: ConfigOperationViewDto): DisplayField[] {
    return Object.entries((op.value ?? {}) as Record<string, unknown>).map(([key, value]) => {
      const text = this.display(value)
      const structured = typeof value === 'object' && value !== null && !this.opRef(value)
      return { key, text, block: structured || text.includes('\n') }
    })
  }

  /** The proposed value of a change; an agent's integration entry reads as the tools it grants. */
  protected proposedValue(change: ConfigFieldChangeDto): string {
    const after: unknown = change.after
    if (!change.path.startsWith(INTEGRATIONS_PATH_PREFIX)) return this.display(after)
    if (after === undefined || after === null) return 'all tools'
    if (Array.isArray(after)) return after.length === 0 ? 'no tools' : `only ${after.join(', ')}`
    return this.display(after)
  }

  /** A value as the user reads it: plain text, `{"$op": ref}` as the step it refers to, JSON otherwise. */
  protected display(value: unknown): string {
    const ref = this.opRef(value)
    if (value === undefined || value === null) return '—'
    if (ref) return this.stepLabel(ref)
    if (typeof value === 'string') return value
    if (typeof value === 'number' || typeof value === 'boolean') return String(value)
    return JSON.stringify(value, null, 2)
  }

  protected decisionLabel(decision: ConfigChangeReportDtoDecisionEnum): string {
    return DECISION_LABELS[decision]
  }

  /** The server's reason for a step that did not succeed, once applied from this card. */
  protected failureMessage(ref: string): string | null {
    const result = this.result(ref)
    return result && !SUCCESS_RESULTS.has(result.status) ? (result.message ?? null) : null
  }

  private result(ref: string): ConfigOperationResultDto | undefined {
    return this.report()?.results.find((r) => r.ref === ref)
  }

  private opRef(value: unknown): string | null {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) return null
    const ref = (value as Record<string, unknown>)['$op']
    return Object.keys(value).length === 1 && typeof ref === 'string' ? ref : null
  }

  private stepLabel(ref: string): string {
    const step = this.view()?.operations.find((op) => op.ref === ref)
    return step ? `id created by step ${step.order} (${step.name})` : `id created by step ${ref}`
  }

  private describeError(err: HttpErrorResponse, action: 'load' | 'apply' | 'reject'): string {
    const detail = typeof err.error?.message === 'string' ? ` ${err.error.message}` : ''
    switch (err.status) {
      case 403:
        return 'Only the person this plan was proposed to can apply or reject it.'
      case 404:
        return 'This plan could not be found.'
      case 409:
        return `The plan can no longer be ${action === 'reject' ? 'rejected' : 'applied'} as displayed. It was refreshed.${detail}`
      case 422:
        return `Some secrets are missing.${detail}`
      default:
        return `Could not ${action} the plan. Please try again.`
    }
  }
}
