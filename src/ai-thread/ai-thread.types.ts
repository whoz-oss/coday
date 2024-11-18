/**
 * @fileoverview Type definitions for thread-related structures
 */

import {MessageEvent, ToolRequestEvent, ToolResponseEvent} from "../shared/coday-events"

/**
 * Union type representing all possible message types in a thread:
 * - MessageEvent: Direct messages between users and agents
 * - ToolRequestEvent: Tool execution requests from agents
 * - ToolResponseEvent: Results from tool executions
 */
export type ThreadMessage = MessageEvent | ToolRequestEvent | ToolResponseEvent

/**
 * Serialized representation of a thread for storage
 */
export type ThreadSerialized = {
  id: string
  messages?: any[]
  name?: string
  summary?: string
  createdDate?: string
  modifiedDate?: string
}

/**
 * Simple status of thread execution.
 */
export enum RunStatus {
  STOPPED = "STOPPED",
  RUNNING = "RUNNING"
}