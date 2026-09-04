import { Component, inject, ChangeDetectionStrategy } from '@angular/core'
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
  changeDetection: ChangeDetectionStrategy.Eager,
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
      <button matButton (click)="apply()">Apply</button>
      <button matButton (click)="close()">Cancel</button>
    </mat-dialog-actions>
  `,
})
export class ThemeModalComponent {
  data = inject(MAT_DIALOG_DATA)
  private dialogRef = inject(MatDialogRef<ThemeModalComponent>)
  private themeService = inject(ThemeService)

  selected: string

  constructor() {
    this.selected = this.data.selected
  }

  close() {
    this.dialogRef.close()
  }

  apply() {
    this.themeService.setTheme(this.selected as any)
    this.close()
  }
}
