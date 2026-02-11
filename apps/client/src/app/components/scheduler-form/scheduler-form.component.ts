import { Component, inject, OnInit } from '@angular/core'
import { CommonModule } from '@angular/common'
import { FormsModule } from '@angular/forms'
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog'
import { MatButtonModule } from '@angular/material/button'
import { MatInputModule } from '@angular/material/input'
import { MatFormFieldModule } from '@angular/material/form-field'
import { MatIconModule } from '@angular/material/icon'
import { MatSelectModule } from '@angular/material/select'
import { MatCheckboxModule } from '@angular/material/checkbox'
import { MatButtonToggleModule } from '@angular/material/button-toggle'
import { SchedulerApiService, Scheduler, IntervalSchedule } from '../../core/services/scheduler-api.service'
import { PromptApiService, PromptInfo } from '../../core/services/prompt-api.service'
import { ProjectStateService } from '../../core/services/project-state.service'

export interface SchedulerFormData {
  mode: 'create' | 'edit'
  scheduler?: Scheduler
}

@Component({
  selector: 'app-scheduler-form',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatDialogModule,
    MatButtonModule,
    MatInputModule,
    MatFormFieldModule,
    MatIconModule,
    MatSelectModule,
    MatCheckboxModule,
    MatButtonToggleModule,
  ],
  templateUrl: './scheduler-form.component.html',
  styleUrls: ['./scheduler-form.component.scss'],
})
export class SchedulerFormComponent implements OnInit {
  private schedulerApi = inject(SchedulerApiService)
  private promptApi = inject(PromptApiService)
  private projectState = inject(ProjectStateService)
  private dialogRef = inject(MatDialogRef<SchedulerFormComponent>)

  // Available prompts
  availablePrompts: PromptInfo[] = []
  isLoadingPrompts = false

  // Form data
  name = ''
  promptId = ''
  enabled = true
  startTimestamp = ''
  intervalValue = 1
  intervalUnit: 'min' | 'h' | 'd' | 'M' = 'h'
  daysOfWeek: number[] = []
  endConditionType: 'none' | 'occurrences' | 'endTimestamp' = 'none'
  endOccurrences = 10
  endTimestamp = ''
  parameterMode: 'simple' | 'structured' = 'simple'
  simpleParameter = ''
  parameters: { key: string; value: string }[] = []

  // UI state
  isSaving = false
  errorMessage = ''

  // Mode
  isEditMode = false
  schedulerId = ''
  data = inject<SchedulerFormData>(MAT_DIALOG_DATA)

  // Days of week options
  daysOfWeekOptions = [
    { value: 0, label: 'Sunday' },
    { value: 1, label: 'Monday' },
    { value: 2, label: 'Tuesday' },
    { value: 3, label: 'Wednesday' },
    { value: 4, label: 'Thursday' },
    { value: 5, label: 'Friday' },
    { value: 6, label: 'Saturday' },
  ]

  constructor() {
    this.isEditMode = this.data.mode === 'edit'
  }

  ngOnInit(): void {
    this.loadPrompts()

    // Set default start timestamp to now
    if (!this.isEditMode) {
      this.startTimestamp = this.formatDateForInput(new Date())
    }

    if (this.isEditMode && this.data.scheduler) {
      // Load existing scheduler data
      this.schedulerId = this.data.scheduler.id
      this.name = this.data.scheduler.name
      this.promptId = this.data.scheduler.promptId
      this.enabled = this.data.scheduler.enabled

      // Start timestamp - convert to datetime-local format (YYYY-MM-DDTHH:mm)
      if (this.data.scheduler.schedule.startTimestamp) {
        const date = new Date(this.data.scheduler.schedule.startTimestamp)
        this.startTimestamp = this.formatDateForInput(date)
      }

      // Parse interval
      const match = this.data.scheduler.schedule.interval.match(/^(\d+)(min|h|d|M)$/)
      if (match && match[1] && match[2]) {
        this.intervalValue = parseInt(match[1], 10)
        this.intervalUnit = match[2] as 'min' | 'h' | 'd' | 'M'
      }

      // Days of week
      this.daysOfWeek = this.data.scheduler.schedule.daysOfWeek || []

      // End condition
      if (this.data.scheduler.schedule.endCondition) {
        this.endConditionType = this.data.scheduler.schedule.endCondition.type
        if (this.endConditionType === 'occurrences') {
          this.endOccurrences = this.data.scheduler.schedule.endCondition.value as number
        } else if (this.endConditionType === 'endTimestamp') {
          this.endTimestamp = this.data.scheduler.schedule.endCondition.value as string
        }
      }

      // Parameters - detect if it's a simple string or structured object
      if (this.data.scheduler.parameters) {
        // Check if parameters is a simple string (stored as {PARAMETERS: "value"})
        if (
          typeof this.data.scheduler.parameters === 'object' &&
          Object.keys(this.data.scheduler.parameters).length === 1 &&
          'PARAMETERS' in this.data.scheduler.parameters
        ) {
          this.parameterMode = 'simple'
          this.simpleParameter = String(this.data.scheduler.parameters.PARAMETERS)
        } else {
          this.parameterMode = 'structured'
          this.parameters = Object.entries(this.data.scheduler.parameters).map(([key, value]) => ({
            key,
            value: String(value),
          }))
        }
      }
    }
  }

  /**
   * Load available prompts
   */
  private loadPrompts(): void {
    this.isLoadingPrompts = true
    this.promptApi.listPrompts().subscribe({
      next: (prompts) => {
        this.availablePrompts = prompts
        this.isLoadingPrompts = false
      },
      error: (error) => {
        console.error('Error loading prompts:', error)
        this.errorMessage = 'Failed to load prompts'
        this.isLoadingPrompts = false
      },
    })
  }

  /**
   * Toggle day of week selection
   */
  toggleDayOfWeek(day: number): void {
    const index = this.daysOfWeek.indexOf(day)
    if (index > -1) {
      this.daysOfWeek.splice(index, 1)
    } else {
      this.daysOfWeek.push(day)
    }
    this.daysOfWeek.sort()
  }

  /**
   * Check if day is selected
   */
  isDaySelected(day: number): boolean {
    return this.daysOfWeek.includes(day)
  }

  /**
   * Add a parameter field
   */
  addParameter(): void {
    this.parameters.push({ key: '', value: '' })
  }

  /**
   * Remove a parameter at specific index
   */
  removeParameter(index: number): void {
    this.parameters.splice(index, 1)
  }

  /**
   * Track by index for ngFor
   */
  trackByIndex(index: number): number {
    return index
  }

  /**
   * Format date for datetime-local input (YYYY-MM-DDTHH:mm)
   */
  private formatDateForInput(date: Date): string {
    const year = date.getFullYear()
    const month = String(date.getMonth() + 1).padStart(2, '0')
    const day = String(date.getDate()).padStart(2, '0')
    const hours = String(date.getHours()).padStart(2, '0')
    const minutes = String(date.getMinutes()).padStart(2, '0')
    return `${year}-${month}-${day}T${hours}:${minutes}`
  }

  /**
   * Validate form before submission
   */
  private validateForm(): boolean {
    if (!this.name.trim()) {
      this.errorMessage = 'Name is required'
      return false
    }

    if (!this.promptId) {
      this.errorMessage = 'Prompt is required'
      return false
    }

    // Validate interval based on unit
    if (this.intervalValue < 1) {
      this.errorMessage = 'Interval must be at least 1'
      return false
    }

    switch (this.intervalUnit) {
      case 'min':
        if (this.intervalValue > 59) {
          this.errorMessage = 'Minutes interval must be between 1 and 59 (use hours for longer)'
          return false
        }
        break
      case 'h':
        if (this.intervalValue > 24) {
          this.errorMessage = 'Hours interval must be between 1 and 24 (use days for longer)'
          return false
        }
        break
      case 'd':
        if (this.intervalValue > 31) {
          this.errorMessage = 'Days interval must be between 1 and 31 (use months for longer)'
          return false
        }
        break
      case 'M':
        if (this.intervalValue > 12) {
          this.errorMessage = 'Months interval must be between 1 and 12'
          return false
        }
        break
    }

    if (this.endConditionType === 'occurrences' && this.endOccurrences < 1) {
      this.errorMessage = 'Occurrences must be at least 1'
      return false
    }

    if (this.endConditionType === 'endTimestamp' && !this.endTimestamp) {
      this.errorMessage = 'End date is required'
      return false
    }

    return true
  }

  /**
   * Build schedule object from form data
   */
  private buildSchedule(): IntervalSchedule {
    // Use provided start timestamp or current time
    const startDate = this.startTimestamp ? new Date(this.startTimestamp) : new Date()

    const schedule: IntervalSchedule = {
      startTimestamp: startDate.toISOString(),
      interval: `${this.intervalValue}${this.intervalUnit}`,
    }

    if (this.daysOfWeek.length > 0) {
      schedule.daysOfWeek = [...this.daysOfWeek]
    }

    if (this.endConditionType === 'occurrences') {
      schedule.endCondition = {
        type: 'occurrences',
        value: this.endOccurrences,
      }
    } else if (this.endConditionType === 'endTimestamp') {
      schedule.endCondition = {
        type: 'endTimestamp',
        value: this.endTimestamp,
      }
    }

    return schedule
  }

  /**
   * Build parameters object from form data
   */
  private buildParameters(): Record<string, unknown> | undefined {
    if (this.parameterMode === 'simple') {
      // Simple mode: store as {PARAMETERS: "value"} if not empty
      if (this.simpleParameter.trim()) {
        return { PARAMETERS: this.simpleParameter.trim() }
      }
      return undefined
    }

    // Structured mode
    const validParams = this.parameters.filter((p) => p.key.trim())
    if (validParams.length === 0) return undefined

    const params: Record<string, unknown> = {}
    validParams.forEach((p) => {
      params[p.key] = p.value
    })
    return params
  }

  /**
   * Save the scheduler (create or update)
   */
  save(): void {
    this.errorMessage = ''

    if (!this.validateForm()) {
      return
    }

    const projectName = this.projectState.getSelectedProjectId()
    if (!projectName) {
      this.errorMessage = 'No project selected'
      return
    }

    const schedule = this.buildSchedule()
    const parameters = this.buildParameters()

    this.isSaving = true

    if (this.isEditMode && this.schedulerId) {
      // Update existing scheduler
      this.schedulerApi
        .updateScheduler(projectName, this.schedulerId, {
          name: this.name.trim(),
          enabled: this.enabled,
          promptId: this.promptId,
          schedule,
          parameters,
        })
        .subscribe({
          next: () => {
            this.isSaving = false
            this.dialogRef.close(true) // Success
          },
          error: (error) => {
            console.error('Error updating scheduler:', error)
            // Extract error message from backend response
            this.errorMessage = error?.error?.error || error?.message || 'Failed to update scheduler'
            this.isSaving = false
          },
        })
    } else {
      // Create new scheduler
      this.schedulerApi
        .createScheduler(projectName, {
          name: this.name.trim(),
          promptId: this.promptId,
          schedule,
          parameters,
          enabled: this.enabled,
        })
        .subscribe({
          next: () => {
            this.isSaving = false
            this.dialogRef.close(true) // Success
          },
          error: (error) => {
            console.error('Error creating scheduler:', error)
            // Extract error message from backend response
            this.errorMessage = error?.error?.error || error?.message || 'Failed to create scheduler'
            this.isSaving = false
          },
        })
    }
  }

  /**
   * Cancel and close dialog
   */
  cancel(): void {
    this.dialogRef.close(false)
  }
}
