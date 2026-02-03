import { Component, inject, OnInit } from '@angular/core'
import { CommonModule } from '@angular/common'
import { MatDialogRef, MatDialogTitle, MatDialogContent } from '@angular/material/dialog'
import { TriggerListComponent } from '../trigger-list/trigger-list.component'
import { TriggerFormComponent } from '../trigger-form/trigger-form.component'
import {
  TriggerApiService,
  Trigger,
  TriggerCreateData,
  TriggerUpdateData,
} from '../../core/services/trigger-api.service'
import { WebhookApiService, Webhook } from '../../core/services/webhook-api.service'
import { ProjectStateService } from '../../core/services/project-state.service'
import { MatIcon } from '@angular/material/icon'
import { MatIconButton } from '@angular/material/button'
import { take } from 'rxjs'

/**
 * Smart/Container Component for trigger management
 * Opened via MatDialog service to manage scheduled tasks
 *
 * Responsibilities:
 * - Manage state (triggers list, webhooks list, loading, errors)
 * - Make API calls via TriggerApiService and WebhookApiService
 * - Coordinate between list and form views
 * - Handle user interactions (create, edit, delete, enable/disable, run-now)
 *
 * Follows the same pattern as WebhookManagerComponent
 */
@Component({
  selector: 'app-trigger-manager',
  standalone: true,
  imports: [
    CommonModule,
    TriggerListComponent,
    TriggerFormComponent,
    MatIcon,
    MatIconButton,
    MatDialogTitle,
    MatDialogContent,
  ],
  templateUrl: './trigger-manager.component.html',
  styleUrl: './trigger-manager.component.scss',
})
export class TriggerManagerComponent implements OnInit {
  private dialogRef = inject(MatDialogRef<TriggerManagerComponent>)
  private triggerApi = inject(TriggerApiService)
  private webhookApi = inject(WebhookApiService)
  private projectState = inject(ProjectStateService)

  // State
  projectName: string = ''
  triggers: Trigger[] = []
  webhooks: Webhook[] = []
  isLoading: boolean = false
  errorMessage: string = ''
  successMessage: string = ''

  // View state
  currentView: 'list' | 'form' = 'list'
  editingTrigger?: Trigger
  isSaving: boolean = false

  ngOnInit(): void {
    // Get current project
    this.projectState.selectedProject$.pipe(take(1)).subscribe((project) => {
      if (!project) {
        this.errorMessage = 'No project selected. Please select a project first.'
        return
      }
      this.projectName = project.name
      this.loadData()
    })
  }

  /**
   * Load triggers and webhooks in parallel
   */
  private loadData(): void {
    this.loadTriggers()
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

  private loadTriggers(): void {
    this.isLoading = true
    this.errorMessage = ''

    this.triggerApi.listTriggers(this.projectName).subscribe({
      next: (triggers) => {
        this.triggers = triggers
        this.isLoading = false
      },
      error: (error) => {
        this.errorMessage = this.handleError(error, 'Failed to load triggers')
        this.isLoading = false
      },
    })
  }

  private loadWebhooks(): void {
    this.webhookApi.listWebhooks(this.projectName).subscribe({
      next: (webhooks) => {
        this.webhooks = webhooks
      },
      error: (error) => {
        console.error('Failed to load webhooks:', error)
        // Don't show error to user, webhooks are needed for form but not critical for list view
      },
    })
  }

  onCreateClick(): void {
    if (this.webhooks.length === 0) {
      this.errorMessage = 'No webhooks available. Please create a webhook first.'
      return
    }
    this.currentView = 'form'
    this.editingTrigger = undefined
    this.errorMessage = ''
    this.successMessage = ''
  }

  onEditClick(trigger: Trigger): void {
    this.currentView = 'form'
    this.editingTrigger = trigger
    this.errorMessage = ''
    this.successMessage = ''
  }

  onDeleteClick(trigger: Trigger): void {
    const confirmed = confirm(
      `Are you sure you want to delete trigger "${trigger.name}"?\n\nThis action cannot be undone.`
    )

    if (!confirmed) {
      return
    }

    this.triggerApi.deleteTrigger(this.projectName, trigger.id).subscribe({
      next: () => {
        this.showSuccess(`Trigger "${trigger.name}" deleted successfully`)
        this.loadTriggers()
      },
      error: (error) => {
        this.errorMessage = this.handleError(error, 'Failed to delete trigger')
      },
    })
  }

  onToggleEnabled(trigger: Trigger): void {
    const action = trigger.enabled ? 'disable' : 'enable'
    const apiCall = trigger.enabled
      ? this.triggerApi.disableTrigger(this.projectName, trigger.id)
      : this.triggerApi.enableTrigger(this.projectName, trigger.id)

    apiCall.subscribe({
      next: () => {
        this.showSuccess(`Trigger "${trigger.name}" ${action}d`)
        this.loadTriggers()
      },
      error: (error) => {
        this.errorMessage = this.handleError(error, `Failed to ${action} trigger`)
      },
    })
  }

  onRunNow(trigger: Trigger): void {
    const confirmed = confirm(
      `Run trigger "${trigger.name}" now?\n\nThis will execute the webhook immediately, creating a new thread.`
    )

    if (!confirmed) {
      return
    }

    this.triggerApi.runTriggerNow(this.projectName, trigger.id).subscribe({
      next: (response) => {
        this.showSuccess(`Trigger executed successfully!\nThread ID: ${response.threadId}`)
        this.loadTriggers() // Reload to update lastRun
      },
      error: (error) => {
        this.errorMessage = this.handleError(error, 'Failed to run trigger')
      },
    })
  }

  onFormSave(data: TriggerCreateData | TriggerUpdateData): void {
    this.isSaving = true
    this.errorMessage = ''

    if (this.editingTrigger) {
      // Update existing trigger
      this.triggerApi.updateTrigger(this.projectName, this.editingTrigger.id, data as TriggerUpdateData).subscribe({
        next: (trigger) => {
          this.isSaving = false
          this.showSuccess(`Trigger "${trigger.name}" updated successfully`)
          this.currentView = 'list'
          this.editingTrigger = undefined
          this.loadTriggers()
        },
        error: (error) => {
          this.errorMessage = this.handleError(error, 'Failed to update trigger')
          this.isSaving = false
        },
      })
    } else {
      // Create new trigger
      this.triggerApi.createTrigger(this.projectName, data as TriggerCreateData).subscribe({
        next: (trigger) => {
          this.isSaving = false
          this.showSuccess(`Trigger "${trigger.name}" created successfully`)
          this.currentView = 'list'
          this.loadTriggers()
        },
        error: (error) => {
          this.errorMessage = this.handleError(error, 'Failed to create trigger')
          this.isSaving = false
        },
      })
    }
  }

  onFormCancel(): void {
    this.currentView = 'list'
    this.editingTrigger = undefined
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
      return this.editingTrigger ? 'Edit Trigger' : 'Create Trigger'
    }
    return 'Scheduler'
  }
}
