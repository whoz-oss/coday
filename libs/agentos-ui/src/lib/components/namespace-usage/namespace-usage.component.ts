import { DecimalPipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, RouterLink } from '@angular/router'
import { FormsModule } from '@angular/forms'
import { UsageAggregateByKey, UsageRecordControllerService } from '@whoz-oss/agentos-api-client'
import { catchError, EMPTY, forkJoin, startWith, Subject, switchMap } from 'rxjs'

@Component({
  selector: 'agentos-namespace-usage',
  imports: [DecimalPipe, FormsModule, RouterLink],
  templateUrl: './namespace-usage.component.html',
  styleUrl: './namespace-usage.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NamespaceUsageComponent {
  private readonly route = inject(ActivatedRoute)
  private readonly api = inject(UsageRecordControllerService)
  private readonly destroy = inject(DestroyRef)
  readonly namespaceId = this.route.snapshot.params['namespaceId'] as string
  readonly agents = signal<UsageAggregateByKey[]>([])
  readonly models = signal<UsageAggregateByKey[]>([])
  readonly error = signal<string | null>(null)
  readonly loading = signal(false)
  from = new Date(Date.now() - 30 * 86400000).toISOString().slice(0, 10)
  to = new Date().toISOString().slice(0, 10)
  private readonly refresh = new Subject<void>()

  constructor() {
    this.refresh
      .pipe(
        startWith(undefined),
        switchMap(() => {
          this.error.set(null)
          this.agents.set([])
          this.models.set([])
          const from = new Date(`${this.from}T00:00:00.000Z`)
          const to = new Date(`${this.to}T23:59:59.999Z`)
          if (!Number.isFinite(from.getTime()) || !Number.isFinite(to.getTime()) || from > to) {
            this.error.set('Select a valid date range.')
            this.loading.set(false)
            return EMPTY
          }
          this.loading.set(true)
          return forkJoin({
            agents: this.api.aggregateByAgentUsageRecord(this.namespaceId, from.toISOString(), to.toISOString()),
            models: this.api.aggregateByModelUsageRecord(this.namespaceId, from.toISOString(), to.toISOString()),
          }).pipe(
            catchError((error) => {
              this.loading.set(false)
              this.error.set(
                error.status === 403
                  ? 'Namespace admin permission is required to view this report.'
                  : 'Could not load usage. Please try again.'
              )
              return EMPTY
            })
          )
        }),
        takeUntilDestroyed(this.destroy)
      )
      .subscribe(({ agents, models }) => {
        this.agents.set(agents)
        this.models.set(models)
        this.loading.set(false)
      })
  }

  load(): void {
    this.refresh.next()
  }
}
