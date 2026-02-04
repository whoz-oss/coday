import { Component, inject, OnInit } from '@angular/core'
import { CommonModule } from '@angular/common'
import { SchedulerApiService, SchedulerInfo } from '../../core/services/scheduler-api.service'
import { ProjectStateService } from '../../core/services/project-state.service'

@Component({
  selector: 'app-scheduler-manager',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './scheduler-manager.component.html',
  styleUrls: ['./scheduler-manager.component.scss'],
})
export class SchedulerManagerComponent implements OnInit {
  private schedulerApi = inject(SchedulerApiService)
  private projectState = inject(ProjectStateService)

  schedulers: SchedulerInfo[] = []
  isLoading = false
  errorMessage = ''

  ngOnInit(): void {
    this.loadSchedulers()
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
        alert('Failed to delete scheduler')
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
        alert('Failed to toggle scheduler')
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
        alert('Failed to run scheduler')
      },
    })
  }

  formatNextRun(nextRun: string | null | undefined): string {
    if (!nextRun) return 'Expired or completed'
    const date = new Date(nextRun)
    return date.toLocaleString()
  }

  formatInterval(interval: string): string {
    // Parse interval like "1h", "30m", "1d"
    const match = interval.match(/^(\d+)([mhd])$/)
    if (!match) return interval

    const [, value, unit] = match
    if (!value || !unit) return interval

    const units: Record<string, string> = {
      m: 'minute',
      h: 'hour',
      d: 'day',
    }
    const unitName = units[unit] || unit
    return `Every ${value} ${unitName}${parseInt(value, 10) > 1 ? 's' : ''}`
  }
}
