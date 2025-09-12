import { Injectable, OnDestroy, inject } from '@angular/core'
import { HttpErrorResponse } from '@angular/common/http'
import { BehaviorSubject, Observable, Subject, catchError, of, tap, map } from 'rxjs'
import { takeUntil } from 'rxjs/operators'
import { 
  CodayEvent, 
  ProjectSelectedEvent,
  TextEvent,
  MessageEvent
} from '@coday/coday-events'
import { CodayApiService } from './coday-api.service'
import { EventStreamService } from './event-stream.service'
import { SessionState } from '@coday/model/session-state'

// Default empty state
const DEFAULT_SESSION_STATE: SessionState = {
  projects: {
    list: null,
    current: null,
    canCreate: true
  },
  threads: {
    list: null,
    current: null
  }
}

@Injectable({
  providedIn: 'root'
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
   * Initialize the service by fetching initial state
   */
  private initializeState(): void {
    console.log('[SESSION-STATE] Fetching initial state')
    this.refreshState().subscribe({
      next: (state) => {
        console.log('[SESSION-STATE] Initial state loaded:', state)
      },
      error: (error) => {
        console.error('[SESSION-STATE] Failed to load initial state:', error)
      }
    })
  }
  
  /**
   * Set up event listeners for automatic state refresh
   */
  private setupEventListeners(): void {
    this.eventStream.events$
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (event) => this.handleEvent(event),
        error: (error) => console.error('[SESSION-STATE] Event stream error:', error)
      })
  }
  
  /**
   * Handle incoming Coday events and trigger state refresh when needed
   */
  private handleEvent(event: CodayEvent): void {
    let shouldRefresh = false
    
    if (event instanceof ProjectSelectedEvent) {
      console.log('[SESSION-STATE] Project selected event received')
      shouldRefresh = true
    } else if (event instanceof TextEvent) {
      // Check for thread selection messages
      if (event.text && event.text.includes('Selected thread')) {
        console.log('[SESSION-STATE] Thread selection detected')
        shouldRefresh = true
      }
    } else if (event instanceof MessageEvent) {
      // Thread might have changed, refresh to get updated current thread
      shouldRefresh = true
    }
    
    if (shouldRefresh) {
      console.log('[SESSION-STATE] Triggering state refresh due to event:', event.type)
      this.refreshState().subscribe({
        error: (error) => console.error('[SESSION-STATE] Auto-refresh failed:', error)
      })
    }
  }
  
  /**
   * Fetch state from API and update local state
   */
  refreshState(): Observable<SessionState> {
    console.log('[SESSION-STATE] Refreshing state via CodayApiService')
    
    return this.codayApi.getSessionState()
      .pipe(
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
    return this.sessionState$.pipe(
      map(state => state.projects)
    )
  }
  
  /**
   * Get threads observable
   */
  getThreads$(): Observable<SessionState['threads']> {
    return this.sessionState$.pipe(
      map(state => state.threads)
    )
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