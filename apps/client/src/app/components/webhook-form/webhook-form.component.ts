import { Component, Input, Output, EventEmitter, OnChanges, SimpleChanges } from '@angular/core'
import { CommonModule } from '@angular/common'
import { FormsModule } from '@angular/forms'
import { Webhook, WebhookCreateData, WebhookUpdateData } from '../../core/services/webhook-api.service'
import { MatButton } from '@angular/material/button'

/**
 * Dumb/Presentation Component for webhook form
 *
 * Responsibilities:
 * - Display form fields for webhook data
 * - Validate input locally
 * - Emit form data on save
 * - No API calls or business logic
 *
 * Can be used for both create and edit modes
 */
@Component({
  selector: 'app-webhook-form',
  standalone: true,
  imports: [CommonModule, FormsModule, MatButton],
  templateUrl: './webhook-form.component.html',
  styleUrl: './webhook-form.component.scss',
})
export class WebhookFormComponent implements OnChanges {
  @Input() webhook?: Webhook // If provided, edit mode; otherwise create mode
  @Input() isSaving: boolean = false

  @Output() save = new EventEmitter<WebhookCreateData | WebhookUpdateData>()
  @Output() cancelForm = new EventEmitter<void>()

  // Form fields
  name: string = ''
  commandType: 'free' | 'template' = 'template'
  commands: string = '' // Commands as newline-separated string

  // Validation
  errorMessage: string = ''

  get isEditMode(): boolean {
    return !!this.webhook
  }

  get formTitle(): string {
    return this.isEditMode ? 'Edit Webhook' : 'Create Webhook'
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['webhook'] && this.webhook) {
      // Populate form with webhook data in edit mode
      this.name = this.webhook.name
      this.commandType = this.webhook.commandType
      this.commands = this.webhook.commands?.join('\n') ?? ''
    }
  }

  onCommandTypeChange(): void {
    // Clear commands when switching to free type
    if (this.commandType === 'free') {
      this.commands = ''
    }
  }

  onSave(): void {
    this.errorMessage = ''

    // Validate
    if (!this.name.trim()) {
      this.errorMessage = 'Webhook name is required'
      return
    }

    if (this.commandType === 'template') {
      const commandLines = this.commands
        .trim()
        .split('\n')
        .filter((line) => line.trim())
      if (commandLines.length === 0) {
        this.errorMessage = 'Template webhooks must have at least one command'
        return
      }
    }

    // Build data object
    const commandLines =
      this.commandType === 'template'
        ? this.commands
            .trim()
            .split('\n')
            .filter((line) => line.trim())
        : undefined

    const data: WebhookCreateData | WebhookUpdateData = {
      name: this.name.trim(),
      commandType: this.commandType,
      ...(commandLines && { commands: commandLines }),
    }

    this.save.emit(data)
  }

  onCancel(): void {
    this.cancelForm.emit()
  }
}
