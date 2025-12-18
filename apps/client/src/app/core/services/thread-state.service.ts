import { inject, Injectable } from '@angular/core'
import {
  BehaviorSubject,
  combineLatest,
  distinctUntilChanged,
  Observable,
  of,
  shareReplay,
  Subject,
  switchMap,
  startWith,
  catchError,
  throwError,
} from 'rxjs'
import { map, tap } from 'rxjs/operators'
import { ThreadApiService, ThreadUpdateResponse } from './thread-api.service'
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
  private readonly isLoadingThreadListSubject = new BehaviorSubject<boolean>(false)
  private readonly refreshThreadListSubject = new Subject<void>()
  private readonly threadListSubject = new BehaviorSubject<any[]>([])
  private hasLoadedOncePerProject = new Map<string, boolean>()
  private currentProjectName: string | null | undefined = null

  // Public observables
  isLoading$ = this.isLoadingSubject.asObservable()
  isLoadingThreadList$ = this.isLoadingThreadListSubject.asObservable()

  // Inject API service
  private readonly threadApi = inject(ThreadApiService)
  private readonly projectStateService = inject(ProjectStateService)

  private readonly projectName$ = this.projectStateService.selectedProject$.pipe(
    map((project) => project?.name),
    distinctUntilChanged()
  )

  constructor() {
    // Set up the thread list fetching logic
    combineLatest([this.projectName$, this.refreshThreadListSubject.asObservable().pipe(startWith(undefined))])
      .pipe(
        tap(([projectName]) => {
          // Clear thread list only when switching projects
          if (projectName !== this.currentProjectName) {
            console.log('[THREAD_STATE] Project changed, clearing thread list')
            this.threadListSubject.next([])
            this.currentProjectName = projectName
          }

          // Only show loading spinner if there's a project selected
          // and it's the first load for this specific project
          if (projectName) {
            const hasLoaded = this.hasLoadedOncePerProject.get(projectName)
            if (!hasLoaded) {
              console.log('[THREAD_STATE] Initial load for project', projectName, '- showing spinner')
              this.isLoadingThreadListSubject.next(true)
            } else {
              console.log('[THREAD_STATE] Refreshing thread list for project', projectName, '(no spinner)')
            }
          } else {
            // No project selected, ensure loading is false
            console.log('[THREAD_STATE] No project selected, hiding spinner')
            this.isLoadingThreadListSubject.next(false)
          }
        }),
        switchMap(([projectName]) => {
          if (!projectName) {
            console.log('[THREAD_STATE] No project selected, returning empty list')
            return of({ threads: [], projectName: null })
          } else {
            console.log('[THREAD_STATE] Fetching threads for project:', projectName)
            return this.threadApi.listThreads(projectName).pipe(
              map((threads) => ({ threads, projectName })),
              catchError((error) => {
                console.error('[THREAD_STATE] Error loading thread list:', error)
                return of({ threads: [], projectName })
              })
            )
          }
        }),
        tap(({ threads, projectName }) => {
          console.log('[THREAD_STATE] Thread list loaded:', threads.length, 'threads for project', projectName)
          if (projectName) {
            this.hasLoadedOncePerProject.set(projectName, true)
          }
          this.isLoadingThreadListSubject.next(false)
          // Update the thread list subject with new data
          this.threadListSubject.next(threads)
        })
      )
      .subscribe()
  }

  // Public observable that always has the latest thread list
  threadList$ = this.threadListSubject.asObservable()

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
          tap((thread) => {
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

  /**
   * Update a thread's name locally in the thread list (optimistic update)
   * @param threadId Thread identifier
   * @param newName New thread name
   */
  updateThreadNameLocally(threadId: string, newName: string): void {
    const currentList = this.threadListSubject.value
    const updatedList = currentList.map((thread) => {
      if (thread.id === threadId) {
        console.log('[THREAD_STATE] Updating thread name locally:', threadId, '->', newName)
        return { ...thread, name: newName }
      }
      return thread
    })
    this.threadListSubject.next(updatedList)
  }

  /**
   * Refresh the thread list
   * This should be called when a thread is updated (e.g., renamed) to refresh the list
   */
  refreshThreadList(): void {
    console.log('[THREAD_STATE] Manually refreshing thread list')
    this.refreshThreadListSubject.next()
  }

  /**
   * Rename a thread
   * @param threadId Thread identifier
   * @param newName New thread name
   * @returns Observable that emits the update response
   */
  renameThread(threadId: string, newName: string): Observable<ThreadUpdateResponse> {
    const projectName = this.projectStateService.getSelectedProjectId()

    if (!projectName) {
      console.error('[THREAD_STATE] Cannot rename thread: no project selected')
      return throwError(() => new Error('No project selected'))
    }

    const trimmedName = newName.trim()
    if (!trimmedName || trimmedName.length === 0) {
      console.error('[THREAD_STATE] Cannot rename thread: name is empty after trimming')
      return throwError(() => new Error('Thread name cannot be empty'))
    }

    console.log('[THREAD_STATE] Renaming thread:', threadId, 'to:', trimmedName)

    return this.threadApi.updateThread(projectName, threadId, trimmedName).pipe(
      tap((response) => {
        console.log('[THREAD_STATE] Thread renamed successfully:', response)
        // Refresh thread list to show updated name
        this.refreshThreadList()
      }),
      catchError((error) => {
        console.error('[THREAD_STATE] Error renaming thread:', error)
        return throwError(() => error)
      })
    )
  }
}
