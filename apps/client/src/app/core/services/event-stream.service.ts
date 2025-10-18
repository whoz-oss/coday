import { Injectable, NgZone, OnDestroy, inject } from '@angular/core'
import { Subject, BehaviorSubject } from 'rxjs'
import { CodayEvent, buildCodayEvent, ErrorEvent } from '@coday/coday-events'
import { CodayApiService } from './coday-api.service'

export interface ConnectionStatus {
  connected: boolean
  reconnectAttempts: number
  maxAttempts: number
}

@Injectable({
  providedIn: 'root',
})
export class EventStreamService implements OnDestroy {
  private eventSource: EventSource | null = null
  private eventsSubject = new Subject<CodayEvent>()
  private connectionStatusSubject = new BehaviorSubject<ConnectionStatus>({
    connected: false,
    reconnectAttempts: 0,
    maxAttempts: 3,
  })

  private reconnectAttempts = 0
  private readonly MAX_RECONNECT_ATTEMPTS = 3
  private readonly RECONNECT_DELAY = 2000 // 2 seconds

  // Public observables
  events$ = this.eventsSubject.asObservable()
  connectionStatus$ = this.connectionStatusSubject.asObservable()

  // Modern Angular dependency injection
  private codayApi = inject(CodayApiService)
  private ngZone = inject(NgZone)

  /**
   * Start the SSE connection (legacy)
   * @deprecated Use connectToThread instead
   */
  connect(): void {
    console.log('[SSE] Setting up new EventSource (legacy)')

    if (this.eventSource) {
      console.log('[SSE] Closing existing EventSource')
      this.eventSource.close()
    }

    const url = this.codayApi.getEventsUrl()
    this.eventSource = new EventSource(url)
    this.setupEventHandlers()
  }

  /**
   * Connect to a specific thread's event stream (new architecture)
   * @param projectName Project name
   * @param threadId Thread identifier
   */
  connectToThread(projectName: string, threadId: string): void {
    console.log('[SSE] Connecting to thread event stream:', projectName, threadId)

    if (this.eventSource) {
      console.log('[SSE] Closing existing EventSource')
      this.eventSource.close()
    }

    const url = `/api/projects/${projectName}/threads/${threadId}/event-stream`
    console.log('[SSE] EventSource URL:', url)
    this.eventSource = new EventSource(url)
    this.setupEventHandlers()
  }

  /**
   * Setup event handlers for EventSource
   */
  private setupEventHandlers(): void {
    if (!this.eventSource) return

    this.eventSource!.onmessage = (event) => {
      // NgZone is needed because SSE events come from outside Angular's zone
      this.ngZone.run(() => {
        console.log('[SSE] Message received:', event.data.substring(0, 100))

        this.reconnectAttempts = 0 // Reset on successful message
        this.updateConnectionStatus(true, 0)

        try {
          const data = JSON.parse(event.data)
          const codayEvent = buildCodayEvent(data)

          if (codayEvent) {
            console.log('[SSE] Event:', codayEvent.type)
            this.eventsSubject.next(codayEvent)
          } else {
            console.warn('[SSE] Failed to build event:', data.type)
          }
        } catch (error: any) {
          console.error('[SSE] Parse error:', error.message)
        }
      })
    }

    this.eventSource!.onopen = () => {
      this.ngZone.run(() => {
        console.log('[SSE] Connection established')
        this.reconnectAttempts = 0
        this.updateConnectionStatus(true, 0)
      })
    }

    this.eventSource!.onerror = (error) => {
      this.ngZone.run(() => {
        console.log('[SSE] EventSource error:', error)

        if (this.eventSource?.readyState === EventSource.CLOSED) {
          console.log('[SSE] Connection closed')
          this.updateConnectionStatus(false, this.reconnectAttempts)

          if (this.reconnectAttempts < this.MAX_RECONNECT_ATTEMPTS) {
            console.log(`[SSE] Attempting reconnect ${this.reconnectAttempts + 1}/${this.MAX_RECONNECT_ATTEMPTS}`)

            // Emit error event
            this.eventsSubject.next(
              new ErrorEvent({
                error: new Error(
                  `Connection lost. Attempting to reconnect (${this.reconnectAttempts + 1}/${this.MAX_RECONNECT_ATTEMPTS})...`
                ),
              })
            )

            setTimeout(() => {
              this.reconnectAttempts++
              // Note: For thread-based connections, we would need to store project/thread
              // to reconnect properly. For now, this reconnect uses the legacy method.
              // TODO: Store connection parameters for proper reconnection
              console.warn('[SSE] Reconnection may not work properly with thread-based connections')
            }, this.RECONNECT_DELAY)
          } else {
            console.log('[SSE] Max reconnection attempts reached')
            this.eventsSubject.next(
              new ErrorEvent({
                error: new Error('Connection lost permanently. Please refresh the page.'),
              })
            )
          }
        }
      })
    }
  }

  /**
   * Disconnect the SSE
   */
  disconnect(): void {
    if (this.eventSource) {
      console.log('[SSE] Disconnecting EventSource')
      this.eventSource.close()
      this.eventSource = null
      this.updateConnectionStatus(false, 0)
    }
  }

  /**
   * Check if currently connected
   */
  isConnected(): boolean {
    return this.connectionStatusSubject.value.connected
  }

  /**
   * Update connection status
   */
  private updateConnectionStatus(connected: boolean, attempts: number): void {
    this.connectionStatusSubject.next({
      connected,
      reconnectAttempts: attempts,
      maxAttempts: this.MAX_RECONNECT_ATTEMPTS,
    })
  }

  /**
   * Clean up on service destroy
   */
  ngOnDestroy(): void {
    this.disconnect()
    this.eventsSubject.complete()
    this.connectionStatusSubject.complete()
  }
}
