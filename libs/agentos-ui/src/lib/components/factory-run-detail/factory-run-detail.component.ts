import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  input,
  OnInit,
  output,
  signal,
} from '@angular/core'
import { toObservable } from '@angular/core/rxjs-interop'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { interval, EMPTY, switchMap } from 'rxjs'
import { map } from 'rxjs/operators'
import { FactoryRunDetail } from '../../services/factory-api.service'
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
export class FactoryRunDetailComponent implements OnInit {
  readonly run = input.required<FactoryRunDetail>()
  readonly selectedPhaseIndex = input.required<number>()
  readonly phaseSelected = output<number>()

  /** Reactive wall-clock tick — incremented ~once per second ONLY while run is active. */
  private readonly clockTick = signal(0)
  private readonly destroyRef = inject(DestroyRef)

  ngOnInit(): void {
    /**
     * switchMap stoppe l'interval dès que le run n'est plus en cours.
     * Évite de continuer à tick toutes les secondes sur un run terminé.
     */
    toObservable(this.run)
      .pipe(
        switchMap((run) => (run.status === 'running' ? interval(1000).pipe(map(() => run)) : EMPTY)),
        takeUntilDestroyed(this.destroyRef)
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
}
