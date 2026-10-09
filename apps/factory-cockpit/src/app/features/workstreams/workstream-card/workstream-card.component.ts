import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core'
import { WorkstreamView } from '../../../core/models'
import { UsdPipe } from '../../../shared/pipes/format.pipes'
import { RunAction, RunCardComponent } from '../run-card/run-card.component'

/** Action event bubbled up from a nested run, carrying the exact run identity. */
export interface WorkstreamActionEvent {
  runId: string
  action: RunAction
}

/**
 * A workstream groups every run sharing the same `namespaceId`.
 *
 * The header surfaces the workstream title, its `namespaceId`, the run count and
 * the aggregated workstream cost. It deliberately carries NO overall state badge:
 * independent run statuses belong exclusively to the individual {@link RunCardComponent}.
 */
@Component({
  selector: 'sf-workstream-card',
  imports: [RunCardComponent, UsdPipe],
  templateUrl: './workstream-card.component.html',
  styleUrl: './workstream-card.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WorkstreamCardComponent {
  readonly workstream = input.required<WorkstreamView>()
  readonly action = output<WorkstreamActionEvent>()

  protected readonly runCount = computed(() => this.workstream().runs.length)

  /** Total workstream cost, summing each run's real cost. */
  protected readonly totalCost = computed(() =>
    this.workstream().runs.reduce((sum, run) => sum + (run.run?.costUsd ?? run.costUsd ?? 0), 0)
  )

  /** Sum of the unknown-cost units across the workstream runs. */
  protected readonly unknownCostCount = computed(() =>
    this.workstream().runs.reduce((sum, run) => sum + (run.run?.unknownCostCount ?? 0), 0)
  )

  protected onRunAction(runId: string, action: RunAction): void {
    this.action.emit({ runId, action })
  }
}
