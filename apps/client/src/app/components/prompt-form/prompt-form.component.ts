import { Component, inject, OnInit } from '@angular/core'
import { CommonModule } from '@angular/common'
import { FormsModule } from '@angular/forms'
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog'
import { MatButtonModule } from '@angular/material/button'
import { MatInputModule } from '@angular/material/input'
import { MatFormFieldModule } from '@angular/material/form-field'
import { MatIconModule } from '@angular/material/icon'
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar'
import { PromptApiService, Prompt } from '../../core/services/prompt-api.service'
import { ProjectStateService } from '../../core/services/project-state.service'
import { ConfigApiService } from '../../core/services/config-api.service'

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
    MatSnackBarModule,
  ],
  templateUrl: './prompt-form.component.html',
  styleUrls: ['./prompt-form.component.scss'],
})
export class PromptFormComponent implements OnInit {
  private promptApi = inject(PromptApiService)
  private projectState = inject(ProjectStateService)
  private dialogRef = inject(MatDialogRef<PromptFormComponent>)
  private configApi = inject(ConfigApiService)
  private snackBar = inject(MatSnackBar)

  // Form data
  name = ''
  description = ''
  commands: string[] = ['']
  webhookEnabled = false

  // UI state
  isSaving = false
  errorMessage = ''
  isAdmin = false

  // Mode
  isEditMode = false
  promptId = ''
  data = inject<PromptFormData>(MAT_DIALOG_DATA)

  constructor() {
    this.isEditMode = this.data.mode === 'edit'
  }

  ngOnInit(): void {
    // Load user config to check if admin
    this.configApi.getUserConfig().subscribe({
      next: (config: any) => {
        this.isAdmin = config.groups?.includes('CODAY_ADMIN') ?? false
      },
      error: (error) => {
        console.error('[PROMPT_FORM] Error loading user config:', error)
        this.isAdmin = false
      },
    })

    if (this.isEditMode && this.data.prompt) {
      // Load existing prompt data
      this.promptId = this.data.prompt.id
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
   * Normalize name: replace spaces and common punctuation with hyphens
   */
  normalizeName(): void {
    this.name = this.name
      .replace(/[\s_.,;:!?()[\]{}]+/g, '-') // Replace spaces and punctuation with hyphens
      .replace(/-+/g, '-') // Replace multiple hyphens with single hyphen
      .replace(/^-|-$/g, '') // Remove leading/trailing hyphens
      .toLowerCase()
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
          webhookEnabled: this.webhookEnabled, // Admin can update webhook status
        })
        .subscribe({
          next: () => {
            this.isSaving = false
            this.dialogRef.close(true) // Success
          },
          error: (error) => {
            console.error('Error updating prompt:', error)
            // Extract error message from backend response
            this.errorMessage = error?.error?.error || error?.message || 'Failed to update prompt'
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
            // Extract error message from backend response
            this.errorMessage = error?.error?.error || error?.message || 'Failed to create prompt'
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

  /**
   * Get the webhook URL
   */
  getWebhookUrl(): string {
    const baseUrl = window.location.origin
    return `${baseUrl}/api/webhooks/${this.promptId}/execute`
  }

  /**
   * Copy webhook URL to clipboard
   */
  copyWebhookUrl(): void {
    if (!this.webhookEnabled) {
      this.snackBar.open('Webhook is not enabled', 'Close', { duration: 3000 })
      return
    }

    if (!this.promptId) {
      this.snackBar.open('Prompt must be saved before getting webhook URL', 'Close', { duration: 3000 })
      return
    }

    const webhookUrl = this.getWebhookUrl()

    // Copy to clipboard
    navigator.clipboard
      .writeText(webhookUrl)
      .then(() => {
        this.snackBar.open('Webhook URL copied to clipboard!', 'Close', { duration: 3000 })
      })
      .catch((error) => {
        console.error('Failed to copy webhook URL:', error)
        this.snackBar.open('Failed to copy URL', 'Close', { duration: 3000 })
      })
  }
}
