import { Injectable, OnDestroy, inject } from '@angular/core'
import { Subject, BehaviorSubject, Observable } from 'rxjs'
import { takeUntil, tap, concatMap, filter, first } from 'rxjs/operators'
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
  ThreadSelectedEvent,
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
  private messageToRestoreSubject = new BehaviorSubject<string>('')
  
  // RxJS-based invite queue management
  private inviteEventStream$ = new Subject<InviteEvent>()
  
  // Store original events for proper response building
  private currentChoiceEvent: ChoiceEvent | null = null
  
  // Thinking state management
  private thinkingTimeout: ReturnType<typeof setTimeout> | null = null
  
  // Public observables
  messages$ = this.messagesSubject.asObservable()
  isThinking$ = this.isThinkingSubject.asObservable()
  currentChoice$ = this.currentChoiceSubject.asObservable()
  projectTitle$ = this.projectTitleSubject.asObservable()
  currentInviteEvent$ = this.currentInviteEventSubject.asObservable()
  messageToRestore$ = this.messageToRestoreSubject.asObservable()

  
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
    this.initializeInviteQueueHandling()
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
    this.currentInviteEventSubject.next(null)
    
    this.codayApi.stopExecution().subscribe({
      next: () => console.log('[CODAY] Stop signal sent'),
      error: (error) => console.error('[CODAY] Error stopping:', error)
    })
  }

  /**
   * Reset messages when changing project or thread context
   */
  resetMessages(): void {
    console.log('[CODAY] Resetting messages for context change')
    this.messagesSubject.next([])
    
    // Also clear related state that doesn't make sense in new context
    this.currentChoiceSubject.next(null)
    this.currentInviteEventSubject.next(null)
    this.currentChoiceEvent = null
    this.stopThinking()
  }



  /**
   * Send a message
   */
  sendMessage(message: string): void {
    const currentInviteEvent = this.currentInviteEventSubject.value
    
    if (currentInviteEvent) {
      // Use the original InviteEvent to build proper answer with parentKey
      const answerEvent = currentInviteEvent.buildAnswer(message)
      
      // The concatMap operator will automatically handle the next invite
      // after the AnswerEvent is processed
      
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
   * Delete a message from the thread (rewind/retry functionality)
   */
  deleteMessage(messageId: string): Observable<{success: boolean, message?: string, error?: string}> {
    console.log('[CODAY] Deleting message:', messageId)
    
    // Extract text content from the message before deleting it
    const messageToDelete = this.messagesSubject.value.find(msg => msg.id === messageId)
    const textContent = this.extractTextContentFromMessage(messageToDelete)
    
    return this.codayApi.deleteMessage(messageId).pipe(
      tap((response: {success: boolean, message?: string, error?: string}) => {
        if (response.success) {
          console.log('[CODAY] Message deleted successfully, updating local messages')
          // Update local messages immediately for better UX (no replay needed)
          this.removeMessagesFromIndex(messageId)
          
          // Restore the message content to textarea if it has text content
          if (textContent.trim()) {
            console.log('[CODAY] Restoring deleted message content to textarea')
            this.messageToRestoreSubject.next(textContent)
          }
        } else {
          console.warn('[CODAY] Failed to delete message:', response.error)
        }
      })
    )
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
   * Initialize RxJS-based invite queue handling with concatMap
   */
  private initializeInviteQueueHandling(): void {
    this.inviteEventStream$
      .pipe(
        takeUntil(this.destroy$),
        tap(inviteEvent => {
          console.log('[CODAY] Processing invite:', inviteEvent.invite.substring(0, 50) + '...')
        }),
        concatMap(inviteEvent => {
          // Set this invite as current
          this.currentInviteEventSubject.next(inviteEvent)
          
          // Wait for the corresponding AnswerEvent with matching parentKey
          return this.eventStream.events$.pipe(
            filter(event => event instanceof AnswerEvent && event.parentKey === inviteEvent.timestamp),
            first(), // Take only the first matching answer
            tap(() => {
              // Clear current invite (will be set by next invite if any)
              this.currentInviteEventSubject.next(null)
            })
          )
        })
      )
      .subscribe({
        next: () => {
          console.log('[CODAY] Invite processed, ready for next')
        },
        error: (error) => {
          console.error('[CODAY] Error in invite queue processing:', error)
        }
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
    } else if (event instanceof ThreadSelectedEvent) {
      this.handleThreadSelectedEvent(event)
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
    // Clear any existing thinking timeout to prevent blinking
    this.clearThinkingTimeout()
    
    this.isThinkingSubject.next(true)
    
    this.tabTitleService?.setSystemActive()
    
    // Auto-hide thinking after debounce time + buffer
    this.thinkingTimeout = setTimeout(() => {
      this.isThinkingSubject.next(false)
      this.thinkingTimeout = null
      this.tabTitleService?.setSystemInactive()
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
    this.stopThinking()
    
    this.currentChoiceEvent = event
    
    this.tabTitleService?.setSystemInactive()
    
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
    console.log('[CODAY] Project selected:', event.projectName)
    
    // Reset messages when changing project
    this.resetMessages()
    
    // Update project title
    this.projectTitleSubject.next(event.projectName || 'Coday')
  }

  private handleThreadSelectedEvent(event: ThreadSelectedEvent): void {
    console.log('[CODAY] Thread selected:', event.threadId, event.threadName)
    
    // Reset messages when changing thread
    this.resetMessages()
  }

  private handleHeartBeatEvent(_event: HeartBeatEvent): void {
    // HeartBeat events are just for connection keep-alive, no action needed
  }

  private handleInviteEvent(event: InviteEvent): void {
    this.stopThinking()
    
    // Send the invite to the RxJS stream for processing
    this.inviteEventStream$.next(event)
    
    this.tabTitleService?.setSystemInactive()
  }

  /**
   * Add a message to the history
   */
  private addMessage(message: ChatMessage): void {
    const currentMessages = this.messagesSubject.value
    const newMessages = [...currentMessages, message]
    
    this.messagesSubject.next(newMessages)
  }

  /**
   * Remove messages from the specified message index onwards
   * Used for local message deletion after successful truncation
   * @param messageId The ID of the message that was deleted (and all following messages)
   */
  private removeMessagesFromIndex(messageId: string): void {
    const currentMessages = this.messagesSubject.value
    const messageIndex = currentMessages.findIndex(msg => msg.id === messageId)
    
    if (messageIndex === -1) {
      console.warn('[CODAY] Message not found for local deletion:', messageId)
      return
    }
    
    if (messageIndex === 0) {
      console.warn('[CODAY] Cannot delete first message locally')
      return
    }
    
    // Remove the message and all messages that come after it
    const updatedMessages = currentMessages.slice(0, messageIndex)
    
    console.log(`[CODAY] Locally removed ${currentMessages.length - updatedMessages.length} messages from index ${messageIndex}`)
    
    this.messagesSubject.next(updatedMessages)
    
    // Clear any pending choice or invite since the conversation state has changed
    // this.currentChoiceSubject.next(null)
    // this.currentInviteEventSubject.next(null)
    // this.currentChoiceEvent = null
    
    // Stop thinking state since we've truncated the conversation
    this.stopThinking()
  }

  /**
   * Extract text content from a ChatMessage for restoration
   * @param message The message to extract text from
   * @returns The extracted text content
   */
  private extractTextContentFromMessage(message: ChatMessage | undefined): string {
    if (!message) {
      return ''
    }
    
    return message.content
      .filter(content => content.type === 'text')
      .map(content => content.content)
      .join('\n\n')
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

  /**
   * Send feedback for an agent message
   */
  sendFeedback(params: {
    messageId: string,
    feedback: 'positive' | 'negative'
  }): Observable<any> {
    console.log('[CODAY] Sending feedback:', params)
    return this.codayApi.sendFeedback(params)
  }




  ngOnDestroy(): void {
    // Clear thinking timeout on destroy
    this.clearThinkingTimeout()
    
    this.destroy$.next()
    this.destroy$.complete()
    this.currentInviteEventSubject.complete()
    this.messageToRestoreSubject.complete()
    this.inviteEventStream$.complete()
    this.eventStream.disconnect()
  }
}