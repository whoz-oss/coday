import { Component, inject, OnInit } from '@angular/core'
import { CommonModule } from '@angular/common'
import { MatIconModule } from '@angular/material/icon'
import { MatDialog } from '@angular/material/dialog'
import { PromptApiService, PromptInfo } from '../../core/services/prompt-api.service'
import { ProjectStateService } from '../../core/services/project-state.service'
import { PromptFormComponent, PromptFormData } from '../prompt-form/prompt-form.component'

@Component({
  selector: 'app-prompt-manager',
  standalone: true,
  imports: [CommonModule, MatIconModule],
  templateUrl: './prompt-manager.component.html',
  styleUrls: ['./prompt-manager.component.scss'],
})
export class PromptManagerComponent implements OnInit {
  private promptApi = inject(PromptApiService)
  private projectState = inject(ProjectStateService)
  private dialog = inject(MatDialog)

  prompts: PromptInfo[] = []
  isLoading = false
  errorMessage = ''

  ngOnInit(): void {
    this.loadPrompts()
  }

  private loadPrompts(): void {
    const projectName = this.projectState.getSelectedProjectId()
    if (!projectName) {
      this.errorMessage = 'No project selected'
      return
    }

    this.isLoading = true
    this.promptApi.listPrompts(projectName).subscribe({
      next: (prompts) => {
        this.prompts = prompts
        this.isLoading = false
      },
      error: (error) => {
        console.error('Error loading prompts:', error)
        this.errorMessage = 'Failed to load prompts'
        this.isLoading = false
      },
    })
  }

  deletePrompt(id: string): void {
    const projectName = this.projectState.getSelectedProjectId()
    if (!projectName) return

    if (!confirm('Are you sure you want to delete this prompt?')) return

    this.promptApi.deletePrompt(projectName, id).subscribe({
      next: () => {
        this.loadPrompts()
      },
      error: (error) => {
        console.error('Error deleting prompt:', error)
        alert('Failed to delete prompt')
      },
    })
  }

  toggleWebhook(prompt: PromptInfo): void {
    const projectName = this.projectState.getSelectedProjectId()
    if (!projectName) return

    const action = prompt.webhookEnabled
      ? this.promptApi.disableWebhook(projectName, prompt.id)
      : this.promptApi.enableWebhook(projectName, prompt.id)

    action.subscribe({
      next: () => {
        this.loadPrompts()
      },
      error: (error) => {
        console.error('Error toggling webhook:', error)
        alert('Failed to toggle webhook. You may need CODAY_ADMIN permissions.')
      },
    })
  }

  createPrompt(): void {
    const dialogRef = this.dialog.open<PromptFormComponent, PromptFormData, boolean>(PromptFormComponent, {
      width: '700px',
      maxHeight: '90vh',
      data: {
        mode: 'create',
      },
    })

    dialogRef.afterClosed().subscribe((result) => {
      if (result) {
        // Reload prompts after successful creation
        this.loadPrompts()
      }
    })
  }

  editPrompt(prompt: PromptInfo): void {
    const projectName = this.projectState.getSelectedProjectId()
    if (!projectName) return

    // Load full prompt details before editing
    this.promptApi.getPrompt(projectName, prompt.id).subscribe({
      next: (fullPrompt) => {
        const dialogRef = this.dialog.open<PromptFormComponent, PromptFormData, boolean>(PromptFormComponent, {
          width: '700px',
          maxHeight: '90vh',
          data: {
            mode: 'edit',
            prompt: fullPrompt,
          },
        })

        dialogRef.afterClosed().subscribe((result) => {
          if (result) {
            // Reload prompts after successful update
            this.loadPrompts()
          }
        })
      },
      error: (error) => {
        console.error('Error loading prompt details:', error)
        alert('Failed to load prompt details')
      },
    })
  }
}
