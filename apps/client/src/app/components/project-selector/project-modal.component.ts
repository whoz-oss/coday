import { Component, inject } from '@angular/core'
import {
  MAT_DIALOG_DATA,
  MatDialogActions,
  MatDialogContent,
  MatDialogRef,
  MatDialogTitle,
} from '@angular/material/dialog'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'
import { CodayService } from '../../core/services/coday.service'

@Component({
  selector: 'app-project-modal',
  standalone: true,
  imports: [MatDialogTitle, MatDialogContent, MatDialogActions, MatButtonModule, MatIconModule],
  template: `
    <h2 mat-dialog-title>Select Project</h2>
    <mat-dialog-content>
      @for (prj of data.projects?.list; track prj.name) {
        <button mat-stroked-button (click)="select(prj.name)">
          {{ prj.name }}
          @if (prj.name === data.projects?.current) {
            <mat-icon>check</mat-icon>
          }
        </button>
      }
      @if (!data.projects?.list?.length) {
        <div class="empty">No projects available</div>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="close()">Close</button>
    </mat-dialog-actions>
  `,
  styles: [
    `
      .empty {
        color: #888;
        font-style: italic;
        margin: 1.2em 0;
      }
    `,
  ],
})
export class ProjectModalComponent {
  data = inject(MAT_DIALOG_DATA)
  private dialogRef = inject(MatDialogRef<ProjectModalComponent>)
  private codayService = inject(CodayService)

  close() {
    this.dialogRef.close()
  }

  select(name: string) {
    this.codayService.sendMessage(`config select-project ${name}`)
    this.close()
  }
}
