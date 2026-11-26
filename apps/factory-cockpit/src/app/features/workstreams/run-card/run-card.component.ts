import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core'
import { DatePipe } from '@angular/common'
import { RouterLink } from '@angular/router'
import { MatCardModule } from '@angular/material/card'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'
import { FactoryStore, buildAgentOsCaseUrl } from '../../../core/factory.store'
import { FactoryRun } from '../../../core/models'
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
