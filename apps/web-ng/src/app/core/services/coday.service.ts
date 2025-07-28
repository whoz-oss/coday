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
  ProjectSelectedEvent
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
    this.eventStream.events$
      .pipe(takeUntil(this.destroy$))
      .subscribe(event => this.handleEvent(event))
  }

  /**
   * Handle incoming Coday events
   */
  private handleEvent(event: CodayEvent): void {
    console.log('[CODAY] Handling event:', event.type, event)

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
    }
  }

  private handleMessageEvent(event: MessageEvent): void {
    const message: ChatMessage = {
      id: event.timestamp,
      role: event.role,
      speaker: event.name,
      content: event.getTextContent(), // Extract text content
      timestamp: new Date(),
      type: 'text'
    }
    
    this.addMessage(message)
  }

  private handleTextEvent(event: TextEvent): void {
    const message: ChatMessage = {
      id: event.timestamp,
      role: event.speaker ? 'assistant' : 'system',
      speaker: event.speaker || 'System',
      content: event.text,
      timestamp: new Date(),
      type: event.speaker ? 'text' : 'technical'
    }
    
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

  /**
   * Add a message to the history
   */
  private addMessage(message: ChatMessage): void {
    const currentMessages = this.messagesSubject.value
    this.messagesSubject.next([...currentMessages, message])
  }

  ngOnDestroy(): void {
    this.destroy$.next()
    this.destroy$.complete()
    this.eventStream.disconnect()
  }
}