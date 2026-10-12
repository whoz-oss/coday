import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core'
import { DatePipe } from '@angular/common'
import { toSignal } from '@angular/core/rxjs-interop'
import { MatButtonModule } from '@angular/material/button'
import { MatExpansionModule } from '@angular/material/expansion'
import { ActivatedRoute } from '@angular/router'
import { map } from 'rxjs'
import { AGENTOS_BASE_URL, FactoryStore, buildAgentOsCaseUrl } from '../../core/factory.store'
import { BlockSelection, TimelineBlock } from '../../core/models'
import { ShellState } from '../../core/shell-state'
import { DurationPipe, TokensPipe, UsdPipe } from '../../shared/pipes/format.pipes'
import { MetricChipComponent } from '../../shared/ui/metric-chip.component'
import { StatusChipComponent } from '../../shared/ui/status-chip.component'
import {
  ActionBarComponent,
  AgentQuestionAnswerIntent,
  CancelIntent,
  ReplyIntent,
  RetryIntent,
} from './action-bar/action-bar.component'
import { AgentTimelineComponent } from './agent-timeline/agent-timeline.component'
import { EventLogComponent } from './event-log/event-log.component'

@Component({
  selector: 'sf-session-page',
  imports: [
    DatePipe,
    MatButtonModule,
    MatExpansionModule,
    StatusChipComponent,
    MetricChipComponent,
    ActionBarComponent,
    AgentTimelineComponent,
    EventLogComponent,
    UsdPipe,
    DurationPipe,
    TokensPipe,
  ],
  templateUrl: './session-page.component.html',
  styleUrl: './session-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SessionPageComponent {
  /** Paramètre de route :runId, éventuellement lié via withComponentInputBinding. */
  readonly runId = input<string>('')

  private readonly route = inject(ActivatedRoute)
  protected readonly store = inject(FactoryStore)
  private readonly shell = inject(ShellState)
  private readonly agentOsBaseUrl = inject(AGENTOS_BASE_URL)

  private readonly routeRunId = toSignal(this.route.paramMap.pipe(map((p) => p.get('runId') ?? '')), {
    initialValue: '',
  })

  protected readonly effectiveRunId = computed(() => this.runId() || this.routeRunId())
  protected readonly session = computed(() => this.store.session(this.effectiveRunId()))
  private readonly run = computed(() => this.store.runs().find((candidate) => candidate.id === this.effectiveRunId()))

  /**
   * Current selection: carries stepId (stable across SSE refreshes),
   * attemptId and caseId of the specific bar that was clicked.
   *
   * - `stepId` is the panel key so the same step stays selected after an
   *   SSE reload (attempt retries do not reset the panel).
   * - `attemptId` + `caseId` scope the Events panel and Case link to the
   *   exact retry the user clicked — an older retry does NOT show the
   *   latest case's events.
   */
  protected readonly selection = signal<BlockSelection | null>(null)

  /**
   * Stable step id from the current selection. Forwarded to the timeline
   * for the block highlight and to the action-bar for step-scoped actions.
   */
  protected readonly selectedStepId = computed(() => this.selection()?.stepId ?? '')

  /**
   * Block highlight key for the timeline (plain string, not the full
   * selection object, so the timeline component stays decoupled).
   */
  protected readonly selectedBlock = computed(() => this.selectedStepId())

  /**
   * Phase detail for the currently selected step, falling back to the
   * session's default "active" phase when no step is selected or found.
   */
  protected readonly selectedPhase = computed(() => {
    const s = this.session()
    if (!s) return undefined
    const stepId = this.selectedStepId()
    if (!stepId) return s.phase
    return s.phaseByStepId?.get(stepId) ?? s.phase
  })

  /**
   * The caseId for the currently selected attempt, used for the Case link
   * in the detail panel.
   *
   * Priority:
   *  1. `caseId` carried by the selection itself (from the clicked block).
   *  2. `caseId` from the phase detail (last attempt of the step).
   *
   * This ensures that clicking an older retry bar shows THAT retry's case,
   * not the latest one.
   */
  protected readonly selectedAttemptCaseId = computed(() => {
    return this.selection()?.caseId ?? this.selectedPhase()?.caseId ?? undefined
  })

  /**
   * The `AgentAttempt` for the currently selected attempt, looked up from
   * `session.attempts` using the selection's `attemptId`.
   *
   * Used to pass attempt timestamps (`startedAt`, `completedAt`) to the
   * Events panel for attempt-duration display without an extra HTTP call.
   *
   * Returns `null` when:
   *  - nothing is selected,
   *  - the selection has no `attemptId` (e.g. human/workspace block),
   *  - or the attempt is not yet in the loaded list.
   */
  protected readonly selectedAttempt = computed(() => {
    const s = this.session()
    const sel = this.selection()
    if (!s?.attempts || !sel?.attemptId) return null
    return s.attempts.find((a) => a.attemptId === sel.attemptId) ?? null
  })

  /**
   * Events scoped to the current selection.
   *
   * - When an attempt is selected: events from `eventsByAttemptId`.
   * - When only a step is selected (no attemptId, e.g. human/workspace):
   *   events from `eventsByStepId` (all attempts of that step).
   * - When nothing is selected: the full global event list (`s.events`).
   *
   * Returns `null` when a selection exists but the evidence maps have not
   * been populated yet (evidence still loading). The template shows an
   * explicit loading state — never silently falls back to global events.
   */
  protected readonly selectedEvents = computed(() => {
    const s = this.session()
    if (!s) return []
    const sel = this.selection()
    if (!sel) return s.events // global view — nothing selected

    const { stepId, attemptId } = sel

    if (attemptId) {
      // Attempt-scoped: return events for that specific retry.
      // null → evidence maps not yet populated (still loading).
      if (!s.eventsByAttemptId) return null
      return s.eventsByAttemptId.get(attemptId) ?? []
    }

    // Step-scoped (no attemptId: human / workspace block).
    if (!s.eventsByStepId) return null
    return s.eventsByStepId.get(stepId) ?? []
  })

  constructor() {
    effect(() => {
      const s = this.session()
      // Auto-select the active step on first load only (when nothing is selected).
      // After that, the user's selection is preserved through SSE refreshes.
      if (s?.phase.stepId && !this.selection()) {
        this.selection.set({ stepId: s.phase.stepId })
      }
      const run = this.run()
      const namespaceId = run?.namespaceId
      const workstreamName = namespaceId
        ? (this.store.namespaces().get(namespaceId) ?? namespaceId)
        : (s?.sandbox ?? '…')
      this.shell.crumbs.set([
        { label: 'Workstreams', link: '/workstreams' },
        {
          label: namespaceId && workstreamName !== namespaceId ? capitalizeFirstLetter(workstreamName) : workstreamName,
          link: '/workstreams',
          mono: !namespaceId || workstreamName === namespaceId,
        },
        { label: run?.title || this.effectiveRunId(), mono: !run?.title },
      ])
    })
  }

  protected selectBlock(b: TimelineBlock): void {
    // Build a full selection from the clicked block so that attemptId and
    // caseId are captured alongside the stable stepId.
    // Falls back to the block label as stepId for non-step blocks (e.g.
    // the human request block which carries no stepId).
    const stepId = b.stepId ?? b.label
    const sel: BlockSelection = { stepId }
    if (b.attemptId) sel.attemptId = b.attemptId
    if (b.caseId) sel.caseId = b.caseId
    this.selection.set(sel)
  }

  protected caseLink(caseId: string): string {
    return buildAgentOsCaseUrl(caseId, this.currentNamespaceId() || undefined, this.agentOsBaseUrl)
  }

  /**
   * Resolve the namespace id to pass in AgentOS deep-link URLs.
   *
   * Priority (most reliable → least reliable):
   *  1. `run.namespaceId` — the canonical workstream namespace, always set by
   *     the backend on the workflow snapshot. This is the same namespace used
   *     for all enrichment API calls and is the one AgentOS expects.
   *  2. `agentQuestions[].case.namespaceId` — namespace carried by a projected
   *     question case; only present when questions are active AND the backend
   *     embeds the case object (not always the case).
   *  3. `agentQuestionsError.namespaceId` — namespace surfaced in an error
   *     payload; only present when the question projection failed with details.
   *
   * The previous implementation skipped (1) entirely, causing the link to open
   * AgentOS without a namespace whenever no agent questions were active.
   */
  protected currentNamespaceId(): string {
    const run = this.run()
    if (run?.namespaceId) return run.namespaceId
    const session = this.session()
    return (
      session?.agentQuestions?.find((question) => question.case?.namespaceId)?.case?.namespaceId ??
      session?.agentQuestionsError?.namespaceId ??
      ''
    )
  }

  protected onReply(intent: ReplyIntent): void {
    const id = this.effectiveRunId()
    if (!id) return
    this.store.replyInteraction(id, intent.interactionId, {
      actionId: intent.actionId,
      text: intent.text,
      expectedRevision: intent.expectedRevision,
    })
  }

  protected onAgentQuestionAnswered(intent: AgentQuestionAnswerIntent): void {
    const id = this.effectiveRunId()
    if (!id) return
    this.store.answerAgentQuestion(id, intent.question, intent.answer)
  }

  protected onRetry(intent: RetryIntent): void {
    const id = this.effectiveRunId()
    if (!id) return
    this.store.retry(id, {
      stepId: intent.stepId,
      expectedRevision: intent.expectedRevision,
      reasonCode: intent.reasonCode,
    })
  }

  protected onCancelAttempt(intent: CancelIntent): void {
    const id = this.effectiveRunId()
    if (!id) return
    this.store.cancelAttempt(id, intent.attemptId, {
      expectedRevision: intent.expectedRevision,
      reason: intent.reason,
    })
  }

  protected onContinueCost(): void {
    const id = this.effectiveRunId()
    if (!id) return
    this.store.continueCost(id)
  }

  protected onStopCost(): void {
    const id = this.effectiveRunId()
    if (!id) return
    this.store.stopCost(id)
  }
}

function capitalizeFirstLetter(value: string): string {
  return value ? value[0]!.toUpperCase() + value.slice(1) : value
}
