import { ChangeDetectionStrategy, Component, effect, inject, input, output, signal } from '@angular/core'
import { DatePipe } from '@angular/common'
import { RouterLink } from '@angular/router'
import { MatCardModule } from '@angular/material/card'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'
import { AGENTOS_BASE_URL, FactoryStore, buildAgentOsCaseUrl } from '../../../core/factory.store'
import { AgentOsApiError } from '../../../core/agentos-api.service'
import { SupervisorCaseResult, Sandbox } from '../../../core/models'
import { StatusChipComponent } from '../../../shared/ui/status-chip.component'
import { MetricChipComponent } from '../../../shared/ui/metric-chip.component'
import { StepDotsComponent } from '../../../shared/ui/step-dots.component'
import { DurationPipe, TokensPipe, UsdPipe } from '../../../shared/pipes/format.pipes'

export type SandboxAction = 'ask' | 'conversation' | 'stop' | 'remove' | 'restore'

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

@Component({
  selector: 'sf-sandbox-card',
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
  templateUrl: './sandbox-card.component.html',
  styleUrl: './sandbox-card.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SandboxCardComponent {
  readonly sandbox = input.required<Sandbox>()
  readonly action = output<SandboxAction>()

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
    // whenever the sandbox input changes. This runs synchronously (no network
    // call) and ensures the "View supervisor case" link is shown immediately
    // after a page refresh, without creating a duplicate case.
    //
    // The effect re-runs when `sandbox` changes (e.g. the same card is reused
    // for a different workflow), but only transitions from `idle` to `done` --
    // it never overwrites a `loading` or `error` state that was set by an
    // in-progress creation flow.
    effect(() => {
      if (this.supervisorState() !== 'idle') return
      const restored = this.store.restoreSupervisorCase(this.sandbox())
      if (!restored) return
      this.supervisorCaseUrl.set(restored.agentOsUrl)
      this.supervisorState.set('done')
    })
  }

  /**
   * A running sandbox can only be stopped when an active attempt is resolvable
   * from the authoritative store state. The cockpit never fabricates an attempt
   * id: when none is resolvable the stop action is simply unavailable.
   */
  protected get canStop(): boolean {
    const s = this.sandbox()
    if (s.status !== 'working' || !s.run?.id) return false
    return Boolean(this.store.session(s.run.id)?.activeAttemptId)
  }

  /**
   * Build the AgentOS conversation link for this sandbox's controller case.
   *
   * `controllerExecution.caseId` is written by Factory when the first agent
   * step executes. It is absent in normal lifecycle states (workflow not yet
   * started, no agent step executed yet, or workflow type without agent steps).
   * This is not a bug the button is simply disabled until the data is present.
   *
   * Returns `null` when no controller case id is known.
   */
  protected get conversationLink(): string | null {
    const s = this.sandbox()
    const caseId = s.controllerCaseId
    if (!caseId) return null
    return buildAgentOsCaseUrl(caseId, s.namespace, this.agentOsBaseUrl)
  }

  /**
   * "Ask supervisor" orchestrated by {@link FactoryStore.openSupervisorCase}.
   *
   * **Popup-blocker strategy**: browsers block `window.open` called inside an
   * async callback. To guarantee the popup opens, we open the target window
   * synchronously in the click handler (before any async work), then navigate
   * it once the case id is known. If the window was blocked (returns `null`),
   * we surface the case URL as a persistent link the user can click manually.
   *
   * **`noopener` vs handle**: `window.open('', '_blank', 'noopener')` always
   * returns `null` -- the browser cannot provide a handle to an isolated window.
   * Instead we open without `noopener` to keep the handle, then immediately set
   * `targetWindow.opener = null` to achieve the same isolation before any async
   * work starts. This is the standard pattern for navigable popup handles.
   *
   * **Namespace guard**: `sandbox.namespace` must be a valid UUID the AgentOS
   * backend enforces this (`@NotNull UUID namespaceId`). When it is absent or
   * non-UUID the button is disabled or shows an inline error.
   *
   * **Idempotency**: once a case is created (`done` state), the button is
   * replaced by a persistent link. A second click is therefore impossible and
   * no duplicate case is ever created from the same card render.
   */
  protected askSupervisor(): void {
    if (this.supervisorState() === 'loading' || this.supervisorState() === 'done') return

    const s = this.sandbox()
    if (!s.namespace) {
      this.supervisorError.set('Unknown namespace -- cannot open a supervisor case.')
      this.supervisorState.set('error')
      return
    }

    this.supervisorState.set('loading')
    this.supervisorError.set(null)

    // Open without 'noopener' so the browser returns a usable handle.
    // 'noopener' causes window.open to always return null, making navigation
    // impossible. We achieve the same isolation by nullifying opener immediately,
    // before any async work, so the child window cannot reference this page.
    // If window.open returns null (popup blocker), we fall back to the
    // persistent link shown in the template once the case URL is known.
    const targetWindow = window.open('', '_blank')
    if (targetWindow) {
      // Sever the back-reference synchronously, before any await.
      targetWindow.opener = null
    }

    this.store.openSupervisorCase(s).subscribe({
      next: (result: SupervisorCaseResult) => {
        this.supervisorCaseUrl.set(result.agentOsUrl)
        this.supervisorState.set('done')
        if (targetWindow && !targetWindow.closed) {
          targetWindow.location.href = result.agentOsUrl
        }
        // If the window was blocked (targetWindow is null) the persistent link
        // in the template gives the user a clickable fallback.
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
