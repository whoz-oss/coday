import { Response } from 'express'
import { Coday } from '@coday/core'

/**
 * Interface for thread execution backends.
 * Abstracts whether execution happens locally (Coday) or remotely (AgentOS).
 */
export interface ThreadInstance {
  readonly threadId: string
  readonly projectName: string
  readonly username: string

  /**
   * Coday instance (only for local execution)
   * Will be undefined for remote backends like AgentOS
   */
  readonly coday?: Coday

  /**
   * Number of active SSE connections
   */
  readonly connectionCount: number

  /**
   * Add an SSE connection to this thread instance
   */
  addConnection(response: Response): void

  /**
   * Remove an SSE connection from this thread instance
   */
  removeConnection(response: Response): void

  /**
   * Prepare the instance without starting execution
   * Useful for webhooks where we need to subscribe to events before starting
   * @returns true if new instance was created, false if already exists
   */
  prepareInstance(): Promise<boolean>

  /**
   * Start the execution for this thread
   * @returns true if new instance was created and started, false if already running
   */
  startExecution(): Promise<boolean>

  /**
   * Send heartbeat to all connected SSE clients
   */
  sendHeartbeat(): void

  /**
   * Stop the current execution
   */
  stop(): void

  /**
   * Cleanup and destroy the instance
   */
  cleanup(): Promise<void>

  /**
   * Get time since last activity in milliseconds
   */
  getInactiveTime(): number

  /**
   * Mark this instance as oneshot (webhook without SSE)
   */
  markAsOneshot(): void
}
