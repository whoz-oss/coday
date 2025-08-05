import { Component, Input, Output, EventEmitter, ElementRef, AfterViewChecked } from '@angular/core'
import { CommonModule } from '@angular/common'
import { ChatMessageComponent, ChatMessage } from '../chat-message/chat-message.component'

@Component({
  selector: 'app-chat-history',
  standalone: true,
  imports: [CommonModule, ChatMessageComponent],
  templateUrl: './chat-history.component.html',
  styleUrl: './chat-history.component.scss'
})
export class ChatHistoryComponent implements AfterViewChecked {
  @Input() messages: ChatMessage[] = []
  @Input() isThinking: boolean = false
  @Output() playRequested = new EventEmitter<ChatMessage>()
  @Output() copyRequested = new EventEmitter<ChatMessage>()
  @Output() stopRequested = new EventEmitter<void>()
  
  private lastMessageCount = 0
  
  constructor(
    private elementRef: ElementRef
  ) {}
  
  ngAfterViewChecked() {
    // Check if we need to scroll after each view update
    if (this.messages.length !== this.lastMessageCount) {
      console.log('[CHAT-HISTORY] Message count changed:', this.lastMessageCount, '->', this.messages.length)
      this.lastMessageCount = this.messages.length
      this.scrollToBottom()
    }
  }
  
  private scrollToBottom() {
    try {
      // Find the scrollable parent (.chat-wrapper)
      const chatHistory = this.elementRef.nativeElement
      const scrollableParent = chatHistory.closest('.chat-wrapper')
      
      if (scrollableParent) {
        // Small delay to ensure DOM is updated
        setTimeout(() => {
          scrollableParent.scrollTo({
            top: scrollableParent.scrollHeight,
            behavior: 'smooth'
          })
        }, 100)
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
}