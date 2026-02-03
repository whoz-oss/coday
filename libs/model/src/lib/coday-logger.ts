/**
 * Interface for logging AI agent interactions and events
 * This interface can be implemented by different logging backends
 */
export interface CodayLogger {
  /**
   * Log an agent interaction with usage information
   * @param username - The username of the user
   * @param agent - The agent name
   * @param model - The model used
   * @param cost - The cost of the interaction
   */
  logAgentUsage(username: string, agent: string, model: string, cost: number): void

  /**
   * Log a webhook event
   * @param data - The webhook data to log
   */
  logWebhook(data: Record<string, any>): void

  /**
   * Log a webhook error
   * @param data - The error information
   */
  logWebhookError(data: {
    error: string
    username: string | null
    project: string | null
    clientId?: string | null
    duration?: number
  }): void

  /**
   * Log a thread cleanup event
   * @param project - The project name
   * @param threadFileName - The thread file name that was cleaned up
   */
  logThreadCleanup(project: string, threadFileName: string): void

  /**
   * Log a trigger execution event
   * @param data - The trigger execution data
   */
  logTriggerExecution(data: {
    triggerId: string
    triggerName: string
    webhookUuid: string
    projectName: string
    success: boolean
    threadId?: string
    error?: string
  }): void

  /**
   * Read logs for a date range
   * @param from - Start date
   * @param to - End date
   * @returns Array of log entries
   */
  readLogs(from: Date, to: Date): Promise<any[]>

  /**
   * Shutdown the logger gracefully
   * Should flush any remaining buffered entries
   */
  shutdown(): Promise<void>
}
