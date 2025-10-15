import {
  Component,
  Input,
  Output,
  EventEmitter,
  ElementRef,
  AfterViewChecked,
  HostListener,
  OnInit,
  OnDestroy,
  inject,
} from '@angular/core'
import { ChatMessageComponent, ChatMessage } from '../chat-message/chat-message.component'
import { UnreadMessagesService } from '../../services/unread-messages.service'
import { VoiceSynthesisService } from '../../services/voice-synthesis.service'
import { PreferencesService } from '../../services/preferences.service'
import { CodayService } from '../../core/services/coday.service'
import { Subject } from 'rxjs'

@Component({
  selector: 'app-chat-history',
  standalone: true,
  imports: [ChatMessageComponent],
  templateUrl: './chat-history.component.html',
  styleUrl: './chat-history.component.scss',
})
export class ChatHistoryComponent implements AfterViewChecked, OnInit, OnDestroy {
  @Input() messages: ChatMessage[] = []
  @Input() isThinking: boolean = false
  @Output() copyRequested = new EventEmitter<ChatMessage>()
  @Output() stopRequested = new EventEmitter<void>()

  private lastMessageCount = 0
  private destroy$ = new Subject<void>()

  // Scroll tracking state
  isTracking = true // Tracking mode active by default
  showGoToBottom = false // Go-to-bottom button visibility
  private scrollContainer: HTMLElement | null = null
  private lastScrollTop = 0
  private readonly NEAR_BOTTOM_THRESHOLD = 100 // pixels
  private scrollCheckTimeout: any

  // Seuil de fraîcheur des messages pour l'annonce automatique (5 minutes)
  private readonly MESSAGE_FRESHNESS_THRESHOLD = 5 * 60 * 1000 // en millisecondes

  // Modern Angular dependency injection
  private elementRef = inject(ElementRef)
  private unreadService = inject(UnreadMessagesService)
  private voiceSynthesisService = inject(VoiceSynthesisService)
  private preferencesService = inject(PreferencesService)
  private codayService = inject(CodayService)

  ngOnInit() {
    // Find scrollable container on startup
    this.findScrollContainer()

    // Listen to tab focus changes
    this.setupFocusListeners()
  }

  ngOnDestroy() {
    this.destroy$.next()
    this.destroy$.complete()

    if (this.scrollCheckTimeout) {
      clearTimeout(this.scrollCheckTimeout)
    }

    // Clean up focus listeners
    window.removeEventListener('focus', this.handleWindowFocus)
    window.removeEventListener('blur', this.handleWindowBlur)
  }

  ngAfterViewChecked() {
    // Check if we need to scroll after each view update
    if (this.messages.length !== this.lastMessageCount) {
      console.log('[CHAT-HISTORY] Message count changed:', this.lastMessageCount, '->', this.messages.length)

      // Detect new messages and mark as unread if necessary
      const newMessagesCount = this.messages.length - this.lastMessageCount
      if (newMessagesCount > 0) {
        this.handleNewMessages(newMessagesCount)
        this.handleVoiceAnnouncement(newMessagesCount)
      }

      this.lastMessageCount = this.messages.length

      // Auto-scroll only if in tracking mode
      if (this.isTracking) {
        this.scrollToBottom()
      }
    }

    // Ensure scroll container is found
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
              behavior: 'smooth',
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

  onCopyMessage(message: ChatMessage) {
    // Extract text from rich content
    const textContent = message.content
      .filter((content) => content.type === 'text')
      .map((content) => content.content)
      .join('\n\n')

    navigator.clipboard
      .writeText(textContent)
      .then(() => console.log('Message copied'))
      .catch((err) => console.error('Failed to copy:', err))

    this.copyRequested.emit(message)
  }

  onStop() {
    this.stopRequested.emit()
  }

  /**
   * Check if a message can be deleted
   * @param index Index of the message in the messages array
   * @returns true if the message can be deleted
   */
  canDeleteMessage(index: number): boolean {
    // Cannot delete the first message
    if (index === 0) {
      return false
    }

    // Cannot delete while thinking
    if (this.isThinking) {
      return false
    }

    return true
  }

  /**
   * Handle message deletion request
   * @param message The message to delete
   */
  onDeleteMessage(message: ChatMessage): void {
    console.log('[CHAT-HISTORY] Delete requested for message:', message.id)

    // Find the message index
    const messageIndex = this.messages.findIndex((msg) => msg.id === message.id)

    // Additional validation
    if (messageIndex === 0) {
      console.warn('[CHAT-HISTORY] Cannot delete first message')
      return
    }

    if (this.isThinking) {
      console.warn('[CHAT-HISTORY] Cannot delete message while agent is thinking')
      return
    }

    if (message.role !== 'user') {
      console.warn('[CHAT-HISTORY] Can only delete user messages')
      return
    }

    // Show confirmation dialog
    const confirmMessage = `Delete this message and all following messages?\n\nThis action cannot be undone once you post another message.`
    if (!confirm(confirmMessage)) {
      return
    }

    // Perform deletion
    console.log('[CHAT-HISTORY] Proceeding with message deletion')
    this.codayService.deleteMessage(message.id).subscribe({
      next: (response: { success: boolean; message?: string; error?: string }) => {
        if (response.success) {
          console.log('[CHAT-HISTORY] Message deleted successfully')
          // The messages will be refreshed automatically via event stream replay
        } else {
          console.error('[CHAT-HISTORY] Failed to delete message:', response.error)
          alert(`Failed to delete message: ${response.error}`)
        }
      },
      error: (error: any) => {
        console.error('[CHAT-HISTORY] Error deleting message:', error)
        alert(`Error deleting message: ${error.message || 'Unknown error'}`)
      },
    })
  }

  /**
   * Scroll handling to detect if user scrolls up
   * Note: This listener won't be triggered since scroll is on parent (.chat-wrapper)
   * We use handleScroll() directly on the scroll container
   */
  @HostListener('scroll')
  onScroll(): void {
    // This listener won't be triggered since scroll is on parent
    // We'll use handleScroll() directly
  }

  /**
   * Find the scrollable container (.chat-wrapper)
   */
  private findScrollContainer(): void {
    const chatHistory = this.elementRef.nativeElement
    this.scrollContainer = chatHistory.closest('.chat-wrapper')

    if (this.scrollContainer) {
      console.log('[CHAT-HISTORY] Scroll container found')
      // Add scroll listener on the correct element
      this.scrollContainer.addEventListener('scroll', this.handleScroll.bind(this))
      // Initialize position
      this.lastScrollTop = this.scrollContainer.scrollTop
      this.checkScrollPosition()
    } else {
      console.warn('[CHAT-HISTORY] Scroll container not found')
      // Retry later
      setTimeout(() => this.findScrollContainer(), 100)
    }
  }

  /**
   * Scroll handler on the container
   */
  private handleScroll = (): void => {
    if (!this.scrollContainer) return

    const currentScrollTop = this.scrollContainer.scrollTop
    const scrollDirection = currentScrollTop > this.lastScrollTop ? 'down' : 'up'
    const scrollDelta = Math.abs(currentScrollTop - this.lastScrollTop)

    // Ignore micro-scrolls (might be programmatic scroll)
    if (scrollDelta < 5) {
      this.lastScrollTop = currentScrollTop
      return
    }

    console.log(
      '[CHAT-HISTORY] Scroll direction:',
      scrollDirection,
      'delta:',
      scrollDelta,
      'position:',
      currentScrollTop
    )

    // Note: We could detect user vs programmatic scroll here if needed

    // If user scrolls up significantly, exit tracking mode
    if (scrollDirection === 'up' && this.isTracking && scrollDelta > 10) {
      console.log('[CHAT-HISTORY] User scrolled up significantly - exiting tracking mode')
      this.isTracking = false
      this.showGoToBottom = true
    }

    // Check if we're near the bottom
    this.checkScrollPosition()

    this.lastScrollTop = currentScrollTop

    // Debounce to avoid too frequent calls
    clearTimeout(this.scrollCheckTimeout)
    this.scrollCheckTimeout = setTimeout(() => {
      // Can be used for future optimizations
    }, 150)
  }

  /**
   * Check scroll position and adjust state
   */
  private checkScrollPosition(): void {
    if (!this.scrollContainer) return

    const { scrollTop, scrollHeight, clientHeight } = this.scrollContainer
    const distanceFromBottom = scrollHeight - scrollTop - clientHeight
    const isNearBottom = distanceFromBottom <= this.NEAR_BOTTOM_THRESHOLD

    // If near bottom and not tracking, reactivate tracking
    if (isNearBottom && !this.isTracking) {
      console.log('[CHAT-HISTORY] Near bottom - entering tracking mode')
      this.isTracking = true
      this.showGoToBottom = false

      // Mark messages as read since user returned to bottom
      this.unreadService.markAllAsRead()
    }

    // If not tracking and far from bottom, ensure button is visible
    if (!this.isTracking && !isNearBottom) {
      this.showGoToBottom = true
    }
  }

  /**
   * Go to bottom of conversation (triggered by button)
   */
  goToBottom(): void {
    console.log('[CHAT-HISTORY] Go to bottom clicked')
    this.scrollToBottom()
    this.isTracking = true
    this.showGoToBottom = false

    // Mark all messages as read
    this.unreadService.markAllAsRead()
  }

  /**
   * Handle arrival of new messages
   */
  private handleNewMessages(newMessagesCount: number): void {
    // Filter only new assistant messages
    const newMessages = this.messages.slice(-newMessagesCount)
    const newAssistantMessages = newMessages.filter((msg) => msg.role === 'assistant')

    if (newAssistantMessages.length === 0) {
      console.log('[CHAT-HISTORY] No new assistant messages to count')
      return
    }

    const shouldMarkAsUnread = this.shouldMarkNewMessagesAsUnread()

    if (shouldMarkAsUnread) {
      console.log('[CHAT-HISTORY] Marking', newAssistantMessages.length, 'new assistant messages as unread')
      for (let i = 0; i < newAssistantMessages.length; i++) {
        this.unreadService.addUnread()
      }
    } else {
      console.log('[CHAT-HISTORY] New assistant messages are considered read (user is focused and tracking)')
    }
  }

  /**
   * Determine if new messages should be marked as unread
   */
  private shouldMarkNewMessagesAsUnread(): boolean {
    // Condition 1: Tab doesn't have focus
    if (!document.hasFocus()) {
      console.log('[CHAT-HISTORY] Tab does not have focus -> unread')
      return true
    }

    // Condition 2: User is not in tracking mode (scrolled up)
    if (!this.isTracking) {
      console.log('[CHAT-HISTORY] User is not tracking (scrolled up) -> unread')
      return true
    }

    // Otherwise, messages are considered read
    return false
  }

  /**
   * Set up listeners for window focus/blur
   */
  private setupFocusListeners(): void {
    window.addEventListener('focus', this.handleWindowFocus)
    window.addEventListener('blur', this.handleWindowBlur)
  }

  /**
   * Handler when window regains focus
   */
  private handleWindowFocus = (): void => {
    console.log('[CHAT-HISTORY] Window gained focus')

    // If user is in tracking mode, mark messages as read
    if (this.isTracking) {
      console.log('[CHAT-HISTORY] User is tracking and focused -> marking messages as read')
      this.unreadService.markAllAsRead()
    }
  }

  /**
   * Handler when window loses focus
   */
  private handleWindowBlur = (): void => {
    console.log('[CHAT-HISTORY] Window lost focus')
    // No special action needed, new messages will be marked as unread
  }

  /**
   * Gérer l'annonce vocale des nouveaux messages assistant
   */
  private handleVoiceAnnouncement(newMessagesCount: number): void {
    // Vérifier si l'annonce automatique est activée
    const announceEnabled = this.preferencesService.getVoiceAnnounceEnabled()
    if (!announceEnabled) {
      return
    }

    // Filtrer seulement les nouveaux messages assistant
    const newMessages = this.messages.slice(-newMessagesCount)
    const newAssistantMessages = newMessages.filter((msg) => msg.role === 'assistant')

    if (newAssistantMessages.length === 0) {
      console.log('[CHAT-HISTORY] No new assistant messages to announce')
      return
    }

    // Prendre le dernier message assistant pour l'annonce
    const lastAssistantMessage = newAssistantMessages[newAssistantMessages.length - 1]

    if (!lastAssistantMessage) {
      console.warn('[CHAT-HISTORY] No last assistant message found')
      return
    }

    // Vérifier si le message est assez récent
    if (!this.isMessageRecentEnoughForAnnouncement(lastAssistantMessage)) {
      console.log('[CHAT-HISTORY] Message too old for voice announcement')
      return
    }

    // Vérifier le mode vocal
    const voiceMode = this.preferencesService.getVoiceMode()

    if (voiceMode === 'notification') {
      // Mode notification : jouer un son
      console.log('[CHAT-HISTORY] Playing notification sound for new assistant message')
      this.voiceSynthesisService.ding()
    } else {
      // Mode speech : lire le message
      const textContent = this.extractTextContentFromMessage(lastAssistantMessage)
      if (textContent.trim()) {
        console.log('[CHAT-HISTORY] Announcing new assistant message via speech')

        // Déterminer si lecture complète ou partielle
        const readFullText = this.preferencesService.getVoiceReadFullText()
        const textToSpeak = readFullText ? textContent : this.extractPartialText(textContent)

        this.voiceSynthesisService
          .speak(textToSpeak)
          .then((success) => {
            if (!success) {
              console.warn('[CHAT-HISTORY] Failed to announce message via speech')
            }
          })
          .catch((error) => {
            console.error('[CHAT-HISTORY] Error during voice announcement:', error)
          })
      }
    }
  }

  /**
   * Vérifier si un message est assez récent pour l'annonce automatique
   */
  private isMessageRecentEnoughForAnnouncement(message: ChatMessage): boolean {
    try {
      const messageTime = message.timestamp.getTime()
      const now = Date.now()
      const timeDiff = now - messageTime

      return timeDiff <= this.MESSAGE_FRESHNESS_THRESHOLD
    } catch (error) {
      console.warn('[CHAT-HISTORY] Error checking message freshness:', error)
      return true // Si erreur, supposer que c'est récent
    }
  }

  /**
   * Extraire le contenu textuel d'un message
   */
  private extractTextContentFromMessage(message: ChatMessage): string {
    return message.content
      .filter((content) => content.type === 'text')
      .map((content) => content.content)
      .join('\n\n')
  }

  /**
   * Extraire une partie du texte pour la lecture partielle
   * (porté de l'ancienne logique)
   */
  private extractPartialText(text: string): string {
    const PARAGRAPH_MIN_LENGTH = 80
    const MAX_PARAGRAPHS = 3

    const paragraphs = text.split('\n').filter((p) => p.trim().length > 0)

    const result = paragraphs.reduce(
      (acc, paragraph) => {
        if (acc.paragraphs >= MAX_PARAGRAPHS) {
          return acc
        }

        const paragraphIncrement = paragraph.length > PARAGRAPH_MIN_LENGTH ? 1 : 0
        return {
          paragraphs: acc.paragraphs + paragraphIncrement,
          text: acc.text + (acc.text ? '\n' : '') + paragraph,
        }
      },
      { paragraphs: 0, text: '' }
    )

    return result.text || text // Fallback au texte complet si rien n'est extrait
  }
}
