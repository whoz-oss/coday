import { Component, Input, Output, EventEmitter } from '@angular/core'
import { CommonModule } from '@angular/common'

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
export class ChatMessageComponent {
  @Input() message!: ChatMessage
  @Output() playRequested = new EventEmitter<ChatMessage>()
  @Output() copyRequested = new EventEmitter<ChatMessage>()
  
  get messageClasses() {
    return {
      [this.message.role]: true,
      [this.message.type]: true
    }
  }
  
  get shouldShowSpeaker(): boolean {
    // Ne pas afficher le speaker pour les messages système/techniques
    return this.message.type !== 'technical' && this.message.speaker !== 'System'
  }
  
  onPlay() {
    this.playRequested.emit(this.message)
  }
  
  onCopy() {
    this.copyRequested.emit(this.message)
  }
}