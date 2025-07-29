import { Component, OnInit, OnDestroy } from '@angular/core'
import { CommonModule } from '@angular/common'
import { Subject } from 'rxjs'
import { takeUntil } from 'rxjs/operators'
import { trigger, transition, style, animate } from '@angular/animations'

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
  animations: [
    trigger('slideIn', [
      transition(':enter', [
        style({ transform: 'translateY(100%)', opacity: 0 }),
        animate('300ms ease-out', style({ transform: 'translateY(0)', opacity: 1 }))
      ]),
      transition(':leave', [
        animate('200ms ease-in', style({ transform: 'translateY(100%)', opacity: 0 }))
      ])
    ])
  ],
  styles: [`
    :host {
      display: block;
      height: 100vh;
      overflow: hidden;
    }

    .app {
      height: 100%;
      display: flex;
      flex-direction: column;
      background: var(--color-bg, #f8f8f2);
      color: var(--color-text, #282a36);
    }

    .connection-status {
      padding: 0.5rem 1rem;
      text-align: center;
      font-size: 0.9rem;
      background: var(--color-error, #ff5555);
      color: var(--color-text-inverse, #ffffff);
      border-bottom: 1px solid var(--color-border, #aeaeae);
      flex-shrink: 0;
    }

    app-header {
      flex-shrink: 0;
    }

    .chat-wrapper {
      flex: 1;
      min-height: 0; /* Important pour flex */
      overflow-y: auto;
      overflow-x: hidden;
      background: var(--color-input-bg, #ffffff);
      
      /* Force scrollbar visibility on all platforms */
      &::-webkit-scrollbar {
        width: 12px;
      }
      
      &::-webkit-scrollbar-track {
        background: var(--color-bg-secondary, #f1f1f1);
        border: 1px solid var(--color-border, #e5e7eb);
      }
      
      &::-webkit-scrollbar-thumb {
        background: var(--color-text-secondary, #888);
        border-radius: 6px;
        border: 2px solid transparent;
        background-clip: padding-box;
        
        &:hover {
          background: var(--color-text, #555);
        }
      }
      
      /* Firefox */
      scrollbar-width: thin;
      scrollbar-color: var(--color-text-secondary, #888) var(--color-bg-secondary, #f1f1f1);
    }

    .input-section {
      flex-shrink: 0;
      border-top: 1px solid var(--color-border, #e5e7eb);
      background: var(--color-bg, #f8f8f2);
      position: relative;
      overflow: hidden;
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
    this.codayService.messages$
      .pipe(takeUntil(this.destroy$))
      .subscribe(messages => {
        console.log('[MAIN-APP] Messages updated:', messages.length)
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
    this.codayService.start()
  }

  ngOnDestroy(): void {
    this.destroy$.next()
    this.destroy$.complete()
  }

  onMessageSubmitted(message: string): void {
    console.log('[MAIN-APP] Sending message:', message)
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