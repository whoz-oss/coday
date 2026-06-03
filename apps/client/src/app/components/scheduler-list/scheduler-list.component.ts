import { Location } from '@angular/common'
import { Component, computed, inject, OnInit, signal } from '@angular/core'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'
import { MatSlideToggleModule } from '@angular/material/slide-toggle'
import { MatMenuModule } from '@angular/material/menu'
import { MatDialog } from '@angular/material/dialog'
import { FormsModule } from '@angular/forms'
import { CardActionsDirective, EntityCardComponent, EntityListComponent, EntityListItem } from '@whoz-oss/design-system'
import { SchedulerApiService, SchedulerInfo } from '../../core/services/scheduler-api.service'
import { PromptApiService, PromptInfo } from '../../core/services/prompt-api.service'
import { ConfigApiService } from '../../core/services/config-api.service'
import { SchedulerFormComponent, SchedulerFormData } from '../scheduler-form/scheduler-form.component'

/**
 * Full-page scheduler list for a project.
 *
 * Route: /project/:projectName/schedulers
 *
 * Schedulers are grouped by owner (createdBy). The "Mine" toggle filters
 * to the current user's schedulers only.
 */
@Component({
  selector: 'app-scheduler-list',
  standalone: true,
  imports: [
    EntityListComponent,
    EntityCardComponent,
    CardActionsDirective,
    MatButtonModule,
    MatIconModule,
    MatSlideToggleModule,
    MatMenuModule,
    FormsModule,
  ],
  templateUrl: './scheduler-list.component.html',
  styleUrl: './scheduler-list.component.scss',
})
export class SchedulerListComponent implements OnInit {
  private readonly schedulerApi = inject(SchedulerApiService)
  private readonly promptApi = inject(PromptApiService)
  private readonly configApi = inject(ConfigApiService)
  private readonly location = inject(Location)
  private readonly dialog = inject(MatDialog)

  protected readonly schedulers = signal<SchedulerInfo[]>([])
  protected readonly prompts = signal<PromptInfo[]>([])
  protected readonly isLoading = signal(false)
  protected readonly errorMessage = signal<string | null>(null)
  protected readonly showOnlyMine = signal(true)
  protected readonly currentUsername = signal('')

  protected readonly schedulerItems = computed<EntityListItem[]>(() => {
    const all = this.schedulers()
    const username = this.currentUsername()
    const onlyMine = this.showOnlyMine()

    const visible = onlyMine && username ? all.filter((s) => this.isMyScheduler(s)) : all

    const ownerSet = new Set(visible.map((s) => s.createdBy))
    const owners = [...ownerSet].sort((a, b) => {
      if (a === username && b !== username) return -1
      if (b === username && a !== username) return 1
      return a.localeCompare(b)
    })

    const items: EntityListItem[] = []
    for (const owner of owners) {
      const label = owner === username ? `${owner} (you)` : owner
      for (const s of visible.filter((x) => x.createdBy === owner)) {
        items.push({
          id: s.id,
          name: s.name,
          description: this.buildDescription(s),
          badges: this.buildBadges(s),
          groupKey: owner,
          groupLabel: label,
        })
      }
    }
    return items
  })

  ngOnInit(): void {
    this.configApi.getUserConfig().subscribe({
      next: (config: any) => this.currentUsername.set(config.username ?? ''),
      error: () => {},
    })
    this.loadPrompts()
    this.loadSchedulers()
  }

  private loadPrompts(): void {
    this.promptApi.listPrompts().subscribe({
      next: (prompts) => this.prompts.set(prompts),
      error: () => {},
    })
  }

  private loadSchedulers(): void {
    this.isLoading.set(true)
    this.schedulerApi.listSchedulers().subscribe({
      next: (schedulers) => {
        this.schedulers.set(schedulers)
        this.isLoading.set(false)
      },
      error: (err) => {
        this.errorMessage.set(err?.error?.error ?? 'Failed to load schedulers')
        this.isLoading.set(false)
      },
    })
  }

  protected onBack(): void {
    this.location.back()
  }

  protected onCreate(): void {
    const dialogRef = this.dialog.open<SchedulerFormComponent, SchedulerFormData, boolean>(SchedulerFormComponent, {
      width: '700px',
      maxHeight: '90vh',
      data: { mode: 'create' },
    })
    dialogRef.afterClosed().subscribe((result) => {
      if (result) this.loadSchedulers()
    })
  }

  protected onEdit(schedulerId: string): void {
    this.schedulerApi.getScheduler(schedulerId).subscribe({
      next: (scheduler) => {
        const dialogRef = this.dialog.open<SchedulerFormComponent, SchedulerFormData, boolean>(SchedulerFormComponent, {
          width: '700px',
          maxHeight: '90vh',
          data: { mode: 'edit', scheduler },
        })
        dialogRef.afterClosed().subscribe((result) => {
          if (result) this.loadSchedulers()
        })
      },
      error: (err) => this.errorMessage.set(err?.error?.error ?? 'Failed to load scheduler'),
    })
  }

  protected onToggleEnabled(schedulerId: string): void {
    const scheduler = this.schedulers().find((s) => s.id === schedulerId)
    if (!scheduler) return
    const action = scheduler.enabled
      ? this.schedulerApi.disableScheduler(schedulerId)
      : this.schedulerApi.enableScheduler(schedulerId)
    action.subscribe({
      next: () => this.loadSchedulers(),
      error: (err) => this.errorMessage.set(err?.error?.error ?? 'Failed to toggle scheduler'),
    })
  }

  protected onRunNow(schedulerId: string): void {
    this.schedulerApi.runSchedulerNow(schedulerId).subscribe({
      next: (response) => {
        alert(`Scheduler executed successfully! Thread ID: ${response.threadId}`)
        this.loadSchedulers()
      },
      error: (err) => this.errorMessage.set(err?.error?.error ?? 'Failed to run scheduler'),
    })
  }

  protected onDelete(schedulerId: string): void {
    if (!confirm('Are you sure you want to delete this scheduler?')) return
    this.schedulerApi.deleteScheduler(schedulerId).subscribe({
      next: () => this.loadSchedulers(),
      error: (err) => this.errorMessage.set(err?.error?.error ?? 'Failed to delete scheduler'),
    })
  }

  protected isEnabled(schedulerId: string): boolean {
    return this.schedulers().find((s) => s.id === schedulerId)?.enabled ?? false
  }

  private buildDescription(scheduler: SchedulerInfo): string {
    let source: string
    if (scheduler.promptId) {
      source = this.getPromptName(scheduler.promptId)
    } else if (scheduler.agentName && scheduler.instruction) {
      const short = scheduler.instruction.length > 40 ? scheduler.instruction.slice(0, 40) + '…' : scheduler.instruction
      source = `${scheduler.agentName}: ${short}`
    } else {
      console.error(`Scheduler "${scheduler.name}" (${scheduler.id}) has neither promptId nor agentName+instruction`)
      source = 'Unknown source'
    }
    const interval = this.formatInterval(scheduler.schedule.interval)
    const nextRun = scheduler.nextRun ? new Date(scheduler.nextRun).toLocaleString() : 'Expired'
    return `${source} · ${interval} · Next: ${nextRun}`
  }

  private buildBadges(scheduler: SchedulerInfo) {
    return [
      scheduler.enabled
        ? { label: 'enabled', variant: 'success' as const }
        : { label: 'disabled', variant: 'warning' as const },
    ]
  }

  private getPromptName(promptId: string): string {
    const prompt = this.prompts().find((p) => p.id === promptId)
    return prompt ? prompt.name : promptId
  }

  private formatInterval(interval: string): string {
    const match = /^(\d+)(min|h|d|M)$/.exec(interval)
    if (!match) return interval
    const [, value, unit] = match
    if (!value || !unit) return interval
    const units: Record<string, string> = { min: 'minute', h: 'hour', d: 'day', M: 'month' }
    const unitName = units[unit] ?? unit
    return `Every ${value} ${unitName}${parseInt(value, 10) > 1 ? 's' : ''}`
  }

  private isMyScheduler(scheduler: SchedulerInfo): boolean {
    const normalize = (s: string) => s.replace(/[.\s]+/g, '_').toLowerCase()
    return normalize(scheduler.createdBy) === normalize(this.currentUsername())
  }
}
