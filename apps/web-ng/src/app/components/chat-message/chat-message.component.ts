import { Component, Input, Output, EventEmitter, OnInit, inject } from '@angular/core'
import { CommonModule } from '@angular/common'
import { DomSanitizer, SafeHtml } from '@angular/platform-browser'
import { marked } from 'marked'
import { MessageContent } from '@coday/coday-events'
import { MessageContextMenuComponent, MenuAction } from '../message-context-menu/message-context-menu.component'

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
  imports: [CommonModule, MessageContextMenuComponent],
  templateUrl: './chat-message.component.html',
  styleUrl: './chat-message.component.scss'
})
export class ChatMessageComponent implements OnInit {
  @Input() message!: ChatMessage
  @Input() canDelete: boolean = true // Can this message be deleted (not first message, not during thinking)
  @Output() copyRequested = new EventEmitter<ChatMessage>()
  @Output() deleteRequested = new EventEmitter<ChatMessage>()
  
  renderedContent: SafeHtml = ''
  
  // Modern Angular dependency injection
  private sanitizer = inject(DomSanitizer)
  
  get messageClasses() {
    return {
      [this.message.role]: true,
      [this.message.type]: true
    }
  }
  
  async ngOnInit() {
    await this.renderMarkdown()
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
  
  get isLongMessage(): boolean {
    const textContent = this.extractTextContent()
    return textContent.length > 1000 || textContent.split('\n').length > 20
  }
  
  get topActions(): MenuAction[] {
    const actions: MenuAction[] = [
      {
        icon: '📋',
        label: 'Copy message',
        tooltip: 'Copy message content to clipboard',
        action: () => this.onCopy()
      }
    ]
    
    if (this.message.role === 'user' && this.canDelete) {
      actions.push({
        icon: '🗑️',
        label: 'Delete from here',
        tooltip: 'Delete this message and all following messages',
        action: () => this.onDelete(),
        destructive: true
      })
    }
    
    return actions
  }
  
  get bottomActions(): MenuAction[] {
    // Pour le menu du bas, on ne met que le copy (pas de delete)
    return [
      {
        icon: '📋',
        label: 'Copy message',
        tooltip: 'Copy message content to clipboard',
        action: () => this.onCopy()
      }
    ]
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
  
  onCopy() {
    const textContent = this.extractTextContent()
    
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
   * Extract text content from message content
   */
  private extractTextContent(): string {
    return this.message.content
      .filter(content => content.type === 'text')
      .map(content => content.content)
      .join('\n\n')
  }
}