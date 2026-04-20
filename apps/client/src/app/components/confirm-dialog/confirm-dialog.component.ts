import { ChangeDetectionStrategy, Component, inject } from '@angular/core'
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'
import { MatCheckbox } from '@angular/material/checkbox'
import { FormsModule } from '@angular/forms'

export interface ConfirmDialogData {
  title: string
  message: string
  confirmLabel?: string
  cancelLabel?: string
  isDestructive?: boolean
  checkboxLabel?: string // If set, shows a checkbox below the message
}

export interface ConfirmDialogResult {
  confirmed: boolean
  checkboxChecked?: boolean
}

@Component({
  selector: 'app-confirm-dialog',
  templateUrl: './confirm-dialog.component.html',
  styleUrl: './confirm-dialog.component.scss',
  imports: [MatDialogModule, MatButtonModule, MatIconModule, MatCheckbox, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConfirmDialogComponent {
  protected readonly dialogRef = inject(MatDialogRef<ConfirmDialogComponent, ConfirmDialogResult>)
  protected readonly data = inject<ConfirmDialogData>(MAT_DIALOG_DATA)

  protected checkboxChecked = false

  confirm(): void {
    this.dialogRef.close({ confirmed: true, checkboxChecked: this.checkboxChecked })
  }

  cancel(): void {
    this.dialogRef.close({ confirmed: false })
  }
}
