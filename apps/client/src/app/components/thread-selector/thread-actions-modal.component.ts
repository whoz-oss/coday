import { Component, inject } from '@angular/core'
import {
  MAT_DIALOG_DATA,
  MatDialogActions,
  MatDialogContent,
  MatDialogRef,
  MatDialogTitle,
} from '@angular/material/dialog'
import { MatButtonModule } from '@angular/material/button'

@Component({
  selector: 'app-thread-actions-modal',
  standalone: true,
  imports: [MatDialogTitle, MatDialogActions, MatDialogContent, MatButtonModule],
  template: `
    <h2 mat-dialog-title>Thread Actions</h2>
    <mat-dialog-content>
      <button mat-stroked-button color="primary" (click)="rename()">Rename</button>
      <button mat-stroked-button color="warn" (click)="confirmDelete()">Delete</button>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button matButton (click)="close()">Close</button>
    </mat-dialog-actions>
    @if (isDeleteConfirm) {
      <div class="delete-confirm">
        <p>Are you sure you want to delete this thread?</p>
        <button matButton="filled" color="warn" (click)="doDelete()">Delete</button>
        <button matButton (click)="isDeleteConfirm = false">Cancel</button>
      </div>
    }
  `,
  styles: [
    `
      .delete-confirm {
        margin-top: 1.5em;
      }
      mat-dialog-content {
        display: flex;
        flex-direction: column;
        gap: 1em;
      }
    `,
  ],
})
export class ThreadActionsModalComponent {
  isDeleteConfirm = false

  data = inject<{ thread: any }>(MAT_DIALOG_DATA)
  private dialogRef = inject(MatDialogRef<ThreadActionsModalComponent>)

  close() {
    this.dialogRef.close()
  }

  rename() {
    /* TODO: implement or emit event */
  }

  confirmDelete() {
    this.isDeleteConfirm = true
  }

  doDelete() {
    /* TODO: implement delete, then close */
    this.close()
  }
}
