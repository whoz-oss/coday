import { ChangeDetectionStrategy, Component, inject } from '@angular/core'
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'

export interface ConfirmDialogData {
  title: string
  message: string
  confirmLabel?: string
  cancelLabel?: string
  destructive?: boolean
}

@Component({
  selector: 'app-confirm-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatDialogModule, MatButtonModule, MatIconModule],
  template: `
    <h2 mat-dialog-title>
      <mat-icon [class.destructive]="data.destructive !== false">warning</mat-icon>
      {{ data.title }}
    </h2>
    <mat-dialog-content>
      <p>{{ data.message }}</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="cancel()">{{ data.cancelLabel ?? 'Cancel' }}</button>
      <button mat-flat-button [class.confirm-destructive]="data.destructive !== false" (click)="confirm()">
        {{ data.confirmLabel ?? 'Confirm' }}
      </button>
    </mat-dialog-actions>
  `,
  styles: [
    `
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
      .confirm-destructive {
        background: var(--color-error, #f44336);
        color: #fff;
      }
    `,
  ],
})
export class ConfirmDialogComponent {
  protected readonly data = inject<ConfirmDialogData>(MAT_DIALOG_DATA)
  private readonly dialogRef = inject(MatDialogRef<ConfirmDialogComponent>)

  confirm(): void {
    this.dialogRef.close(true)
  }

  cancel(): void {
    this.dialogRef.close(false)
  }
}
