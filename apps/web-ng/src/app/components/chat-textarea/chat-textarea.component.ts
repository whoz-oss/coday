import { Component, Output, EventEmitter, Input } from '@angular/core'
import { CommonModule } from '@angular/common'
import { FormsModule } from '@angular/forms'

@Component({
  selector: 'app-chat-textarea',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './chat-textarea.component.html',
  styleUrl: './chat-textarea.component.scss'
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