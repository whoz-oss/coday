import { Component, inject, OnInit } from '@angular/core'
import { CommonModule } from '@angular/common'
import { FormsModule } from '@angular/forms'
import { MatIconModule } from '@angular/material/icon'
import { MatInputModule } from '@angular/material/input'
import { MatFormFieldModule } from '@angular/material/form-field'
import { MatSlideToggleModule } from '@angular/material/slide-toggle'
import { MatDialog } from '@angular/material/dialog'
import { SchedulerApiService, SchedulerInfo } from '../../core/services/scheduler-api.service'
import { PromptApiService, PromptInfo } from '../../core/services/prompt-api.service'
import { ConfigApiService } from '../../core/services/config-api.service'
import { SchedulerFormComponent, SchedulerFormData } from '../scheduler-form/scheduler-form.component'

@Component({
  selector: 'app-scheduler-manager',
  standalone: true,
  imports: [CommonModule, FormsModule, MatIconModule, MatInputModule, MatFormFieldModule, MatSlideToggleModule],
  templateUrl: './scheduler-manager.component.html',
  styleUrls: ['./scheduler-manager.component.scss'],
})
export class SchedulerManagerComponent implements OnInit {
  private readonly schedulerApi = inject(SchedulerApiService)
  private readonly promptApi = inject(PromptApiService)
  private readonly configApi = inject(ConfigApiService)
  private readonly dialog = inject(MatDialog)

  schedulers: SchedulerInfo[] = []
  filteredSchedulers: SchedulerInfo[] = []
  prompts: PromptInfo[] = []
  searchQuery = ''
  showOnlyMine = true // Default to showing only user's schedulers
  isLoading = false
  errorMessage = ''
  currentUsername = ''

  ngOnInit(): void {
    // Load user config to get username
    this.configApi.getUserConfig().subscribe({
      next: (config: any) => {
        // Store username without normalization (backend stores it as-is)
        this.currentUsername = config.username ?? ''
        console.log('[SCHEDULER_MANAGER] Current username:', this.currentUsername)
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
    this.isLoading = true
    this.schedulerApi.listSchedulers().subscribe({
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
    if (!confirm('Are you sure you want to delete this scheduler?')) return

    this.schedulerApi.deleteScheduler(id).subscribe({
      next: () => {
        this.loadSchedulers()
      },
      error: (error) => {
        console.error('Error deleting scheduler:', error)
        const errorMessage = error?.error?.error ?? error?.message ?? 'Failed to delete scheduler'
        alert(`Failed to delete scheduler: ${errorMessage}`)
      },
    })
  }

  toggleEnabled(scheduler: SchedulerInfo): void {
    const action = scheduler.enabled
      ? this.schedulerApi.disableScheduler(scheduler.id)
      : this.schedulerApi.enableScheduler(scheduler.id)

    action.subscribe({
      next: () => {
        this.loadSchedulers()
      },
      error: (error) => {
        console.error('Error toggling scheduler:', error)
        const errorMessage = error?.error?.error ?? error?.message ?? 'Failed to toggle scheduler'
        alert(`Failed to toggle scheduler: ${errorMessage}`)
      },
    })
  }

  runNow(id: string): void {
    this.schedulerApi.runSchedulerNow(id).subscribe({
      next: (response) => {
        alert(`Scheduler executed successfully! Thread ID: ${response.threadId}`)
        this.loadSchedulers()
      },
      error: (error) => {
        console.error('Error running scheduler:', error)
        // Extract detailed error message from backend
        const errorMessage = error?.error?.error ?? error?.message ?? 'Failed to run scheduler'
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
    const match = RegExp(/^(\d+)(min|h|d|M)$/).exec(interval)
    if (!match) return interval

    const [, value, unit] = match
    if (!value || !unit) return interval

    const units: Record<string, string> = {
      min: 'minute',
      h: 'hour',
      d: 'day',
      M: 'month',
    }
    const unitName = units[unit] ?? unit
    return `Every ${value} ${unitName}${parseInt(value, 10) > 1 ? 's' : ''}`
  }

  /**
   * Normalize username for comparison (replace dots and spaces with underscores)
   */
  private normalizeUsername(username: string): string {
    return username.replace(/[.\s]+/g, '_')
  }

  /**
   * Check if a scheduler belongs to the current user (case-insensitive, normalized comparison)
   */
  private isMyScheduler(scheduler: SchedulerInfo): boolean {
    if (!this.currentUsername) return false
    const normalizedCurrent = this.normalizeUsername(this.currentUsername.toLowerCase())
    const normalizedCreatedBy = this.normalizeUsername(scheduler.createdBy.toLowerCase())
    return normalizedCreatedBy === normalizedCurrent
  }

  /**
   * Filter schedulers based on search query and "mine" toggle
   */
  applyFilter(): void {
    const query = this.searchQuery.toLowerCase().trim()

    // Start with all schedulers or only user's schedulers
    let filtered =
      this.showOnlyMine && this.currentUsername
        ? this.schedulers.filter((s) => this.isMyScheduler(s))
        : [...this.schedulers]

    // Apply search query if present
    if (query) {
      filtered = filtered.filter((scheduler) => {
        // Search in name
        if (scheduler.name.toLowerCase().includes(query)) return true

        // Search in promptId
        if (scheduler.promptId.toLowerCase().includes(query)) return true

        // Search in prompt name
        const promptName = this.getPromptName(scheduler.promptId).toLowerCase()
        if (promptName.includes(query)) return true

        // Search in createdBy
        return scheduler.createdBy.toLowerCase().includes(query)
      })
    }

    this.filteredSchedulers = filtered
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
    // Load full scheduler details before editing
    this.schedulerApi.getScheduler(scheduler.id).subscribe({
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
        const errorMessage = error?.error?.error ?? error?.message ?? 'Failed to load scheduler details'
        alert(`Failed to load scheduler details: ${errorMessage}`)
      },
    })
  }
}
