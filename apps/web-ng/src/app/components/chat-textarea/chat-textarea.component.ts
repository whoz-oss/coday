import { Component, Output, EventEmitter, Input } from '@angular/core'
import { CommonModule } from '@angular/common'
import { FormsModule } from '@angular/forms'

@Component({
  selector: 'app-chat-textarea',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="chat-input-container">
      <div class="input-wrapper">
        <textarea
          #messageInput
          class="message-input"
          [(ngModel)]="message"
          placeholder="Type your message here..."
          (keydown)="onKeyDown($event)"
          [disabled]="isDisabled"
          rows="3"
        ></textarea>
        
        <div class="input-actions">
          <button
            class="voice-btn"
            [class.recording]="isRecording"
            (click)="toggleVoiceRecording()"
            title="Voice input"
          >
            🎤
          </button>
          
          <button
            class="send-btn"
            (click)="sendMessage()"
            [disabled]="!message.trim() || isDisabled"
            title="Send message"
          >
            Send
          </button>
        </div>
      </div>
      
      <div *ngIf="isRecording" class="recording-indicator">
        <span class="recording-dot"></span>
        Recording... Click microphone to stop
      </div>
    </div>
  `,
  styles: [`
    .chat-input-container {
      padding: 1rem;
      background: #ffffff;
      border-top: 1px solid #e5e7eb;
    }
    
    .input-wrapper {
      display: flex;
      gap: 0.5rem;
      align-items: flex-end;
    }
    
    .message-input {
      flex: 1;
      padding: 0.75rem;
      border: 1px solid #d1d5db;
      border-radius: 8px;
      resize: vertical;
      min-height: 60px;
      font-family: inherit;
      font-size: 1rem;
      line-height: 1.5;
    }
    
    .message-input:focus {
      outline: none;
      border-color: #3b82f6;
      box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1);
    }
    
    .message-input:disabled {
      background-color: #f9fafb;
      color: #6b7280;
    }
    
    .input-actions {
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
    }
    
    .voice-btn, .send-btn {
      padding: 0.75rem;
      border: 1px solid #d1d5db;
      border-radius: 6px;
      background: #ffffff;
      cursor: pointer;
      transition: all 0.2s;
    }
    
    .voice-btn {
      font-size: 1.2rem;
    }
    
    .voice-btn.recording {
      background: #fef2f2;
      border-color: #ef4444;
      animation: pulse 1.5s infinite;
    }
    
    .send-btn {
      background: #3b82f6;
      color: white;
      border-color: #3b82f6;
      font-weight: 500;
    }
    
    .send-btn:hover:not(:disabled) {
      background: #2563eb;
      border-color: #2563eb;
    }
    
    .send-btn:disabled {
      background: #9ca3af;
      border-color: #9ca3af;
      cursor: not-allowed;
    }
    
    .voice-btn:hover {
      background: #f3f4f6;
    }
    
    .recording-indicator {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      margin-top: 0.5rem;
      color: #ef4444;
      font-size: 0.9rem;
    }
    
    .recording-dot {
      width: 8px;
      height: 8px;
      background: #ef4444;
      border-radius: 50%;
      animation: pulse 1.5s infinite;
    }
    
    @keyframes pulse {
      0%, 100% { opacity: 1; }
      50% { opacity: 0.5; }
    }
  `]
})
export class ChatTextareaComponent {
  @Input() isDisabled: boolean = false
  @Output() messageSubmitted = new EventEmitter<string>()
  @Output() voiceRecordingToggled = new EventEmitter<boolean>()
  
  message: string = ''
  isRecording: boolean = false
  
  onKeyDown(event: KeyboardEvent) {
    // TODO: Add preference for Enter to send vs Shift+Enter
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      this.sendMessage()
    }
  }
  
  sendMessage() {
    if (this.message.trim() && !this.isDisabled) {
      this.messageSubmitted.emit(this.message.trim())
      this.message = ''
    }
  }
  
  toggleVoiceRecording() {
    this.isRecording = !this.isRecording
    this.voiceRecordingToggled.emit(this.isRecording)
    
    // TODO: Integrate with speech-to-text service
    console.log('Voice recording:', this.isRecording ? 'started' : 'stopped')
  }
  
  // TODO: Add speech-to-text integration
  // TODO: Add file upload drag & drop
  // TODO: Add preferences integration for Enter behavior
}