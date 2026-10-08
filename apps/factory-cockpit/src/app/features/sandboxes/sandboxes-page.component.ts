import { ChangeDetectionStrategy, Component, inject } from '@angular/core'
import { RouterLink } from '@angular/router'
import { MatButtonModule } from '@angular/material/button'
import { MatDialog } from '@angular/material/dialog'
import { MatIconModule } from '@angular/material/icon'
import { FactoryStore } from '../../core/factory.store'
import { ShellState } from '../../core/shell-state'
import { UsdPipe } from '../../shared/pipes/format.pipes'
import { ConfirmDialogComponent, ConfirmDialogData } from '../admin/confirm-dialog.component'
import { SandboxAction, SandboxCardComponent } from './sandbox-card/sandbox-card.component'

@Component({
  selector: 'sf-sandboxes-page',
  imports: [RouterLink, MatButtonModule, MatIconModule, SandboxCardComponent, UsdPipe],
  templateUrl: './sandboxes-page.component.html',
  styleUrl: './sandboxes-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SandboxesPageComponent {
  protected readonly store = inject(FactoryStore)
  private readonly dialog = inject(MatDialog)

  constructor() {
    inject(ShellState).crumbs.set([{ label: 'Sandboxes' }])
  }

  protected onAction(workflowId: string, action: SandboxAction): void {
    switch (action) {
      case 'stop':
        this.store.stop(workflowId)
        return
      case 'remove':
        this.confirmRemove(workflowId)
        return
      case 'restore':
        this.store.restore(workflowId)
        return
      case 'ask':
      case 'conversation':
        // Both actions are handled entirely by the child card component:
        // 'conversation' opens the controller case link directly from the card;
        // 'ask' is no longer emitted (supervisor flow is orchestrated via FactoryStore).
        return
    }
  }

  /** Soft-remove a sandbox only after an explicit, destructive confirmation. */
  private confirmRemove(workflowId: string): void {
    const data: ConfirmDialogData = {
      title: 'Remove sandbox',
      message:
        'Are you sure you want to remove this sandbox? The action is recoverable via the destroyed sandboxes toggle.',
      confirmLabel: 'Remove',
      destructive: true,
    }
    this.dialog
      .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, { data, width: '440px' })
      .afterClosed()
      .subscribe((confirmed) => {
        if (confirmed === true) this.store.remove(workflowId)
      })
  }
}
