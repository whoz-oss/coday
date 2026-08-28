import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core'
import { toObservable, takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { interval, EMPTY, switchMap } from 'rxjs'
import { map } from 'rxjs/operators'
import { FactoryRunDetail } from '../../services/factory-api.service'
import { FactoryStateService } from '../../services/factory-state.service'
import { FactoryRunTimelineComponent } from '../factory-run-timeline/factory-run-timeline.component'
import { formatTimelineDuration, timelineStatus } from '../factory-run-timeline/factory-run-timeline.models'
import { FactoryPhasePanelComponent } from '../factory-phase-panel/factory-phase-panel.component'

@Component({
  selector: 'agentos-factory-run-detail',
  imports: [FactoryRunTimelineComponent, FactoryPhasePanelComponent],
  templateUrl: './factory-run-detail.component.html',
  styleUrl: './factory-run-detail.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FactoryRunDetailComponent {
  readonly run = input.required<FactoryRunDetail>()
  readonly selectedPhaseIndex = input.required<number>()
  readonly phaseSelected = output<number>()

  /** Reactive wall-clock tick — incremented ~once per second ONLY while run is active. */
  private readonly clockTick = signal(0)
  protected readonly factoryState = inject(FactoryStateService)

  constructor() {
    /**
     * Field initializers and constructors run inside the injection context,
     * so toObservable() is safe here. switchMap stops the interval as soon as
     * the run leaves 'running' state, avoiding a leaked interval on finished runs.
     */
    toObservable(this.run)
      .pipe(
        switchMap((run) => (run.status === 'running' ? interval(1000).pipe(map(() => run)) : EMPTY)),
        takeUntilDestroyed()
      )
      .subscribe(() => this.clockTick.update((n) => n + 1))
  }

  protected readonly runningElapsedMs = computed(() => {
    if (this.run().status !== 'running' || !this.run().startedAt) return null
    const startedAtValue = this.run().startedAt
    if (!startedAtValue) return null
    const startedAt = Date.parse(startedAtValue)
    // Reading clockTick() makes this computed reactive to clock ticks.
    void this.clockTick()
    return Number.isFinite(startedAt) ? Math.max(Date.now() - startedAt, this.run().durationMs ?? 0) : null
  })

  protected readonly stats = computed(() => {
    const run = this.run()
    const phases = run.phases
    return [
      { label: 'Duration', value: formatTimelineDuration(this.runningElapsedMs() ?? run.durationMs ?? 0) },
      { label: 'Phases', value: `${phases.length}` },
      {
        label: 'Failures',
        value: `${phases.filter((phase) => timelineStatus(phase.status) === 'failed').length}`,
        alert: true,
      },
      { label: 'Status', value: run.status },
    ]
  })

  protected formatDuration(durationMs: number | null): string {
    return durationMs == null ? '—' : formatTimelineDuration(durationMs)
  }

  protected onStopClick(): void {
    this.factoryState.stopSelectedRun()
  }
}
