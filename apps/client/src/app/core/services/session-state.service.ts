import { HttpErrorResponse } from '@angular/common/http'
import { inject, Injectable, OnDestroy } from '@angular/core'
import { CodayEvent, ProjectSelectedEvent, ThreadSelectedEvent } from '@coday/coday-events'
import { SessionState } from '@coday/model/session-state'
import { BehaviorSubject, catchError, map, Observable, of, Subject, switchMap, takeWhile, tap, timer } from 'rxjs'
import { takeUntil } from 'rxjs/operators'
import { CodayApiService } from './coday-api.service'
import { EventStreamService } from './event-stream.service'

// Default empty state
const DEFAULT_SESSION_STATE: SessionState = {
  projects: {
    list: null,
    current: null,
    canCreate: true,
  },
  threads: {
    list: null,
    current: null,
  },
}

@Injectable({
  providedIn: 'root',
})
export class SessionStateService implements OnDestroy {
  private destroy$ = new Subject<void>()

  // State management
  private sessionStateSubject = new BehaviorSubject<SessionState>(DEFAULT_SESSION_STATE)

  // Public observables
  public sessionState$ = this.sessionStateSubject.asObservable()

  // Modern Angular dependency injection
  private codayApi = inject(CodayApiService)
  private eventStream = inject(EventStreamService)

  constructor() {
    console.log('[SESSION-STATE] Service initializing')
    this.setupEventListeners()
    this.initializeState()
  }

  /**
   * Initialize the service by fetching initial state with retry mechanism
   * Will attempt to refresh state for at least 20 seconds
   */
  private initializeState(): void {
    console.log('[SESSION-STATE] Fetching initial state with retry mechanism')
    const startTime = Date.now()
    const maxDuration = 20000 // 20 seconds
    const retryInterval = 2000 // 2 seconds between attempts
    let attemptCount = 0
    let foundValidState = false

    // Use timer to create a polling mechanism
    timer(0, retryInterval)
      .pipe(
        // Continue until we find valid state OR timeout
        takeWhile(() => {
          const elapsed = Date.now() - startTime
          const timeRemaining = elapsed < maxDuration

          if (foundValidState) {
            console.log('[SESSION-STATE] Valid state found, stopping retry mechanism')
            return false
          }

          if (!timeRemaining) {
            console.log(`[SESSION-STATE] Retry timeout reached after ${elapsed}ms`)
            return false
          }

          return true
        }, true),
        // Attempt to fetch state on each tick
        switchMap(() => {
          attemptCount++
          const elapsed = Date.now() - startTime
          console.log(`[SESSION-STATE] Attempt #${attemptCount} (${elapsed}ms elapsed) to refresh state...`)

          // Call the API directly
          return this.codayApi.getSessionState().pipe(
            tap((state) => {
              console.log('[SESSION-STATE] State fetched successfully:', state)
              this.sessionStateSubject.next(state)

              // Check if this is a valid state
              const hasProjects = state.projects.list !== null && state.projects.list.length > 0
              const hasThreads = state.threads.list !== null && state.threads.list.length > 0
              const hasCurrentProject = state.projects.current !== null

              if (hasProjects || hasThreads || hasCurrentProject) {
                console.log('[SESSION-STATE] Valid state detected:')
                console.log('[SESSION-STATE] - Has projects:', hasProjects)
                console.log('[SESSION-STATE] - Has threads:', hasThreads)
                console.log('[SESSION-STATE] - Has current project:', hasCurrentProject)
                foundValidState = true
              } else {
                console.log('[SESSION-STATE] State is empty (no projects/threads/current), will retry...')
              }
            }),
            map(() => true),
            catchError((error) => {
              console.warn(`[SESSION-STATE] Attempt #${attemptCount} failed:`, error.message || error)
              return of(false)
            })
          )
        }),
        takeUntil(this.destroy$)
      )
      .subscribe({
        next: () => {
          // Just track progress
        },
        error: (error) => {
          console.error('[SESSION-STATE] Unexpected error in retry mechanism:', error)
          // Set fallback state on catastrophic error
          this.sessionStateSubject.next(DEFAULT_SESSION_STATE)
        },
        complete: () => {
          const elapsed = Date.now() - startTime
          console.log(`[SESSION-STATE] Initialization completed after ${attemptCount} attempts in ${elapsed}ms`)

          // If we still don't have valid state, set the default
          const currentState = this.sessionStateSubject.value
          if (!currentState.projects.list && !currentState.threads.list && !currentState.projects.current) {
            console.log('[SESSION-STATE] No valid state found after retries, using default state')
            this.sessionStateSubject.next(DEFAULT_SESSION_STATE)
          }

          // Mark initialization as complete
          console.log('[SESSION-STATE] Initialization flag set to false - UI can now be enabled')
        },
      })
  }

  /**
   * Set up event listeners for automatic state refresh
   */
  private setupEventListeners(): void {
    this.eventStream.events$.pipe(takeUntil(this.destroy$)).subscribe({
      next: (event) => this.handleEvent(event),
      error: (error) => console.error('[SESSION-STATE] Event stream error:', error),
    })
  }

  /**
   * Handle incoming Coday events and trigger state refresh when needed
   */
  private handleEvent(event: CodayEvent): void {
    console.log('[SESSION-STATE] Event received:', event.type, event)
    let shouldRefresh = false

    if (event instanceof ProjectSelectedEvent) {
      console.log('[SESSION-STATE] Project selected event received')
      shouldRefresh = true
    } else if (event instanceof ThreadSelectedEvent) {
      console.log('[SESSION-STATE] Thread selected event received:', event.threadName)
      shouldRefresh = true
    }

    if (shouldRefresh) {
      console.log('[SESSION-STATE] Triggering state refresh due to event:', event.type)
      this.refreshState().subscribe({
        error: (error) => console.error('[SESSION-STATE] Auto-refresh failed:', error),
      })
    }
  }

  /**
   * Fetch state from API and update local state
   */
  refreshState(): Observable<SessionState> {
    console.log('[SESSION-STATE] Refreshing state via CodayApiService')

    return this.codayApi.getSessionState().pipe(
      tap((state) => {
        console.log('[SESSION-STATE] State refreshed:', state)
        this.sessionStateSubject.next(state)
      }),
      catchError((error: HttpErrorResponse) => {
        console.error('[SESSION-STATE] Error fetching state:', error)

        // Provide fallback state on error
        const fallbackState = { ...DEFAULT_SESSION_STATE }

        if (error.status === 404) {
          console.warn('[SESSION-STATE] Client not found, using default state')
        } else if (error.status === 400) {
          console.error('[SESSION-STATE] Bad request, check clientId')
        } else {
          console.error('[SESSION-STATE] Server error:', error.status)
        }

        this.sessionStateSubject.next(fallbackState)
        return of(fallbackState)
      })
    )
  }

  /**
   * Get projects observable
   */
  getProjects$(): Observable<SessionState['projects']> {
    return this.sessionState$.pipe(map((state) => state.projects))
  }

  /**
   * Get threads observable
   */
  getThreads$(): Observable<SessionState['threads']> {
    return this.sessionState$.pipe(map((state) => state.threads))
  }

  /**
   * Get current project name
   */
  getCurrentProject(): string | null {
    return this.sessionStateSubject.value.projects.current
  }

  /**
   * Get current thread ID
   */
  getCurrentThread(): string | null {
    return this.sessionStateSubject.value.threads.current
  }

  /**
   * Check if project creation is locked
   */
  isProjectLocked(): boolean {
    return !this.sessionStateSubject.value.projects.canCreate
  }

  /**
   * Get current session state synchronously
   */
  getCurrentState(): SessionState {
    return this.sessionStateSubject.value
  }

  /**
   * Check if a project is currently selected
   */
  hasProjectSelected(): boolean {
    return this.getCurrentProject() !== null
  }

  /**
   * Check if a thread is currently selected
   */
  hasThreadSelected(): boolean {
    return this.getCurrentThread() !== null
  }

  /**
   * Get available projects list
   */
  getAvailableProjects(): Array<{ name: string }> | null {
    return this.sessionStateSubject.value.projects.list
  }

  /**
   * Get available threads list
   */
  getAvailableThreads(): Array<{ id: string; name: string; modifiedDate: string }> | null {
    return this.sessionStateSubject.value.threads.list
  }

  /**
   * Clean up on service destroy
   */
  ngOnDestroy(): void {
    console.log('[SESSION-STATE] Service destroying')
    this.destroy$.next()
    this.destroy$.complete()
    this.sessionStateSubject.complete()
  }
}
