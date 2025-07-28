import { Component, Input } from '@angular/core'
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
  selector: 'app-chat-history',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="chat-history" #chatContainer>
      <div 
        *ngFor="let message of messages; trackBy: trackByMessageId"
        class="message"
        [ngClass]="{
          'left': message.role === 'assistant' || message.role === 'system',
          'right': message.role === 'user',
          'error': message.type === 'error',
          'warning': message.type === 'warning',
          'technical': message.type === 'technical'
        }"
      >
        <div class="speaker" *ngIf="message.speaker">
          {{ message.speaker }}
        </div>
        
        <div class="message-content" [innerHTML]="message.content">
        </div>
        
        <div class="message-actions">
          <button 
            class="action-btn play-btn" 
            title="Play message"
            (click)="playMessage(message)"
          >
            ▶️
          </button>
          
          <button 
            class="action-btn copy-btn" 
            title="Copy message"
            (click)="copyMessage(message)"
          >
            📋
          </button>
        </div>
      </div>
      
      <div 
        *ngIf="isThinking" 
        class="thinking-indicator"
      >
        <div class="thinking-dots">
          <span></span>
          <span></span>
          <span></span>
        </div>
        <button 
          class="stop-btn"
          (click)="onStop()"
        >
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
    
    .message {
      margin: 1rem 0;
      padding: 1rem;
      border-radius: 8px;
      position: relative;
    }
    
    .message.left {
      background: #f8fafc;
      border-left: 3px solid #3b82f6;
    }
    
    .message.right {
      background: #eff6ff;
      border-right: 3px solid #10b981;
      margin-left: 2rem;
    }
    
    .message.error {
      background: #fef2f2;
      border-left: 3px solid #ef4444;
      color: #dc2626;
    }
    
    .message.warning {
      background: #fffbeb;
      border-left: 3px solid #f59e0b;
      color: #d97706;
    }
    
    .message.technical {
      background: #f1f5f9;
      border-left: 3px solid #64748b;
      color: #475569;
      font-family: monospace;
      font-size: 0.9rem;
    }
    
    .speaker {
      font-weight: 600;
      color: #374151;
      margin-bottom: 0.5rem;
      font-size: 0.9rem;
    }
    
    .message-content {
      margin-bottom: 0.5rem;
      line-height: 1.6;
    }
    
    .message-actions {
      display: flex;
      gap: 0.5rem;
      opacity: 0;
      transition: opacity 0.2s;
    }
    
    .message:hover .message-actions {
      opacity: 1;
    }
    
    .action-btn {
      background: none;
      border: 1px solid #d1d5db;
      border-radius: 4px;
      padding: 0.25rem 0.5rem;
      cursor: pointer;
      font-size: 0.8rem;
    }
    
    .action-btn:hover {
      background: #f3f4f6;
    }
    
    .thinking-indicator {
      display: flex;
      align-items: center;
      gap: 1rem;
      padding: 1rem;
      background: #f8fafc;
      border-radius: 8px;
      margin: 1rem 0;
    }
    
    .thinking-dots {
      display: flex;
      gap: 0.25rem;
    }
    
    .thinking-dots span {
      width: 8px;
      height: 8px;
      background: #3b82f6;
      border-radius: 50%;
      animation: thinking 1.4s infinite ease-in-out;
    }
    
    .thinking-dots span:nth-child(1) { animation-delay: -0.32s; }
    .thinking-dots span:nth-child(2) { animation-delay: -0.16s; }
    
    @keyframes thinking {
      0%, 80%, 100% { transform: scale(0); }
      40% { transform: scale(1); }
    }
    
    .stop-btn {
      background: #ef4444;
      color: white;
      border: none;
      padding: 0.5rem 1rem;
      border-radius: 4px;
      cursor: pointer;
    }
    
    .stop-btn:hover {
      background: #dc2626;
    }
  `]
})
export class ChatHistoryComponent {
  @Input() messages: ChatMessage[] = []
  @Input() isThinking: boolean = false
  
  trackByMessageId(_index: number, message: ChatMessage): string {
    return message.id
  }
  
  playMessage(message: ChatMessage) {
    // TODO: Integrate with voice synthesis service
    console.log('Play message:', message.content)
  }
  
  copyMessage(message: ChatMessage) {
    navigator.clipboard.writeText(message.content)
      .then(() => console.log('Message copied'))
      .catch(err => console.error('Failed to copy:', err))
  }
  
  onStop() {
    // TODO: Connect to stop callback
    console.log('Stop requested')
  }
  
  // TODO: Add drag & drop for image upload
  // TODO: Add markdown parsing
  // TODO: Add voice synthesis integration
  // TODO: Add tool request/response handling
}