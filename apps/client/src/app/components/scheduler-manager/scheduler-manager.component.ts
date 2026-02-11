import { Component, inject, OnInit } from '@angular/core'
import { CommonModule } from '@angular/common'
import { FormsModule } from '@angular/forms'
import { MatIconModule } from '@angular/material/icon'
import { MatInputModule } from '@angular/material/input'
import { MatFormFieldModule } from '@angular/material/form-field'
import { MatDialog } from '@angular/material/dialog'
import { SchedulerApiService, SchedulerInfo } from '../../core/services/scheduler-api.service'
import { PromptApiService, PromptInfo } from '../../core/services/prompt-api.service'
import { ProjectStateService } from '../../core/services/project-state.service'
import { ConfigApiService } from '../../core/services/config-api.service'
import { SchedulerFormComponent, SchedulerFormData } from '../scheduler-form/scheduler-form.component'

@Component({
  selector: 'app-scheduler-manager',
  standalone: true,
  imports: [CommonModule, FormsModule, MatIconModule, MatInputModule, MatFormFieldModule],
  templateUrl: './scheduler-manager.component.html',
  styleUrls: ['./scheduler-manager.component.scss'],
})
export class SchedulerManagerComponent implements OnInit {
  private schedulerApi = inject(SchedulerApiService)
  private promptApi = inject(PromptApiService)
  private projectState = inject(ProjectStateService)
  private configApi = inject(ConfigApiService)
  private dialog = inject(MatDialog)

  schedulers: SchedulerInfo[] = []
  filteredSchedulers: SchedulerInfo[] = []
  prompts: PromptInfo[] = []
  searchQuery = ''
  isLoading = false
  errorMessage = ''
  currentUsername = ''

  ngOnInit(): void {
    // Load user config to get username
    this.configApi.getUserConfig().subscribe({
      next: (config: any) => {
        // Normalize username: replace dots and spaces with underscores
        this.currentUsername = (config.username || '').replace(/[.\s]+/g, '_')
      },
      error: (error) => {
        console.error('[SCHEDULER_MANAGER] Error loading user config:', error)
      },
    })

    this.loadPrompts()
    this.loadSchedulers()
  }

  private loadPrompts(): void {
    this.promptApi.listPrompts().subscribe({
      next: (prompts) => {
        this.prompts = prompts
      },
      error: (error) => {
        console.error('Error loading prompts:', error)
      },
    })
  }

  private loadSchedulers(): void {
    const projectName = this.projectState.getSelectedProjectId()
    if (!projectName) {
      this.errorMessage = 'No project selected'
      return
    }

    this.isLoading = true
    this.schedulerApi.listSchedulers(projectName).subscribe({
      next: (schedulers) => {
        this.schedulers = schedulers
        this.applyFilter()
        this.isLoading = false
      },
      error: (error) => {
        console.error('Error loading schedulers:', error)
        this.errorMessage = 'Failed to load schedulers'
        this.isLoading = false
      },
    })
  }

  deleteScheduler(id: string): void {
    const projectName = this.projectState.getSelectedProjectId()
    if (!projectName) return

    if (!confirm('Are you sure you want to delete this scheduler?')) return

    this.schedulerApi.deleteScheduler(projectName, id).subscribe({
      next: () => {
        this.loadSchedulers()
      },
      error: (error) => {
        console.error('Error deleting scheduler:', error)
        const errorMessage = error?.error?.error || error?.message || 'Failed to delete scheduler'
        alert(`Failed to delete scheduler: ${errorMessage}`)
      },
    })
  }

  toggleEnabled(scheduler: SchedulerInfo): void {
    const projectName = this.projectState.getSelectedProjectId()
    if (!projectName) return

    const action = scheduler.enabled
      ? this.schedulerApi.disableScheduler(projectName, scheduler.id)
      : this.schedulerApi.enableScheduler(projectName, scheduler.id)

    action.subscribe({
      next: () => {
        this.loadSchedulers()
      },
      error: (error) => {
        console.error('Error toggling scheduler:', error)
        const errorMessage = error?.error?.error || error?.message || 'Failed to toggle scheduler'
        alert(`Failed to toggle scheduler: ${errorMessage}`)
      },
    })
  }

  runNow(id: string): void {
    const projectName = this.projectState.getSelectedProjectId()
    if (!projectName) return

    this.schedulerApi.runSchedulerNow(projectName, id).subscribe({
      next: (response) => {
        alert(`Scheduler executed successfully! Thread ID: ${response.threadId}`)
        this.loadSchedulers()
      },
      error: (error) => {
        console.error('Error running scheduler:', error)
        // Extract detailed error message from backend
        const errorMessage = error?.error?.error || error?.message || 'Failed to run scheduler'
        alert(`Failed to run scheduler: ${errorMessage}`)
      },
    })
  }

  formatNextRun(nextRun: string | null | undefined): string {
    if (!nextRun) return 'Expired or completed'
    const date = new Date(nextRun)
    return date.toLocaleString()
  }

  getCurrentUsername(): string {
    return this.currentUsername
  }

  getPromptName(promptId: string): string {
    const prompt = this.prompts.find((p) => p.id === promptId)
    return prompt ? prompt.name : promptId
  }

  formatInterval(interval: string): string {
    // Parse interval like "1h", "30min", "1d", "2M"
    const match = interval.match(/^(\d+)(min|h|d|M)$/)
    if (!match) return interval

    const [, value, unit] = match
    if (!value || !unit) return interval

    const units: Record<string, string> = {
      min: 'minute',
      h: 'hour',
      d: 'day',
      M: 'month',
    }
    const unitName = units[unit] || unit
    return `Every ${value} ${unitName}${parseInt(value, 10) > 1 ? 's' : ''}`
  }

  /**
   * Filter schedulers based on search query
   */
  applyFilter(): void {
    const query = this.searchQuery.toLowerCase().trim()

    if (!query) {
      this.filteredSchedulers = [...this.schedulers]
      return
    }

    this.filteredSchedulers = this.schedulers.filter((scheduler) => {
      // Search in name
      if (scheduler.name.toLowerCase().includes(query)) return true

      // Search in promptId
      if (scheduler.promptId.toLowerCase().includes(query)) return true

      // Search in prompt name
      const promptName = this.getPromptName(scheduler.promptId).toLowerCase()
      if (promptName.includes(query)) return true

      // Search in createdBy
      if (scheduler.createdBy.toLowerCase().includes(query)) return true

      return false
    })
  }

  /**
   * Handle search input changes
   */
  onSearchChange(): void {
    this.applyFilter()
  }

  createScheduler(): void {
    const dialogRef = this.dialog.open<SchedulerFormComponent, SchedulerFormData, boolean>(SchedulerFormComponent, {
      width: '700px',
      maxHeight: '90vh',
      data: {
        mode: 'create',
      },
    })

    dialogRef.afterClosed().subscribe((result) => {
      if (result) {
        // Reload schedulers after successful creation
        this.loadSchedulers()
      }
    })
  }

  editScheduler(scheduler: SchedulerInfo): void {
    const projectName = this.projectState.getSelectedProjectId()
    if (!projectName) return

    // Load full scheduler details before editing
    this.schedulerApi.getScheduler(projectName, scheduler.id).subscribe({
      next: (fullScheduler) => {
        const dialogRef = this.dialog.open<SchedulerFormComponent, SchedulerFormData, boolean>(SchedulerFormComponent, {
          width: '700px',
          maxHeight: '90vh',
          data: {
            mode: 'edit',
            scheduler: fullScheduler,
          },
        })

        dialogRef.afterClosed().subscribe((result) => {
          if (result) {
            // Reload schedulers after successful update
            this.loadSchedulers()
          }
        })
      },
      error: (error) => {
        console.error('Error loading scheduler details:', error)
        const errorMessage = error?.error?.error || error?.message || 'Failed to load scheduler details'
        alert(`Failed to load scheduler details: ${errorMessage}`)
      },
    })
  }
}
