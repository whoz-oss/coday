import { Component, Inject } from '@angular/core'
import { CommonModule } from '@angular/common'
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
  imports: [CommonModule, MatDialogTitle, MatDialogContent, MatDialogActions, MatButtonModule, MatIconModule],
  template: `
    <h2 mat-dialog-title>Select Project</h2>
    <mat-dialog-content>
      <button mat-stroked-button *ngFor="let prj of data.projects?.list" (click)="select(prj.name)">
        {{ prj.name }} <mat-icon *ngIf="prj.name === data.projects?.current">check</mat-icon>
      </button>
      <div *ngIf="!data.projects?.list?.length" class="empty">No projects available</div>
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
  constructor(
    @Inject(MAT_DIALOG_DATA) public data: any,
    private dialogRef: MatDialogRef<ProjectModalComponent>,
    private codayService: CodayService
  ) {}
  close() {
    this.dialogRef.close()
  }
  select(name: string) {
    this.codayService.sendMessage(`config select-project ${name}`)
    this.close()
  }
}
