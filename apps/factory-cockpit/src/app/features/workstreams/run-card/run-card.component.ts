import { ChangeDetectionStrategy, Component, effect, inject, input, output, signal } from '@angular/core'
import { DatePipe } from '@angular/common'
import { RouterLink } from '@angular/router'
import { MatCardModule } from '@angular/material/card'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'
import { FactoryStore, buildAgentOsCaseUrl } from '../../../core/factory.store'
import { AgentOsApiError } from '../../../core/agentos-api.service'
import { FactoryRun, StopFeedback, SupervisorCaseResult } from '../../../core/models'
import { StatusChipComponent } from '../../../shared/ui/status-chip.component'
import { MetricChipComponent } from '../../../shared/ui/metric-chip.component'
import { StepDotsComponent } from '../../../shared/ui/step-dots.component'
import { DurationPipe, TokensPipe, UsdPipe } from '../../../shared/pipes/format.pipes'

export type RunAction = 'ask' | 'conversation' | 'stop' | 'remove' | 'restore'

/** @deprecated Use {@link RunAction}. Kept as an alias for compatibility. */
export type SandboxAction = RunAction

/**
 * State of the "Ask supervisor" async flow.
 *
 * - `idle`    button is ready to be clicked.
 * - `loading` case creation + message POST in progress; button is disabled.
 * - `done`    case created; a persistent link is shown so the user can
 *              re-open it without creating a duplicate.
 * - `error`   case creation failed; an inline error message is shown and
 *              the button reverts to its clickable state for a retry.
 */
type SupervisorState = 'idle' | 'loading' | 'done' | 'error'

/**
 * Renders a single Factory run and owns ALL its actions (Stop, Remove, Restore,
 * Ask supervisor, Conversation). Every action targets the run by its unique
 * `id` (workflow id): the parent page binds the emitted action to `run.id`, so
 * two runs sharing a display title can never be confused.
 */
@Component({
  selector: 'sf-run-card',
  imports: [
    DatePipe,
    RouterLink,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    StatusChipComponent,
    MetricChipComponent,
    StepDotsComponent,
    UsdPipe,
    DurationPipe,
    TokensPipe,
  ],
  templateUrl: './run-card.component.html',
  styleUrl: './run-card.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RunCardComponent {
  readonly run = input.required<FactoryRun>()
  readonly action = output<RunAction>()

  private readonly store = inject(FactoryStore)

  /** Tracks the async state of the supervisor case-creation flow. */
  protected readonly supervisorState = signal<SupervisorState>('idle')

  /** Error message from a failed supervisor call, shown inline. */
  protected readonly supervisorError = signal<string | null>(null)

  /**
   * URL of the last successfully created supervisor case. Shown as a persistent
   * link once the case exists so the user can re-open it without creating a
   * duplicate case.
   */
  protected readonly supervisorCaseUrl = signal<string | null>(null)

  constructor() {
    // Restore a previously created supervisor case from the local browser store
    // whenever the run input changes. This runs synchronously (no network call)
    // and ensures the "View supervisor case" link is shown immediately after a
    // page refresh, without creating a duplicate case.
    effect(() => {
      if (this.supervisorState() !== 'idle') return
      const restored = this.store.restoreSupervisorCase(this.run())
      if (!restored) return
      this.supervisorCaseUrl.set(restored.agentOsUrl)
      this.supervisorState.set('done')
    })
  }

  /**
   * A run can be stopped when an active attempt is resolvable from the authoritative store state: a running
   * attempt, or a cancelled one whose kill AgentOS has not confirmed (the backend then offers "Retry stop" as a
   * `cancel_attempt` action). The cockpit never fabricates an attempt id: when none is resolvable the stop action
   * is simply unavailable, and a removed run cannot be stopped.
   */
  protected get canStop(): boolean {
    const run = this.run()
    if (run.status === 'destroyed' || !run.id) return false
    return Boolean(this.store.session(run.id)?.activeAttemptId)
  }

  /** Label of the Stop button: a Stop whose kill is not confirmed is offered again as a retry. */
  protected get stopLabel(): string {
    const feedback = this.stopFeedback
    return feedback?.kind === 'unconfirmed' || this.isKillUnconfirmed ? 'Retry stop' : 'Stop'
  }

  /** The last Stop of THIS run that was refused, not confirmed or failed, if any. */
  protected get stopFeedback(): StopFeedback | null {
    const feedback = this.store.stopFeedback()
    return feedback && feedback.workflowId === this.run().id ? feedback : null
  }

  private get isKillUnconfirmed(): boolean {
    const session = this.store.session(this.run().id)
    return Boolean(session?.blockers?.some((blocker) => blocker.code === 'AGENT_KILL_UNCONFIRMED'))
  }

  /**
   * Build the AgentOS conversation link for this run's controller case.
   *
   * `controllerExecution.caseId` is written by Factory when the first agent step
   * executes. It is absent in normal lifecycle states (workflow not yet started,
   * no agent step executed yet, or workflow type without agent steps).
   * Returns `null` when no controller case id is known.
   */
  protected get conversationLink(): string | null {
    const run = this.run()
    const caseId = run.controllerCaseId
    if (!caseId) return null
    return buildAgentOsCaseUrl(caseId, run.namespaceId)
  }

  /**
   * "Ask supervisor" orchestrated by {@link FactoryStore.openSupervisorCase}.
   *
   * **Popup-blocker strategy**: browsers block `window.open` called inside an
   * async callback. To guarantee the popup opens, we open the target window
   * synchronously in the click handler (before any async work), then navigate it
   * once the case id is known. If the window was blocked (returns `null`), we
   * surface the case URL as a persistent link the user can click manually.
   *
   * **Idempotency**: once a case is created (`done` state), the button is
   * replaced by a persistent link, so no duplicate case is ever created.
   */
  protected askSupervisor(): void {
    if (this.supervisorState() === 'loading' || this.supervisorState() === 'done') return

    const run = this.run()
    if (!run.namespaceId) {
      this.supervisorError.set('Unknown namespace -- cannot open a supervisor case.')
      this.supervisorState.set('error')
      return
    }

    this.supervisorState.set('loading')
    this.supervisorError.set(null)

    // Open without 'noopener' so the browser returns a usable handle.
    // If window.open returns null (popup blocker), we fall back to the
    // persistent link shown in the template once the case URL is known.
    const targetWindow = window.open('', '_blank')
    if (targetWindow) {
      // Sever the back-reference synchronously, before any await.
      targetWindow.opener = null
    }

    this.store.openSupervisorCase(run).subscribe({
      next: (result: SupervisorCaseResult) => {
        this.supervisorCaseUrl.set(result.agentOsUrl)
        this.supervisorState.set('done')
        if (targetWindow && !targetWindow.closed) {
          targetWindow.location.href = result.agentOsUrl
        }
      },
      error: (error: AgentOsApiError) => {
        // Case creation failed: close the blank window we opened (it would
        // remain as an orphaned empty tab otherwise).
        if (targetWindow && !targetWindow.closed) {
          targetWindow.close()
        }
        this.supervisorState.set('error')
        this.supervisorError.set(`Failed to create supervisor case: ${error.message}`)
      },
    })
  }
}
