import { Component, inject, OnInit } from '@angular/core'
import { CommonModule } from '@angular/common'
import { MatDialogRef, MatDialogTitle, MatDialogContent } from '@angular/material/dialog'
import { WebhookListComponent } from '../webhook-list/webhook-list.component'
import { WebhookFormComponent } from '../webhook-form/webhook-form.component'
import {
  WebhookApiService,
  Webhook,
  WebhookCreateData,
  WebhookUpdateData,
} from '../../core/services/webhook-api.service'
import { MatIcon } from '@angular/material/icon'
import { MatButton, MatIconButton } from '@angular/material/button'
import { ProjectStateService } from '../../core/services/project-state.service'

/**
 * Smart/Container Component for webhook management
 * Opened via MatDialog service to manage webhooks
 *
 * Responsibilities:
 * - Manage state (webhooks list, loading, errors)
 * - Make API calls via WebhookApiService
 * - Coordinate between list and form views
 * - Handle user interactions (create, edit, delete)
 *
 * Follows the same pattern as JsonEditorComponent for user/project config
 */
@Component({
  selector: 'app-webhook-manager',
  standalone: true,
  imports: [
    CommonModule,
    WebhookListComponent,
    WebhookFormComponent,
    MatIcon,
    MatButton,
    MatIconButton,
    MatDialogTitle,
    MatDialogContent,
  ],
  templateUrl: './webhook-manager.component.html',
  styleUrl: './webhook-manager.component.scss',
})
export class WebhookManagerComponent implements OnInit {
  private dialogRef = inject(MatDialogRef<WebhookManagerComponent>)
  private webhookApi = inject(WebhookApiService)
  private projectState = inject(ProjectStateService)

  // State
  webhooks: Webhook[] = []
  isLoading: boolean = false
  errorMessage: string = ''
  projectName: string | null = null

  // View state
  currentView: 'list' | 'form' = 'list'
  editingWebhook?: Webhook
  isSaving: boolean = false

  successMessage: string = ''

  ngOnInit(): void {
    // Get current project name
    this.projectName = this.projectState.getSelectedProjectId()

    if (!this.projectName) {
      this.errorMessage = 'No project selected. Please select a project first.'
      return
    }

    this.loadWebhooks()
  }

  /**
   * Handle API errors consistently
   * Extracts error message from different error formats
   */
  private handleError(error: any, defaultMessage: string): string {
    console.error(defaultMessage, error)
    return `${defaultMessage}: ${error.error?.error || error.message || 'Unknown error'}`
  }

  private loadWebhooks(): void {
    if (!this.projectName) {
      this.errorMessage = 'No project selected'
      return
    }

    this.isLoading = true
    this.errorMessage = ''

    this.webhookApi.listWebhooks(this.projectName).subscribe({
      next: (webhooks) => {
        this.webhooks = webhooks
        this.isLoading = false
      },
      error: (error) => {
        this.errorMessage = this.handleError(error, 'Failed to load webhooks')
        this.isLoading = false
      },
    })
  }

  onCreateClick(): void {
    this.currentView = 'form'
    this.editingWebhook = undefined
    this.errorMessage = ''
    this.successMessage = ''
  }

  onEditClick(webhook: Webhook): void {
    this.currentView = 'form'
    this.editingWebhook = webhook
    this.errorMessage = ''
    this.successMessage = ''
  }

  onDeleteClick(webhook: Webhook): void {
    if (!this.projectName) {
      this.errorMessage = 'No project selected'
      return
    }

    const confirmed = confirm(
      `Are you sure you want to delete webhook "${webhook.name}"?\n\nThis action cannot be undone.`
    )

    if (!confirmed) {
      return
    }

    this.webhookApi.deleteWebhook(this.projectName, webhook.uuid).subscribe({
      next: () => {
        this.showSuccess(`Webhook "${webhook.name}" deleted successfully`)
        this.loadWebhooks()
      },
      error: (error) => {
        this.errorMessage = this.handleError(error, 'Failed to delete webhook')
      },
    })
  }

  onCopyUrl(_url: string): void {
    this.showSuccess('Webhook URL copied to clipboard!')
  }

  onFormSave(data: WebhookCreateData | WebhookUpdateData): void {
    if (!this.projectName) {
      this.errorMessage = 'No project selected'
      return
    }

    this.isSaving = true
    this.errorMessage = ''

    if (this.editingWebhook) {
      // Update existing webhook
      this.webhookApi.updateWebhook(this.projectName, this.editingWebhook.uuid, data as WebhookUpdateData).subscribe({
        next: (response) => {
          this.isSaving = false
          this.showSuccess(`Webhook "${response.webhook.name}" updated successfully`)
          this.currentView = 'list'
          this.editingWebhook = undefined
          this.loadWebhooks()
        },
        error: (error) => {
          this.errorMessage = this.handleError(error, 'Failed to update webhook')
          this.isSaving = false
        },
      })
    } else {
      // Create new webhook
      this.webhookApi.createWebhook(this.projectName, data as WebhookCreateData).subscribe({
        next: (webhook) => {
          this.isSaving = false
          this.showSuccess(`Webhook "${webhook.name}" created successfully`)
          this.currentView = 'list'
          this.loadWebhooks()
        },
        error: (error) => {
          this.errorMessage = this.handleError(error, 'Failed to create webhook')
          this.isSaving = false
        },
      })
    }
  }

  onFormCancel(): void {
    this.currentView = 'list'
    this.editingWebhook = undefined
    this.errorMessage = ''
  }

  /**
   * Show success message that auto-dismisses after 3 seconds
   * The 3-second timeout provides enough time for users to read the message
   * without being intrusive or requiring manual dismissal
   */
  private showSuccess(message: string): void {
    this.successMessage = message
    setTimeout(() => (this.successMessage = ''), 3000)
  }

  onCloseClick(): void {
    this.dialogRef.close()
  }

  /**
   * Get the dialog title based on current view
   */
  getTitle(): string {
    if (this.currentView === 'form') {
      return this.editingWebhook ? 'Edit Webhook' : 'Create Webhook'
    }
    return 'Webhook Management'
  }
}
