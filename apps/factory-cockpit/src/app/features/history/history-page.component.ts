import { ChangeDetectionStrategy, Component, computed, effect, inject, signal, viewChild } from '@angular/core'
import { RouterLink } from '@angular/router'
import { MatButtonModule } from '@angular/material/button'
import { MatChipsModule } from '@angular/material/chips'
import { MatFormFieldModule } from '@angular/material/form-field'
import { MatIconModule } from '@angular/material/icon'
import { MatInputModule } from '@angular/material/input'
import { MatMenuModule } from '@angular/material/menu'
import { MatPaginator, MatPaginatorIntl, MatPaginatorModule } from '@angular/material/paginator'
import { MatSelectModule } from '@angular/material/select'
import { MatSort, MatSortModule } from '@angular/material/sort'
import { MatTableDataSource, MatTableModule } from '@angular/material/table'
import { FactoryStore } from '../../core/factory.store'
import { HistoryStateService } from '../../core/history-state.service'
import { FactoryRun } from '../../core/models'
import { FrPaginatorIntl } from '../../core/paginator-intl.fr'
import { ShellState } from '../../core/shell-state'
import { UsdPipe } from '../../shared/pipes/format.pipes'
import { PhaseBarComponent } from '../../shared/ui/phase-bar.component'
import { StatusChipComponent } from '../../shared/ui/status-chip.component'

type StatusFilter = 'all' | 'working' | 'destroyed'

interface Row extends FactoryRun {
  /** Null means Factory explicitly could not obtain run analytics. */
  cost: number | null
  unknownCostCount: number | null
}

@Component({
  selector: 'sf-history-page',
  imports: [
    RouterLink,
    MatButtonModule,
    MatChipsModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatMenuModule,
    MatPaginatorModule,
    MatSelectModule,
    MatSortModule,
    MatTableModule,
    StatusChipComponent,
    PhaseBarComponent,
    UsdPipe,
  ],
  templateUrl: './history-page.component.html',
  styleUrl: './history-page.component.scss',
  providers: [{ provide: MatPaginatorIntl, useClass: FrPaginatorIntl }, HistoryStateService],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HistoryPageComponent {
  private readonly historyState = inject(HistoryStateService)
  private readonly store = inject(FactoryStore)

  protected readonly columns = ['run', 'namespace', 'branch', 'status', 'phases', 'cost', 'actions']
  protected readonly query = signal('')
  protected readonly status = signal<StatusFilter>('all')
  protected readonly namespaceId = signal('')

  protected readonly namespaces = this.historyState.namespaces
  protected readonly namespacesLoading = this.historyState.loading
  protected readonly namespacesError = computed(() => (this.historyState.error() ? 'Namespaces unavailable.' : null))

  /** Run-oriented history: one row per real Factory run. */
  protected readonly rows = computed<Row[]>(() =>
    this.store.runs().map((run) => ({
      ...run,
      // Removed (destroyed) runs are not readable through the Factory metrics endpoint (it rejects
      // a removed workflow), so they only have a cost when a durable final cost exists. Active and
      // history rows never substitute projection/budget values for unavailable analytics.
      cost:
        run.status === 'destroyed' && run.finalCostUsd !== undefined
          ? run.finalCostUsd
          : run.run
            ? run.run.costUsd
            : run.costUsd,
      unknownCostCount: run.run?.unknownCostCount ?? null,
    }))
  )

  protected readonly counts = computed(() => ({
    all: this.rows().length,
    working: this.rows().filter((r) => r.status !== 'destroyed').length,
    destroyed: this.rows().filter((r) => r.status === 'destroyed').length,
  }))

  protected readonly filtered = computed(() => {
    const q = this.query().trim().toLowerCase()
    const st = this.status()
    const ns = this.namespaceId()
    return this.rows().filter(
      (r) =>
        (!ns || r.namespaceId === ns) &&
        (st === 'all' || (st === 'working' ? r.status !== 'destroyed' : r.status === 'destroyed')) &&
        (!q || [r.title, r.branch, r.id, r.run?.workflow].some((v) => v?.toLowerCase().includes(q)))
    )
  })

  protected readonly totalCost = computed<number | null>(() => {
    const rows = this.filtered()
    if (rows.some((row) => row.cost === null)) return null
    return rows.reduce<number>((sum, row) => sum + (row.cost ?? 0), 0)
  })

  /**
   * Completeness of {@link totalCost}: the sum of the rows' unknown-cost counts, or null when any
   * row cannot establish its own completeness (the total is then not known to be exact).
   */
  protected readonly totalUnknownCostCount = computed<number | null>(() => {
    const rows = this.filtered()
    if (rows.some((row) => row.unknownCostCount === null)) return null
    return rows.reduce<number>((sum, row) => sum + (row.unknownCostCount ?? 0), 0)
  })

  /** Cost bars per run, sorted from highest to lowest cost. */
  protected readonly costBars = computed(() => {
    // Unknown costs have no meaningful bar height, so leave them out rather
    // than visually treating them as zero-cost runs.
    const rows = this.filtered().filter((row): row is Row & { cost: number } => row.cost !== null)
    const sorted = [...rows].sort((a, b) => b.cost - a.cost)
    const max = Math.max(...sorted.map((row) => row.cost), 0.0001)
    return sorted.map((row) => ({
      name: row.title,
      cost: row.cost,
      unknownCostCount: row.unknownCostCount,
      active: row.status !== 'destroyed',
      pct: Math.max((row.cost / max) * 100, 0.5),
    }))
  })

  protected readonly dataSource = new MatTableDataSource<Row>([])
  private readonly sort = viewChild(MatSort)
  private readonly paginator = viewChild(MatPaginator)

  constructor() {
    inject(ShellState).crumbs.set([{ label: 'Run history', link: '/history' }])
    this.historyState.loadNamespaces()
    // Removed runs are not readable through Factory's metrics endpoint: ask the store (once per run,
    // bounded) for their lifetime usage whenever the history shows removed runs not yet attempted.
    effect(() => {
      if (this.store.runs().some((run) => run.status === 'destroyed')) this.store.enrichRemovedRunsUsage()
    })
    effect(() => {
      this.dataSource.data = this.filtered()
    })
    effect(() => {
      this.dataSource.sort = this.sort() ?? null
      this.dataSource.paginator = this.paginator() ?? null
    })
  }

  protected exportCsv(): void {
    const lines = [
      'run_id;namespace_id;project;branch;status;workflow;cost_usd',
      ...this.filtered().map((r) =>
        [
          r.id,
          r.namespaceId ?? '',
          r.project,
          r.branch ?? '',
          r.status,
          r.run?.workflow ?? r.title,
          r.cost?.toFixed(4) ?? '',
        ].join(';')
      ),
    ]
    const url = URL.createObjectURL(new Blob([lines.join('\n')], { type: 'text/csv' }))
    Object.assign(document.createElement('a'), { href: url, download: 'runs.csv' }).click()
    URL.revokeObjectURL(url)
  }
}
