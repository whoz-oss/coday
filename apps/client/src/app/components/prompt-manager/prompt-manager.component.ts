import { Component, inject, OnInit } from '@angular/core'
import { CommonModule } from '@angular/common'
import { FormsModule } from '@angular/forms'
import { MatIconModule } from '@angular/material/icon'
import { MatInputModule } from '@angular/material/input'
import { MatFormFieldModule } from '@angular/material/form-field'
import { MatDialog } from '@angular/material/dialog'
import { PromptApiService, PromptInfo } from '../../core/services/prompt-api.service'
import { ProjectStateService } from '../../core/services/project-state.service'
import { ConfigApiService } from '../../core/services/config-api.service'
import { PromptFormComponent, PromptFormData } from '../prompt-form/prompt-form.component'

@Component({
  selector: 'app-prompt-manager',
  standalone: true,
  imports: [CommonModule, FormsModule, MatIconModule, MatInputModule, MatFormFieldModule],
  templateUrl: './prompt-manager.component.html',
  styleUrls: ['./prompt-manager.component.scss'],
})
export class PromptManagerComponent implements OnInit {
  private promptApi = inject(PromptApiService)
  private projectState = inject(ProjectStateService)
  private dialog = inject(MatDialog)
  private configApi = inject(ConfigApiService)

  prompts: PromptInfo[] = []
  filteredPrompts: PromptInfo[] = []
  searchQuery = ''
  isLoading = false
  errorMessage = ''
  isAdmin = false
  currentUsername = ''

  ngOnInit(): void {
    // Load user config to check if admin and get username
    this.configApi.getUserConfig().subscribe({
      next: (config: any) => {
        this.isAdmin = config.groups?.includes('CODAY_ADMIN') ?? false
        // Normalize username: replace dots and spaces with underscores
        this.currentUsername = (config.username || '').replace(/[.\s]+/g, '_')
      },
      error: (error) => {
        console.error('[PROMPT_MANAGER] Error loading user config:', error)
        this.isAdmin = false
      },
    })

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
        this.applyFilter()
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
        const errorMessage = error?.error?.error || error?.message || 'Failed to delete prompt'
        alert(`Failed to delete prompt: ${errorMessage}`)
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
        const errorMessage = error?.error?.error || error?.message || 'Failed to toggle webhook'
        alert(`Failed to toggle webhook: ${errorMessage}`)
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

  getCurrentUsername(): string {
    return this.currentUsername
  }

  /**
   * Filter prompts based on search query
   */
  applyFilter(): void {
    const query = this.searchQuery.toLowerCase().trim()

    if (!query) {
      this.filteredPrompts = [...this.prompts]
      return
    }

    this.filteredPrompts = this.prompts.filter((prompt) => {
      // Search in name
      if (prompt.name.toLowerCase().includes(query)) return true

      // Search in description
      if (prompt.description.toLowerCase().includes(query)) return true

      // Search in createdBy
      if (prompt.createdBy.toLowerCase().includes(query)) return true

      return false
    })
  }

  /**
   * Handle search input changes
   */
  onSearchChange(): void {
    this.applyFilter()
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
        const errorMessage = error?.error?.error || error?.message || 'Failed to load prompt details'
        alert(`Failed to load prompt details: ${errorMessage}`)
      },
    })
  }
}
