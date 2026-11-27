import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core'
import { DatePipe } from '@angular/common'
import { RouterLink } from '@angular/router'
import { MatCardModule } from '@angular/material/card'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'
import { FactoryStore, buildAgentOsCaseUrl } from '../../../core/factory.store'
import { FactoryRun, StopFeedback } from '../../../core/models'
import { StatusChipComponent } from '../../../shared/ui/status-chip.component'
import { MetricChipComponent } from '../../../shared/ui/metric-chip.component'
import { StepDotsComponent } from '../../../shared/ui/step-dots.component'
import { DurationPipe, TokensPipe, UsdPipe } from '../../../shared/pipes/format.pipes'

export type RunAction = 'conversation' | 'stop' | 'remove' | 'restore'

/** @deprecated Use {@link RunAction}. Kept as an alias for compatibility. */
export type SandboxAction = RunAction

/**
 * Renders a single Factory run and owns ALL its actions (Stop, Remove, Restore,
 * Conversation). Every action targets the run by its unique `id` (workflow id):
 * the parent page binds the emitted action to `run.id`, so two runs sharing a
 * display title can never be confused.
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
   * Build the AgentOS conversation link for the main case of this run.
   *
   * The main case is resolved by the mappers (`resolveMainCaseId`): the controller case when Factory exposes one,
   * else the run root case, i.e. the case of the entry agent step. It is absent until the first agent step of the
   * run has been dispatched. Returns `null` when no main case id is known.
   */
  protected get conversationLink(): string | null {
    const run = this.run()
    const caseId = run.mainCaseId ?? run.controllerCaseId
    if (!caseId) return null
    return buildAgentOsCaseUrl(caseId, run.namespaceId)
  }
}
