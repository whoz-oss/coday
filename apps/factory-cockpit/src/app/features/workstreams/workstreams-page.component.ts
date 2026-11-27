import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core'
import { RouterLink } from '@angular/router'
import { MatButtonModule } from '@angular/material/button'
import { MatChipsModule } from '@angular/material/chips'
import { MatDialog } from '@angular/material/dialog'
import { MatIconModule } from '@angular/material/icon'
import { MatFormFieldModule } from '@angular/material/form-field'
import { MatSelectModule } from '@angular/material/select'
import { FactoryStore } from '../../core/factory.store'
import { WorkstreamView } from '../../core/models'
import { ShellState } from '../../core/shell-state'
import { UsdPipe } from '../../shared/pipes/format.pipes'
import { ConfirmDialogComponent, ConfirmDialogData } from '../admin/confirm-dialog.component'
import { RUN_STATUS_OPTIONS, RunStatusFilter, RunStatusKey, runStatusKey } from './run-status-filter'
import { WorkstreamActionEvent, WorkstreamCardComponent } from './workstream-card/workstream-card.component'

@Component({
  selector: 'sf-workstreams-page',
  imports: [
    RouterLink,
    MatButtonModule,
    MatChipsModule,
    MatIconModule,
    MatFormFieldModule,
    MatSelectModule,
    WorkstreamCardComponent,
    UsdPipe,
  ],
  templateUrl: './workstreams-page.component.html',
  styleUrl: './workstreams-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WorkstreamsPageComponent {
  protected readonly store = inject(FactoryStore)
  private readonly dialog = inject(MatDialog)

  /** Selected run status, local to the page (not persisted). */
  protected readonly status = signal<RunStatusFilter>('all')

  /** Falls back to 'all' when Destroyed is selected but the Destroyed toggle is switched off. */
  protected readonly activeStatus = computed<RunStatusFilter>(() =>
    this.status() === 'destroyed' && !this.store.showDestroyed() ? 'all' : this.status()
  )

  protected readonly statusOptions = computed(() =>
    RUN_STATUS_OPTIONS.filter((option) => option.value !== 'destroyed' || this.store.showDestroyed())
  )

  /** Run counts per status over the runs visible on the page, before the status filter. */
  protected readonly statusCounts = computed<Record<RunStatusKey | 'all', number>>(() => {
    const counts: Record<RunStatusKey | 'all', number> = {
      all: 0,
      running: 0,
      pending: 0,
      waiting_human: 0,
      blocked: 0,
      failed: 0,
      succeeded: 0,
      destroyed: 0,
    }
    for (const workstream of this.store.workstreams()) {
      for (const run of workstream.runs) {
        counts.all++
        counts[runStatusKey(run)]++
      }
    }
    return counts
  })

  /** With 'all' the store reference is returned untouched, keeping the stable refresh of #1705. */
  protected readonly filteredWorkstreams = computed<WorkstreamView[]>(() => {
    const active = this.activeStatus()
    const workstreams = this.store.workstreams()
    if (active === 'all') return workstreams
    return workstreams
      .map((workstream) => ({ ...workstream, runs: workstream.runs.filter((run) => runStatusKey(run) === active) }))
      .filter((workstream) => workstream.runs.length > 0)
  })

  constructor() {
    inject(ShellState).crumbs.set([{ label: 'Workstreams', link: '/workstreams' }])
  }

  /** The listbox emits undefined when the selected chip is clicked again: that means 'all'. */
  protected selectStatus(value: RunStatusFilter | null | undefined): void {
    this.status.set(value ?? 'all')
  }

  protected selectNamespace(namespaceId: string): void {
    this.store.selectNamespace(namespaceId || null)
  }

  /** Actions are always targeted by the exact run identity, never a display name. */
  protected onAction(event: WorkstreamActionEvent): void {
    const { runId, action } = event
    switch (action) {
      case 'stop':
        this.store.stop(runId)
        return
      case 'remove':
        this.confirmRemove(runId)
        return
      case 'restore':
        this.store.restore(runId)
        return
      case 'conversation':
        // The run card opens the main case link itself.
        return
    }
  }

  /** Soft-remove a run only after an explicit, destructive confirmation. */
  private confirmRemove(runId: string): void {
    const data: ConfirmDialogData = {
      title: 'Remove run',
      message: 'Are you sure you want to remove this run? The action is recoverable via the destroyed runs toggle.',
      confirmLabel: 'Remove',
      destructive: true,
    }
    this.dialog
      .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, { data, width: '440px' })
      .afterClosed()
      .subscribe((confirmed) => {
        if (confirmed === true) this.store.remove(runId)
      })
  }
}
