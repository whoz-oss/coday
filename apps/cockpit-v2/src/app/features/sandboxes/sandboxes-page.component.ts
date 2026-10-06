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
      default:
        return
    }
  }

  /** Soft-remove a sandbox only after an explicit, destructive confirmation. */
  private confirmRemove(workflowId: string): void {
    const data: ConfirmDialogData = {
      title: 'Supprimer la sandbox',
      message:
        "Voulez-vous vraiment supprimer cette sandbox ? L'action est récupérable via le toggle des sandboxes détruites.",
      confirmLabel: 'Supprimer',
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
