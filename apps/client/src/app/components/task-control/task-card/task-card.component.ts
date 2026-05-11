import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core'
import { Router } from '@angular/router'
import { MatIconModule } from '@angular/material/icon'
import { MatButtonModule } from '@angular/material/button'
import { MatTooltipModule } from '@angular/material/tooltip'
import { TaskStatus, TaskThread } from '../../../core/services/task-status.service'
import { ProjectStateService } from '../../../core/services/project-state.service'

export interface TaskThreadWithProject extends TaskThread {
  projectId: string
}

@Component({
  selector: 'app-task-card',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatIconModule, MatButtonModule, MatTooltipModule],
  templateUrl: './task-card.component.html',
  styleUrl: './task-card.component.scss',
})
export class TaskCardComponent {
  readonly task = input.required<TaskThread>()
  readonly projectNameOverride = input<string | null>(null)
  readonly showProject = input<boolean>(false)
  /** When true, clicking the card emits previewRequested instead of navigating. */
  readonly previewMode = input<boolean>(false)
  readonly stopRequested = output<string>()
  readonly deleteRequested = output<string>()
  readonly starToggled = output<string>()
  readonly closeTaskRequested = output<string>()
  readonly markDoneRequested = output<string>()
  readonly markActiveRequested = output<string>()
  readonly previewRequested = output<string>()

  private readonly router = inject(Router)
  private readonly projectState = inject(ProjectStateService)

  protected readonly projectName = computed(
    () => this.projectNameOverride() ?? this.projectState.getSelectedProjectId()
  )
  protected readonly statusLabel = computed(() => LABELS[this.task().status])
  protected readonly statusIcon = computed(() => ICONS[this.task().status])
  protected readonly isWorktree = computed(() => !!this.task().worktreeProject)

  navigate(): void {
    if (this.previewMode()) {
      this.previewRequested.emit(this.task().id)
      return
    }
    const project = this.projectName()
    if (!project) return
    // Pre-select the project so the guard finds it already loaded on first click
    this.projectState.selectProject(project)
    void this.router.navigate(['project', project, 'thread', this.task().id])
  }

  onStop(event: Event): void {
    event.stopPropagation()
    this.stopRequested.emit(this.task().id)
  }

  onDelete(event: Event): void {
    event.stopPropagation()
    this.deleteRequested.emit(this.task().id)
  }

  onStarToggle(event: Event): void {
    event.stopPropagation()
    this.starToggled.emit(this.task().id)
  }

  onCloseTask(event: Event): void {
    event.stopPropagation()
    this.closeTaskRequested.emit(this.task().id)
  }

  onMarkDone(event: Event): void {
    event.stopPropagation()
    this.markDoneRequested.emit(this.task().id)
  }

  onMarkActive(event: Event): void {
    event.stopPropagation()
    this.markActiveRequested.emit(this.task().id)
  }

  /** Navigate directly to the thread, bypassing preview mode. */
  onOpenDirect(event: Event): void {
    event.stopPropagation()
    const project = this.projectName()
    if (!project) return
    this.projectState.selectProject(project)
    void this.router.navigate(['project', project, 'thread', this.task().id])
  }

  formatRelativeTime(dateString: string): string {
    const diffMs = Date.now() - new Date(dateString).getTime()
    const minutes = Math.floor(diffMs / 60_000)
    if (minutes < 1) return 'just now'
    if (minutes < 60) return `${minutes} min ago`
    const hours = Math.floor(minutes / 60)
    if (hours < 24) return `${hours}h ago`
    const days = Math.floor(hours / 24)
    if (days === 1) return 'yesterday'
    if (days < 7) return `${days} days ago`
    return new Date(dateString).toLocaleDateString('en', { day: 'numeric', month: 'short' })
  }
}

const LABELS: Record<TaskStatus, string> = {
  'waiting-you': 'Waiting for you',
  'in-progress': 'In progress…',
  done: 'Done',
  paused: 'Paused',
  error: 'Error',
}

const ICONS: Record<TaskStatus, string> = {
  'waiting-you': 'mark_unread_chat_alt',
  'in-progress': 'pending',
  done: 'check_circle',
  paused: 'pause_circle',
  error: 'error',
}
