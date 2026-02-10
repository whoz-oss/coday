import { Injectable } from '@angular/core'

/**
 * Service to manage the first message state during implicit thread creation.
 *
 * Flow:
 * 1. MainAppComponent creates thread and stores first message via setPendingFirstMessage()
 * 2. Router navigates to new thread
 * 3. ThreadComponent retrieves and clears first message via consumePendingFirstMessage()
 *
 * This avoids relying on router navigation state which is unreliable after navigation completes.
 */
@Injectable({
  providedIn: 'root',
})
export class FirstMessageStateService {
  private pendingFirstMessage: string | null = null

  /**
   * Store a first message to be sent after thread navigation
   * @param message The message to send as first message
   */
  setPendingFirstMessage(message: string): void {
    console.log('[FIRST-MESSAGE-STATE] Storing pending first message:', message.substring(0, 50) + '...')
    this.pendingFirstMessage = message
  }

  /**
   * Retrieve and clear the pending first message (consume pattern)
   * @returns The pending first message, or null if none
   */
  consumePendingFirstMessage(): string | null {
    const message = this.pendingFirstMessage
    if (message) {
      console.log('[FIRST-MESSAGE-STATE] Consuming pending first message:', message.substring(0, 50) + '...')
      this.pendingFirstMessage = null
    } else {
      console.log('[FIRST-MESSAGE-STATE] No pending first message to consume')
    }
    return message
  }

  /**
   * Check if there's a pending first message without consuming it
   * @returns True if there's a pending first message
   */
  hasPendingFirstMessage(): boolean {
    return this.pendingFirstMessage !== null
  }

  /**
   * Clear any pending first message without consuming it
   */
  clear(): void {
    if (this.pendingFirstMessage) {
      console.log('[FIRST-MESSAGE-STATE] Clearing pending first message')
      this.pendingFirstMessage = null
    }
  }
}
