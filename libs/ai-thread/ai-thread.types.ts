/**
 * @fileoverview Type definitions for thread-related structures
 */

// eslint-disable-next-line @nx/enforce-module-boundaries
import {MessageEvent, ToolRequestEvent, ToolResponseEvent} from '../shared/coday-events'

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
  username: string
  messages?: any[]
  name?: string
  summary?: string
  createdDate?: string
  modifiedDate?: string
  price?: number // Total accumulated price for the thread
}

export interface ThreadSummary {
  id: string
  username: string
  name: string
  summary: string
  createdDate: string
  modifiedDate: string
  price: number
}

/**
 * Simple status of thread execution.
 */
export enum RunStatus {
  STOPPED = 'STOPPED',
  RUNNING = 'RUNNING',
}

/**
 * Custom error class for repository operations
 */
export class ThreadRepositoryError extends Error {
  constructor(
    message: string,
    public cause?: Error
  ) {
    super(message)
    this.name = 'ThreadRepositoryError'
  }
}

export type Usage = {
  input: number
  output: number
  cache_read: number
  cache_write: number
  price: number
  iterations: number
  priceThreshold: number
  iterationsThreshold: number
}

export const EmptyUsage: Usage = {
  input: 0,
  output: 0,
  cache_read: 0,
  cache_write: 0,
  price: 0,
  iterations: 0,
  priceThreshold: 2.0, // Default $2 threshold
  iterationsThreshold: 100, // Default 100 iterations threshold
}
