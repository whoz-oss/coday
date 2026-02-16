import { Component, inject, OnInit } from '@angular/core'
import { CommonModule } from '@angular/common'
import { FormsModule } from '@angular/forms'
import { MatIconModule } from '@angular/material/icon'
import { MatInputModule } from '@angular/material/input'
import { MatFormFieldModule } from '@angular/material/form-field'
import { MatSlideToggleModule } from '@angular/material/slide-toggle'
import { MatDialog } from '@angular/material/dialog'
import { PromptApiService, PromptInfo } from '../../core/services/prompt-api.service'
import { ConfigApiService } from '../../core/services/config-api.service'
import { PromptFormComponent, PromptFormData } from '../prompt-form/prompt-form.component'

@Component({
  selector: 'app-prompt-manager',
  standalone: true,
  imports: [CommonModule, FormsModule, MatIconModule, MatInputModule, MatFormFieldModule, MatSlideToggleModule],
  templateUrl: './prompt-manager.component.html',
  styleUrls: ['./prompt-manager.component.scss'],
})
export class PromptManagerComponent implements OnInit {
  private readonly promptApi = inject(PromptApiService)
  private readonly dialog = inject(MatDialog)
  private readonly configApi = inject(ConfigApiService)

  prompts: PromptInfo[] = []
  filteredPrompts: PromptInfo[] = []
  searchQuery = ''
  showOnlyMine = true // Default to showing only user's prompts
  isLoading = false
  errorMessage = ''
  isAdmin = false
  currentUsername = ''

  ngOnInit(): void {
    // Load user config to check if admin and get username
    this.configApi.getUserConfig().subscribe({
      next: (config: any) => {
        this.isAdmin = config.groups?.includes('CODAY_ADMIN') ?? false
        // Store username without normalization (backend stores it as-is)
        this.currentUsername = config.username ?? ''
        console.log('[PROMPT_MANAGER] Current username:', this.currentUsername)
      },
      error: (error) => {
        console.error('[PROMPT_MANAGER] Error loading user config:', error)
        this.isAdmin = false
      },
    })

    this.loadPrompts()
  }

  private loadPrompts(): void {
    this.isLoading = true
    this.promptApi.listPrompts().subscribe({
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
    if (!confirm('Are you sure you want to delete this prompt?')) return

    this.promptApi.deletePrompt(id).subscribe({
      next: () => {
        this.loadPrompts()
      },
      error: (error) => {
        console.error('Error deleting prompt:', error)
        const errorMessage = error?.error?.error ?? error?.message ?? 'Failed to delete prompt'
        alert(`Failed to delete prompt: ${errorMessage}`)
      },
    })
  }

  toggleWebhook(prompt: PromptInfo): void {
    const action = prompt.webhookEnabled
      ? this.promptApi.disableWebhook(prompt.id)
      : this.promptApi.enableWebhook(prompt.id)

    action.subscribe({
      next: () => {
        this.loadPrompts()
      },
      error: (error) => {
        console.error('Error toggling webhook:', error)
        const errorMessage = error?.error?.error ?? error?.message ?? 'Failed to toggle webhook'
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
   * Normalize username for comparison (replace dots and spaces with underscores)
   */
  private normalizeUsername(username: string): string {
    return username.replace(/[.\s]+/g, '_')
  }

  /**
   * Check if a prompt belongs to the current user (case-insensitive, normalized comparison)
   */
  private isMyPrompt(prompt: PromptInfo): boolean {
    if (!this.currentUsername) return false
    const normalizedCurrent = this.normalizeUsername(this.currentUsername.toLowerCase())
    const normalizedCreatedBy = this.normalizeUsername(prompt.createdBy.toLowerCase())
    return normalizedCreatedBy === normalizedCurrent
  }

  /**
   * Filter prompts based on search query and "mine" toggle
   */
  applyFilter(): void {
    const query = this.searchQuery.toLowerCase().trim()

    // Start with all prompts or only user's prompts
    let filtered =
      this.showOnlyMine && this.currentUsername ? this.prompts.filter((p) => this.isMyPrompt(p)) : [...this.prompts]

    // Apply search query if present
    if (query) {
      filtered = filtered.filter((prompt) => {
        // Search in name
        if (prompt.name.toLowerCase().includes(query)) return true

        // Search in description
        if (prompt.description.toLowerCase().includes(query)) return true

        // Search in createdBy
        return prompt.createdBy.toLowerCase().includes(query)
      })
    }

    this.filteredPrompts = filtered
  }

  /**
   * Handle search input changes
   */
  onSearchChange(): void {
    this.applyFilter()
  }

  editPrompt(prompt: PromptInfo): void {
    // Load full prompt details before editing
    this.promptApi.getPrompt(prompt.id).subscribe({
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
        const errorMessage = error?.error?.error ?? error?.message ?? 'Failed to load prompt details'
        alert(`Failed to load prompt details: ${errorMessage}`)
      },
    })
  }
}
