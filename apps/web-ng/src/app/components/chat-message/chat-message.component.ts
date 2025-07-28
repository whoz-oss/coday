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
  template: `
    <div class="message" [ngClass]="messageClasses">
      <div class="speaker" *ngIf="message.speaker">
        {{ message.speaker }}
      </div>
      
      <div class="content">
        {{ message.content }}
      </div>
      
      <div class="actions">
        <button 
          class="action-btn" 
          (click)="onPlay()"
          title="Play message"
        >
          ▶️
        </button>
        
        <button 
          class="action-btn" 
          (click)="onCopy()"
          title="Copy message"
        >
          📋
        </button>
      </div>
    </div>
  `,
  styles: [`
    .message {
      margin: 0.5rem 0;
      padding: 1rem;
      border-radius: 6px;
      position: relative;
    }
    
    /* Role-based styling */
    .message.user {
      background: #e0f2fe;
      margin-left: 2rem;
      border-left: 3px solid #0ea5e9;
    }
    
    .message.assistant {
      background: #f0f9ff;
      margin-right: 2rem;
      border-left: 3px solid #3b82f6;
    }
    
    .message.system {
      background: #f8fafc;
      border-left: 3px solid #64748b;
    }
    
    /* Type-based styling */
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
      font-size: 0.85rem;
      color: #6b7280;
      margin-bottom: 0.25rem;
    }
    
    .content {
      line-height: 1.5;
      margin-bottom: 0.5rem;
    }
    
    .actions {
      display: flex;
      gap: 0.5rem;
      opacity: 0;
      transition: opacity 0.2s;
    }
    
    .message:hover .actions {
      opacity: 1;
    }
    
    .action-btn {
      background: none;
      border: 1px solid #d1d5db;
      border-radius: 3px;
      padding: 0.2rem 0.4rem;
      cursor: pointer;
      font-size: 0.7rem;
    }
    
    .action-btn:hover {
      background: #f3f4f6;
    }
  `]
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
  
  onPlay() {
    this.playRequested.emit(this.message)
  }
  
  onCopy() {
    this.copyRequested.emit(this.message)
  }
}