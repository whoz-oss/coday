import { Injectable } from '@angular/core'
import { combineLatest } from 'rxjs'
import { UnreadMessagesService } from './unread-messages.service'
import { CodayService } from '../core/services/coday.service'

@Injectable({
  providedIn: 'root'
})
export class TabTitleService {
  
  // Différents emojis de pastille à tester
  private readonly NOTIFICATION_EMOJIS = [
    '🔴',  // Rouge classique
    '🟠',  // Orange
    '🔵',  // Bleu
    '🟢',  // Vert
    '⭕',  // Cercle rouge
    '🚨',  // Sirène
    '📍',  // Pin rouge
  ]
  
  private currentEmojiIndex = 0 // Pour tester différents emojis
  
  constructor(
    private unreadService: UnreadMessagesService,
    private codayService: CodayService
  ) {
    this.initializeTitleUpdates()
  }
  
  /**
   * Initialiser la mise à jour automatique du titre
   */
  private initializeTitleUpdates(): void {
    // Combiner le nom du projet et le nombre de messages non lus
    combineLatest([
      this.codayService.projectTitle$,
      this.unreadService.unreadCount$
    ]).subscribe(([projectTitle, unreadCount]) => {
      this.updateTitle(projectTitle, unreadCount)
    })
  }
  
  /**
   * Mettre à jour le titre de l'onglet
   */
  private updateTitle(projectTitle: string, unreadCount: number): void {
    let title: string
    
    if (unreadCount > 0) {
      const emoji = this.getCurrentEmoji()
      title = `${projectTitle} ${emoji} (${unreadCount})`
    } else {
      title = projectTitle
    }
    
    document.title = title
    console.log('[TAB-TITLE] Updated to:', title)
  }
  
  /**
   * Obtenir l'emoji actuel (pour les tests)
   */
  private getCurrentEmoji(): string {
    return this.NOTIFICATION_EMOJIS[this.currentEmojiIndex] || '🔴'
  }
  
  /**
   * Changer d'emoji pour tester (méthode de debug)
   */
  cycleEmoji(): void {
    this.currentEmojiIndex = (this.currentEmojiIndex + 1) % this.NOTIFICATION_EMOJIS.length
    console.log('[TAB-TITLE] Emoji changed to:', this.getCurrentEmoji())
    
    // Forcer la mise à jour du titre
    const currentProject = this.codayService.getCurrentProjectTitle()
    const currentUnread = this.unreadService.getCurrentCount()
    this.updateTitle(currentProject, currentUnread)
  }
  
  /**
   * Définir un emoji spécifique pour les tests
   */
  setEmoji(emoji: string): void {
    const customIndex = this.NOTIFICATION_EMOJIS.indexOf(emoji)
    if (customIndex !== -1) {
      this.currentEmojiIndex = customIndex
    } else {
      // Ajouter temporairement l'emoji custom
      this.NOTIFICATION_EMOJIS.push(emoji)
      this.currentEmojiIndex = this.NOTIFICATION_EMOJIS.length - 1
    }
    
    // Forcer la mise à jour
    const currentProject = this.codayService.getCurrentProjectTitle()
    const currentUnread = this.unreadService.getCurrentCount()
    this.updateTitle(currentProject, currentUnread)
  }
  
  /**
   * Obtenir la liste des emojis disponibles pour les tests
   */
  getAvailableEmojis(): string[] {
    return [...this.NOTIFICATION_EMOJIS]
  }
}