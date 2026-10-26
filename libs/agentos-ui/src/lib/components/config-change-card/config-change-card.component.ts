import { HttpErrorResponse } from '@angular/common/http'
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, input, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import {
  ConfigChangeControllerService,
  ConfigChangeReportDto,
  ConfigChangeViewDto,
  ConfigChangeViewDtoStateEnum,
  ConfigOperationViewDto,
} from '@whoz-oss/agentos-api-client'

/** Label shown instead of the actions when the plan can no longer be applied. */
const STATE_LABELS: Record<string, string> = {
  [ConfigChangeViewDtoStateEnum.ANSWERED]: 'This plan has been answered.',
  [ConfigChangeViewDtoStateEnum.EXPIRED]: 'This plan has expired. Ask the agent for a new one.',
  [ConfigChangeViewDtoStateEnum.SUPERSEDED]: 'A newer plan replaces this one.',
  [ConfigChangeViewDtoStateEnum.IN_PROGRESS]: 'This plan is being applied.',
}

/**
 * ConfigChangeCardComponent — shows a configuration plan proposed by an agent with
 * `AGENTOS_CONFIG__propose`, and lets the user it was proposed to apply or reject it.
 *
 * The card never writes configuration itself: Apply sends the plan's hash and the secrets the
 * user typed to the server, which applies the frozen plan with that user's rights and answers
 * the agent. Every status shown comes from the server, re-read with the viewer's own rights.
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

  protected readonly view = signal<ConfigChangeViewDto | null>(null)
  protected readonly report = signal<ConfigChangeReportDto | null>(null)
  protected readonly errorMessage = signal<string | null>(null)
  protected readonly busy = signal(false)
  protected readonly secrets = signal<Record<string, string>>({})
  protected readonly rejecting = signal(false)
  protected readonly rejectComment = signal('')

  protected readonly secretKeys = computed(() =>
    (this.view()?.operations ?? [])
      .filter((op) => op.status === 'PENDING')
      .flatMap((op) => op.secretInputs.map((s) => s.key))
  )
  protected readonly secretsComplete = computed(() => this.secretKeys().every((key) => !!this.secrets()[key]?.trim()))
  protected readonly canApply = computed(() => !!this.view()?.canApply && !this.busy() && this.secretsComplete())
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
        },
      })
  }

  protected title(op: ConfigOperationViewDto): string {
    const kind = op.kind.replace('_', ' ').toLowerCase()
    return `${op.action.toLowerCase()} ${kind} ${op.name}`.trim()
  }

  protected scopeLabel(op: ConfigOperationViewDto): string {
    switch (op.affects) {
      case 'CALLER_ONLY':
        return 'Only you'
      case 'PLATFORM':
        return 'Whole platform'
      default:
        return 'Namespace members'
    }
  }

  protected statusLabel(op: ConfigOperationViewDto): string {
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

  protected isHighlighted(op: ConfigOperationViewDto): boolean {
    return (
      op.affects === 'PLATFORM' ||
      op.action === 'DEPLOY' ||
      op.changes.some((c) => c.path.startsWith('/instructions') || c.path.startsWith('/integrations')) ||
      (op.kind === 'INTEGRATION' && op.action === 'CREATE')
    )
  }

  protected json(value: unknown): string {
    return value === undefined ? '(absent)' : JSON.stringify(value, null, 2)
  }

  protected resultLabel(ref: string): string | null {
    return this.report()?.results.find((r) => r.ref === ref)?.status ?? null
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
