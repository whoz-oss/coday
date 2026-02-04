import { Component, inject, Inject, OnInit } from '@angular/core'
import { CommonModule } from '@angular/common'
import { FormsModule } from '@angular/forms'
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog'
import { MatButtonModule } from '@angular/material/button'
import { MatInputModule } from '@angular/material/input'
import { MatFormFieldModule } from '@angular/material/form-field'
import { MatIconModule } from '@angular/material/icon'
import { PromptApiService, Prompt } from '../../core/services/prompt-api.service'
import { ProjectStateService } from '../../core/services/project-state.service'

export interface PromptFormData {
  mode: 'create' | 'edit'
  prompt?: Prompt
}

@Component({
  selector: 'app-prompt-form',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatDialogModule,
    MatButtonModule,
    MatInputModule,
    MatFormFieldModule,
    MatIconModule,
  ],
  templateUrl: './prompt-form.component.html',
  styleUrls: ['./prompt-form.component.scss'],
})
export class PromptFormComponent implements OnInit {
  private promptApi = inject(PromptApiService)
  private projectState = inject(ProjectStateService)
  private dialogRef = inject(MatDialogRef<PromptFormComponent>)

  // Form data
  name = ''
  description = ''
  commands: string[] = ['']
  webhookEnabled = false

  // UI state
  isSaving = false
  errorMessage = ''

  // Mode
  isEditMode = false

  constructor(@Inject(MAT_DIALOG_DATA) public data: PromptFormData) {
    this.isEditMode = data.mode === 'edit'
  }

  ngOnInit(): void {
    if (this.isEditMode && this.data.prompt) {
      // Load existing prompt data
      this.name = this.data.prompt.name
      this.description = this.data.prompt.description
      this.commands = [...this.data.prompt.commands]
      this.webhookEnabled = this.data.prompt.webhookEnabled
    }
  }

  /**
   * Add a new command field
   */
  addCommand(): void {
    this.commands.push('')
  }

  /**
   * Remove a command at specific index
   */
  removeCommand(index: number): void {
    if (this.commands.length > 1) {
      this.commands.splice(index, 1)
    }
  }

  /**
   * Track by index for ngFor
   */
  trackByIndex(index: number): number {
    return index
  }

  /**
   * Validate form before submission
   */
  private validateForm(): boolean {
    if (!this.name.trim()) {
      this.errorMessage = 'Name is required'
      return false
    }

    if (!this.description.trim()) {
      this.errorMessage = 'Description is required'
      return false
    }

    // Filter out empty commands
    const validCommands = this.commands.filter((cmd) => cmd.trim())
    if (validCommands.length === 0) {
      this.errorMessage = 'At least one command is required'
      return false
    }

    return true
  }

  /**
   * Save the prompt (create or update)
   */
  save(): void {
    this.errorMessage = ''

    if (!this.validateForm()) {
      return
    }

    const projectName = this.projectState.getSelectedProjectId()
    if (!projectName) {
      this.errorMessage = 'No project selected'
      return
    }

    // Filter out empty commands
    const validCommands = this.commands.filter((cmd) => cmd.trim())

    this.isSaving = true

    if (this.isEditMode && this.data.prompt) {
      // Update existing prompt
      this.promptApi
        .updatePrompt(projectName, this.data.prompt.id, {
          name: this.name.trim(),
          description: this.description.trim(),
          commands: validCommands,
          // Note: webhookEnabled is not updated here (requires CODAY_ADMIN)
        })
        .subscribe({
          next: () => {
            this.isSaving = false
            this.dialogRef.close(true) // Success
          },
          error: (error) => {
            console.error('Error updating prompt:', error)
            this.errorMessage = error?.error?.error || 'Failed to update prompt'
            this.isSaving = false
          },
        })
    } else {
      // Create new prompt
      this.promptApi
        .createPrompt(projectName, {
          name: this.name.trim(),
          description: this.description.trim(),
          commands: validCommands,
          webhookEnabled: this.webhookEnabled,
        })
        .subscribe({
          next: () => {
            this.isSaving = false
            this.dialogRef.close(true) // Success
          },
          error: (error) => {
            console.error('Error creating prompt:', error)
            this.errorMessage = error?.error?.error || 'Failed to create prompt'
            this.isSaving = false
          },
        })
    }
  }

  /**
   * Cancel and close dialog
   */
  cancel(): void {
    this.dialogRef.close(false)
  }
}
