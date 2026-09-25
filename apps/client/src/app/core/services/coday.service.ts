import { inject, Injectable, OnDestroy } from '@angular/core'
import { BehaviorSubject, Observable, Subject } from 'rxjs'
import { takeUntil, tap } from 'rxjs/operators'
import {
  AnswerEvent,
  buildCodayEvent,
  ChoiceEvent,
  CodayEvent,
  DelegationEvent,
  ErrorEvent,
  HeartBeatEvent,
  InviteEvent,
  InviteEventDefault,
  MessageEvent,
  OAuthCallbackEvent,
  OAuthRequestEvent,
  TextChunkEvent,
  TextEvent,
  ThinkingEvent,
  ThreadUpdateEvent,
  ToolRequestEvent,
  ToolResponseEvent,
  WarnEvent,
} from '@coday/model'

import { EventStreamService } from './event-stream.service'
import { MessageApiService } from './message-api.service'
import { UserService } from './user.service'

import { ChatMessage } from '../../components/chat-message/chat-message.component'
import { buildToolRequestFullContent } from '../../components/chat-message/chat-message.utils'
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
  private readonly currentChoiceSubject = new BehaviorSubject<{
    options: ChoiceOption[]
    label: string
    allowFreeText: boolean
  } | null>(null)
  private readonly projectTitleSubject = new BehaviorSubject<string>('Coday')
  private readonly currentInviteEventSubject = new BehaviorSubject<InviteEvent | null>(null)
  private readonly messageToRestoreSubject = new BehaviorSubject<string>('')
  private readonly threadUpdateEventSubject = new BehaviorSubject<ThreadUpdateEvent | null>(null)

  // Store original events for proper response building
  private currentChoiceEvent: ChoiceEvent | null = null

  // Thinking state management
  private thinkingTimeout: ReturnType<typeof setTimeout> | null = null

  // Text chunk accumulation for streaming (separate from messages)
  private accumulatedChunks: string = ''
  private readonly streamingTextSubject = new BehaviorSubject<string>('')

  // Sub-thread event routing: events tagged with a threadId are forwarded here
  // keyed by threadId, so DelegationInlineComponent can subscribe to its own sub-thread
  private readonly subThreadEventsSubject = new Subject<CodayEvent>()
  subThreadEvents$ = this.subThreadEventsSubject.asObservable()

  // Buffer of sub-thread events keyed by threadId.
  // Events may arrive before the DelegationInlineComponent is instantiated by Angular,
  // so we buffer them here and replay on subscription.
  private readonly subThreadEventBuffers = new Map<string, CodayEvent[]>()

  /**
   * Get buffered events for a sub-thread that arrived before the component subscribed.
   */
  getBufferedSubThreadEvents(threadId: string): CodayEvent[] {
    return this.subThreadEventBuffers.get(threadId) ?? []
  }

  // Public observables
  messages$ = this.messagesSubject.asObservable()
  streamingText$ = this.streamingTextSubject.asObservable()
  isThinking$ = this.isThinkingSubject.asObservable()
  currentChoice$ = this.currentChoiceSubject.asObservable()
  currentInviteEvent$ = this.currentInviteEventSubject.asObservable()
  messageToRestore$ = this.messageToRestoreSubject.asObservable()
  threadUpdateEvent$ = this.threadUpdateEventSubject.asObservable()

  // Connection status will be initialized in constructor
  connectionStatus$!: typeof this.eventStream.connectionStatus$

  // Reference to title service (injected from outside)
  private tabTitleService: any = null

  // Modern Angular dependency injection
  private readonly eventStream = inject(EventStreamService)
  private readonly messageApi = inject(MessageApiService)
  private readonly userService = inject(UserService)

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
    // Store current project and thread for API calls
    this.currentProject = projectName
    this.currentThread = threadId
    this.eventStream.connectToThread(projectName, threadId)
  }

  /**
   * Pre-populate messages from the REST history (raw serialized events).
   * Called by ThreadComponent after connectToThread() to show existing messages
   * before — or instead of — the SSE replay from the backend.
   *
   * When the server instance is cold (no live Coday object), it cannot replay
   * the history over SSE. This call fills that gap by fetching the persisted
   * messages via REST and injecting them through the normal event pipeline.
   *
   * IMPORTANT: InviteEvent and ChoiceEvent are injected as visible history messages
   * but NEVER as active interactive prompts. Interactive state comes exclusively
   * from the live SSE stream — never from the REST history snapshot.
   * A historical InviteEvent is already answered: activating it would corrupt
   * currentInviteEventSubject and cause the frontend to build a stale AnswerEvent,
   * breaking the conversation flow.
   *
   * Uses addMessage() internally so duplicates from any SSE replay are silently skipped.
   */
  loadHistoryFromRest(rawMessages: any[]): void {
    for (const raw of rawMessages) {
      const event = buildCodayEvent(raw)
      if (event) {
        this.handleEvent(event, true)
      }
    }
  }

  /**
   * Reset messages when changing project or thread context
   */
  resetMessages(): void {
    this.messagesSubject.next([])

    // Also clear related state that doesn't make sense in new context
    this.currentChoiceSubject.next(null)
    this.currentInviteEventSubject.next(null)
    this.currentChoiceEvent = null
    this.accumulatedChunks = ''
    this.streamingTextSubject.next('')
    this.subThreadEventBuffers.clear()
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

    const pendingInvite = this.currentInviteEventSubject.value

    if (pendingInvite) {
      // There is a pending InviteEvent: build a proper AnswerEvent with the correct parentKey
      // so the backend's promptText() can match it via its filter on parentKey.
      //
      // NOTE: a waiting state without a timeout is structurally a deadlock in disguise.
      // We set a 30 s safety timeout that releases the thinking state if no server event
      // arrives. This covers network issues, server errors, or stale invites that somehow
      // slipped through the fromHistory guard. 30 s is generous enough for normal latency.
      this.clearThinkingTimeout()
      this.isThinkingSubject.next(true)
      this.thinkingTimeout = setTimeout(() => {
        this.thinkingTimeout = null
        this.stopThinking()
        console.warn('[CODAY] Safety timeout: no server response after 30 s, releasing thinking state')
      }, 30_000)
      this.tabTitleService?.setSystemActive()
      this.currentInviteEventSubject.next(null)

      const answerEvent = pendingInvite.buildAnswer(message)

      // Do NOT display optimistically here: the ToolRequestEvent and InviteEvent that
      // preceded this answer have not yet arrived via SSE. Inserting the AnswerEvent now
      // would place it before those events in the list, breaking chronological order.
      // The backend will emit the AnswerEvent over SSE in the correct position.

      this.messageApi.sendMessage(answerEvent).subscribe({
        error: (error) => {
          console.error('[CODAY] Send invite answer error:', error)
          // Restore the invite so the user can retry without refreshing
          this.currentInviteEventSubject.next(pendingInvite)
          this.stopThinking()
        },
      })
    } else {
      // No pending invite: use the free-form endpoint — server queues if agent is running
      this.messageApi.sendFreeMessage(message).subscribe({
        error: (error) => {
          console.error('[CODAY] Send error:', error)
        },
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
      // NOTE: a waiting state without a timeout is structurally a deadlock in disguise.
      // We set a 30 s safety timeout that releases the thinking state if no server event
      // arrives (e.g. stale ChoiceEvent whose server-side observable is already resolved).
      this.clearThinkingTimeout()
      this.isThinkingSubject.next(true)
      this.thinkingTimeout = setTimeout(() => {
        this.thinkingTimeout = null
        this.stopThinking()
        console.warn('[CODAY] Safety timeout: no server response after 30 s, releasing thinking state')
      }, 30_000)
      this.tabTitleService?.setSystemActive()

      // Use the original ChoiceEvent to build proper answer with parentKey
      const answerEvent = this.currentChoiceEvent.buildAnswer(choice)

      // Hide choice interface immediately
      this.currentChoiceSubject.next(null)
      // Clear the current choice event to prevent reuse
      this.currentChoiceEvent = null
      this.messageApi.sendMessage(answerEvent).subscribe({
        next: () => {},
        error: (error) => {
          console.error('[CODAY-CHOICE] Choice error:', error)
          // Reset thinking state on error
          this.stopThinking()
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
    // Extract text content from the message before deleting it
    const messageToDelete = this.messagesSubject.value.find((msg) => msg.id === messageId)
    const textContent = this.extractTextContentFromMessage(messageToDelete)

    return this.messageApi.deleteMessage(messageId).pipe(
      tap((response: { success: boolean; message?: string; error?: string }) => {
        if (response.success) {
          // Update local messages immediately for better UX (no replay needed)
          this.removeMessagesFromIndex(messageId)

          // Restore the message content to textarea if it has text content
          if (textContent.trim()) {
            this.messageToRestoreSubject.next(textContent)
          }
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
   * Set thinking state for pending first message
   * This prevents UI from showing agent selection while waiting for InviteEvent
   */
  setThinkingForPendingMessage(): void {
    this.isThinkingSubject.next(true)
    this.tabTitleService?.setSystemActive()
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
   * Handle incoming Coday events.
   *
   * @param event The event to handle.
   * @param fromHistory When true, the event originates from the REST history snapshot
   *   rather than from the live SSE stream. In this mode, InviteEvent and ChoiceEvent
   *   are rendered as visible history messages but NEVER activate the interactive
   *   prompt state (currentInviteEventSubject / currentChoiceSubject).
   *   Rule: interactive state comes exclusively from the live SSE stream.
   */
  private handleEvent(event: CodayEvent, fromHistory = false): void {
    // Route events tagged with a sub-thread ID to the sub-thread stream
    // so DelegationInlineComponent can pick them up in real-time.
    // Root thread events have threadId === currentThread or undefined.
    if (event.threadId && event.threadId !== this.currentThread) {
      // Buffer the event so late-subscribing components can replay it
      const buffer = this.subThreadEventBuffers.get(event.threadId)
      if (buffer) {
        buffer.push(event)
      } else {
        this.subThreadEventBuffers.set(event.threadId, [event])
      }
      this.subThreadEventsSubject.next(event)
      return
    }

    if (event instanceof MessageEvent) {
      this.handleMessageEvent(event)
    } else if (event instanceof TextChunkEvent) {
      this.handleTextChunkEvent(event)
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
      this.handleChoiceEvent(event, fromHistory)
    } else if (event instanceof HeartBeatEvent) {
      this.handleHeartBeatEvent(event)
    } else if (event instanceof InviteEvent) {
      this.handleInviteEvent(event, fromHistory)
    } else if (event instanceof ThreadUpdateEvent) {
      this.handleThreadUpdateEvent(event)
    } else if (event instanceof DelegationEvent) {
      this.handleDelegationEvent(event)
    } else if (event instanceof OAuthRequestEvent || event instanceof OAuthCallbackEvent) {
      // OAuth events are handled by OAuthService, no action needed here
    } else {
      console.warn('[CODAY] Unhandled event type:', event.type)
    }
  }

  private handleDelegationEvent(event: DelegationEvent): void {
    console.log('[CODAY] DelegationEvent received:', event.subThreadId, event.agentName)
    const currentMessages = this.messagesSubject.value

    // IDEMPOTENCE FIRST: a re-delivered event (same timestamp) must be a strict no-op.
    // The history transits through two channels on thread open (REST + SSE replay, debt #343),
    // so the same DelegationEvent can arrive twice. Any mutation that runs before the
    // duplicate check — such as closing the previous block's window — would corrupt state:
    // the second delivery would find the first block (same subThreadId) and set its
    // windowEnd = its own windowStart, collapsing the window to [ts, ts) and hiding all content.
    // Rule: an event re-delivered must never trigger recalculation of derived state.
    if (currentMessages.some((m) => m.id === event.timestamp)) {
      return
    }

    // Find the last existing delegation block for this subThreadId (if any).
    // When a sub-thread is resumed, a new DelegationEvent is emitted for the same subThreadId.
    // We close the previous block's temporal window and create a new block at the current position.
    const lastExistingIndex = currentMessages.reduce(
      (lastIdx, m, i) => (m.subThreadId === event.subThreadId ? i : lastIdx),
      -1
    )

    let updatedMessages = currentMessages
    if (lastExistingIndex !== -1) {
      // Close the previous block's window: its upper bound is this new event's timestamp
      const previous = currentMessages[lastExistingIndex]
      const closedPrevious: ChatMessage = { ...previous, windowEnd: event.timestamp } as ChatMessage
      updatedMessages = [
        ...currentMessages.slice(0, lastExistingIndex),
        closedPrevious,
        ...currentMessages.slice(lastExistingIndex + 1),
      ]
    }

    // Create a new delegation block for this occurrence, with an open upper window
    const message: ChatMessage = {
      id: event.timestamp,
      role: 'system',
      speaker: event.agentName,
      content: [{ type: 'text', content: '' }],
      timestamp: new Date(),
      type: 'delegation',
      subThreadId: event.subThreadId,
      delegationAgentName: event.agentName,
      windowStart: event.timestamp,
      windowEnd: undefined,
    }

    this.messagesSubject.next([...updatedMessages, message])
  }

  private handleThreadUpdateEvent(event: ThreadUpdateEvent): void {
    this.threadUpdateEventSubject.next(event)
  }

  private handleMessageEvent(event: MessageEvent): void {
    // Reset streaming state if assistant message (final message replaces streaming)
    if (event.role === 'assistant' && this.accumulatedChunks) {
      this.accumulatedChunks = ''
      this.streamingTextSubject.next('')
    }

    // Add message normally
    const message: ChatMessage = {
      id: event.timestamp,
      role: event.role,
      speaker: event.name,
      content: event.content,
      timestamp: event.date,
      type: 'text',
    }

    this.addMessage(message)
  }

  private handleTextChunkEvent(event: TextChunkEvent): void {
    // Accumulate chunks and emit as separate streaming state
    this.accumulatedChunks += event.chunk
    this.streamingTextSubject.next(this.accumulatedChunks)
  }

  private handleTextEvent(event: TextEvent): void {
    const message: ChatMessage = {
      id: event.timestamp,
      role: event.speaker ? 'assistant' : 'system',
      speaker: event.speaker ?? 'System',
      content: [{ type: 'text', content: event.text }],
      timestamp: event.date,
      type: event.speaker ? 'text' : 'technical',
    }

    this.addMessage(message)
  }

  private handleAnswerEvent(event: AnswerEvent): void {
    // If this answer resolves the current pending invite (replay scenario),
    // clear the invite so the input is not shown as active.
    // Match by parentKey (new threads with parentKey) or by chronological order
    // (legacy threads: any AnswerEvent after the invite clears it).
    const pendingInvite = this.currentInviteEventSubject.value
    if (pendingInvite) {
      const matchesByKey = !!event.parentKey && event.parentKey === pendingInvite.timestamp
      const matchesByOrder = !event.parentKey && event.date >= pendingInvite.date
      if (matchesByKey || matchesByOrder) {
        this.currentInviteEventSubject.next(null)
      }
    }

    // Symmetrically, clear any pending choice that this answer resolves.
    // Match by parentKey (new threads) or by chronological order (legacy threads).
    if (this.currentChoiceEvent) {
      const matchesByKey = !!event.parentKey && event.parentKey === this.currentChoiceEvent.timestamp
      const matchesByOrder = !event.parentKey && event.date >= this.currentChoiceEvent.date
      if (matchesByKey || matchesByOrder) {
        this.currentChoiceEvent = null
        this.currentChoiceSubject.next(null)
      }
    }

    // Silent answers (e.g. voice message triggers) are already displayed via another
    // message (audio upload) — skip rendering to avoid duplicates.
    if ((event as any).silent) {
      return
    }

    // Display AnswerEvent as a user message
    const message: ChatMessage = {
      id: event.timestamp,
      role: 'user',
      speaker: event.name ?? this.userService.getUsername() ?? 'User',
      content: [{ type: 'text', content: event.answer }],
      timestamp: event.date,
      type: 'text',
      parentKey: event.parentKey, // Link to the InviteEvent/ChoiceEvent
      invite: event.invite, // Original question for context
    }

    this.addMessage(message)
  }

  private handleErrorEvent(event: ErrorEvent): void {
    const message: ChatMessage = {
      id: event.timestamp,
      role: 'system',
      speaker: 'System',
      content: [{ type: 'text', content: `Error: ${JSON.stringify(event.error)}` }],
      timestamp: event.date,
      type: 'error',
    }

    this.addMessage(message)
  }

  private handleWarnEvent(event: WarnEvent): void {
    const message: ChatMessage = {
      id: event.timestamp,
      role: 'system',
      speaker: 'System',
      content: [{ type: 'text', content: `Warning: ${JSON.stringify(event.warning)}` }],
      timestamp: event.date,
      type: 'warning',
    }

    this.addMessage(message)
  }

  private handleThinkingEvent(_event: ThinkingEvent): void {
    // Don't show thinking state if we have an active invite waiting for user response
    if (this.currentInviteEventSubject.value) {
      return
    }

    // Don't show thinking state if we have an active choice waiting for user response
    if (this.currentChoiceEvent) {
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
      content: [{ type: 'text', content: event.toSingleLineString() }],
      timestamp: event.date,
      type: 'technical',
      eventId: event.timestamp,
      fullContent: buildToolRequestFullContent(event),
    }

    this.addMessage(message)
  }

  private handleToolResponseEvent(event: ToolResponseEvent): void {
    const message: ChatMessage = {
      id: event.timestamp,
      role: 'system',
      speaker: 'System',
      content: [{ type: 'text', content: event.toSingleLineString() }],
      timestamp: event.date,
      type: 'technical',
      eventId: event.timestamp,
    }

    this.addMessage(message)
  }

  private handleChoiceEvent(event: ChoiceEvent, fromHistory = false): void {
    this.stopThinking()

    // Build the visible message (choice question shown in the conversation history).
    const choiceMessage: ChatMessage = {
      id: event.timestamp,
      role: 'assistant',
      speaker: 'Assistant',
      content: [
        {
          type: 'text',
          content: event.optionalQuestion ? `${event.optionalQuestion} ${event.invite}` : event.invite,
        },
      ],
      timestamp: event.date,
      type: 'text',
    }
    this.addMessage(choiceMessage)

    // RULE: interactive state comes only from the live SSE stream, never from REST history.
    // When fromHistory is true, stop here — the choice is already answered.
    if (fromHistory) {
      return
    }

    // Defense-in-depth: even on live SSE, skip if this choice is already answered
    // (e.g. delayed SSE replay after the AnswerEvent already arrived).
    const currentMessages = this.messagesSubject.value
    const isAlreadyAnswered = currentMessages.some(
      (m) =>
        m.parentKey === event.timestamp || // explicit link via parentKey
        (m.role === 'user' && m.timestamp > event.date) || // any user reply after the choice
        (m.role === 'assistant' && m.timestamp > event.date) // conversation continued past this choice
    )
    if (isAlreadyAnswered) {
      return
    }

    this.currentChoiceEvent = event

    this.tabTitleService?.setSystemInactive()

    const options: ChoiceOption[] = event.options.map((option) => ({
      value: option,
      label: option,
    }))

    const label = event.optionalQuestion ? `${event.optionalQuestion} ${event.invite}` : event.invite

    this.currentChoiceSubject.next({ options, label, allowFreeText: event.allowFreeText })
  }

  private handleHeartBeatEvent(_event: HeartBeatEvent): void {
    // HeartBeat events are just for connection keep-alive, no action needed
  }

  private handleInviteEvent(event: InviteEvent, fromHistory = false): void {
    // IMMEDIATELY stop thinking state - this is critical for UX
    // User should be able to respond instantly when an invite arrives
    this.stopThinking()

    const isInviteEventDefault = event.invite === InviteEventDefault

    if (!isInviteEventDefault) {
      // Add the invite as a visible message (deduplicated by id)
      const inviteMessage: ChatMessage = {
        id: event.timestamp,
        role: 'assistant',
        speaker: 'Assistant',
        content: [{ type: 'text', content: event.invite }],
        timestamp: event.date,
        type: 'text',
      }
      this.addMessage(inviteMessage)

      // Check if this invite is already answered:
      // - by parentKey (new threads)
      // - or by a chronologically later AnswerEvent already in the message list
      //   (legacy threads where REST loaded the AnswerEvent before SSE sent the InviteEvent)
      const currentMessages = this.messagesSubject.value
      const isAlreadyAnswered = currentMessages.some(
        (m) =>
          m.parentKey === event.timestamp || // new threads: explicit link
          (m.role === 'user' && m.timestamp > event.date) || // legacy: any user reply after the invite
          (m.role === 'assistant' && m.timestamp > event.date) // conversation continued past this invite
      )

      if (!isAlreadyAnswered) {
        // RULE: interactive state comes only from the live SSE stream, never from REST history.
        if (!fromHistory) {
          this.currentInviteEventSubject.next(event)
          this.tabTitleService?.setSystemInactive()
        }
      }
    } else {
      // InviteEventDefault: main loop prompt, set as pending without displaying.
      // RULE: only activate from live SSE stream.
      if (!fromHistory) {
        this.currentInviteEventSubject.next(event)
        this.tabTitleService?.setSystemInactive()
      }
    }
  }

  /**
   * Add a message to the history, skipping duplicates by id
   */
  private addMessage(message: ChatMessage): void {
    const currentMessages = this.messagesSubject.value
    if (currentMessages.some((m) => m.id === message.id)) {
      return
    }
    this.messagesSubject.next([...currentMessages, message])
  }

  /**
   * Remove messages from the specified message index onwards
   * Used for local message deletion after successful truncation
   * @param messageId The ID of the message that was deleted (and all following messages)
   */
  private removeMessagesFromIndex(messageId: string): void {
    const currentMessages = this.messagesSubject.value
    const messageIndex = currentMessages.findIndex((msg) => msg.id === messageId)

    if (messageIndex === -1 || messageIndex === 0) {
      return
    }

    // Remove the message and all messages that come after it
    const updatedMessages = currentMessages.slice(0, messageIndex)

    this.messagesSubject.next(updatedMessages)

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
    this.threadUpdateEventSubject.complete()
    this.streamingTextSubject.complete()
    this.eventStream.disconnect()
  }
}
