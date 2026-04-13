import { Component, inject } from '@angular/core'
import { FormsModule } from '@angular/forms'
import { MAT_DIALOG_DATA, MatDialogActions, MatDialogRef, MatDialogTitle } from '@angular/material/dialog'
import { ChangeDetectionStrategy, Component, inject } from '@angular/core'
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog'
import { MatButtonModule } from '@angular/material/button'
import { MatCheckboxModule } from '@angular/material/checkbox'
import { MatIconModule } from '@angular/material/icon'

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
  destructive?: boolean
}

@Component({
  selector: 'app-confirm-dialog',
  standalone: true,
  imports: [MatDialogTitle, MatDialogActions, MatButtonModule, MatCheckboxModule, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatDialogModule, MatButtonModule, MatIconModule],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>

    <p class="confirm-dialog__message">{{ data.message }}</p>

    @if (data.checkboxLabel) {
      <mat-checkbox class="confirm-dialog__checkbox" [(ngModel)]="checkboxChecked">{{
        data.checkboxLabel
      }}</mat-checkbox>
    }

    <h2 mat-dialog-title>
      <mat-icon [class.destructive]="data.destructive !== false">warning</mat-icon>
      {{ data.title }}
    </h2>
    <mat-dialog-content>
      <p>{{ data.message }}</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="cancel()">{{ data.cancelLabel ?? 'Cancel' }}</button>
      <button mat-flat-button [color]="data.isDestructive ? 'warn' : 'primary'" (click)="confirm()">
      <button mat-flat-button [class.confirm-destructive]="data.destructive !== false" (click)="confirm()">
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
      h2[mat-dialog-title] {
        display: flex;
        align-items: center;
        gap: 0.5rem;
      }
      mat-icon.destructive {
        color: var(--color-error, #f44336);
      }
      p {
        color: var(--color-text-secondary);
        line-height: 1.5;
      }

      .confirm-dialog__checkbox {
        display: block;
        margin: 0 24px 8px;
        font-size: 0.875rem;
      .confirm-destructive {
        background: var(--color-error, #f44336);
        color: #fff;
      }
    `,
  ],
})
export class ConfirmDialogComponent {
  protected readonly dialogRef = inject(MatDialogRef<ConfirmDialogComponent, ConfirmDialogResult>)
  protected readonly data = inject<ConfirmDialogData>(MAT_DIALOG_DATA)

  protected checkboxChecked = false
  confirm(): void {
    this.dialogRef.close(true)
  }

  cancel(): void {
    this.dialogRef.close({ confirmed: false })
  }

  confirm(): void {
    this.dialogRef.close({ confirmed: true, checkboxChecked: this.checkboxChecked })
    this.dialogRef.close(false)
  }
}
