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
  
  // Store original events for proper response building
  private currentChoiceEvent: ChoiceEvent | null = null
  private currentInviteEvent: InviteEvent | null = null
  
  // Thinking state management
  private thinkingTimeout: ReturnType<typeof setTimeout> | null = null
  
  // Public observables
  messages$ = this.messagesSubject.asObservable()
  isThinking$ = this.isThinkingSubject.asObservable()
  currentChoice$ = this.currentChoiceSubject.asObservable()
  projectTitle$ = this.projectTitleSubject.asObservable()
  
  // Connection status will be initialized in constructor
  connectionStatus$!: typeof this.eventStream.connectionStatus$

  // Référence au service de titre (injectée depuis l'extérieur)
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
   * Injecter le service de titre (pour éviter la dépendance circulaire)
   */
  setTabTitleService(tabTitleService: any): void {
    this.tabTitleService = tabTitleService
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
    console.log('[CODAY] sendMessage called with:', message)
    console.log('[CODAY] currentInviteEvent:', this.currentInviteEvent ? 'exists' : 'null')
    
    if (this.currentInviteEvent) {
      // Use the original InviteEvent to build proper answer with parentKey
      const answerEvent = this.currentInviteEvent.buildAnswer(message)
      console.log('[CODAY] Built AnswerEvent with parentKey:', answerEvent.parentKey)
      
      this.currentInviteEvent = null
      this.codayApi.sendEvent(answerEvent).subscribe({
        next: () => {
          console.log('[CODAY] Message sent successfully')
          // Ne pas réinitialiser currentInviteEvent ici
          // Il sera remplacé par le prochain InviteEvent du serveur
        },
        error: (error) => console.error('[CODAY] Send error:', error)
      })
    } else {
      // Fallback to basic AnswerEvent if no invite event stored
      console.warn('[CODAY] No invite event available, sending basic answer')
      const answerEvent = new AnswerEvent({ answer: message })
      console.log('[CODAY] Sending basic AnswerEvent without parentKey')
      
      this.codayApi.sendEvent(answerEvent).subscribe({
        next: () => console.log('[CODAY] Basic message sent'),
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
      console.log('[CODAY] Sending choice:', choice)
      
      this.codayApi.sendEvent(answerEvent).subscribe({
        next: () => {
          console.log('[CODAY] Choice sent successfully')
          // Masquer l'interface de choix immédiatement
          this.currentChoiceSubject.next(null)
          // Mais garder currentChoiceEvent jusqu'au prochain événement
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
    
    console.log('[CODAY] Message from:', event.name, 'with', event.content.length, 'content items')
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
    
    console.log('[CODAY] Text from:', event.speaker || 'System')
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
    // Clear any existing thinking timeout to prevent blinking
    this.clearThinkingTimeout()
    
    this.isThinkingSubject.next(true)
    
    // Notifier le service de titre que le système est actif
    if (this.tabTitleService) {
      this.tabTitleService.setSystemActive()
    }
    
    // Auto-hide thinking after debounce time + buffer
    this.thinkingTimeout = setTimeout(() => {
      this.isThinkingSubject.next(false)
      this.thinkingTimeout = null
      
      // Notifier le service de titre que le système est inactif
      if (this.tabTitleService) {
        this.tabTitleService.setSystemInactive()
      }
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
    // Stop thinking when a choice event arrives
    this.stopThinking()
    
    console.log('[CODAY] ChoiceEvent received:', event.options.join(', '))
    console.log('[CODAY] ChoiceEvent parentKey:', event.parentKey)
    console.log('[CODAY] Replacing previous ChoiceEvent:', this.currentChoiceEvent ? 'exists' : 'none')
    
    this.currentChoiceEvent = event
    
    // Notifier le service de titre que le système est inactif (interface utilisateur disponible)
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
    // Stop thinking when an invite event arrives
    this.stopThinking()
    
    console.log('[CODAY] InviteEvent received:', event.invite)
    console.log('[CODAY] InviteEvent parentKey:', event.parentKey)
    console.log('[CODAY] Replacing previous InviteEvent:', this.currentInviteEvent ? 'exists' : 'none')
    this.currentInviteEvent = event
    
    // Notifier le service de titre que le système est inactif (interface utilisateur disponible)
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
    
    console.log('[CODAY] Added message:', message.role, '-', message.speaker)
    this.messagesSubject.next(newMessages)
  }

  /**
   * Clear the thinking timeout to prevent blinking
   */
  private clearThinkingTimeout(): void {
    if (this.thinkingTimeout) {
      clearTimeout(this.thinkingTimeout)
      this.thinkingTimeout = null
    }
  }
  
  /**
   * Stop thinking state immediately and clear timeout
   */
  private stopThinking(): void {
    this.clearThinkingTimeout()
    this.isThinkingSubject.next(false)
    
    // Notifier le service de titre que le système est inactif
    if (this.tabTitleService) {
      this.tabTitleService.setSystemInactive()
    }
  }

  ngOnDestroy(): void {
    // Clear thinking timeout on destroy
    this.clearThinkingTimeout()
    
    this.destroy$.next()
    this.destroy$.complete()
    this.eventStream.disconnect()
  }
}