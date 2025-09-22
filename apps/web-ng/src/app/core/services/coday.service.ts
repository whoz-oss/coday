import { Injectable, OnDestroy, inject } from '@angular/core'
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
  HeartBeatEvent,
  InviteEvent
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
  private currentInviteEventSubject = new BehaviorSubject<InviteEvent | null>(null)
  
  // Store original events for proper response building
  private currentChoiceEvent: ChoiceEvent | null = null
  
  // Public observables
  messages$ = this.messagesSubject.asObservable()
  isThinking$ = this.isThinkingSubject.asObservable()
  currentChoice$ = this.currentChoiceSubject.asObservable()
  projectTitle$ = this.projectTitleSubject.asObservable()
  currentInviteEvent$ = this.currentInviteEventSubject.asObservable()
  
  // Connection status will be initialized in constructor
  connectionStatus$!: typeof this.eventStream.connectionStatus$

  // Reference to title service (injected from outside)
  private tabTitleService: any = null
  
  // Modern Angular dependency injection
  private codayApi = inject(CodayApiService)
  private eventStream = inject(EventStreamService)
  
  constructor() {
    // Initialize connection status observable after eventStream is available
    this.connectionStatus$ = this.eventStream.connectionStatus$
    this.initializeEventHandling()
  }
  
  /**
   * Inject title service (to avoid circular dependency)
   */
  setTabTitleService(tabTitleService: any): void {
    this.tabTitleService = tabTitleService
  }

  /**
   * Start the Coday service
   */
  start(): void {
    this.eventStream.connect()
  }

  /**
   * Stop the Coday service
   */
  stop(): void {
    this.codayApi.stopExecution().subscribe({
      next: () => console.log('[CODAY] Stop signal sent'),
      error: (error) => console.error('[CODAY] Error stopping:', error)
    })
  }

  /**
   * Send a message
   */
  sendMessage(message: string): void {
    const currentInviteEvent = this.currentInviteEventSubject.value
    
    if (currentInviteEvent) {
      // Use the original InviteEvent to build proper answer with parentKey
      const answerEvent = currentInviteEvent.buildAnswer(message)
      
      // Clear the current invite event immediately after using it
      this.currentInviteEventSubject.next(null)
      
      this.codayApi.sendEvent(answerEvent).subscribe({
        error: (error) => console.error('[CODAY] Send error:', error)
      })
    } else {
      // Fallback to basic AnswerEvent if no invite event stored
      const answerEvent = new AnswerEvent({ answer: message })
      
      this.codayApi.sendEvent(answerEvent).subscribe({
        error: (error) => console.error('[CODAY] Send error:', error)
      })
    }
  }

  /**
   * Send a choice selection
   */
  sendChoice(choice: string): void {
    if (this.currentChoiceEvent) {
      // Use the original ChoiceEvent to build proper answer with parentKey
      const answerEvent = this.currentChoiceEvent.buildAnswer(choice)
      
      this.codayApi.sendEvent(answerEvent).subscribe({
        next: () => {
          // Hide choice interface immediately
          this.currentChoiceSubject.next(null)
          // But keep currentChoiceEvent until next event
        },
        error: (error) => console.error('[CODAY] Choice error:', error)
      })
    } else {
      console.error('[CODAY] No choice event available')
    }
  }

  /**
   * Get current messages
   */
  getCurrentMessages(): ChatMessage[] {
    return this.messagesSubject.value
  }
  
  /**
   * Get current project title
   */
  getCurrentProjectTitle(): string {
    return this.projectTitleSubject.value
  }
  
  /**
   * Get current pending InviteEvent if any
   */
  getCurrentInviteEvent(): InviteEvent | null {
    return this.currentInviteEventSubject.value
  }

  /**
   * Initialize event handling
   */
  private initializeEventHandling(): void {
    this.eventStream.events$
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: event => this.handleEvent(event),
        error: error => console.error('[CODAY] Event stream error:', error),
        complete: () => console.log('[CODAY] Event stream completed')
      })
  }

  /**
   * Handle incoming Coday events
   */
  private handleEvent(event: CodayEvent): void {

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
    } else if (event instanceof InviteEvent) {
      this.handleInviteEvent(event)
    } else {
      console.warn('[CODAY] Unhandled event type:', event.type)
    }
  }

  private handleMessageEvent(event: MessageEvent): void {
    const message: ChatMessage = {
      id: event.timestamp,
      role: event.role,
      speaker: event.name,
      content: event.content, // Directement le contenu riche
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
      content: [{ type: 'text', content: event.text }], // Convertir en contenu riche
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
      content: [{ type: 'text', content: event.answer }], // Convertir en contenu riche
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
      content: [{ type: 'text', content: `Error: ${JSON.stringify(event.error)}` }], // Convertir en contenu riche
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
      content: [{ type: 'text', content: `Warning: ${JSON.stringify(event.warning)}` }], // Convertir en contenu riche
      timestamp: new Date(),
      type: 'warning'
    }
    
    this.addMessage(message)
  }

  private handleThinkingEvent(_event: ThinkingEvent): void {
    this.isThinkingSubject.next(true)
    
    // Notify title service that system is active
    if (this.tabTitleService) {
      this.tabTitleService.setSystemActive()
    }
    
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
      content: [{ type: 'text', content: event.toSingleLineString() }], // Convertir en contenu riche
      timestamp: new Date(),
      type: 'technical',
      eventId: event.timestamp
    }
    
    this.addMessage(message)
  }

  private handleToolResponseEvent(event: ToolResponseEvent): void {
    const message: ChatMessage = {
      id: event.timestamp,
      role: 'system',
      speaker: 'System',
      content: [{ type: 'text', content: event.toSingleLineString() }], // Convertir en contenu riche
      timestamp: new Date(),
      type: 'technical',
      eventId: event.timestamp
    }
    
    this.addMessage(message)
  }

  private handleChoiceEvent(event: ChoiceEvent): void {
    
    this.currentChoiceEvent = event
    
    // Notify title service that system is inactive (user interface available)
    if (this.tabTitleService) {
      this.tabTitleService.setSystemInactive()
    }
    
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
  }

  private handleInviteEvent(event: InviteEvent): void {
    this.currentInviteEventSubject.next(event)
    
    // Notify title service that system is inactive (user interface available)
    if (this.tabTitleService) {
      this.tabTitleService.setSystemInactive()
    }
  }

  /**
   * Add a message to the history
   */
  private addMessage(message: ChatMessage): void {
    const currentMessages = this.messagesSubject.value
    const newMessages = [...currentMessages, message]
    

    this.messagesSubject.next(newMessages)
  }

  ngOnDestroy(): void {
    this.destroy$.next()
    this.destroy$.complete()
    this.currentInviteEventSubject.complete()
    this.eventStream.disconnect()
  }
}