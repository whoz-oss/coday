import { Component, Input, Output, EventEmitter } from '@angular/core'
import { CommonModule } from '@angular/common'
import { ChatMessageComponent, ChatMessage } from '../chat-message/chat-message.component'

@Component({
  selector: 'app-chat-history',
  standalone: true,
  imports: [CommonModule, ChatMessageComponent],
  template: `
    <div class="chat-history">
      <app-chat-message
        *ngFor="let message of messages; trackBy: trackByMessageId"
        [message]="message"
        (playRequested)="onPlayMessage($event)"
        (copyRequested)="onCopyMessage($event)"
      ></app-chat-message>
      
      <div *ngIf="isThinking" class="thinking">
        <div class="dots">
          <span></span>
          <span></span>
          <span></span>
        </div>
        <button class="stop-btn" (click)="onStop()">
          Stop
        </button>
      </div>
    </div>
  `,
  styles: [`
    .chat-history {
      flex: 1;
      overflow-y: auto;
      padding: 1rem;
      background: #ffffff;
    }
    
    .thinking {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.75rem;
      background: #f8fafc;
      border-radius: 6px;
      margin: 0.5rem 0;
    }
    
    .dots {
      display: flex;
      gap: 0.2rem;
    }
    
    .dots span {
      width: 6px;
      height: 6px;
      background: #3b82f6;
      border-radius: 50%;
      animation: bounce 1.4s infinite ease-in-out;
    }
    
    .dots span:nth-child(1) { animation-delay: -0.32s; }
    .dots span:nth-child(2) { animation-delay: -0.16s; }
    
    @keyframes bounce {
      0%, 80%, 100% { transform: scale(0); }
      40% { transform: scale(1); }
    }
    
    .stop-btn {
      background: #ef4444;
      color: white;
      border: none;
      padding: 0.3rem 0.6rem;
      border-radius: 4px;
      cursor: pointer;
      font-size: 0.8rem;
    }
    
    .stop-btn:hover {
      background: #dc2626;
    }
  `]
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