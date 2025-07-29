import { Component, Input, Output, EventEmitter, OnInit } from '@angular/core'
import { CommonModule } from '@angular/common'
import { DomSanitizer, SafeHtml } from '@angular/platform-browser'
import { marked } from 'marked'

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant' | 'system'
  speaker: string
  content: string
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
      // Parse markdown to HTML
      const html = await marked.parse(this.message.content)
      // Sanitize the HTML for security
      this.renderedContent = this.sanitizer.bypassSecurityTrustHtml(html)
    } catch (error) {
      console.error('Error parsing markdown:', error)
      // Fallback to plain text
      this.renderedContent = this.sanitizer.bypassSecurityTrustHtml(this.message.content)
    }
  }
  
  onPlay() {
    this.playRequested.emit(this.message)
  }
  
  onCopy() {
    this.copyRequested.emit(this.message)
  }
}