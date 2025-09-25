import { Component, Input, Output, EventEmitter, OnInit, OnDestroy, inject } from '@angular/core'
import { CommonModule } from '@angular/common'
import { DomSanitizer, SafeHtml } from '@angular/platform-browser'
import { marked } from 'marked'
import { MessageContent } from '@coday/coday-events'
import { VoiceSynthesisService } from '../../services/voice-synthesis.service'
import { Subject } from 'rxjs'
import { takeUntil } from 'rxjs/operators'

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant' | 'system'
  speaker: string
  content: MessageContent[] // Toujours du contenu riche maintenant
  timestamp: Date
  type: 'text' | 'error' | 'warning' | 'technical'
  eventId?: string // Pour les liens vers les événements
}

@Component({
  selector: 'app-chat-message',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './chat-message.component.html',
  styleUrl: './chat-message.component.scss'
})
export class ChatMessageComponent implements OnInit, OnDestroy {
  @Input() message!: ChatMessage
  @Input() canDelete: boolean = true // Can this message be deleted (not first message, not during thinking)
  @Output() playRequested = new EventEmitter<ChatMessage>()
  @Output() copyRequested = new EventEmitter<ChatMessage>()
  @Output() deleteRequested = new EventEmitter<ChatMessage>()
  
  renderedContent: SafeHtml = ''
  private destroy$ = new Subject<void>()
  
  // État du bouton play
  isPlaying = false
  
  // Modern Angular dependency injection
  private sanitizer = inject(DomSanitizer)
  private voiceSynthesisService = inject(VoiceSynthesisService)
  
  get messageClasses() {
    return {
      [this.message.role]: true,
      [this.message.type]: true
    }
  }
  
  async ngOnInit() {
    await this.renderMarkdown()
    
    // Écouter l'état global de la synthèse vocale
    this.voiceSynthesisService.speaking$
      .pipe(takeUntil(this.destroy$))
      .subscribe(globalSpeaking => {
        // Si la synthèse globale s'arrête et que ce message était en train de jouer
        if (!globalSpeaking && this.isPlaying) {
          this.isPlaying = false
        }
      })
  }
  
  ngOnDestroy(): void {
    this.destroy$.next()
    this.destroy$.complete()
  }
  
  get shouldShowSpeaker(): boolean {
    // Afficher le speaker pour user et assistant, pas pour les autres
    return this.message.role === 'user' || this.message.role === 'assistant'
  }
  
  get shouldShowActions(): boolean {
    // Afficher les actions seulement pour user et assistant
    return this.message.role === 'user' || this.message.role === 'assistant'
  }
  
  get isSimplified(): boolean {
    // Messages simplifiés pour tout ce qui n'est pas user/assistant
    return this.message.role !== 'user' && this.message.role !== 'assistant'
  }
  
  get eventLink(): string | null {
    if (!this.message.eventId) return null
    
    // Obtenir le clientId depuis l'URL
    const params = new URLSearchParams(window.location.search)
    const clientId = params.get('clientId')
    if (!clientId) return null
    
    return `/api/event/${this.message.eventId}?clientId=${clientId}`
  }
  
  private async renderMarkdown() {
    try {
      // Toujours utiliser le contenu riche maintenant
      const html = await this.renderRichContent(this.message.content)
      this.renderedContent = this.sanitizer.bypassSecurityTrustHtml(html)
    } catch (error) {
      console.error('Error parsing rich content:', error)
      // Fallback: essayer d'extraire le texte et l'afficher
      const textContent = this.extractTextContent()
      this.renderedContent = this.sanitizer.bypassSecurityTrustHtml(textContent)
    }
  }
  
  private async renderRichContent(content: MessageContent[]): Promise<string> {
    const htmlParts: string[] = []
    
    for (const item of content) {
      if (item.type === 'text') {
        // Parse markdown for text content
        const html = await marked.parse(item.content)
        htmlParts.push(`<div class="text-part">${html}</div>`)
      } else if (item.type === 'image') {
        // Create image element
        const imgSrc = `data:${item.mimeType};base64,${item.content}`
        const imgAlt = item.source || 'Image'
        const imgHtml = `
          <div class="image-content">
            <img src="${imgSrc}" 
                 alt="${imgAlt}" 
                 class="message-image"
                 style="max-width: 100%; height: auto; margin: 8px 0; border-radius: 8px; cursor: pointer; box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1); transition: transform 0.2s ease, box-shadow 0.2s ease;"
                 onclick="window.open(this.src, '_blank')" />
          </div>
        `
        htmlParts.push(imgHtml)
      }
    }
    
    return htmlParts.join('')
  }
  
  onPlay() {
    const textContent = this.getTextContentForVoice()
    
    if (!textContent.trim()) {
      console.warn('[CHAT-MESSAGE] No text content to play')
      return
    }
    
    if (this.isPlaying) {
      // Arrêter la lecture en cours
      console.log('[CHAT-MESSAGE] Stopping current playback')
      this.voiceSynthesisService.stopSpeech()
      this.isPlaying = false
    } else {
      // Démarrer une nouvelle lecture
      console.log('[CHAT-MESSAGE] Starting playback for message:', this.message.id)
      
      // Callback pour remettre le bouton en état normal quand la lecture se termine
      const onEndCallback = () => {
        console.log('[CHAT-MESSAGE] Playback ended for message:', this.message.id)
        this.isPlaying = false
      }
      
      this.voiceSynthesisService.speak(textContent, onEndCallback)
        .then(success => {
          if (success) {
            this.isPlaying = true
          } else {
            console.warn('[CHAT-MESSAGE] Failed to start speech synthesis')
          }
        })
        .catch(error => {
          console.error('[CHAT-MESSAGE] Error during speech synthesis:', error)
          this.isPlaying = false
        })
    }
    
    // Émettre l'événement pour compatibilité (si nécessaire pour d'autres composants)
    this.playRequested.emit(this.message)
  }
  
  onCopy() {
    const textContent = this.getTextContentForVoice()
    
    if (textContent.trim()) {
      navigator.clipboard.writeText(textContent)
        .then(() => {
          console.log('[CHAT-MESSAGE] Message copied to clipboard')
        })
        .catch(err => {
          console.error('[CHAT-MESSAGE] Failed to copy message:', err)
        })
    }
    
    // Émettre l'événement pour compatibilité
    this.copyRequested.emit(this.message)
  }

  onDelete() {
    console.log('[CHAT-MESSAGE] Delete requested for message:', this.message.id)
    this.deleteRequested.emit(this.message)
  }
  
  /**
   * Gestionnaire de clic sur le message - arrête la synthèse vocale
   */
  onMessageClick(): void {
    // Arrêter la synthèse vocale si elle est en cours
    if (this.voiceSynthesisService.isSpeaking()) {
      this.voiceSynthesisService.stopSpeech()
    }
  }
  
  /**
   * Extract all text content from rich content for voice synthesis and copying
   */
  getTextContentForVoice(): string {
    return this.extractTextContent()
  }
  
  /**
   * Extract text content from message content
   */
  private extractTextContent(): string {
    return this.message.content
      .filter(content => content.type === 'text')
      .map(content => content.content)
      .join('\n\n')
  }
}