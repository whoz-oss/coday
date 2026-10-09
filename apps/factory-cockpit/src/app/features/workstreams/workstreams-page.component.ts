import { ChangeDetectionStrategy, Component, inject } from '@angular/core'
import { RouterLink } from '@angular/router'
import { MatButtonModule } from '@angular/material/button'
import { MatDialog } from '@angular/material/dialog'
import { MatIconModule } from '@angular/material/icon'
import { FactoryStore } from '../../core/factory.store'
import { ShellState } from '../../core/shell-state'
import { UsdPipe } from '../../shared/pipes/format.pipes'
import { ConfirmDialogComponent, ConfirmDialogData } from '../admin/confirm-dialog.component'
import { WorkstreamActionEvent, WorkstreamCardComponent } from './workstream-card/workstream-card.component'

@Component({
  selector: 'sf-workstreams-page',
  imports: [RouterLink, MatButtonModule, MatIconModule, WorkstreamCardComponent, UsdPipe],
  templateUrl: './workstreams-page.component.html',
  styleUrl: './workstreams-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WorkstreamsPageComponent {
  protected readonly store = inject(FactoryStore)
  private readonly dialog = inject(MatDialog)

  constructor() {
    inject(ShellState).crumbs.set([{ label: 'Workstreams', link: '/workstreams' }])
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
      case 'ask':
      case 'conversation':
        // Both actions are handled entirely by the run card component:
        // 'conversation' opens the controller case link directly from the card;
        // 'ask' is no longer emitted (supervisor flow is orchestrated via FactoryStore).
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
