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
import { FactoryApiService, NamespaceOption, extractNamespaceOptions } from '../../core/factory-api.service'
import { FactoryStore } from '../../core/factory.store'
import { Sandbox } from '../../core/models'
import { FrPaginatorIntl } from '../../core/paginator-intl.fr'
import { ShellState } from '../../core/shell-state'
import { UsdPipe } from '../../shared/pipes/format.pipes'
import { PhaseBarComponent } from '../../shared/ui/phase-bar.component'
import { StatusChipComponent } from '../../shared/ui/status-chip.component'

type StatusFilter = 'all' | 'working' | 'destroyed'

interface Row extends Sandbox {
  cost: number
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
  providers: [{ provide: MatPaginatorIntl, useClass: FrPaginatorIntl }],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HistoryPageComponent {
  private readonly api = inject(FactoryApiService)
  private readonly store = inject(FactoryStore)

  protected readonly columns = ['name', 'branch', 'status', 'run', 'phases', 'cost', 'actions']
  protected readonly query = signal('')
  protected readonly status = signal<StatusFilter>('all')
  protected readonly project = signal('')

  protected readonly namespaces = signal<NamespaceOption[]>([])
  protected readonly namespacesLoading = signal(false)
  protected readonly namespacesError = signal<string | null>(null)

  protected readonly rows = computed<Row[]>(() =>
    this.store.sandboxes().map((s) => ({ ...s, cost: s.run?.costUsd ?? s.finalCostUsd ?? 0 }))
  )

  protected readonly counts = computed(() => ({
    all: this.rows().length,
    working: this.rows().filter((r) => r.status !== 'destroyed').length,
    destroyed: this.rows().filter((r) => r.status === 'destroyed').length,
  }))

  protected readonly filtered = computed(() => {
    const q = this.query().trim().toLowerCase()
    const st = this.status()
    const proj = this.project()
    return this.rows().filter(
      (r) =>
        (!proj || r.project === proj) &&
        (st === 'all' || (st === 'working' ? r.status !== 'destroyed' : r.status === 'destroyed')) &&
        (!q || [r.name, r.branch, r.run?.id, r.run?.workflow].some((v) => v?.toLowerCase().includes(q)))
    )
  })

  protected readonly totalCost = computed(() => this.filtered().reduce((sum, r) => sum + r.cost, 0))

  /** Cost bars per sandbox, sorted from highest to lowest cost */
  protected readonly costBars = computed(() => {
    const rows = [...this.filtered()].sort((a, b) => b.cost - a.cost)
    const max = Math.max(...rows.map((r) => r.cost), 0.0001)
    return rows.map((r) => ({
      name: r.name,
      cost: r.cost,
      active: r.status !== 'destroyed',
      pct: Math.max((r.cost / max) * 100, 0.5),
    }))
  })

  protected readonly dataSource = new MatTableDataSource<Row>([])
  private readonly sort = viewChild(MatSort)
  private readonly paginator = viewChild(MatPaginator)

  constructor() {
    inject(ShellState).crumbs.set([{ label: 'History' }])
    this.loadNamespaces()
    effect(() => {
      this.dataSource.data = this.filtered()
    })
    effect(() => {
      this.dataSource.sort = this.sort() ?? null
      this.dataSource.paginator = this.paginator() ?? null
    })
  }

  private loadNamespaces(): void {
    this.namespacesLoading.set(true)
    this.namespacesError.set(null)
    this.api.getNamespaces().subscribe({
      next: (payload) => {
        this.namespaces.set(extractNamespaceOptions(payload))
        this.namespacesLoading.set(false)
      },
      error: () => {
        this.namespacesError.set('Projects unavailable.')
        this.namespacesLoading.set(false)
      },
    })
  }

  protected exportCsv(): void {
    const lines = [
      'sandbox;project;branch;status;run;workflow;cost_usd',
      ...this.filtered().map((r) =>
        [r.name, r.project, r.branch ?? '', r.status, r.run?.id ?? '', r.run?.workflow ?? '', r.cost.toFixed(4)].join(
          ';'
        )
      ),
    ]
    const url = URL.createObjectURL(new Blob([lines.join('\n')], { type: 'text/csv' }))
    Object.assign(document.createElement('a'), { href: url, download: 'sandboxes.csv' }).click()
    URL.revokeObjectURL(url)
  }
}
