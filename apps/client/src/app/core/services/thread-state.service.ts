import { inject, Injectable } from '@angular/core'
import { BehaviorSubject, combineLatest, distinctUntilChanged, of, shareReplay, switchMap } from 'rxjs'
import { map, tap } from 'rxjs/operators'
import { ThreadApiService } from './thread-api.service'
import { ProjectStateService } from './project-state.service'

/**
 * Service managing the currently selected thread state.
 * Provides observables for reactive UI updates and fetches
 * full thread details (including metadata) when a thread is selected.
 */
@Injectable({
  providedIn: 'root',
})
export class ThreadStateService {
  private readonly selectedThreadIdSubject = new BehaviorSubject<string | null>(null)
  private readonly isLoadingSubject = new BehaviorSubject<boolean>(false)

  // Public observables
  isLoading$ = this.isLoadingSubject.asObservable()

  // Inject API service
  private readonly threadApi = inject(ThreadApiService)
  private readonly projectStateService = inject(ProjectStateService)

  private readonly projectName$ = this.projectStateService.selectedProject$.pipe(
    map((project) => project?.name),
    distinctUntilChanged()
  )

  threadList$ = this.projectName$.pipe(
    switchMap((projectName) => {
      if (!projectName) {
        return of([])
      } else {
        return this.threadApi.listThreads(projectName)
      }
    }),
    shareReplay({ bufferSize: 1, refCount: true })
  )

  selectedThread$ = combineLatest([this.projectName$, this.selectedThreadIdSubject.pipe(distinctUntilChanged())]).pipe(
    tap(([projectName, threadId]) => {
      console.log('🐼 combineLatest emitted:', { projectName, threadId })
    }),
    switchMap(([projectName, threadId]) => {
      if (!projectName || !threadId) {
        console.log('🐼 null')
        return of(null)
      } else {
        console.log('🐼 loading thread:', threadId)

        this.isLoadingSubject.next(true)
        return this.threadApi.getThread(projectName, threadId).pipe(
          tap(thread => {
            console.log('🐼 loaded thread:', thread?.id)
          }),
          // shareReplay at this level: shares the result for THIS specific threadId
          // When threadId changes, switchMap cancels and creates a new inner observable
          shareReplay({ bufferSize: 1, refCount: true })
        )
      }
    }),
    tap(() => {
      console.log('🐼 finished loading')
      this.isLoadingSubject.next(false)
    })
  )

  /**
   * Select a thread by ID and fetch its full details
   * @param threadId Thread identifier to select
   */
  selectThread(threadId: string): void {
    this.selectedThreadIdSubject.next(threadId)
  }

  getSelectedThreadId(): string | null {
    return this.selectedThreadIdSubject.value
  }

  /**
   * Clear the selected thread
   */
  clearSelection(): void {
    this.selectedThreadIdSubject.next(null)
  }

  /**
   * Stop the current execution for the selected thread
   * Requires both a project and a thread to be selected
   */
  stop(): void {
    const projectName = this.projectStateService.getSelectedProjectId()
    const threadId = this.selectedThreadIdSubject.value

    if (!projectName || !threadId) {
      console.error('[THREAD_STATE] Cannot stop: no project or thread selected')
      return
    }

    console.log('[THREAD_STATE] Stopping thread:', threadId, 'in project:', projectName)
    this.threadApi.stopThread(projectName, threadId).subscribe({
      next: (response) => console.log('[THREAD_STATE] Stop signal sent:', response.message),
      error: (error) => console.error('[THREAD_STATE] Error stopping thread:', error),
    })
  }
}
