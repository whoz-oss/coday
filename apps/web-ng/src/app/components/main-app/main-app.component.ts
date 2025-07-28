import { Component, OnInit, OnDestroy } from '@angular/core'
import { CommonModule } from '@angular/common'
import { Subject } from 'rxjs'
import { takeUntil } from 'rxjs/operators'

import { HeaderComponent } from '../header/header.component'
import { ChatHistoryComponent } from '../chat-history/chat-history.component'
import { ChatMessage } from '../chat-message/chat-message.component'
import { ChatTextareaComponent } from '../chat-textarea/chat-textarea.component'
import { ChoiceSelectComponent, ChoiceOption } from '../choice-select/choice-select.component'

import { CodayService } from '../../core/services/coday.service'
import { ConnectionStatus } from '../../core/services/event-stream.service'

@Component({
  selector: 'app-main',
  standalone: true,
  imports: [CommonModule, HeaderComponent, ChatHistoryComponent, ChatTextareaComponent, ChoiceSelectComponent],
  templateUrl: './main-app.component.html',
  styles: [`
    .app {
      height: 100vh;
      display: flex;
      flex-direction: column;
      font-family: system-ui, sans-serif;
    }

    .connection-status {
      padding: 0.5rem 1rem;
      text-align: center;
      font-size: 0.9rem;
      background: #fef2f2;
      color: #dc2626;
      border-bottom: 1px solid #fecaca;
    }

    .chat-container {
      flex: 1;
      display: flex;
      flex-direction: column;
      min-height: 0;
    }
  `]
})
export class MainAppComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>()
  
  // State from services
  messages: ChatMessage[] = []
  isThinking: boolean = false
  currentChoice: {options: ChoiceOption[], label: string} | null = null
  connectionStatus: ConnectionStatus | null = null
  isConnected: boolean = false

  constructor(private codayService: CodayService) {}

  ngOnInit(): void {
    console.log('[MAIN-APP] Component initializing...')
    
    // Subscribe to service observables
    this.codayService.messages$
      .pipe(takeUntil(this.destroy$))
      .subscribe(messages => {
        console.log('[MAIN-APP] Received messages update:', {
          count: messages.length,
          messages: messages.map(m => ({ id: m.id, role: m.role, speaker: m.speaker, type: m.type }))
        })
        this.messages = messages
      })

    this.codayService.isThinking$
      .pipe(takeUntil(this.destroy$))
      .subscribe(isThinking => {
        this.isThinking = isThinking
      })

    this.codayService.currentChoice$
      .pipe(takeUntil(this.destroy$))
      .subscribe(choice => {
        this.currentChoice = choice
      })

    this.codayService.connectionStatus$
      .pipe(takeUntil(this.destroy$))
      .subscribe(status => {
        this.connectionStatus = status
        this.isConnected = status.connected
      })

    // Start the Coday service
    console.log('[MAIN-APP] Starting Coday service...')
    this.codayService.start()
    
    console.log('[MAIN-APP] Component initialization complete')
  }

  ngOnDestroy(): void {
    this.destroy$.next()
    this.destroy$.complete()
  }

  onMessageSubmitted(message: string): void {
    console.log('[MAIN-APP] User submitted message:', message)
    this.codayService.sendMessage(message)
  }

  onVoiceToggled(isRecording: boolean): void {
    console.log('[VOICE] Recording:', isRecording)
    // TODO: Implement speech-to-text
  }

  onChoiceSelected(choice: string): void {
    console.log('choosing selected', choice)
    this.codayService.sendChoice(choice)
  }

  onPlayMessage(message: ChatMessage): void {
    console.log('[VOICE] Play requested:', message.content)
    // TODO: Implement voice synthesis
  }

  onCopyMessage(message: ChatMessage): void {
    console.log('[COPY] Message copied:', message.id)
  }

  onStopRequested(): void {
    this.codayService.stop()
  }
}