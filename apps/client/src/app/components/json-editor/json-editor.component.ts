import { Component, inject, Inject } from '@angular/core'
import { CommonModule } from '@angular/common'
import { FormsModule } from '@angular/forms'
import { MatIcon } from '@angular/material/icon'
import { MatButton, MatIconButton } from '@angular/material/button'
import {
  MAT_DIALOG_DATA,
  MatDialogRef,
  MatDialogTitle,
  MatDialogContent,
  MatDialogActions,
} from '@angular/material/dialog'

export type ConfigType = 'user' | 'project'

export interface JsonEditorData {
  configType: ConfigType
  projectName?: string
  initialContent: string
  title?: string
}

/**
 * JSON Editor Dialog Component
 * Opened via MatDialog service with configuration data
 */
@Component({
  selector: 'app-json-editor',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatIcon,
    MatIconButton,
    MatButton,
    MatDialogTitle,
    MatDialogContent,
    MatDialogActions,
  ],
  templateUrl: './json-editor.component.html',
  styleUrl: './json-editor.component.scss',
})
export class JsonEditorComponent {
  private dialogRef = inject(MatDialogRef<JsonEditorComponent>)

  jsonContent: string
  errorMessage = ''
  title: string

  constructor(@Inject(MAT_DIALOG_DATA) public data: JsonEditorData) {
    this.jsonContent = data.initialContent
    this.title = data.title || this.getDefaultTitle(data.configType, data.projectName)
  }

  /**
   * Get default title based on config type
   */
  private getDefaultTitle(configType: ConfigType, projectName?: string): string {
    if (configType === 'user') {
      return 'User Configuration'
    }
    return `Project Configuration: ${projectName || 'Unknown'}`
  }

  /**
   * Validate and return parsed JSON
   */
  onSave(): void {
    this.errorMessage = ''

    // Validate JSON syntax
    let parsedConfig: any
    try {
      parsedConfig = JSON.parse(this.jsonContent)
    } catch (error) {
      this.errorMessage = `Invalid JSON syntax: ${error instanceof Error ? error.message : 'Unknown error'}`
      return
    }

    // Close dialog and return parsed JSON
    this.dialogRef.close(parsedConfig)
  }

  /**
   * Cancel and close dialog
   */
  onCancel(): void {
    this.dialogRef.close()
  }
}
