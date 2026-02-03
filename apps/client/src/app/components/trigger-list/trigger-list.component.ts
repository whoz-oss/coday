import { Component, Input, Output, EventEmitter, inject } from '@angular/core'
import { CommonModule } from '@angular/common'
import { Trigger, IntervalSchedule } from '../../core/services/trigger-api.service'
import { Webhook } from '../../core/services/webhook-api.service'
import { MatIcon } from '@angular/material/icon'
import { MatButton, MatIconButton } from '@angular/material/button'
import { MatTooltip } from '@angular/material/tooltip'
import { MatChipsModule } from '@angular/material/chips'
import { MatTableModule } from '@angular/material/table'
import { UserService } from '../../core/services/user.service'

/**
 * Dumb/Presentation Component for displaying trigger list
 *
 * Responsibilities:
 * - Display triggers in a table
 * - Emit events for user actions
 * - No API calls or business logic
 *
 * Can be used standalone or inside modals
 */
@Component({
  selector: 'app-trigger-list',
  standalone: true,
  imports: [CommonModule, MatIcon, MatButton, MatIconButton, MatTooltip, MatChipsModule, MatTableModule],
  templateUrl: './trigger-list.component.html',
  styleUrl: './trigger-list.component.scss',
})
export class TriggerListComponent {
  private userService = inject(UserService)

  @Input() triggers: Trigger[] = []
  @Input() webhooks: Webhook[] = []
  @Input() isLoading: boolean = false
  @Input() errorMessage: string = ''

  @Output() create = new EventEmitter<void>()
  @Output() edit = new EventEmitter<Trigger>()
  @Output() deleteTrigger = new EventEmitter<Trigger>()
  @Output() toggleEnabled = new EventEmitter<Trigger>()
  @Output() runNow = new EventEmitter<Trigger>()

  onCreateClick(): void {
    this.create.emit()
  }

  onEditClick(trigger: Trigger): void {
    this.edit.emit(trigger)
  }

  onDeleteClick(trigger: Trigger): void {
    this.deleteTrigger.emit(trigger)
  }

  onToggleEnabled(trigger: Trigger): void {
    this.toggleEnabled.emit(trigger)
  }

  onRunNow(trigger: Trigger): void {
    this.runNow.emit(trigger)
  }

  /**
   * Get webhook name from UUID
   */
  getWebhookName(webhookUuid: string): string {
    const webhook = this.webhooks.find((w) => w.uuid === webhookUuid)
    return webhook?.name || webhookUuid
  }

  /**
   * Format schedule to human-readable string
   * Examples:
   * - "Every 2 minutes"
   * - "Every 5 hours"
   * - "Every 2 days on Mon, Wed, Fri"
   * - "Every 1 month"
   */
  formatSchedule(schedule: IntervalSchedule): string {
    const match = schedule.interval.match(/^(\d+)(min|h|d|M)$/)
    if (!match || !match[1] || !match[2]) return schedule.interval

    const value = match[1]
    const unit = match[2]
    const units: Record<string, string> = { min: 'minute', h: 'hour', d: 'day', M: 'month' }
    const unitName = units[unit] || unit
    const plural = parseInt(value, 10) > 1 ? 's' : ''

    let result = `Every ${value} ${unitName}${plural}`

    if (schedule.daysOfWeek && schedule.daysOfWeek.length > 0) {
      const dayNames = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']
      const days = schedule.daysOfWeek.map((d) => dayNames[d]).join(', ')
      result += ` on ${days}`
    }

    return result
  }

  /**
   * Format date to localized string
   */
  formatDate(date: string | undefined): string {
    if (!date) return 'Never'
    const d = new Date(date)
    return d.toLocaleDateString() + ' ' + d.toLocaleTimeString()
  }

  /**
   * Get tooltip for next run
   */
  getNextRunTooltip(trigger: Trigger): string {
    if (trigger.nextRun === null) {
      return 'No more scheduled runs'
    }
    if (!trigger.nextRun) {
      return 'Calculating next run...'
    }
    return 'Next scheduled execution'
  }

  /**
   * Get display text for next run
   */
  getNextRunDisplay(trigger: Trigger): string {
    if (trigger.nextRun === null) {
      return 'No more runs'
    }
    if (!trigger.nextRun) {
      return '—'
    }
    return this.formatDate(trigger.nextRun)
  }

  /**
   * Check if a trigger belongs to another user
   */
  isOtherUser(trigger: Trigger): boolean {
    const currentUsername = this.userService.getUsername()
    return currentUsername !== null && trigger.createdBy !== currentUsername
  }
}
