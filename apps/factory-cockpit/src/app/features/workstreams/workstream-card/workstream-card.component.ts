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

  /** Null when any run's analytics are unavailable; never synthesize a zero. */
  protected readonly totalCost = computed<number | null>(() => {
    const runs = this.workstream().runs
    const realCostOf = (run: WorkstreamView['runs'][number]): number | null => (run.run ? run.run.costUsd : run.costUsd)
    if (runs.some((run) => realCostOf(run) === null)) return null
    return runs.reduce<number>((sum, run) => sum + (realCostOf(run) ?? 0), 0)
  })

  /** Null when the backend cannot establish cost completeness for any run. */
  protected readonly unknownCostCount = computed<number | null>(() => {
    const runs = this.workstream().runs
    if (runs.some((run) => run.run?.unknownCostCount == null)) return null
    return runs.reduce<number>((sum, run) => sum + (run.run?.unknownCostCount ?? 0), 0)
  })

  protected onRunAction(runId: string, action: RunAction): void {
    this.action.emit({ runId, action })
  }
}
