import { Injectable } from '@angular/core'
import { BehaviorSubject } from 'rxjs'

@Injectable({
  providedIn: 'root',
})
export class UnreadMessagesService {
  private unreadCountSubject = new BehaviorSubject<number>(0)

  // Public observable for components
  unreadCount$ = this.unreadCountSubject.asObservable()

  constructor() {
    console.log('[UNREAD] Service initialized')
  }

  /**
   * Add an unread message
   */
  addUnread(): void {
    const currentCount = this.unreadCountSubject.value
    const newCount = currentCount + 1
    this.unreadCountSubject.next(newCount)
    console.log('[UNREAD] Added unread message, total:', newCount)
  }

  /**
   * Mark all messages as read
   */
  markAllAsRead(): void {
    const currentCount = this.unreadCountSubject.value
    if (currentCount > 0) {
      this.unreadCountSubject.next(0)
      console.log('[UNREAD] Marked all messages as read')
    }
  }

  /**
   * Get the current count of unread messages
   */
  getCurrentCount(): number {
    return this.unreadCountSubject.value
  }

  /**
   * Reset the counter (for debug or special cases)
   */
  reset(): void {
    this.unreadCountSubject.next(0)
    console.log('[UNREAD] Counter reset')
  }
}
