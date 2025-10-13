import { Component, Input, Output, EventEmitter, OnChanges, SimpleChanges, inject } from '@angular/core'
import { CommonModule } from '@angular/common'
import { WebhookListComponent } from '../webhook-list/webhook-list.component'
import { WebhookFormComponent } from '../webhook-form/webhook-form.component'
import { 
  WebhookApiService, 
  Webhook, 
  WebhookCreateData, 
  WebhookUpdateData 
} from '../../core/services/webhook-api.service'

/**
 * Smart/Container Component for webhook management
 * 
 * Responsibilities:
 * - Manage state (webhooks list, loading, errors)
 * - Make API calls via WebhookApiService
 * - Coordinate between list and form views
 * - Handle user interactions (create, edit, delete)
 * 
 * This component can be used inside a modal or as a standalone page
 */
@Component({
  selector: 'app-webhook-manager',
  standalone: true,
  imports: [CommonModule, WebhookListComponent, WebhookFormComponent],
  templateUrl: './webhook-manager.component.html',
  styleUrl: './webhook-manager.component.scss'
})
export class WebhookManagerComponent implements OnChanges {
  @Input() isOpen: boolean = false
  @Input() availableProjects: string[] = [] // Passed from parent
  
  @Output() isOpenChange = new EventEmitter<boolean>()
  @Output() close = new EventEmitter<void>()
  
  private webhookApi = inject(WebhookApiService)
  
  // State
  webhooks: Webhook[] = []
  isLoading: boolean = false
  errorMessage: string = ''
  
  // View state
  currentView: 'list' | 'form' = 'list'
  editingWebhook?: Webhook
  isSaving: boolean = false
  
  successMessage: string = ''
  
  ngOnChanges(changes: SimpleChanges): void {
    if (changes['isOpen']) {
      const currentValue = changes['isOpen'].currentValue
      const previousValue = changes['isOpen'].previousValue
      
      // Load webhooks when modal opens
      if (currentValue === true && previousValue === false) {
        this.loadWebhooks()
        this.currentView = 'list'
        this.editingWebhook = undefined
      }
      
      // Clear state when modal closes
      if (currentValue === false && previousValue === true) {
        this.clearState()
      }
    }
  }
  
  private clearState(): void {
    this.webhooks = []
    this.errorMessage = ''
    this.successMessage = ''
    this.currentView = 'list'
    this.editingWebhook = undefined
    this.isLoading = false
    this.isSaving = false
  }
  
  private loadWebhooks(): void {
    this.isLoading = true
    this.errorMessage = ''
    
    this.webhookApi.listWebhooks().subscribe({
      next: (webhooks) => {
        this.webhooks = webhooks
        this.isLoading = false
      },
      error: (error) => {
        console.error('Failed to load webhooks:', error)
        this.errorMessage = 'Failed to load webhooks. Please try again.'
        this.isLoading = false
      }
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
    const confirmed = confirm(`Are you sure you want to delete webhook "${webhook.name}"?\n\nThis action cannot be undone.`)
    
    if (!confirmed) {
      return
    }
    
    this.webhookApi.deleteWebhook(webhook.uuid).subscribe({
      next: () => {
        this.showSuccess(`Webhook "${webhook.name}" deleted successfully`)
        this.loadWebhooks()
      },
      error: (error) => {
        console.error('Failed to delete webhook:', error)
        this.errorMessage = `Failed to delete webhook: ${error.error?.error || 'Unknown error'}`
      }
    })
  }
  
  onCopyUrl(_url: string): void {
    this.showSuccess('Webhook URL copied to clipboard!')
  }
  
  onFormSave(data: WebhookCreateData | WebhookUpdateData): void {
    this.isSaving = true
    this.errorMessage = ''
    
    if (this.editingWebhook) {
      // Update existing webhook
      this.webhookApi.updateWebhook(this.editingWebhook.uuid, data as WebhookUpdateData).subscribe({
        next: (response) => {
          this.isSaving = false
          this.showSuccess(`Webhook "${response.webhook.name}" updated successfully`)
          this.currentView = 'list'
          this.editingWebhook = undefined
          this.loadWebhooks()
        },
        error: (error) => {
          console.error('Failed to update webhook:', error)
          this.errorMessage = `Failed to update webhook: ${error.error?.error || 'Unknown error'}`
          this.isSaving = false
        }
      })
    } else {
      // Create new webhook
      this.webhookApi.createWebhook(data as WebhookCreateData).subscribe({
        next: (webhook) => {
          this.isSaving = false
          this.showSuccess(`Webhook "${webhook.name}" created successfully`)
          this.currentView = 'list'
          this.loadWebhooks()
        },
        error: (error) => {
          console.error('Failed to create webhook:', error)
          this.errorMessage = `Failed to create webhook: ${error.error?.error || 'Unknown error'}`
          this.isSaving = false
        }
      })
    }
  }
  
  onFormCancel(): void {
    this.currentView = 'list'
    this.editingWebhook = undefined
    this.errorMessage = ''
  }
  
  private showSuccess(message: string): void {
    this.successMessage = message
    setTimeout(() => this.successMessage = '', 3000)
  }
  
  onCloseClick(): void {
    this.isOpenChange.emit(false)
    this.close.emit()
  }
  
  onBackdropClick(event: MouseEvent): void {
    if (event.target === event.currentTarget) {
      this.onCloseClick()
    }
  }
}
