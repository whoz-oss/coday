import { inject, Injectable, OnDestroy } from '@angular/core'
import { BehaviorSubject, Observable, Subject } from 'rxjs'
import { takeUntil, tap } from 'rxjs/operators'
import {
  AnswerEvent,
  ChoiceEvent,
  CodayEvent,
  ErrorEvent,
  HeartBeatEvent,
  InviteEvent,
  InviteEventDefault,
  MessageEvent,
  TextEvent,
  ThinkingEvent,
  ToolRequestEvent,
  ToolResponseEvent,
  WarnEvent,
} from '@coday/coday-events'

import { EventStreamService } from './event-stream.service'
import { MessageApiService } from './message-api.service'
import { ProjectStateService } from './project-state.service'
import { ThreadStateService } from './thread-state.service'

import { ChatMessage } from '../../components/chat-message/chat-message.component'
import { ChoiceOption } from '../../components/choice-select/choice-select.component'

@Injectable({
  providedIn: 'root',
})
export class CodayService implements OnDestroy {
  private readonly destroy$ = new Subject<void>()

  // Current project and thread for API calls
  private currentProject: string | null = null
  private currentThread: string | null = null

  // State subjects
  private readonly messagesSubject = new BehaviorSubject<ChatMessage[]>([])
  private readonly isThinkingSubject = new BehaviorSubject<boolean>(false)
  private readonly currentChoiceSubject = new BehaviorSubject<{ options: ChoiceOption[]; label: string } | null>(null)
  private readonly projectTitleSubject = new BehaviorSubject<string>('Coday')
  private readonly currentInviteEventSubject = new BehaviorSubject<InviteEvent | null>(null)
  private readonly messageToRestoreSubject = new BehaviorSubject<string>('')

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
  private readonly eventStream = inject(EventStreamService)
  private readonly messageApi = inject(MessageApiService)
  private readonly projectState = inject(ProjectStateService)
  private readonly threadState = inject(ThreadStateService)

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
   * Connect to a specific thread's event stream
   * @param projectName Project name
   * @param threadId Thread identifier
   */
  connectToThread(projectName: string, threadId: string): void {
    console.log('[CODAY] Connecting to thread:', projectName, threadId)
    // Store current project and thread for API calls
    this.currentProject = projectName
    this.currentThread = threadId
    this.eventStream.connectToThread(projectName, threadId)
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
    if (!this.currentProject || !this.currentThread) {
      console.error('[CODAY] Cannot send message: no project or thread selected')
      return
    }

    const currentInviteEvent = this.currentInviteEventSubject.value

    if (currentInviteEvent) {
      // Use the original InviteEvent to build proper answer with parentKey
      const answerEvent = currentInviteEvent.buildAnswer(message)

      // Clear the current invite event immediately after using it
      this.currentInviteEventSubject.next(null)

      this.messageApi.sendMessage(this.currentProject, this.currentThread, answerEvent).subscribe({
        error: (error) => console.error('[CODAY] Send error:', error),
      })
    } else {
      // Fallback to basic AnswerEvent if no invite event stored
      const answerEvent = new AnswerEvent({ answer: message })

      this.messageApi.sendMessage(this.currentProject, this.currentThread, answerEvent).subscribe({
        error: (error) => console.error('[CODAY] Send error:', error),
      })
    }
  }

  /**
   * Send a choice selection
   */
  sendChoice(choice: string): void {
    if (!this.currentProject || !this.currentThread) {
      console.error('[CODAY] Cannot send choice: no project or thread selected')
      return
    }

    if (this.currentChoiceEvent) {
      // Use the original ChoiceEvent to build proper answer with parentKey
      const answerEvent = this.currentChoiceEvent.buildAnswer(choice)

      console.log('[CODAY-CHOICE] Choice sent successfully, clearing UI')
      // Hide choice interface immediately
      this.currentChoiceSubject.next(null)
      // Clear the current choice event to prevent reuse
      this.currentChoiceEvent = null
      this.messageApi.sendMessage(this.currentProject, this.currentThread, answerEvent).subscribe({
        next: () => {},
        error: (error) => {
          console.error('[CODAY-CHOICE] Choice error:', error)
        },
      })
    } else {
      console.error('[CODAY-CHOICE] No choice event available for choice:', choice)
    }
  }

  /**
   * Delete a message from the thread (rewind/retry functionality)
   */
  deleteMessage(messageId: string): Observable<{ success: boolean; message?: string; error?: string }> {
    console.log('[CODAY] Deleting message:', messageId)

    // Get current project and thread from state services
    const projectName = this.projectState.getSelectedProjectId()
    const threadId = this.threadState.getSelectedThreadId()

    if (!projectName || !threadId) {
      throw new Error('Cannot delete message: no project or thread selected')
    }

    // Extract text content from the message before deleting it
    const messageToDelete = this.messagesSubject.value.find((msg) => msg.id === messageId)
    const textContent = this.extractTextContentFromMessage(messageToDelete)

    return this.messageApi.deleteMessage(projectName, threadId, messageId).pipe(
      tap((response: { success: boolean; message?: string; error?: string }) => {
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
    this.eventStream.events$.pipe(takeUntil(this.destroy$)).subscribe({
      next: (event) => this.handleEvent(event),
      error: (error) => console.error('[CODAY] Event stream error:', error),
      complete: () => console.log('[CODAY] Event stream completed'),
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
      type: 'text',
    }

    this.addMessage(message)
  }

  private handleTextEvent(event: TextEvent): void {
    const message: ChatMessage = {
      id: event.timestamp,
      role: event.speaker ? 'assistant' : 'system',
      speaker: event.speaker ?? 'System',
      content: [{ type: 'text', content: event.text }], // Convertir en contenu riche
      timestamp: new Date(),
      type: event.speaker ? 'text' : 'technical',
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
      type: 'text',
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
      type: 'error',
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
      type: 'warning',
    }

    this.addMessage(message)
  }

  private handleThinkingEvent(_event: ThinkingEvent): void {
    // Don't show thinking state if we have an active invite waiting for user response
    if (this.currentInviteEventSubject.value) {
      console.log('[CODAY] Ignoring ThinkingEvent - active invite waiting for user response')
      return
    }

    // Don't show thinking state if we have an active choice waiting for user response
    if (this.currentChoiceEvent) {
      console.log('[CODAY] Ignoring ThinkingEvent - active choice waiting for user response')
      return
    }

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
      eventId: event.timestamp,
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
      eventId: event.timestamp,
    }

    this.addMessage(message)
  }

  private handleChoiceEvent(event: ChoiceEvent): void {
    this.stopThinking()

    this.currentChoiceEvent = event

    this.tabTitleService?.setSystemInactive()

    const options: ChoiceOption[] = event.options.map((option) => ({
      value: option,
      label: option,
    }))

    const label = event.optionalQuestion ? `${event.optionalQuestion} ${event.invite}` : event.invite

    this.currentChoiceSubject.next({ options, label })
  }

  private handleHeartBeatEvent(_event: HeartBeatEvent): void {
    // HeartBeat events are just for connection keep-alive, no action needed
  }

  private handleInviteEvent(event: InviteEvent): void {
    this.stopThinking()

    // Check if the last message already contains this invite content to avoid duplicates
    const currentMessages = this.messagesSubject.value
    const lastMessage = currentMessages[currentMessages.length - 1]
    const isInviteEventDefault = event.invite === InviteEventDefault

    // Only add invite as a message if it's not already displayed
    const inviteAlreadyDisplayed =
      !isInviteEventDefault &&
      lastMessage &&
      lastMessage.role === 'assistant' &&
      lastMessage.content.some((c) => c.type === 'text' && c.content.includes(event.invite))

    if (!inviteAlreadyDisplayed && event.invite !== InviteEventDefault) {
      // Create an assistant message with the invite content
      const inviteMessage: ChatMessage = {
        id: event.timestamp,
        role: 'assistant',
        speaker: 'Assistant',
        content: [{ type: 'text', content: event.invite }],
        timestamp: new Date(),
        type: 'text',
      }

      // Add the invite as a visible message in the chat
      this.addMessage(inviteMessage)
    } else {
      console.log('[CODAY] Invite already displayed in last message, skipping duplicate')
    }

    // ALWAYS update the currentInviteEventSubject, even if we didn't display the message
    // This is critical for components waiting for the invite (e.g., ThreadComponent with pending first message)
    console.log('[CODAY] Setting current invite event')
    this.currentInviteEventSubject.next(event)

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
    const messageIndex = currentMessages.findIndex((msg) => msg.id === messageId)

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

    console.log(
      `[CODAY] Locally removed ${currentMessages.length - updatedMessages.length} messages from index ${messageIndex}`
    )

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
      .filter((content) => content.type === 'text')
      .map((content) => content.content)
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

  ngOnDestroy(): void {
    // Clear thinking timeout on destroy
    this.clearThinkingTimeout()

    this.destroy$.next()
    this.destroy$.complete()
    this.currentInviteEventSubject.complete()
    this.messageToRestoreSubject.complete()
    this.eventStream.disconnect()
  }
}
