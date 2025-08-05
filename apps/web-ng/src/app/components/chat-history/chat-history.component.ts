import { Component, Input, Output, EventEmitter, ElementRef, AfterViewChecked, HostListener, OnInit, OnDestroy } from '@angular/core'
import { CommonModule } from '@angular/common'
import { ChatMessageComponent, ChatMessage } from '../chat-message/chat-message.component'
import { UnreadMessagesService } from '../../services/unread-messages.service'

@Component({
  selector: 'app-chat-history',
  standalone: true,
  imports: [CommonModule, ChatMessageComponent],
  templateUrl: './chat-history.component.html',
  styleUrl: './chat-history.component.scss'
})
export class ChatHistoryComponent implements AfterViewChecked, OnInit, OnDestroy {
  @Input() messages: ChatMessage[] = []
  @Input() isThinking: boolean = false
  @Output() playRequested = new EventEmitter<ChatMessage>()
  @Output() copyRequested = new EventEmitter<ChatMessage>()
  @Output() stopRequested = new EventEmitter<void>()
  
  private lastMessageCount = 0
  
  // Scroll tracking state
  isTracking = true // Mode tracking actif par défaut
  showGoToBottom = false // Bouton go-to-bottom visible
  private scrollContainer: HTMLElement | null = null
  private lastScrollTop = 0
  private readonly NEAR_BOTTOM_THRESHOLD = 100 // pixels
  private scrollCheckTimeout: any
  
  constructor(
    private elementRef: ElementRef,
    private unreadService: UnreadMessagesService
  ) {}
  
  ngOnInit() {
    // Trouver le conteneur scrollable au démarrage
    this.findScrollContainer()
    
    // Écouter les changements de focus de l'onglet
    this.setupFocusListeners()
  }
  
  ngOnDestroy() {
    if (this.scrollCheckTimeout) {
      clearTimeout(this.scrollCheckTimeout)
    }
    
    // Nettoyer les listeners de focus
    window.removeEventListener('focus', this.handleWindowFocus)
    window.removeEventListener('blur', this.handleWindowBlur)
  }
  
  ngAfterViewChecked() {
    // Check if we need to scroll after each view update
    if (this.messages.length !== this.lastMessageCount) {
      console.log('[CHAT-HISTORY] Message count changed:', this.lastMessageCount, '->', this.messages.length)
      
      // Détecter les nouveaux messages et marquer comme non lus si nécessaire
      const newMessagesCount = this.messages.length - this.lastMessageCount
      if (newMessagesCount > 0) {
        this.handleNewMessages(newMessagesCount)
      }
      
      this.lastMessageCount = this.messages.length
      
      // Auto-scroll seulement si on est en mode tracking
      if (this.isTracking) {
        this.scrollToBottom()
      }
    }
    
    // S'assurer que le scroll container est trouvé
    if (!this.scrollContainer) {
      this.findScrollContainer()
    }
  }
  
  private scrollToBottom() {
    try {
      if (!this.scrollContainer) {
        this.findScrollContainer()
      }
      
      if (this.scrollContainer) {
        // Note: Scroll programmatique
        
        // Small delay to ensure DOM is updated
        setTimeout(() => {
          if (this.scrollContainer) {
            this.scrollContainer.scrollTo({
              top: this.scrollContainer.scrollHeight,
              behavior: 'smooth'
            })
          }
        }, 50)
      }
    } catch (err) {
      console.error('Error scrolling to bottom:', err)
    }
  }
  
  trackByMessageId(_index: number, message: ChatMessage): string {
    return message.id
  }
  
  onPlayMessage(message: ChatMessage) {
    this.playRequested.emit(message)
  }
  
  onCopyMessage(message: ChatMessage) {
    // Extraire le texte du contenu riche
    const textContent = message.content
      .filter(content => content.type === 'text')
      .map(content => content.content)
      .join('\n\n')
      
    navigator.clipboard.writeText(textContent)
      .then(() => console.log('Message copied'))
      .catch(err => console.error('Failed to copy:', err))
    
    this.copyRequested.emit(message)
  }
  
  onStop() {
    this.stopRequested.emit()
  }
  
  /**
   * Gestion du scroll pour détecter si l'utilisateur scrolle vers le haut
   * Note: Ce listener ne sera pas déclenché car le scroll est sur le parent (.chat-wrapper)
   * On utilise handleScroll() directement sur le scroll container
   */
  @HostListener('scroll')
  onScroll(): void {
    // Ce listener ne sera pas déclenché car le scroll est sur le parent
    // On utilisera handleScroll() directement
  }
  
  /**
   * Trouver le conteneur scrollable (.chat-wrapper)
   */
  private findScrollContainer(): void {
    const chatHistory = this.elementRef.nativeElement
    this.scrollContainer = chatHistory.closest('.chat-wrapper')
    
    if (this.scrollContainer) {
      console.log('[CHAT-HISTORY] Scroll container found')
      // Ajouter le listener de scroll sur le bon élément
      this.scrollContainer.addEventListener('scroll', this.handleScroll.bind(this))
      // Initialiser la position
      this.lastScrollTop = this.scrollContainer.scrollTop
      this.checkScrollPosition()
    } else {
      console.warn('[CHAT-HISTORY] Scroll container not found')
      // Réessayer plus tard
      setTimeout(() => this.findScrollContainer(), 100)
    }
  }
  
  /**
   * Gestionnaire de scroll sur le conteneur
   */
  private handleScroll = (): void => {
    if (!this.scrollContainer) return
    
    const currentScrollTop = this.scrollContainer.scrollTop
    const scrollDirection = currentScrollTop > this.lastScrollTop ? 'down' : 'up'
    const scrollDelta = Math.abs(currentScrollTop - this.lastScrollTop)
    
    // Ignorer les micro-scrolls (peut être du scroll programmatique)
    if (scrollDelta < 5) {
      this.lastScrollTop = currentScrollTop
      return
    }
    
    console.log('[CHAT-HISTORY] Scroll direction:', scrollDirection, 'delta:', scrollDelta, 'position:', currentScrollTop)
    
    // Note: On pourrait détecter le scroll utilisateur vs programmatique ici si nécessaire
    
    // Si l'utilisateur scrolle vers le haut de manière significative, quitter le mode tracking
    if (scrollDirection === 'up' && this.isTracking && scrollDelta > 10) {
      console.log('[CHAT-HISTORY] User scrolled up significantly - exiting tracking mode')
      this.isTracking = false
      this.showGoToBottom = true
    }
    
    // Vérifier si on est proche du bas
    this.checkScrollPosition()
    
    this.lastScrollTop = currentScrollTop
    
    // Debounce pour éviter les appels trop fréquents
    clearTimeout(this.scrollCheckTimeout)
    this.scrollCheckTimeout = setTimeout(() => {
      // Peut être utilisé pour des optimisations futures
    }, 150)
  }
  
  /**
   * Vérifier la position de scroll et ajuster l'état
   */
  private checkScrollPosition(): void {
    if (!this.scrollContainer) return
    
    const { scrollTop, scrollHeight, clientHeight } = this.scrollContainer
    const distanceFromBottom = scrollHeight - scrollTop - clientHeight
    const isNearBottom = distanceFromBottom <= this.NEAR_BOTTOM_THRESHOLD
    
    // Si on est proche du bas et qu'on n'était pas en tracking, réactiver
    if (isNearBottom && !this.isTracking) {
      console.log('[CHAT-HISTORY] Near bottom - entering tracking mode')
      this.isTracking = true
      this.showGoToBottom = false
      
      // Marquer les messages comme lus car l'utilisateur est revenu au bas
      this.unreadService.markAllAsRead()
    }
    
    // Si on n'est pas en tracking et qu'on est loin du bas, s'assurer que le bouton est visible
    if (!this.isTracking && !isNearBottom) {
      this.showGoToBottom = true
    }
  }
  
  /**
   * Aller en bas de la conversation (déclenché par le bouton)
   */
  goToBottom(): void {
    console.log('[CHAT-HISTORY] Go to bottom clicked')
    this.scrollToBottom()
    this.isTracking = true
    this.showGoToBottom = false
    
    // Marquer tous les messages comme lus
    this.unreadService.markAllAsRead()
  }
  
  /**
   * Gérer l'arrivée de nouveaux messages
   */
  private handleNewMessages(newMessagesCount: number): void {
    const shouldMarkAsUnread = this.shouldMarkNewMessagesAsUnread()
    
    if (shouldMarkAsUnread) {
      console.log('[CHAT-HISTORY] Marking', newMessagesCount, 'new messages as unread')
      for (let i = 0; i < newMessagesCount; i++) {
        this.unreadService.addUnread()
      }
    } else {
      console.log('[CHAT-HISTORY] New messages are considered read (user is focused and tracking)')
    }
  }
  
  /**
   * Déterminer si les nouveaux messages doivent être marqués comme non lus
   */
  private shouldMarkNewMessagesAsUnread(): boolean {
    // Condition 1: L'onglet n'a pas le focus
    if (!document.hasFocus()) {
      console.log('[CHAT-HISTORY] Tab does not have focus -> unread')
      return true
    }
    
    // Condition 2: L'utilisateur n'est pas en mode tracking (a scrollé vers le haut)
    if (!this.isTracking) {
      console.log('[CHAT-HISTORY] User is not tracking (scrolled up) -> unread')
      return true
    }
    
    // Sinon, les messages sont considérés comme lus
    return false
  }
  
  /**
   * Configurer les listeners pour le focus/blur de la fenêtre
   */
  private setupFocusListeners(): void {
    window.addEventListener('focus', this.handleWindowFocus)
    window.addEventListener('blur', this.handleWindowBlur)
  }
  
  /**
   * Gestionnaire quand la fenêtre regagne le focus
   */
  private handleWindowFocus = (): void => {
    console.log('[CHAT-HISTORY] Window gained focus')
    
    // Si l'utilisateur est en mode tracking, marquer les messages comme lus
    if (this.isTracking) {
      console.log('[CHAT-HISTORY] User is tracking and focused -> marking messages as read')
      this.unreadService.markAllAsRead()
    }
  }
  
  /**
   * Gestionnaire quand la fenêtre perd le focus
   */
  private handleWindowBlur = (): void => {
    console.log('[CHAT-HISTORY] Window lost focus')
    // Pas d'action spéciale nécessaire, les nouveaux messages seront marqués comme non lus
  }
}