import { Injectable, NgZone } from '@angular/core'
import { Subject, BehaviorSubject } from 'rxjs'
import { CodayEvent, buildCodayEvent, ErrorEvent } from '@coday/coday-events'
import { CodayApiService } from './coday-api.service'

export interface ConnectionStatus {
  connected: boolean
  reconnectAttempts: number
  maxAttempts: number
}

@Injectable({
  providedIn: 'root'
})
export class EventStreamService {
  private eventSource: EventSource | null = null
  private eventsSubject = new Subject<CodayEvent>()
  private connectionStatusSubject = new BehaviorSubject<ConnectionStatus>({
    connected: false,
    reconnectAttempts: 0,
    maxAttempts: 3
  })

  private reconnectAttempts = 0
  private readonly MAX_RECONNECT_ATTEMPTS = 3
  private readonly RECONNECT_DELAY = 2000 // 2 seconds

  // Public observables
  events$ = this.eventsSubject.asObservable()
  connectionStatus$ = this.connectionStatusSubject.asObservable()

  constructor(
    private codayApi: CodayApiService,
    private ngZone: NgZone
  ) {}

  /**
   * Start the SSE connection
   */
  connect(): void {
    console.log('[SSE] Setting up new EventSource')
    
    if (this.eventSource) {
      console.log('[SSE] Closing existing EventSource')
      this.eventSource.close()
    }

    const url = this.codayApi.getEventsUrl()
    this.eventSource = new EventSource(url)

    this.eventSource.onmessage = (event) => {
      this.ngZone.run(() => {
        console.log('[SSE] ===== RAW MESSAGE RECEIVED =====', {
          data: event.data,
          type: event.type,
          origin: event.origin
        })
        
        this.reconnectAttempts = 0 // Reset on successful message
        this.updateConnectionStatus(true, 0)

        try {
          const data = JSON.parse(event.data)
          console.log('[SSE] Parsed data:', data)
          
          const codayEvent = buildCodayEvent(data)
          if (codayEvent) {
            console.log('[SSE] Built CodayEvent:', {
              type: codayEvent.type,
              timestamp: codayEvent.timestamp,
              event: codayEvent
            })
            this.eventsSubject.next(codayEvent)
            console.log('[SSE] Event emitted to subscribers')
          } else {
            console.warn('[SSE] Failed to build CodayEvent from data:', data)
          }
        } catch (error: any) {
          console.error('[SSE] Could not parse event:', {
            error: error.message,
            rawData: event.data
          })
        }
      })
    }

    this.eventSource.onopen = () => {
      this.ngZone.run(() => {
        console.log('[SSE] Connection established')
        this.reconnectAttempts = 0
        this.updateConnectionStatus(true, 0)
      })
    }

    this.eventSource.onerror = (error) => {
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
              this.connect()
            }, this.RECONNECT_DELAY)
          } else {
            console.log('[SSE] Max reconnection attempts reached')
            this.eventsSubject.next(
              new ErrorEvent({ 
                error: new Error('Connection lost permanently. Please refresh the page.') 
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
      maxAttempts: this.MAX_RECONNECT_ATTEMPTS
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