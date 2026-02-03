import { Component, Input, Output, EventEmitter, inject } from '@angular/core'
import { CommonModule } from '@angular/common'
import { Webhook } from '../../core/services/webhook-api.service'
import { MatIcon } from '@angular/material/icon'
import { MatButton, MatIconButton } from '@angular/material/button'
import { MatTableModule } from '@angular/material/table'
import { MatChipsModule } from '@angular/material/chips'
import { MatTooltip } from '@angular/material/tooltip'
import { UserService } from '../../core/services/user.service'

/**
 * Dumb/Presentation Component for displaying webhook list
 *
 * Responsibilities:
 * - Display webhooks in a table
 * - Emit events for user actions
 * - No API calls or business logic
 *
 * Can be used standalone or inside modals
 */
@Component({
  selector: 'app-webhook-list',
  standalone: true,
  imports: [CommonModule, MatIcon, MatButton, MatIconButton, MatTableModule, MatChipsModule, MatTooltip],
  templateUrl: './webhook-list.component.html',
  styleUrl: './webhook-list.component.scss',
})
export class WebhookListComponent {
  private userService = inject(UserService)

  @Input() set webhooks(value: Webhook[]) {
    console.log('[WEBHOOK-LIST] Received webhooks:', value)
    this._webhooks = value
  }
  get webhooks(): Webhook[] {
    return this._webhooks
  }
  private _webhooks: Webhook[] = []

  @Input() isLoading: boolean = false
  @Input() errorMessage: string = ''

  @Output() create = new EventEmitter<void>()
  @Output() edit = new EventEmitter<Webhook>()
  @Output() deleteWebhook = new EventEmitter<Webhook>()
  @Output() copyUrl = new EventEmitter<string>() // Copy webhook URL

  onCreateClick(): void {
    this.create.emit()
  }

  onEditClick(webhook: Webhook): void {
    this.edit.emit(webhook)
  }

  onDeleteClick(webhook: Webhook): void {
    this.deleteWebhook.emit(webhook)
  }

  onCopyUrl(webhook: Webhook): void {
    const url = `${window.location.origin}/api/webhooks/${webhook.uuid}/execute`
    navigator.clipboard
      .writeText(url)
      .then(() => {
        this.copyUrl.emit(url)
      })
      .catch((err) => {
        console.error('Failed to copy URL:', err)
      })
  }

  formatDate(date: Date | string): string {
    const d = typeof date === 'string' ? new Date(date) : date
    return d.toLocaleDateString() + ' ' + d.toLocaleTimeString()
  }

  getCommandTypeLabel(type: 'free' | 'template'): string {
    if (!type) {
      console.warn('[WEBHOOK-LIST] commandType is missing!', type)
      return 'Unknown'
    }
    const label = type === 'free' ? 'Free Form' : 'Template'
    console.log('[WEBHOOK-LIST] getCommandTypeLabel called with:', type, '→', label)
    return label
  }

  /**
   * Check if a webhook belongs to another user
   */
  isOtherUser(webhook: Webhook): boolean {
    const currentUsername = this.userService.getUsername()
    return currentUsername !== null && webhook.createdBy !== currentUsername
  }
}
