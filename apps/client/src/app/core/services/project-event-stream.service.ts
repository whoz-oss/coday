import { inject, Injectable, OnDestroy } from '@angular/core'
import { Subject } from 'rxjs'
import { takeUntil } from 'rxjs/operators'
import { buildCodayEvent, ThreadUpdateEvent } from '@coday/model'
import { ProjectStateService } from './project-state.service'
import { ThreadStateService } from './thread-state.service'

/**
 * Manages the project-level SSE connection for the currently selected project.
 *
 * Automatically tracks the selected project via ProjectStateService and
 * maintains one SSE connection. Triggers thread-list refreshes on ThreadUpdateEvent.
 */
@Injectable({
  providedIn: 'root',
})
export class ProjectEventStreamService implements OnDestroy {
  private readonly projectState = inject(ProjectStateService)
  private readonly threadState = inject(ThreadStateService)
  private readonly destroy$ = new Subject<void>()

  private eventSource: EventSource | null = null
  private currentProject: string | null = null

  constructor() {
    this.projectState.selectedProject$.pipe(takeUntil(this.destroy$)).subscribe((project) => {
      const projectName = project?.name ?? null
      if (projectName === this.currentProject) return
      this.disconnect()
      if (projectName) {
        this.connect(projectName)
      }
    })
  }

  private connect(projectName: string, retryDelayMs = 5_000): void {
    this.currentProject = projectName
    const url = `/api/projects/${encodeURIComponent(projectName)}/event-stream`
    console.log(`[PROJECT_SSE] Connecting to ${url}`)

    this.eventSource = new EventSource(url)

    this.eventSource.onmessage = (evt) => {
      try {
        const raw = JSON.parse(evt.data)
        const event = buildCodayEvent(raw)
        if (!event) return

        if (event instanceof ThreadUpdateEvent) {
          console.log(`[PROJECT_SSE] ThreadUpdateEvent — refreshing thread list`)
          this.threadState.refreshThreadList()
        }
        // InviteEvent, ChoiceEvent, ThinkingEvent from project SSE are informational only.
        // Status is derived from thread.pendingInvite (persisted) for non-active threads.
      } catch (e) {
        console.warn('[PROJECT_SSE] Failed to parse event:', e)
      }
    }

    this.eventSource.onerror = () => {
      if (this.eventSource?.readyState === EventSource.CLOSED) {
        console.warn(`[PROJECT_SSE] Connection closed for ${projectName}, reconnecting in ${retryDelayMs}ms`)
        this.eventSource = null
        setTimeout(() => {
          if (this.currentProject === projectName) {
            this.connect(projectName, Math.min(retryDelayMs * 2, 60_000))
          }
        }, retryDelayMs)
      }
    }
  }

  private disconnect(): void {
    if (this.eventSource) {
      this.eventSource.close()
      this.eventSource = null
      console.log(`[PROJECT_SSE] Disconnected from project ${this.currentProject}`)
    }
    this.currentProject = null
  }

  ngOnDestroy(): void {
    this.disconnect()
    this.destroy$.next()
    this.destroy$.complete()
  }
}
