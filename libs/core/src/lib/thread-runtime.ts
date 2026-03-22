import { Observable, Subject } from 'rxjs'
import { CodayEvent } from '@coday/model'

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
  private readonly messageQueue: Array<{ username: string; message: string }> = []
  private interruptRequested = false
  private killed = false

  /**
   * Observable stream of events emitted by this runtime.
   * Consumers (SSE broadcaster, UI) subscribe to this.
   */
  readonly events$: Observable<CodayEvent> = this.eventSubject.asObservable()

  constructor(
    readonly threadId: string,
    private readonly callbacks: ThreadRuntimeCallbacks,
    inputEvents: CodayEvent[] = []
  ) {
    this.events.push(...inputEvents)
  }

  /**
   * Add a user message. This is the main input API.
   * If the runtime is idle, it will trigger a new run.
   * If the runtime is running, the message is queued.
   */
  addUserMessage(username: string, message: string): void {
    this.messageQueue.push({ username, message })
    // TODO: trigger run if idle
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
  }

  /**
   * Emit and store an event.
   * Called by the (not yet implemented) run/processNextStep state machine.
   */
  emitEvent(event: CodayEvent): void {
    const stored = this.callbacks.storeEvent(event)
    this.events.push(stored)
    this.eventSubject.next(stored)
  }

  /**
   * Check if the runtime should continue processing.
   * Passed to agent callbacks as the shouldContinue polling function.
   * Exposed so the future run() implementation can reference it directly.
   */
  readonly shouldContinue = (): boolean => {
    return !this.interruptRequested && !this.killed
  }
}
