import { Injectable, OnDestroy } from '@angular/core'
import { Subject, BehaviorSubject } from 'rxjs'
import { takeUntil } from 'rxjs/operators'
import { 
  CodayEvent, 
  MessageEvent, 
  TextEvent, 
  AnswerEvent, 
  ErrorEvent, 
  WarnEvent, 
  ThinkingEvent, 
  ToolRequestEvent, 
  ToolResponseEvent, 
  ChoiceEvent,
  ProjectSelectedEvent,
  HeartBeatEvent
} from '@coday/coday-events'

import { CodayApiService } from './coday-api.service'
import { EventStreamService } from './event-stream.service'

import { ChatMessage } from '../../components/chat-message/chat-message.component'
import { ChoiceOption } from '../../components/choice-select/choice-select.component'

@Injectable({
  providedIn: 'root'
})
export class CodayService implements OnDestroy {
  private destroy$ = new Subject<void>()
  
  // State subjects
  private messagesSubject = new BehaviorSubject<ChatMessage[]>([])
  private isThinkingSubject = new BehaviorSubject<boolean>(false)
  private currentChoiceSubject = new BehaviorSubject<{options: ChoiceOption[], label: string} | null>(null)
  private projectTitleSubject = new BehaviorSubject<string>('Coday')
  
  // Public observables
  messages$ = this.messagesSubject.asObservable()
  isThinking$ = this.isThinkingSubject.asObservable()
  currentChoice$ = this.currentChoiceSubject.asObservable()
  projectTitle$ = this.projectTitleSubject.asObservable()
  
  // Connection status will be initialized in constructor
  connectionStatus$!: typeof this.eventStream.connectionStatus$

  constructor(
    private codayApi: CodayApiService,
    private eventStream: EventStreamService
  ) {
    // Initialize connection status observable after eventStream is available
    this.connectionStatus$ = this.eventStream.connectionStatus$
    this.initializeEventHandling()
  }

  /**
   * Start the Coday service
   */
  start(): void {
    console.log('[CODAY] Starting service...')
    this.eventStream.connect()
  }

  /**
   * Stop the Coday service
   */
  stop(): void {
    console.log('[CODAY] Stopping service...')
    this.codayApi.stopExecution().subscribe({
      next: () => console.log('[CODAY] Stop signal sent'),
      error: (error) => console.error('[CODAY] Error stopping:', error)
    })
  }

  /**
   * Send a message
   */
  sendMessage(message: string): void {
    const answerEvent = new AnswerEvent({ answer: message })
    this.codayApi.sendEvent(answerEvent).subscribe({
      next: () => console.log('[CODAY] Message sent successfully'),
      error: (error) => console.error('[CODAY] Error sending message:', error)
    })
  }

  /**
   * Send a choice selection
   */
  sendChoice(choice: string): void {
    // Find the current choice event to build proper answer
    const currentChoice = this.currentChoiceSubject.value
    if (currentChoice) {
      // For now, create a simple answer event
      // TODO: Store the original ChoiceEvent to build proper answer
      const answerEvent = new AnswerEvent({ answer: choice })
      this.codayApi.sendEvent(answerEvent).subscribe({
        next: () => {
          console.log('[CODAY] Choice sent successfully')
          this.currentChoiceSubject.next(null) // Clear choice
        },
        error: (error) => console.error('[CODAY] Error sending choice:', error)
      })
    }
  }

  /**
   * Get current messages
   */
  getCurrentMessages(): ChatMessage[] {
    return this.messagesSubject.value
  }

  /**
   * Initialize event handling
   */
  private initializeEventHandling(): void {
    console.log('[CODAY-SERVICE] Initializing event handling...')
    
    this.eventStream.events$
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: event => {
          console.log('[CODAY-SERVICE] Received event from stream:', event.type, event)
          this.handleEvent(event)
        },
        error: error => {
          console.error('[CODAY-SERVICE] Error in event stream:', error)
        },
        complete: () => {
          console.log('[CODAY-SERVICE] Event stream completed')
        }
      })
  }

  /**
   * Handle incoming Coday events
   */
  private handleEvent(event: CodayEvent): void {
    console.log('[CODAY-SERVICE] ===== HANDLING EVENT =====', {
      type: event.type,
      timestamp: event.timestamp,
      event: event
    })

    if (event instanceof MessageEvent) {
      this.handleMessageEvent(event)
    } else if (event instanceof TextEvent) {
      this.handleTextEvent(event)
    } else if (event instanceof AnswerEvent) {
      this.handleAnswerEvent(event)
    } else if (event instanceof ErrorEvent) {
      this.handleErrorEvent(event)
    } else if (event instanceof WarnEvent) {
      this.handleWarnEvent(event)
    } else if (event instanceof ThinkingEvent) {
      this.handleThinkingEvent(event)
    } else if (event instanceof ToolRequestEvent) {
      this.handleToolRequestEvent(event)
    } else if (event instanceof ToolResponseEvent) {
      this.handleToolResponseEvent(event)
    } else if (event instanceof ChoiceEvent) {
      this.handleChoiceEvent(event)
    } else if (event instanceof ProjectSelectedEvent) {
      this.handleProjectSelectedEvent(event)
    } else if (event instanceof HeartBeatEvent) {
      this.handleHeartBeatEvent(event)
    } else {
      console.log('[CODAY-SERVICE] ===== UNHANDLED EVENT TYPE =====', {
        type: event.type,
        event: event
      })
    }
  }

  private handleMessageEvent(event: MessageEvent): void {
    console.log('[CODAY-SERVICE] Processing MessageEvent:', {
      role: event.role,
      speaker: event.name,
      contentLength: event.content.length,
      textContent: event.getTextContent()
    })
    
    const message: ChatMessage = {
      id: event.timestamp,
      role: event.role,
      speaker: event.name,
      content: event.getTextContent(), // Extract text content
      timestamp: new Date(),
      type: 'text'
    }
    
    console.log('[CODAY-SERVICE] Adding message to history:', message)
    this.addMessage(message)
  }

  private handleTextEvent(event: TextEvent): void {
    console.log('[CODAY-SERVICE] Processing TextEvent:', {
      speaker: event.speaker,
      textLength: event.text.length,
      text: event.text.substring(0, 100) + (event.text.length > 100 ? '...' : '')
    })
    
    const message: ChatMessage = {
      id: event.timestamp,
      role: event.speaker ? 'assistant' : 'system',
      speaker: event.speaker || 'System',
      content: event.text,
      timestamp: new Date(),
      type: event.speaker ? 'text' : 'technical'
    }
    
    console.log('[CODAY-SERVICE] Adding text message to history:', message)
    this.addMessage(message)
  }

  private handleAnswerEvent(event: AnswerEvent): void {
    const message: ChatMessage = {
      id: event.timestamp,
      role: 'user',
      speaker: 'User',
      content: event.answer,
      timestamp: new Date(),
      type: 'text'
    }
    
    this.addMessage(message)
  }

  private handleErrorEvent(event: ErrorEvent): void {
    const message: ChatMessage = {
      id: event.timestamp,
      role: 'system',
      speaker: 'System',
      content: `Error: ${JSON.stringify(event.error)}`,
      timestamp: new Date(),
      type: 'error'
    }
    
    this.addMessage(message)
  }

  private handleWarnEvent(event: WarnEvent): void {
    const message: ChatMessage = {
      id: event.timestamp,
      role: 'system',
      speaker: 'System',
      content: `Warning: ${JSON.stringify(event.warning)}`,
      timestamp: new Date(),
      type: 'warning'
    }
    
    this.addMessage(message)
  }

  private handleThinkingEvent(_event: ThinkingEvent): void {
    this.isThinkingSubject.next(true)
    
    // Auto-hide thinking after debounce time + buffer
    setTimeout(() => {
      this.isThinkingSubject.next(false)
    }, ThinkingEvent.debounce + 1000)
  }

  private handleToolRequestEvent(event: ToolRequestEvent): void {
    const message: ChatMessage = {
      id: event.timestamp,
      role: 'system',
      speaker: 'System',
      content: event.toSingleLineString(),
      timestamp: new Date(),
      type: 'technical'
    }
    
    this.addMessage(message)
  }

  private handleToolResponseEvent(event: ToolResponseEvent): void {
    const message: ChatMessage = {
      id: event.timestamp,
      role: 'system',
      speaker: 'System',
      content: event.toSingleLineString(),
      timestamp: new Date(),
      type: 'technical'
    }
    
    this.addMessage(message)
  }

  private handleChoiceEvent(event: ChoiceEvent): void {
    // Convert to our choice format
    const options: ChoiceOption[] = event.options.map(option => ({
      value: option,
      label: option
    }))
    
    const label = event.optionalQuestion ? 
      `${event.optionalQuestion} ${event.invite}` : 
      event.invite
    
    this.currentChoiceSubject.next({ options, label })
  }

  private handleProjectSelectedEvent(event: ProjectSelectedEvent): void {
    this.projectTitleSubject.next(event.projectName || 'Coday')
  }

  private handleHeartBeatEvent(_event: HeartBeatEvent): void {
    // HeartBeat events are just for connection keep-alive, no action needed
    console.log('[CODAY-SERVICE] HeartBeat received - connection alive')
  }

  /**
   * Add a message to the history
   */
  private addMessage(message: ChatMessage): void {
    const currentMessages = this.messagesSubject.value
    const newMessages = [...currentMessages, message]
    
    console.log('[CODAY-SERVICE] Adding message to history:', {
      messageId: message.id,
      role: message.role,
      speaker: message.speaker,
      type: message.type,
      currentCount: currentMessages.length,
      newCount: newMessages.length
    })
    
    this.messagesSubject.next(newMessages)
    
    // Verify the update was applied
    setTimeout(() => {
      const updatedMessages = this.messagesSubject.value
      console.log('[CODAY-SERVICE] Message history updated. Current count:', updatedMessages.length)
    }, 0)
  }

  ngOnDestroy(): void {
    this.destroy$.next()
    this.destroy$.complete()
    this.eventStream.disconnect()
  }
}