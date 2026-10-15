import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, viewChild } from '@angular/core'
import { toSignal } from '@angular/core/rxjs-interop'
import { CdkVirtualScrollViewport, ScrollingModule } from '@angular/cdk/scrolling'
import { MatButtonToggleModule } from '@angular/material/button-toggle'
import { MatCheckboxModule } from '@angular/material/checkbox'
import { MatDialog } from '@angular/material/dialog'
import { MatIconModule } from '@angular/material/icon'
import { UsdPipe, DurationPipe } from '../../../shared/pipes/format.pipes'
import { CaseEventStateService } from '../../../core/case-event-state.service'
import { CaseMetricsStateService } from '../../../core/case-metrics-state.service'
import { BlockSelection, AgentAttempt, RunEvent, RunEventType } from '../../../core/models'
import { MessageDetailDialogComponent, MessageDetailDialogData } from './message-detail-dialog.component'
import { CodeDiagnosticDialogComponent, CodeDiagnosticDialogData } from './code-diagnostic-dialog.component'

type Filter = 'all' | RunEventType

/**
 * Event log panel for a Factory session phase.
 *
 * **Event source:**
 * - When `caseId` is provided: fetches the AgentOS case event history
 *   (REST) and subscribes to live events (SSE) via {@link CaseEventStateService}.
 *   The source badge shows `agentos` in the header.
 * - When `caseId` is absent: displays the Factory evidence events passed via
 *   the `events` input. The source badge shows `factory`.
 *
 * The component never calls HttpClient or EventSource directly. All
 * AgentOS I/O is delegated to {@link CaseEventStateService}, which is
 * provided at the component level so each instance has its own isolated
 * state (and therefore its own SSE connection lifecycle).
 *
 * **State transitions:**
 * - `loading` — initial REST fetch in flight (AgentOS mode) or evidence maps
 *   not yet populated (Factory mode, `events` input is `null`).
 * - `error`   — REST fetch failed; explicit error message shown.
 * - `empty`   — no events in the resolved list after loading.
 * - `ready`   — events rendered in the virtualised list.
 */
@Component({
  selector: 'sf-event-log',
  imports: [ScrollingModule, MatButtonToggleModule, MatCheckboxModule, MatIconModule, UsdPipe, DurationPipe],
  templateUrl: './event-log.component.html',
  styleUrl: './event-log.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [CaseEventStateService, CaseMetricsStateService],
})
export class EventLogComponent {
  /**
   * Factory-sourced events. Three distinct states:
   *  - `RunEvent[]` — events to render (may be empty array).
   *  - `null`       — evidence maps not yet populated (still loading).
   */
  readonly events = input.required<RunEvent[] | null>()
  readonly running = input(false)
  readonly activityLabel = input('')

  /**
   * AgentOS case id of the currently selected attempt.
   *
   * When present: the component switches to AgentOS mode — REST history +
   * live SSE via {@link CaseEventStateService}. Factory events are not shown.
   * When absent: the component shows Factory evidence events from `events`.
   */
  readonly caseId = input<string | null | undefined>()

  /**
   * Block selection context, used for the header label and filter reset.
   */
  readonly selectionContext = input<BlockSelection | null>(null)

  /**
   * Attempt metadata for the currently selected block.
   *
   * When present: provides `startedAt` / `completedAt` for attempt duration
   * (without an HTTP call), and confirms the attempt is known.
   * When absent: duration is unavailable (null).
   */
  readonly selectedAttempt = input<AgentAttempt | null>(null)

  protected readonly filter = signal<Filter>('all')
  protected readonly follow = signal(true)
  protected readonly filters: { value: Filter; label: string }[] = [
    { value: 'all', label: 'All' },
    { value: 'tool_call', label: 'tool_call' },
    { value: 'agent_message', label: 'agent_message' },
  ]

  // ── Service state (AgentOS) ──────────────────────────────────────────────

  private readonly caseEventState = inject(CaseEventStateService)
  private readonly dialog = inject(MatDialog)
  protected readonly metricsState = inject(CaseMetricsStateService)

  /** AgentOS load state as a signal (converted from Observable). */
  private readonly agentOsState = toSignal(this.caseEventState.state$, {
    initialValue: 'idle' as const,
  })

  /** AgentOS accumulated events as a signal (converted from Observable). */
  private readonly agentOsEvents = toSignal(this.caseEventState.events$, {
    initialValue: [] as RunEvent[],
  })

  // ── Derived display state ─────────────────────────────────────────────────

  /** True when AgentOS is the active event source. */
  protected readonly isAgentOsMode = computed(() => !!this.caseId())

  protected readonly isAgentOsLoading = computed(() => this.agentOsState() === 'loading')
  protected readonly isAgentOsError = computed(() => this.agentOsState() === 'error')

  /**
   * Unified loading indicator.
   * AgentOS mode: loading while REST history is in flight.
   * Factory mode: loading while evidence maps are not yet populated (null input).
   */
  protected readonly isLoading = computed(() =>
    this.isAgentOsMode() ? this.isAgentOsLoading() : this.events() === null
  )

  /**
   * Resolved event list.
   * AgentOS mode: accumulated events from the state service.
   * Factory mode: events from the `events` input.
   */
  protected readonly resolvedEvents = computed<RunEvent[]>(() =>
    this.isAgentOsMode() ? this.agentOsEvents() : (this.events() ?? [])
  )

  protected readonly visible = computed<RunEvent[]>(() => {
    const f = this.filter()
    const all = this.resolvedEvents()
    return f === 'all' ? all : all.filter((e) => e.type === f)
  })

  /** Source badge: `'agentos'` or `'factory'`. */
  protected readonly sourceLabel = computed<'agentos' | 'factory'>(() => (this.isAgentOsMode() ? 'agentos' : 'factory'))

  /** Context label for the header (null = global view). */
  protected readonly contextLabel = computed<string | null>(() => {
    const ctx = this.selectionContext()
    if (!ctx) return null
    const parts: string[] = [`step ${ctx.stepId}`]
    if (ctx.attemptId) parts.push(`attempt ${ctx.attemptId.slice(0, 8)}…`)
    return parts.join(' · ')
  })

  private readonly viewport = viewChild(CdkVirtualScrollViewport)

  protected openMessage(event: RunEvent): void {
    if (event.type !== 'agent_message') return
    const data: MessageDetailDialogData = {
      actorLabel: event.actorLabel,
      time: event.time,
      // Factory events predate the dedicated body field and already store the
      // complete message in `text`; AgentOS events explicitly preserve absence.
      messageText: event.messageText ?? (event.actorLabel ? '' : event.text),
    }
    this.dialog.open<MessageDetailDialogComponent, MessageDetailDialogData>(MessageDetailDialogComponent, {
      data,
      width: 'min(760px, 92vw)',
      maxWidth: '92vw',
      autoFocus: 'dialog',
    })
  }

  protected openCodeDiagnostic(event: RunEvent): void {
    if (!event.codeDiagnostic) return
    this.dialog.open<CodeDiagnosticDialogComponent, CodeDiagnosticDialogData>(CodeDiagnosticDialogComponent, {
      data: event.codeDiagnostic,
      width: 'min(680px, 92vw)',
      maxWidth: '92vw',
      autoFocus: 'dialog',
      ariaLabel: `${event.codeDiagnostic.verification || 'Code verification'} diagnostics`,
    })
  }

  protected diagnosticAriaLabel(event: RunEvent): string {
    return `Open ${event.codeDiagnostic?.verification || 'code verification'} diagnostics`
  }

  protected messageAriaLabel(event: RunEvent): string {
    const actor = event.actorLabel ? ` from ${event.actorLabel}` : ''
    return `Read full agent message${actor} at ${event.time}`
  }

  constructor() {
    // ── Live follow: scroll to bottom on each new visible event ────────────
    effect(() => {
      const count = this.visible().length
      const vp = this.viewport()
      if (this.follow() && vp && count) {
        queueMicrotask(() => vp.scrollToIndex(count - 1, 'smooth'))
      }
    })

    // ── Reset filter on context change ───────────────────────────────
    effect(() => {
      this.selectionContext()
      this.filter.set('all')
    })

    // ── Activate / deactivate the AgentOS case event service ────────────
    // Reacts to caseId changes: activates the service for a new case,
    // clears it when the caseId is removed. The service handles cancellation
    // of the previous pipeline internally (see CaseEventStateService.activate).
    effect(() => {
      const id = this.caseId()
      if (id) {
        this.caseEventState.activate(id)
      } else {
        this.caseEventState.clear()
      }
    })

    // ── Activate / deactivate attempt metrics ───────────────────────────
    // Reacts to caseId changes: loads run-cost for the selected attempt case.
    // Cleared when no case is selected (block deselected or non-agent block).
    effect(() => {
      const id = this.caseId()
      const att = this.selectedAttempt()
      if (id) {
        this.metricsState.activate(id, att)
      } else {
        this.metricsState.clear()
      }
    })
  }
}
