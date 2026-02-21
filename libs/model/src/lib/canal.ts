import { CodayEvent } from './coday-events'

/**
 * Handle to a specific thread managed by a canal.
 * The adapter subscribes to outbound events rather than the core calling into the adapter.
 * The core never calls outward — it emits events and adapters subscribe to them.
 */
export interface CanalThreadHandle {
  readonly threadId: string
  /**
   * Subscribe to outbound events from this thread.
   * @returns Unsubscribe function to clean up the subscription
   */
  onEvent(handler: (event: CodayEvent) => void): () => void
}

/**
 * Bridge that canal adapters call into the core.
 * Provides the adapter with the ability to create/retrieve threads and send messages.
 */
export interface CanalBridge {
  /**
   * Get or create a Coday thread for a given external conversation key.
   * The externalKey uniquely identifies the external conversation (e.g., Slack channel ID).
   * Thread mapping (externalKey → threadId) is managed by the adapter, not the core.
   */
  getOrCreateThread(
    projectName: string,
    username: string,
    externalKey: string,
    threadName: string,
    options: Record<string, unknown>
  ): Promise<CanalThreadHandle>

  /**
   * Get a handle to an existing thread by its ID (e.g., after server restart).
   * Creates a Coday instance if one doesn't already exist.
   * Does NOT create a new thread in the thread service.
   */
  getExistingThread(threadId: string, projectName: string, username: string): CanalThreadHandle

  /**
   * Send a message into an existing thread.
   */
  sendMessage(threadId: string, message: string): void
}

/**
 * Port interface for communication channel adapters.
 * Each provider (Slack, Discord, Teams, HTTP webhook...) implements this interface.
 * The server manages canal lifecycle via initialize/shutdown only.
 */
export interface CommunicationCanal {
  readonly name: string
  /**
   * Initialize the canal with access to the core bridge.
   * The canal sets up its connections, registers any HTTP routes, subscribes to events, etc.
   */
  initialize(bridge: CanalBridge): Promise<void>
  /**
   * Gracefully shut down the canal, closing connections and cleaning up resources.
   */
  shutdown(): Promise<void>
}
