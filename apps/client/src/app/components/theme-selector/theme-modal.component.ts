import { Component, Inject } from '@angular/core'
import { FormsModule } from '@angular/forms'
import {
  MAT_DIALOG_DATA,
  MatDialogActions,
  MatDialogContent,
  MatDialogRef,
  MatDialogTitle,
} from '@angular/material/dialog'
import { MatButtonModule } from '@angular/material/button'
import { MatRadioModule } from '@angular/material/radio'
import { ThemeService } from '../../core/services/theme.service'

@Component({
  selector: 'app-theme-modal',
  standalone: true,
  imports: [FormsModule, MatDialogTitle, MatDialogContent, MatDialogActions, MatButtonModule, MatRadioModule],
  template: `
    <h2 mat-dialog-title>Theme</h2>
    <mat-dialog-content>
      <mat-radio-group [(ngModel)]="selected">
        <mat-radio-button value="light">☀️ Light</mat-radio-button>
        <mat-radio-button value="dark">🌙 Dark</mat-radio-button>
        <mat-radio-button value="system">💻 System</mat-radio-button>
      </mat-radio-group>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="apply()">Apply</button>
      <button mat-button (click)="close()">Cancel</button>
    </mat-dialog-actions>
  `,
})
export class ThemeModalComponent {
  selected: string
  constructor(
    @Inject(MAT_DIALOG_DATA) public data: any,
    private dialogRef: MatDialogRef<ThemeModalComponent>,
    private themeService: ThemeService
  ) {
    this.selected = data.selected
  }
  close() {
    this.dialogRef.close()
  }
  apply() {
    this.themeService.setTheme(this.selected as any)
    this.close()
  }
}
