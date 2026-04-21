/**
 * @fileoverview Type definitions for thread-related structures
 */

import {
  AnswerEvent,
  ChoiceEvent,
  DelegationEvent,
  InviteEvent,
  MessageEvent,
  SummaryEvent,
  ToolRequestEvent,
  ToolResponseEvent,
} from './coday-events'

/**
 * Union type representing all possible message types in a thread:
 * - MessageEvent: Direct messages between users and agents
 * - ToolRequestEvent: Tool execution requests from agents
 * - ToolResponseEvent: Results from tool executions
 * - SummaryEvent: Compacted summary of previous messages
 * - InviteEvent: Questions asking for user input
 * - ChoiceEvent: Multiple choice questions for user selection
 * - AnswerEvent: User responses to InviteEvent or ChoiceEvent
 */
export type ThreadMessage =
  | MessageEvent
  | ToolRequestEvent
  | ToolResponseEvent
  | SummaryEvent
  | InviteEvent
  | ChoiceEvent
  | AnswerEvent
  | DelegationEvent

/**
 * Represents a user with access to a thread.
 * Extensible: future fields (role, addedAt, etc.) can be added without migration.
 */
export interface ThreadUser {
  userId: string
}

/**
 * Serialized representation of a thread for storage
 */
export type ThreadSerialized = {
  id: string
  /** @deprecated Use users instead. Kept for retro-compatibility with persisted threads. */
  username?: string
  projectId?: string
  messages?: any[]
  name?: string
  summary?: string
  createdDate?: string
  modifiedDate?: string
  price?: number // Total accumulated price for the thread
  starring?: string[] // List of usernames who starred this thread
  users?: ThreadUser[] // List of users who own and have full access to this thread
  parentThreadId?: string
  parentEventId?: string
  delegatedAgentName?: string
  delegatedTask?: string
  /** Name of the worktree project created for this mission (worktree mode only) */
  worktreeProject?: string
  /** True when the user has manually marked this thread as done */
  closedByUser?: boolean
}

export interface ThreadSummary {
  id: string
  /** @deprecated Use users instead. Kept for retro-compatibility. */
  username: string
  projectId: string
  name: string
  summary: string
  createdDate: string
  modifiedDate: string
  price: number
  starring: string[] // List of usernames who starred this thread
  users: ThreadUser[] // List of users who own and have full access to this thread
  parentThreadId?: string
  parentEventId?: string
  delegatedAgentName?: string
  delegatedTask?: string
  /** Name of the worktree project created for this mission (worktree mode only) */
  worktreeProject?: string
  /** True when the user has manually marked this thread as done */
  closedByUser?: boolean
  /** True when a non-default InviteEvent is pending a user response */
  pendingInvite?: boolean
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
    public override cause?: Error
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
  priceThreshold: 10.0, // Default $10 threshold
  iterationsThreshold: 100, // Default 100 iterations threshold
}
