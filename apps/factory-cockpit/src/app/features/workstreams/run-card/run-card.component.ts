import { ChangeDetectionStrategy, Component, effect, inject, input, output, signal } from '@angular/core'
import { DatePipe } from '@angular/common'
import { RouterLink } from '@angular/router'
import { MatCardModule } from '@angular/material/card'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'
import { AGENTOS_BASE_URL, FactoryStore, buildAgentOsCaseUrl } from '../../../core/factory.store'
import { AgentOsApiError } from '../../../core/agentos-api.service'
import { FactoryRun, SupervisorCaseResult } from '../../../core/models'
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
  private readonly agentOsBaseUrl = inject(AGENTOS_BASE_URL)

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
   * A running run can only be stopped when an active attempt is resolvable from
   * the authoritative store state. The cockpit never fabricates an attempt id:
   * when none is resolvable the stop action is simply unavailable.
   */
  protected get canStop(): boolean {
    const run = this.run()
    if (run.status !== 'working' || !run.id) return false
    return Boolean(this.store.session(run.id)?.activeAttemptId)
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
    return buildAgentOsCaseUrl(caseId, run.namespaceId, this.agentOsBaseUrl)
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
