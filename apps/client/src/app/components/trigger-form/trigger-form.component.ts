import { Component, Input, Output, EventEmitter, OnChanges, SimpleChanges } from '@angular/core'
import { CommonModule } from '@angular/common'
import { FormsModule } from '@angular/forms'
import {
  Trigger,
  TriggerCreateData,
  TriggerUpdateData,
  IntervalSchedule,
} from '../../core/services/trigger-api.service'
import { Webhook } from '../../core/services/webhook-api.service'
import { MatButton } from '@angular/material/button'
import { MatFormFieldModule } from '@angular/material/form-field'
import { MatInputModule } from '@angular/material/input'
import { MatSelectModule } from '@angular/material/select'
import { MatCheckboxModule } from '@angular/material/checkbox'
import { MatRadioModule } from '@angular/material/radio'
import { MatIcon } from '@angular/material/icon'

/**
 * Dumb/Presentation Component for trigger form
 *
 * Responsibilities:
 * - Display form fields for trigger data
 * - Validate input locally
 * - Emit form data on save
 * - No API calls or business logic
 *
 * Can be used for both create and edit modes
 */
@Component({
  selector: 'app-trigger-form',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButton,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatCheckboxModule,
    MatRadioModule,
    MatIcon,
  ],
  templateUrl: './trigger-form.component.html',
  styleUrl: './trigger-form.component.scss',
})
export class TriggerFormComponent implements OnChanges {
  @Input() trigger?: Trigger
  @Input() isSaving: boolean = false
  @Input() webhooks: Webhook[] = []

  @Output() save = new EventEmitter<TriggerCreateData | TriggerUpdateData>()
  @Output() cancelForm = new EventEmitter<void>()

  // Form fields
  name: string = ''
  webhookUuid: string = ''
  enabled: boolean = true

  // Schedule fields
  startDate: string = '' // YYYY-MM-DD
  startTime: string = '' // HH:mm
  intervalValue: number = 1
  intervalUnit: string = 'h'
  daysOfWeek: boolean[] = [false, false, false, false, false, false, false] // Sun-Sat
  endConditionType: string = 'none' // 'none' | 'occurrences' | 'endTimestamp'
  endOccurrences: number = 10
  endDate: string = ''
  endTime: string = ''

  // Parameters (JSON)
  parametersJson: string = ''

  // Validation
  errorMessage: string = ''

  // Day names for checkbox labels
  dayNames = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday']

  get isEditMode(): boolean {
    return !!this.trigger
  }

  get formTitle(): string {
    return this.isEditMode ? 'Edit Trigger' : 'Create Trigger'
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['trigger'] && this.trigger) {
      this.populateForm()
    } else if (changes['trigger'] && !this.trigger) {
      this.resetForm()
    }
  }

  /**
   * Populate form with trigger data in edit mode
   */
  private populateForm(): void {
    if (!this.trigger) return

    this.name = this.trigger.name
    this.webhookUuid = this.trigger.webhookUuid
    this.enabled = this.trigger.enabled

    // Parse schedule
    const schedule = this.trigger.schedule

    // Start timestamp (convert UTC to local)
    const startDate = new Date(schedule.startTimestamp)
    // Format date as YYYY-MM-DD in local timezone
    this.startDate = this.formatDateForInput(startDate)
    // Format time as HH:mm in local timezone
    this.startTime = this.formatTimeForInput(startDate)

    // Interval
    const match = schedule.interval.match(/^(\d+)(min|h|d|M)$/)
    if (match) {
      this.intervalValue = parseInt(match[1] || '1')
      this.intervalUnit = match[2] || 'h'
    }

    // Days of week
    if (schedule.daysOfWeek) {
      this.daysOfWeek = [false, false, false, false, false, false, false]
      schedule.daysOfWeek.forEach((day) => {
        if (day >= 0 && day <= 6) {
          this.daysOfWeek[day] = true
        }
      })
    } else {
      this.daysOfWeek = [false, false, false, false, false, false, false]
    }

    // End condition
    if (schedule.endCondition) {
      this.endConditionType = schedule.endCondition.type
      if (schedule.endCondition.type === 'occurrences') {
        this.endOccurrences = schedule.endCondition.value as number
      } else if (schedule.endCondition.type === 'endTimestamp') {
        const endDate = new Date(schedule.endCondition.value as string)
        // Format date and time in local timezone
        this.endDate = this.formatDateForInput(endDate)
        this.endTime = this.formatTimeForInput(endDate)
      }
    } else {
      this.endConditionType = 'none'
    }

    // Parameters
    if (this.trigger.parameters) {
      this.parametersJson = JSON.stringify(this.trigger.parameters, null, 2)
    } else {
      this.parametersJson = ''
    }
  }

  /**
   * Reset form to default values
   */
  private resetForm(): void {
    this.name = ''
    this.webhookUuid = ''
    this.enabled = true

    // Set default start to now + 1 hour (in local timezone)
    const now = new Date()
    now.setHours(now.getHours() + 1)
    now.setMinutes(0)
    now.setSeconds(0)
    now.setMilliseconds(0)
    this.startDate = this.formatDateForInput(now)
    this.startTime = this.formatTimeForInput(now)

    this.intervalValue = 1
    this.intervalUnit = 'h'
    this.daysOfWeek = [false, false, false, false, false, false, false]
    this.endConditionType = 'none'
    this.endOccurrences = 10
    this.endDate = ''
    this.endTime = ''
    this.parametersJson = ''
    this.errorMessage = ''
  }

  /**
   * Show/hide days of week based on interval
   * Days of week constraint makes sense for:
   * - Minutes: run every X minutes but only on specific days
   * - Hours: run every X hours but only on specific days
   * - Days: run every X days but only on specific days of week
   * Not relevant for months (too long interval)
   */
  shouldShowDaysOfWeek(): boolean {
    return this.intervalUnit === 'min' || this.intervalUnit === 'h' || this.intervalUnit === 'd'
  }

  onSave(): void {
    this.errorMessage = ''

    // Validate
    const validation = this.validateForm()
    if (validation !== null) {
      this.errorMessage = validation
      return
    }

    // Build schedule
    const schedule = this.buildSchedule()

    // Build parameters
    let parameters: Record<string, unknown> | undefined = undefined
    if (this.parametersJson.trim()) {
      try {
        parameters = JSON.parse(this.parametersJson)
      } catch (error) {
        this.errorMessage = 'Invalid JSON in parameters field'
        return
      }
    }

    // Build data object
    const data: TriggerCreateData | TriggerUpdateData = {
      name: this.name.trim(),
      webhookUuid: this.webhookUuid,
      schedule,
      enabled: this.enabled,
      ...(parameters && { parameters }),
    }

    this.save.emit(data)
  }

  /**
   * Validate form fields
   */
  private validateForm(): string | null {
    if (!this.name.trim()) {
      return 'Trigger name is required'
    }

    if (!this.webhookUuid) {
      return 'Webhook is required'
    }

    if (!this.startDate || !this.startTime) {
      return 'Start date and time are required'
    }

    if (!this.intervalValue || this.intervalValue < 1) {
      return 'Interval must be at least 1'
    }

    if (!this.intervalUnit) {
      return 'Interval unit is required'
    }

    // Validate interval ranges
    if (this.intervalUnit === 'min' && (this.intervalValue < 1 || this.intervalValue > 59)) {
      return 'Minutes must be between 1 and 59'
    }
    if (this.intervalUnit === 'h' && (this.intervalValue < 1 || this.intervalValue > 24)) {
      return 'Hours must be between 1 and 24'
    }
    if (this.intervalUnit === 'd' && (this.intervalValue < 1 || this.intervalValue > 31)) {
      return 'Days must be between 1 and 31'
    }
    if (this.intervalUnit === 'M' && (this.intervalValue < 1 || this.intervalValue > 12)) {
      return 'Months must be between 1 and 12'
    }

    // Validate end condition
    if (this.endConditionType === 'occurrences') {
      if (!this.endOccurrences || this.endOccurrences < 1) {
        return 'Occurrences must be at least 1'
      }
    } else if (this.endConditionType === 'endTimestamp') {
      if (!this.endDate || !this.endTime) {
        return 'End date and time are required'
      }

      // Check end is after start
      const start = new Date(`${this.startDate}T${this.startTime}`)
      const end = new Date(`${this.endDate}T${this.endTime}`)
      if (end <= start) {
        return 'End date must be after start date'
      }
    }

    return null
  }

  /**
   * Format a Date object as YYYY-MM-DD string in local timezone
   */
  private formatDateForInput(date: Date): string {
    const year = date.getFullYear()
    const month = String(date.getMonth() + 1).padStart(2, '0')
    const day = String(date.getDate()).padStart(2, '0')
    return `${year}-${month}-${day}`
  }

  /**
   * Format a Date object as HH:mm string in local timezone
   */
  private formatTimeForInput(date: Date): string {
    const hours = String(date.getHours()).padStart(2, '0')
    const minutes = String(date.getMinutes()).padStart(2, '0')
    return `${hours}:${minutes}`
  }

  /**
   * Build IntervalSchedule from form fields
   */
  private buildSchedule(): IntervalSchedule {
    // Build start timestamp (convert local to UTC)
    const startLocal = new Date(`${this.startDate}T${this.startTime}`)
    const startTimestamp = startLocal.toISOString()

    // Build interval string
    const interval = `${this.intervalValue}${this.intervalUnit}`

    // Build schedule object
    const schedule: IntervalSchedule = {
      startTimestamp,
      interval,
    }

    // Add days of week if any selected
    const selectedDays = this.daysOfWeek.map((selected, index) => (selected ? index : -1)).filter((day) => day !== -1)

    if (selectedDays.length > 0 && this.shouldShowDaysOfWeek()) {
      schedule.daysOfWeek = selectedDays
    }

    // Add end condition if specified
    if (this.endConditionType === 'occurrences') {
      schedule.endCondition = {
        type: 'occurrences',
        value: this.endOccurrences,
      }
    } else if (this.endConditionType === 'endTimestamp') {
      const endLocal = new Date(`${this.endDate}T${this.endTime}`)
      schedule.endCondition = {
        type: 'endTimestamp',
        value: endLocal.toISOString(),
      }
    }

    return schedule
  }

  onCancel(): void {
    this.cancelForm.emit()
  }

  /**
   * Get webhook name for display
   */
  getWebhookName(uuid: string): string {
    const webhook = this.webhooks.find((w) => w.uuid === uuid)
    return webhook?.name || uuid
  }
}
