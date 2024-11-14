/**
 * @fileoverview Manages a conversation thread between users and AI agents, including tool interactions.
 * Provides a unified interface for message and tool execution handling while maintaining thread state
 * and ensuring proper message sequencing.
 */

import {buildCodayEvent, MessageEvent, ToolRequestEvent, ToolResponseEvent} from "../shared/coday-events"
import {ToolCall, ToolResponse} from "../integration/tool-call"
import {ThreadMessage, ThreadSerialized} from "./ai-thread.types"

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
  
  /** Name or title or very short sentence about the content of the thread */
  name: string
  
  /** Summary of the whole thread, to be used for cross-thread research */
  summary: string
  
  createdDate: string
  modifiedDate: string
  
  /** Internal storage of thread messages in chronological order */
  private _messages: ThreadMessage[]
  
  /**
   * Creates a new AiThread instance.
   * @param thread - Configuration object containing thread ID and optional message history
   * @param thread.id - Unique identifier for the thread
   * @param thread.messages - Optional array of raw message objects to initialize the thread
   */
  constructor(thread: ThreadSerialized) {
    this.id = thread.id!
    this.name = thread.name ?? "untitled"
    this.summary = thread.summary ?? ""
    this.createdDate = thread.createdDate ?? new Date().toISOString()
    this.modifiedDate = thread.modifiedDate ?? this.createdDate
    
    // Filter on type first, then build events
    this._messages = (thread.messages ?? [])
      .filter(msg => THREAD_MESSAGE_TYPES.includes(msg.type))
      .map(msg => buildCodayEvent(msg))
      .filter((event): event is ThreadMessage => event !== undefined)
  }
  
  /**
   * Returns a copy of all messages in the thread.
   * @returns Array of thread messages in chronological order
   */
  get messages(): ThreadMessage[] {
    return [...this._messages]
  }
  
  /**
   * Adds a new message to the thread.
   * @param message - The message to add
   */
  private add(message: ThreadMessage): void {
    this._messages.push(message)
  }
  
  /**
   * Finds a tool request by its unique identifier.
   * @param id - The tool request ID to search for
   * @returns The matching ToolRequestEvent or undefined if not found
   */
  private findToolRequestById(id: string): ToolRequestEvent | undefined {
    return this._messages.find(msg =>
      msg instanceof ToolRequestEvent &&
      msg.toolRequestId === id
    ) as ToolRequestEvent | undefined
  }
  
  /**
   * Finds all tool requests that are similar to the reference request
   * (same name and arguments, but different ID).
   * Used for deduplication of repeated tool calls.
   * @param reference - The tool request to compare against
   * @returns Array of similar tool requests (excluding the reference)
   */
  private findSimilarToolRequests(reference: ToolRequestEvent): ToolRequestEvent[] {
    return this._messages.filter(msg =>
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
    return this._messages.filter(msg =>
      msg instanceof ToolResponseEvent &&
      requests.some(r => r.toolRequestId === msg.toolRequestId)
    ) as ToolResponseEvent[]
  }
  
  /**
   * Removes specific messages from the thread.
   * Used primarily for cleaning up duplicated tool calls and responses.
   * @param messages - Array of messages to remove
   */
  private removeMessages(messages: ThreadMessage[]): void {
    this._messages = this._messages.filter(msg => !messages.includes(msg))
  }
  
  /**
   * Adds a user message to the thread.
   * @param username - The name of the user sending the message
   * @param content - The content of the message
   */
  addUserMessage(username: string, content: string): void {
    this.add(new MessageEvent({
      role: "user",
      content,
      name: username
    }))
  }
  
  /**
   * Adds an AI agent message to the thread.
   * @param agentName - The name of the AI agent sending the message
   * @param content - The content of the message
   */
  addAgentMessage(agentName: string, content: string): void {
    this.add(new MessageEvent({
      role: "assistant",
      content,
      name: agentName
    }))
  }
  
  /**
   * Adds tool execution requests to the thread.
   * Validates each tool call for required fields before adding.
   * @param agentName - The name of the AI agent making the tool calls
   * @param toolCalls - Array of tool calls to process
   */
  addToolCalls(agentName: string, toolCalls: ToolCall[]): void {
    toolCalls.forEach(call => {
      if (!call.id || !call.name || !call.args) return
      this.add(new ToolRequestEvent({
        name: call.name,
        args: call.args,
        toolRequestId: call.id
      }))
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
  addToolResponses(username: string, responses: ToolResponse[]): void {
    responses.forEach(response => {
      if (!response.id || !response.response) return
      const request = this.findToolRequestById(response.id)
      if (!request) return
      
      // Find similar requests using the current request as reference
      const similarRequests = this.findSimilarToolRequests(request)
      const responsesToRemove = this.findToolResponsesToRequests(similarRequests)
      
      // Remove old requests and responses
      this.removeMessages([...similarRequests, ...responsesToRemove])
      
      // Add the new response
      this.add(new ToolResponseEvent({
        toolRequestId: response.id,
        output: response.response
      }))
    })
  }
}