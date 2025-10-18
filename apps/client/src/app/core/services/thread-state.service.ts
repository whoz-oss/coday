import { Injectable, inject } from '@angular/core'
import { BehaviorSubject, Observable } from 'rxjs'
import { tap } from 'rxjs/operators'
import { ThreadApiService, ThreadDetails } from './thread-api.service'

/**
 * Service managing the currently selected thread state.
 * Provides observables for reactive UI updates and fetches
 * full thread details (including metadata) when a thread is selected.
 */
@Injectable({
  providedIn: 'root',
})
export class ThreadStateService {
  private selectedThreadSubject = new BehaviorSubject<ThreadDetails | null>(null)
  private isLoadingSubject = new BehaviorSubject<boolean>(false)

  // Public observables
  selectedThread$ = this.selectedThreadSubject.asObservable()
  isLoading$ = this.isLoadingSubject.asObservable()

  // Inject API service
  private threadApi = inject(ThreadApiService)

  /**
   * Select a thread by ID and fetch its full details
   * @param projectName Project name the thread belongs to
   * @param threadId Thread identifier to select
   * @returns Observable of thread details
   */
  selectThread(projectName: string, threadId: string): Observable<ThreadDetails> {
    this.isLoadingSubject.next(true)

    return this.threadApi.getThread(projectName, threadId).pipe(
      tap({
        next: (thread) => {
          console.log('[THREAD-STATE] Thread loaded:', thread.id, thread.name)
          this.selectedThreadSubject.next(thread)
          this.isLoadingSubject.next(false)
        },
        error: (error) => {
          console.error('[THREAD-STATE] Error loading thread:', error)
          this.isLoadingSubject.next(false)
          // Keep previous selection on error
        },
      })
    )
  }

  /**
   * Get the currently selected thread (synchronous)
   * @returns Current thread or null
   */
  getSelectedThread(): ThreadDetails | null {
    return this.selectedThreadSubject.value
  }

  /**
   * Get the currently selected thread ID (synchronous)
   * @returns Current thread ID or null
   */
  getSelectedThreadId(): string | null {
    return this.selectedThreadSubject.value?.id || null
  }

  /**
   * Get the currently selected thread name (synchronous)
   * @returns Current thread name or null
   */
  getSelectedThreadName(): string | null {
    return this.selectedThreadSubject.value?.name || null
  }

  /**
   * Clear the selected thread
   */
  clearSelection(): void {
    console.log('[THREAD-STATE] Clearing thread selection')
    this.selectedThreadSubject.next(null)
  }

  /**
   * Check if a thread is currently selected
   */
  hasSelection(): boolean {
    return this.selectedThreadSubject.value !== null
  }

  /**
   * Update the selected thread's name locally (after successful API update)
   * @param newName New thread name
   */
  updateThreadName(newName: string): void {
    const current = this.selectedThreadSubject.value
    if (current) {
      this.selectedThreadSubject.next({
        ...current,
        name: newName,
      })
    }
  }
}
