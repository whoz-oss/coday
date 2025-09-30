/**
 * @fileoverview Manages a conversation thread between users and AI agents, including tool interactions.
 * Provides a unified interface for message and tool execution handling while maintaining thread state
 * and ensuring proper message sequencing.
 */

import { buildCodayEvent, MessageContent, MessageEvent, ToolRequestEvent, ToolResponseEvent } from '@coday/coday-events'
// eslint-disable-next-line @nx/enforce-module-boundaries
import { ToolCall, ToolResponse } from '../integration/tool-call'
import { EmptyUsage, RunStatus, ThreadMessage, ThreadSerialized, Usage } from './ai-thread.types'
import { partition } from './ai-thread.helpers'

/**
 * Allowed message types for filtering when building thread history
 */
const THREAD_MESSAGE_TYPES = [MessageEvent.type, ToolRequestEvent.type, ToolResponseEvent.type]

/**
 * AiThread manages the state and interactions of a conversation thread between users and AI agents.
 * It handles message sequencing, tool execution requests/responses, and maintains thread history
 * while providing deduplication of similar tool calls.
 *
 * Temporary notes:
 * To narrate it, an aiThread start just after the user chose a project: the configuration being defined, we can then get the list of the existing threads, and select the last one being used (or create the first if none). At every point in time, the user can select another (or create a new) thread, name it (but later it would be better to generate the name), and delete it.
 *
 * The aiThread is then present for all the ai-related parts : ai.handler.ts, agents, aiClients to receive the events composing the history. When a thread is loaded, the said events are also re-played for the frontend to udpate the display.
 *
 * On some future occasions, the current aiThread can be forked or cloned to run a task without polluting the parent thread. Also, in the near future, the agents will be aware of the existence of these other threads and may request informations from them. This would happen through the name (and/or the summary) of aiThreads, and the request would produce an end text message that will be transfered to the parent thread (as a tool response most probably).
 *
 * When an aiThread is left by the user (disconnection, explicit save), it is saved and may trigger several actions like a new summarization, a memory extraction to benefit all threads and users.
 *
 * AiThreads are (for now) tied to a user by default, maybe later shareable or multi-user capable, so aiThread management is important, leading to some necessary work to do on the frontend and API, to select a thread without resorting to commands (although these should still work for terminal interfaces).
 */
export class AiThread {
  /** Unique identifier for the thread */
  id: string

  username: string

  /** Name or title or very short sentence about the content of the thread */
  name: string

  /** Summary of the whole thread, to be used for cross-thread research */
  summary: string

  createdDate: string
  modifiedDate: string
  runStatus: RunStatus = RunStatus.STOPPED

  /** Garbage object for passing data or keeping track of counters or stuff...*/
  data: any = {}

  usage: Usage = { ...EmptyUsage }

  price: number = 0

  /** Track depth of thread delegations (not serializable) */
  delegationDepth: number = 0

  /** Store forked threads for specific agents */
  private forkedThreads: Map<string | null, AiThread> = new Map()

  private parentThread: AiThread | undefined

  /** Internal storage of thread messages in chronological order */
  private messages: ThreadMessage[]

  /**
   * Creates a new AiThread instance.
   * @param thread - Configuration object containing thread ID and optional message history
   * @param thread.id - Unique identifier for the thread
   * @param thread.messages - Optional array of raw message objects to initialize the thread
   */
  constructor(thread: ThreadSerialized) {
    this.id = thread.id
    this.username = thread.username
    this.name = thread.name ?? ''
    this.summary = thread.summary ?? ''
    this.createdDate = thread.createdDate ?? new Date().toISOString()
    this.modifiedDate = thread.modifiedDate ?? this.createdDate
    this.price = thread.price ?? 0

    // Filter on type first, then build events
    // Ensure messages is always initialized as an array, even if empty
    const rawMessages = thread.messages ?? []
    if (!Array.isArray(rawMessages)) {
      this.messages = []
    } else {
      this.messages = rawMessages
        .filter((msg) => THREAD_MESSAGE_TYPES.includes(msg.type))
        .map((msg) => buildCodayEvent(msg))
        .filter((event): event is ThreadMessage => event !== undefined)
    }
  }

  /**
   * Returns a partition of the messages regarding the charBudget.
   * `messages` contains the last messages that fit under the charBudget.
   * `overflow` contains the first messages that make the conversation overflow the charBudget.
   * Both are chronolically ordered.
   * @param maxChars Maximum character limit for the returned messages
   * @returns an object with truncated messages and those that overflow
   */
  async getMessages(
    maxChars: number | undefined,
    compactor: undefined | ((msgs: ThreadMessage[]) => Promise<ThreadMessage>)
  ): Promise<{
    messages: ThreadMessage[]
    compacted: boolean
  }> {
    // Defensive programming: ensure messages is always an array
    if (!this.messages || !Array.isArray(this.messages)) {
      this.messages = []
    }

    if (!maxChars) return { messages: [...this.messages], compacted: false }

    // from the end (hence the toReversed), take all messages that fit into the charbudget
    let { messages, overflow } = partition(this.messages.toReversed(), maxChars)
    messages = messages.toReversed()
    overflow = overflow.toReversed()

    // loop into the accepted message to catch all the tool responses without tool requests
    // this can break the API, so move these lone tool responses into the overflow section instead
    const messageIds: Set<string> = new Set(messages.map((m) => m.timestamp))
    for (let i = 0; i < messages.length; i++) {
      const message = messages[i]
      if (message instanceof ToolResponseEvent && !messageIds.has(message.parentKey ?? 'no_parent_key')) {
        messages.splice(i, 1)
        overflow.push(message)
      }
      //ignore other weird cases like a tool request without a response, the thread is either broken or oblivious to that
    }

    if (!compactor) {
      // IMPORTANT: apply compaction to the thread to avoid re-doing it all over for every LLM call.
      this.messages = messages // forget about the overflow part
      return { messages, compacted: true }
    }

    // time to compact, with the same charBudget
    // overflow itself can be larger than the charBudget, so need to iteratively summarize
    let summary: ThreadMessage | undefined
    while (overflow.length) {
      const overflowPartition = partition(overflow, maxChars)
      summary = await compactor(overflowPartition.messages)
      overflow = overflowPartition.overflow.length ? [summary, ...overflowPartition.overflow] : []
    }

    /*
     * IMPORTANT: apply compaction to the thread to avoid re-doing it all over for every LLM call.
     * This might happen if a small model is taking part in the current thread and that is a questionable decision.
     * Smaller models should have delegation instead of being selected or redirected to.
     */
    this.messages = summary ? [summary, ...messages] : messages

    return {
      messages,
      compacted: true,
    }
  }

  /**
   * Resets the counters related to the run (all except price.thread)
   */
  resetUsageForRun(): void {
    this.usage = { ...EmptyUsage }
  }

  addUsage(usage: Partial<Usage>): void {
    this.price += usage.price ?? 0
    this.usage.price += usage.price ?? 0
    this.usage.iterations += 1

    const tokens = this.usage
    tokens.input += usage.input ?? 0
    tokens.output += usage.output ?? 0
    tokens.cache_read += usage.cache_read ?? 0
    tokens.cache_write += usage.cache_write ?? 0
  }

  /**
   * Adds a new message to the thread.
   * @param message - The message to add
   */
  private add(message: ThreadMessage): void {
    this.messages.push(message)
  }

  /**
   * Finds a tool request by its unique identifier.
   * @param id - The tool request ID to search for
   * @returns The matching ToolRequestEvent or undefined if not found
   */
  private findToolRequestById(id: string): ToolRequestEvent | undefined {
    return this.messages.find((msg) => msg instanceof ToolRequestEvent && msg.toolRequestId === id) as
      | ToolRequestEvent
      | undefined
  }

  /**
   * Finds all tool requests that are similar to the reference request
   * (same name and arguments, but different ID).
   * Used for deduplication of repeated tool calls.
   * @param reference - The tool request to compare against
   * @returns Array of similar tool requests (excluding the reference)
   */
  private findSimilarToolRequests(reference: ToolRequestEvent): ToolRequestEvent[] {
    return this.messages.filter(
      (msg) =>
        msg !== reference && // Exclude the reference request
        msg instanceof ToolRequestEvent &&
        msg.name === reference.name &&
        msg.args === reference.args
    ) as ToolRequestEvent[]
  }

  /**
   * Finds all tool responses associated with the given tool requests.
   * Used when cleaning up duplicated tool calls and their responses.
   * @param requests - Array of tool requests to find responses for
   * @returns Array of tool responses matching the given requests
   */
  private findToolResponsesToRequests(requests: ToolRequestEvent[]): ToolResponseEvent[] {
    return this.messages.filter(
      (msg) => msg instanceof ToolResponseEvent && requests.some((r) => r.toolRequestId === msg.toolRequestId)
    ) as ToolResponseEvent[]
  }

  /**
   * Removes specific messages from the thread.
   * Used primarily for cleaning up duplicated tool calls and responses.
   * @param messages - Array of messages to remove
   */
  private removeMessages(messages: ThreadMessage[]): void {
    this.messages = this.messages.filter((msg) => !messages.includes(msg))
  }

  /**
   * Adds a user message to the thread.
   * @param username - The name of the user sending the message
   * @param content - The content of the message
   */
  addUserMessage(username: string, content: MessageContent): void {
    const lastMessage = this.messages[this.messages.length - 1]
    const shouldMergeIntoLastMessage =
      lastMessage && lastMessage instanceof MessageEvent && lastMessage.role === 'user' && lastMessage.name === username

    if (shouldMergeIntoLastMessage) {
      lastMessage.content.push(content)
    } else {
      this.add(
        new MessageEvent({
          role: 'user',
          content: [content],
          name: username,
        })
      )
    }
  }

  /**
   * Adds an AI agent message to the thread.
   * @param agentName - The name of the AI agent sending the message
   * @param content - The content of the message
   */
  addAgentMessage(agentName: string, content: MessageContent): void {
    const lastMessage = this.messages[this.messages.length - 1]
    const shouldMergeIntoLastMessage =
      lastMessage &&
      lastMessage instanceof MessageEvent &&
      lastMessage.role === 'assistant' &&
      lastMessage.name === agentName

    if (shouldMergeIntoLastMessage) {
      lastMessage.content.push(content)
    } else {
      this.add(
        new MessageEvent({
          role: 'assistant',
          content: [content],
          name: agentName,
        })
      )
    }
  }

  /**
   * Fork a thread for a specific agent delegation
   * @param agentName Name of the agent to delegate to
   * @returns Forked AiThread instance
   */
  /**
   * Fork a thread, optionally for a specific agent
   * @param agentName Optional name of the agent to delegate to
   * @returns Forked AiThread instance
   */
  fork(agentName?: string | null): AiThread {
    // Check if a forked thread for this agent already exists
    const existingForkedThread = this.forkedThreads.get(agentName ?? null)
    if (existingForkedThread) {
      existingForkedThread.runStatus = RunStatus.RUNNING
      return existingForkedThread
    }

    // Create a new forked thread with destructured properties
    const forkedThread = new AiThread({
      id: this.id, // Reuse parent thread ID as we don't save this
      username: this.username,
      name: agentName ? `${this.name} - Delegated to ${agentName}` : `${this.name} - Forked`,
      summary: this.summary,
      createdDate: this.createdDate,
      modifiedDate: new Date().toISOString(),
      price: 0,
      messages: [...this.messages], // Create a new array reference
    })

    // Increment delegation depth (non-serializable)
    forkedThread.delegationDepth = this.delegationDepth + 1
    forkedThread.parentThread = this
    forkedThread.runStatus = RunStatus.RUNNING

    // Store the forked thread
    this.forkedThreads.set(agentName ?? null, forkedThread)

    return forkedThread
  }

  /**
   * Merge a specific forked thread back into this thread
   * @param forkedThread The thread to merge back
   */
  merge(forkedThread: AiThread): void {
    // Add the forked thread's price to this thread's price
    this.price += forkedThread.price

    // Reset the price of the forked as moved into the parent
    forkedThread.price = 0
  }

  get totalPrice(): number {
    return this.price + (this.parentThread?.totalPrice ?? 0)
  }

  /**
   * Returns the name of the last agent (assistant) that responded in this thread, or undefined if none.
   */
  getLastAgentName(): string | undefined {
    for (let i = this.messages.length - 1; i >= 0; i--) {
      const msg = this.messages[i]
      if (msg instanceof MessageEvent && msg.role === 'assistant' && msg.name) {
        return msg.name
      }
    }
    return undefined
  }

  /**
   * Returns the count of user messages in this thread.
   */
  getUserMessageCount(): number {
    return this.messages.filter((msg) => msg instanceof MessageEvent && msg.role === 'user').length
  }

  /**
   * Gets an event by its timestamp ID
   * @param eventId The timestamp ID of the event to retrieve
   * @returns The event if found, undefined otherwise
   */
  getEventById(eventId: string): ThreadMessage | undefined {
    return this.messages.find((msg) => msg.timestamp === eventId)
  }

  /**
   * Truncates the thread at a specific user message, removing that message and all subsequent messages.
   * This provides a "rewind" functionality allowing users to retry from an earlier point in the conversation.
   *
   * @param eventId The timestamp ID of the user message to delete
   * @returns true if truncation was successful, false otherwise
   *
   * Validation rules:
   * - Only user messages (MessageEvent with role='user') can be deleted
   * - Cannot delete the first message in the thread (index 0)
   * - Message must exist in the thread
   */
  truncateAtMessage(eventId: string, shift: number = 0): boolean {
    // Find the message index
    const index = this.messages.findIndex((msg) => msg.timestamp === eventId)
    if (index === -1) {
      return false // Message not found
    }

    // Validate that it's a user or assistant message
    const message = this.messages[index]
    if (!(message instanceof MessageEvent)) {
      return false // Not a user or assistant message
    }

    // Prevent deletion of the first message
    if (index === 0) {
      return false // Cannot delete first message
    }

    // Truncate the messages array at the specified index
    this.messages = this.messages.slice(0, index + shift)

    // Update modification timestamp
    this.modifiedDate = new Date().toISOString()

    return true
  }

  addToolRequests(_agentName: string, toolRequests: ToolRequestEvent[]): void {
    toolRequests.forEach((toolRequest) => {
      if (!toolRequest.toolRequestId || !toolRequest.name || !toolRequest.args) return
      this.add(toolRequest)
    })
  }

  addToolResponseEvents(toolResponseEvents: ToolResponseEvent[]): void {
    toolResponseEvents.forEach((response) => {
      if (!response.toolRequestId || !response.output) return
      const request = this.findToolRequestById(response.toolRequestId)
      if (!request) return

      // Find similar requests using the current request as reference
      const similarRequests = this.findSimilarToolRequests(request)
      const responsesToRemove = this.findToolResponsesToRequests(similarRequests)

      // Remove old requests and responses
      this.removeMessages([...similarRequests, ...responsesToRemove])

      // Add the new response
      this.add(response)
    })
  }

  /**
   * Adds tool execution requests to the thread.
   * Validates each tool call for required fields before adding.
   * @param agentName - The name of the AI agent making the tool calls
   * @param toolCalls - Array of tool calls to process
   */
  addToolCalls(_agentName: string, toolCalls: ToolCall[]): void {
    toolCalls.forEach((call) => {
      if (!call.id || !call.name || !call.args) return
      this.add(
        new ToolRequestEvent({
          name: call.name,
          args: call.args,
          toolRequestId: call.id,
        })
      )
    })
  }

  /**
   * Adds tool execution responses to the thread.
   * Implements deduplication by removing similar previous tool calls and their responses
   * when a new response is added. This ensures that only the latest execution of a tool
   * with specific arguments is kept in the thread.
   *
   * @param username - The name of the user/system processing the tool responses
   * @param responses - Array of tool responses to process
   */
  addToolResponses(_username: string, responses: ToolResponse[]): void {
    responses.forEach((response) => {
      if (!response.id || !response.response) return
      const request = this.findToolRequestById(response.id)
      if (!request) return

      // Find similar requests using the current request as reference
      const similarRequests = this.findSimilarToolRequests(request)
      const responsesToRemove = this.findToolResponsesToRequests(similarRequests)

      // Remove old requests and responses
      this.removeMessages([...similarRequests, ...responsesToRemove])

      // Add the new response
      this.add(
        new ToolResponseEvent({
          toolRequestId: response.id,
          output: response.response,
        })
      )
    })
  }
}
