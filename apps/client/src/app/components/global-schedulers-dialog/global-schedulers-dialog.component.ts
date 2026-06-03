import { ChangeDetectionStrategy, Component, inject, OnInit, signal, computed } from '@angular/core'
import { FormsModule } from '@angular/forms'
import { MatButtonModule } from '@angular/material/button'
import { MatDialogModule, MatDialogRef, MatDialog } from '@angular/material/dialog'
import { MatIconModule } from '@angular/material/icon'
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner'
import { MatSelectModule } from '@angular/material/select'
import { MatTooltipModule } from '@angular/material/tooltip'
import { MatSlideToggleModule } from '@angular/material/slide-toggle'
import { forkJoin, of } from 'rxjs'
import { catchError } from 'rxjs/operators'
import { ProjectApiService } from '../../core/services/project-api.service'
import { SchedulerApiService, SchedulerInfo } from '../../core/services/scheduler-api.service'
import {
  QuickSchedulerDialogComponent,
  QuickSchedulerDialogData,
} from '../quick-scheduler-dialog/quick-scheduler-dialog.component'

interface SchedulerWithProject extends SchedulerInfo {
  projectName: string
}

/**
 * GlobalSchedulersDialogComponent — home-page overview of all schedulers across all projects.
 *
 * Shows schedulers grouped or filtered by project, with enable/disable and run-now actions.
 */
@Component({
  selector: 'app-global-schedulers-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    MatDialogModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatSelectModule,
    MatTooltipModule,
    MatSlideToggleModule,
  ],
  templateUrl: './global-schedulers-dialog.component.html',
  styleUrl: './global-schedulers-dialog.component.scss',
})
export class GlobalSchedulersDialogComponent implements OnInit {
  private readonly projectApi = inject(ProjectApiService)
  private readonly schedulerApi = inject(SchedulerApiService)
  private readonly dialog = inject(MatDialog)
  private readonly dialogRef = inject(MatDialogRef<GlobalSchedulersDialogComponent>)

  protected readonly allSchedulers = signal<SchedulerWithProject[]>([])
  protected readonly isLoading = signal(true)
  protected readonly errorMessage = signal('')
  protected readonly activeProject = signal<string | null>(null)
  protected readonly projects = signal<string[]>([])

  protected readonly filteredSchedulers = computed(() => {
    const project = this.activeProject()
    const all = this.allSchedulers()
    return project ? all.filter((s) => s.projectName === project) : all
  })

  ngOnInit(): void {
    this.load()
  }

  private load(): void {
    this.isLoading.set(true)
    this.errorMessage.set('')

    this.projectApi.listProjects().subscribe({
      next: ({ projects }) => {
        const names = projects.map((p) => p.name).filter((n) => !n.includes('__'))
        this.projects.set(names)

        if (names.length === 0) {
          this.isLoading.set(false)
          return
        }

        // Load schedulers for all projects in parallel
        const calls = names.map((name) =>
          this.schedulerApi.listSchedulersForProject(name).pipe(catchError(() => of([])))
        )

        forkJoin(calls).subscribe({
          next: (results) => {
            const all: SchedulerWithProject[] = []
            results.forEach((schedulers, i) => {
              schedulers.forEach((s) => all.push({ ...s, projectName: names[i]! }))
            })
            this.allSchedulers.set(all)
            this.isLoading.set(false)
          },
          error: () => {
            this.errorMessage.set('Failed to load schedulers')
            this.isLoading.set(false)
          },
        })
      },
      error: () => {
        this.errorMessage.set('Failed to load projects')
        this.isLoading.set(false)
      },
    })
  }

  protected setProject(name: string | null): void {
    this.activeProject.set(name)
  }

  protected formatInterval(interval: string): string {
    const match = /^(\d+)(min|h|d|M)$/.exec(interval)
    if (!match) return interval
    const [, value, unit] = match
    const units: Record<string, string> = { min: 'min', h: 'h', d: 'd', M: 'mo' }
    return `Every ${value}${units[unit ?? ''] ?? unit}`
  }

  protected formatNextRun(nextRun?: string | null): string {
    if (!nextRun) return 'Expired'
    const d = new Date(nextRun)
    const now = new Date()
    const diffMs = d.getTime() - now.getTime()
    if (diffMs < 0) return 'Due'
    const diffMin = Math.floor(diffMs / 60000)
    if (diffMin < 60) return `in ${diffMin}m`
    const diffH = Math.floor(diffMin / 60)
    if (diffH < 24) return `in ${diffH}h`
    return `in ${Math.floor(diffH / 24)}d`
  }

  protected getSource(s: SchedulerWithProject): string {
    if (s.agentName && s.instruction) {
      const short = s.instruction.length > 35 ? s.instruction.slice(0, 35) + '…' : s.instruction
      return `${s.agentName}: ${short}`
    }
    return s.promptId ?? ''
  }

  protected toggleEnabled(s: SchedulerWithProject): void {
    const action$ = s.enabled
      ? this.schedulerApi.disableSchedulerForProject(s.projectName, s.id)
      : this.schedulerApi.enableSchedulerForProject(s.projectName, s.id)

    action$.subscribe({
      next: () => {
        this.allSchedulers.update((all) =>
          all.map((item) => (item.id === s.id ? { ...item, enabled: !item.enabled } : item))
        )
      },
      error: () => this.errorMessage.set('Failed to toggle scheduler'),
    })
  }

  protected runNow(s: SchedulerWithProject): void {
    this.schedulerApi.runSchedulerNowForProject(s.projectName, s.id).subscribe({
      error: () => this.errorMessage.set('Failed to run scheduler'),
    })
  }

  protected edit(s: SchedulerWithProject): void {
    const data: QuickSchedulerDialogData = {
      mode: 'edit',
      projectName: s.projectName,
      scheduler: {
        id: s.id,
        name: s.name,
        agentName: s.agentName,
        instruction: s.instruction,
        enabled: s.enabled,
        schedule: s.schedule,
      },
    }
    const ref = this.dialog.open(QuickSchedulerDialogComponent, { width: '480px', data })
    ref.afterClosed().subscribe((saved) => {
      if (saved) this.load()
    })
  }

  protected delete(s: SchedulerWithProject): void {
    this.schedulerApi.deleteSchedulerForProject(s.projectName, s.id).subscribe({
      next: () => {
        this.allSchedulers.update((all) => all.filter((item) => item.id !== s.id))
      },
      error: () => this.errorMessage.set('Failed to delete scheduler'),
    })
  }

  protected openCreate(): void {
    this.dialogRef.close()
    this.dialog.open(QuickSchedulerDialogComponent, { width: '480px' })
  }

  protected close(): void {
    this.dialogRef.close()
  }
}
