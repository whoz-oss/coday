import { ChangeDetectionStrategy, Component, inject } from '@angular/core'
import { MatButtonModule } from '@angular/material/button'
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog'

/** Data passed to {@link ConfirmDialogComponent}. */
export interface ConfirmDialogData {
  title: string
  message: string
  /** Optional precise identity (artifact id, `type@version`, …) shown as code. */
  detail?: string
  confirmLabel?: string
  /** Applies the destructive styling to the confirm button. */
  destructive?: boolean
}

/**
 * Small confirmation modal used before destructive admin commands. Resolves
 * `true` on confirm and `false` on cancel/dismiss, so a destructive command is
 * never sent without an explicit confirmation.
 */
@Component({
  selector: 'sf-confirm-dialog',
  imports: [MatButtonModule, MatDialogModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content>
      <p>{{ data.message }}</p>
      @if (data.detail) {
        <code class="sf-mono dialog-detail">{{ data.detail }}</code>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button [mat-dialog-close]="false">Cancel</button>
      <button mat-flat-button [class.sf-danger]="data.destructive" [mat-dialog-close]="true">
        {{ data.confirmLabel ?? 'Confirm' }}
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .dialog-detail {
      display: inline-block;
      margin-top: 10px;
      padding: 4px 8px;
      border-radius: 6px;
      background: var(--sf-surface-2);
      color: var(--sf-primary);
    }
  `,
})
export class ConfirmDialogComponent {
  protected readonly data = inject<ConfirmDialogData>(MAT_DIALOG_DATA)
}
