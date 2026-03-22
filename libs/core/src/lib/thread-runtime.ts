import { Observable, Subject } from 'rxjs'
import {
  AgentFinishedEvent,
  AgentRunningEvent,
  AgentSelectedEvent,
  AnswerEvent,
  CodayEvent,
  MessageEvent,
} from '@coday/model'

const MAX_ITERATIONS = 100

export type ThreadRuntimeStatus = 'idle' | 'running' | 'killed' | 'error'

/**
 * Callbacks injected into ThreadRuntime for side-effects.
 * The runtime itself is a pure orchestration engine — it decides WHAT to do
 * by inspecting events, and delegates HOW to do it through these callbacks.
 */
export interface ThreadRuntimeCallbacks {
  /**
   * Persist an event and return the stored copy (with stable id).
   * The runtime adds the returned event to its list and emits it on the SSE flow.
   */
  storeEvent: (event: CodayEvent) => CodayEvent

  /**
   * Select which agent should handle the given user message content.
   * Returns the agent name, or undefined if no agent is available.
   * May emit side-effect events (e.g. WarnEvent) through storeEvent.
   */
  selectAgent: (messageContent: string) => Promise<string | undefined>

  /**
   * Run the named agent against the current event history.
   * The callback is responsible for:
   * - Instantiating the agent with the correct user context
   * - Streaming events back through the runtime's event emitter
   * - Handling errors gracefully
   *
   * @param agentName Name of the agent to run
   * @param events Full event history for context
   * @param shouldContinue Polling function — agent must check this before each LLM call
   */
  runAgent: (agentName: string, events: CodayEvent[], shouldContinue: () => boolean) => Promise<void>
}

/**
 * ThreadRuntime — orchestration engine for a conversation thread.
 *
 * Analogous to CaseRuntime in AgentOS. Owns the event timeline and message queue,
 * drives execution by inspecting events to determine the next action:
 * - If last meaningful event is a user message → select and run agent
 * - If last event is AgentFinished → idle, wait for next message
 * - If last events are ToolRequest + Invite (confirmation pending) → wait for answer,
 *   then resume tool execution and re-run agent
 *
 * All side-effects are delegated through injected callbacks.
 * The runtime does NOT know about UserService, AiClientProvider, etc.
 *
 * Lifecycle:
 *   IDLE → RUNNING → IDLE → RUNNING → ... → KILLED
 */
export class ThreadRuntime {
  private readonly events: CodayEvent[] = []
  private readonly eventSubject = new Subject<CodayEvent>()
  private _status: ThreadRuntimeStatus = 'idle'
  private interruptRequested = false
  private killed = false

  /**
   * Observable stream of events emitted by this runtime.
   * Consumers (SSE broadcaster, UI) subscribe to this.
   */
  readonly events$: Observable<CodayEvent> = this.eventSubject.asObservable()

  get status(): ThreadRuntimeStatus {
    return this._status
  }

  constructor(
    readonly threadId: string,
    private readonly callbacks: ThreadRuntimeCallbacks,
    inputEvents: CodayEvent[] = []
  ) {
    this.events.push(...inputEvents)
  }

  /**
   * Add a user message. This is the main input API.
   * Stores the message as an event in the timeline and triggers a run if idle.
   */
  addUserMessage(username: string, message: string): void {
    const messageEvent = new MessageEvent({
      role: 'user',
      content: [{ type: 'text', content: message }],
      name: username,
    })
    this.emitEvent(messageEvent)

    if (this._status === 'idle') {
      this.run().catch((e) => {
        console.error(`[ThreadRuntime ${this.threadId}] Error during run:`, e)
        this._status = 'error'
      })
    }
  }

  /**
   * Get all events in chronological order.
   */
  getEvents(): ReadonlyArray<CodayEvent> {
    return this.events
  }

  /**
   * Request graceful interruption of the current run.
   * The agent will finish its current LLM call but won't start a new one.
   */
  requestInterrupt(): void {
    this.interruptRequested = true
  }

  /**
   * Request permanent termination.
   */
  requestKill(): void {
    this.killed = true
    this.interruptRequested = true
    this._status = 'killed'
  }

  /**
   * Emit and store an event.
   * Delegates persistence to the storeEvent callback, then records and broadcasts the result.
   */
  emitEvent(event: CodayEvent): void {
    const stored = this.callbacks.storeEvent(event)
    this.events.push(stored)
    this.eventSubject.next(stored)
  }

  /**
   * Check if the runtime should continue processing.
   * Passed to runAgent callbacks as a polling function — agents check this before each LLM call.
   */
  readonly shouldContinue = (): boolean => {
    return !this.interruptRequested && !this.killed
  }

  /**
   * Main orchestration loop. Drives processNextStep() until the turn is complete,
   * an interrupt is requested, or the iteration ceiling is hit.
   */
  async run(): Promise<void> {
    if (this._status === 'running') return

    this._status = 'running'
    this.interruptRequested = false
    let iterations = 0

    try {
      while (!this.interruptRequested && iterations < MAX_ITERATIONS) {
        await this.processNextStep()
        iterations++
      }

      if (this.killed) {
        this._status = 'killed'
      } else if (iterations >= MAX_ITERATIONS) {
        this._status = 'error'
      } else {
        this._status = 'idle'
      }
    } catch (e) {
      this._status = 'error'
      throw e
    }
  }

  /**
   * Single step of the state machine. Scans events backward from the end to find
   * the last orchestration event that belongs to the current turn (i.e. after the
   * last user message), then acts:
   *
   * - AgentFinishedEvent → turn complete, set interruptRequested
   * - AgentRunningEvent  → execute the agent via callback
   * - AgentSelectedEvent → transition to running by emitting AgentRunningEvent
   * - No orchestration event after last user message → select an agent
   * - No user message at all → nothing to do, set interruptRequested
   */
  private async processNextStep(): Promise<void> {
    const lastUserMessageIndex = this.findLastUserMessageIndex()

    for (let i = this.events.length - 1; i >= 0; i--) {
      const event = this.events[i]

      // Orchestration events that predate the last user message belong to a previous
      // turn — skip them so we don't re-act on stale state.
      if (
        i < lastUserMessageIndex &&
        (event instanceof AgentFinishedEvent ||
          event instanceof AgentRunningEvent ||
          event instanceof AgentSelectedEvent)
      ) {
        continue
      }

      if (event instanceof AgentFinishedEvent) {
        // Turn complete — let the run() loop exit cleanly
        this.interruptRequested = true
        return
      }

      if (event instanceof AgentRunningEvent) {
        // Agent already selected and running — hand off to the callback
        await this.callbacks.runAgent(event.agentName, [...this.events], this.shouldContinue)
        return
      }

      if (event instanceof AgentSelectedEvent) {
        // Agent selected but not yet running — advance the state
        this.emitEvent(new AgentRunningEvent({ agentName: event.agentName }))
        return
      }
    }

    // No orchestration event found in the current turn — this is a fresh user message.
    if (lastUserMessageIndex >= 0) {
      const lastUserEvent = this.events[lastUserMessageIndex]
      const messageContent = lastUserEvent ? this.extractMessageContent(lastUserEvent) : undefined
      if (messageContent) {
        const agentName = await this.callbacks.selectAgent(messageContent)
        if (agentName) {
          this.emitEvent(new AgentSelectedEvent({ agentName }))
        } else {
          this.interruptRequested = true
        }
      } else {
        this.interruptRequested = true
      }
    } else {
      // No user message in the timeline — nothing to orchestrate
      this.interruptRequested = true
    }
  }

  /**
   * Returns the index of the most recent user message (MessageEvent with role='user'
   * or AnswerEvent) in the event list, or -1 if none exists.
   */
  private findLastUserMessageIndex(): number {
    for (let i = this.events.length - 1; i >= 0; i--) {
      const event = this.events[i]
      if ((event instanceof MessageEvent && event.role === 'user') || event instanceof AnswerEvent) {
        return i
      }
    }
    return -1
  }

  /**
   * Extracts plain-text content from a user-facing event for agent selection.
   */
  private extractMessageContent(event: CodayEvent): string | undefined {
    if (event instanceof MessageEvent && event.role === 'user') {
      return event.getTextContent()
    }
    if (event instanceof AnswerEvent) {
      return event.answer
    }
    return undefined
  }
}
