import { Component, Input, Output, EventEmitter, OnInit } from '@angular/core'
import { CommonModule } from '@angular/common'
import { DomSanitizer, SafeHtml } from '@angular/platform-browser'
import { marked } from 'marked'
import { MessageContent } from '@coday/coday-events'

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
export class ChatMessageComponent implements OnInit {
  @Input() message!: ChatMessage
  @Output() playRequested = new EventEmitter<ChatMessage>()
  @Output() copyRequested = new EventEmitter<ChatMessage>()
  
  renderedContent: SafeHtml = ''
  
  constructor(private sanitizer: DomSanitizer) {}
  
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
    this.playRequested.emit(this.message)
  }
  
  onCopy() {
    this.copyRequested.emit(this.message)
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