import { Component, Input, Output, EventEmitter } from '@angular/core'
import { CommonModule } from '@angular/common'
import { ChatMessageComponent, ChatMessage } from '../chat-message/chat-message.component'

@Component({
  selector: 'app-chat-history',
  standalone: true,
  imports: [CommonModule, ChatMessageComponent],
  templateUrl: './chat-history.component.html',
  styleUrl: './chat-history.component.scss'
})
export class ChatHistoryComponent {
  @Input() messages: ChatMessage[] = []
  @Input() isThinking: boolean = false
  @Output() playRequested = new EventEmitter<ChatMessage>()
  @Output() copyRequested = new EventEmitter<ChatMessage>()
  @Output() stopRequested = new EventEmitter<void>()
  
  trackByMessageId(_index: number, message: ChatMessage): string {
    return message.id
  }
  
  onPlayMessage(message: ChatMessage) {
    this.playRequested.emit(message)
  }
  
  onCopyMessage(message: ChatMessage) {
    navigator.clipboard.writeText(message.content)
      .then(() => console.log('Message copied'))
      .catch(err => console.error('Failed to copy:', err))
    
    this.copyRequested.emit(message)
  }
  
  onStop() {
    this.stopRequested.emit()
  }
}