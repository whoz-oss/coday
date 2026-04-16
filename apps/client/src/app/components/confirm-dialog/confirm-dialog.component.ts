import { Component, inject } from '@angular/core'
import { FormsModule } from '@angular/forms'
import { MAT_DIALOG_DATA, MatDialogActions, MatDialogRef, MatDialogTitle } from '@angular/material/dialog'
import { MatButtonModule } from '@angular/material/button'
import { MatCheckboxModule } from '@angular/material/checkbox'

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
  standalone: true,
  imports: [MatDialogTitle, MatDialogActions, MatButtonModule, MatCheckboxModule, FormsModule],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>

    <p class="confirm-dialog__message">{{ data.message }}</p>

    @if (data.checkboxLabel) {
      <mat-checkbox class="confirm-dialog__checkbox" [(ngModel)]="checkboxChecked">{{
        data.checkboxLabel
      }}</mat-checkbox>
    }

    <mat-dialog-actions align="end">
      <button mat-button (click)="cancel()">{{ data.cancelLabel ?? 'Cancel' }}</button>
      <button mat-flat-button [color]="data.isDestructive ? 'warn' : 'primary'" (click)="confirm()">
        {{ data.confirmLabel ?? 'Confirm' }}
      </button>
    </mat-dialog-actions>
  `,
  styles: [
    `
      .confirm-dialog__message {
        margin: 0 24px 8px;
        color: var(--color-text-secondary, #6e6e73);
        font-size: 0.875rem;
        line-height: 1.5;
      }

      .confirm-dialog__checkbox {
        display: block;
        margin: 0 24px 8px;
        font-size: 0.875rem;
      }
    `,
  ],
})
export class ConfirmDialogComponent {
  protected readonly dialogRef = inject(MatDialogRef<ConfirmDialogComponent, ConfirmDialogResult>)
  protected readonly data = inject<ConfirmDialogData>(MAT_DIALOG_DATA)

  protected checkboxChecked = false

  cancel(): void {
    this.dialogRef.close({ confirmed: false })
  }

  confirm(): void {
    this.dialogRef.close({ confirmed: true, checkboxChecked: this.checkboxChecked })
  }
}
