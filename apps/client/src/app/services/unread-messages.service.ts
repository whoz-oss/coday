import { Injectable } from '@angular/core'
import { BehaviorSubject } from 'rxjs'

@Injectable({
  providedIn: 'root'
})
export class UnreadMessagesService {
  private unreadCountSubject = new BehaviorSubject<number>(0)
  
  // Observable public pour les composants
  unreadCount$ = this.unreadCountSubject.asObservable()
  
  constructor() {
    console.log('[UNREAD] Service initialized')
  }
  
  /**
   * Ajouter un message non lu
   */
  addUnread(): void {
    const currentCount = this.unreadCountSubject.value
    const newCount = currentCount + 1
    this.unreadCountSubject.next(newCount)
    console.log('[UNREAD] Added unread message, total:', newCount)
  }
  
  /**
   * Marquer tous les messages comme lus
   */
  markAllAsRead(): void {
    const currentCount = this.unreadCountSubject.value
    if (currentCount > 0) {
      this.unreadCountSubject.next(0)
      console.log('[UNREAD] Marked all messages as read')
    }
  }
  
  /**
   * Obtenir le nombre actuel de messages non lus
   */
  getCurrentCount(): number {
    return this.unreadCountSubject.value
  }
  
  /**
   * Réinitialiser le compteur (pour debug ou cas spéciaux)
   */
  reset(): void {
    this.unreadCountSubject.next(0)
    console.log('[UNREAD] Counter reset')
  }
}