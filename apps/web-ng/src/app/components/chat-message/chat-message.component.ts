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
    // Ne pas afficher le speaker pour les messages système/techniques
    return this.message.type !== 'technical' && this.message.speaker !== 'System'
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