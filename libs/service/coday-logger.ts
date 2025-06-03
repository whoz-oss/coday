import { promises as fs } from 'fs'
import * as path from 'path'
import * as os from 'os'

/**
 * Simple usage logger for AI agent interactions
 * Logs to daily JSONL files when logging is enabled
 * Uses buffering to avoid concurrent file access issues
 */
export class CodayLogger {
  private enabled: boolean
  private logFolder: string
  private buffer: any[] = []
  private flushInterval: NodeJS.Timeout | null = null

  constructor(enabled: boolean = false, customLogFolder?: string, flushIntervalMs = 5000) {
    this.enabled = enabled
    this.logFolder = customLogFolder || path.join(os.homedir(), '.coday', 'logs')

    // Only start flushing if logging is enabled
    if (enabled) {
      this.flushInterval = setInterval(() => this.flush(), flushIntervalMs)
    }
  }

  /**
   * Log an agent interaction
   */
  async logAgentUsage(username: string, agent: string, model: string, cost: number): Promise<void> {
    // Skip logging if not enabled
    if (!this.enabled) return

    const entry = {
      timestamp: new Date().toISOString(),
      username,
      agent,
      model,
      cost,
    }

    // Add to buffer
    this.buffer.push(entry)

    // Flush immediately if buffer gets too large
    if (this.buffer.length >= 100) {
      await this.flush()
    }
  }

  /**
   * Get the current day's log file path
   */
  private getLogFilePath(): string {
    const now = new Date()
    const year = now.getFullYear()
    const month = String(now.getMonth() + 1).padStart(2, '0')
    const day = String(now.getDate()).padStart(2, '0')
    const filename = `${year}-${month}-${day}.jsonl`
    return path.join(this.logFolder, filename)
  }

  /**
   * Flush buffered entries to disk
   */
  private async flush(): Promise<void> {
    if (this.buffer.length === 0) return

    try {
      const entries = this.buffer.splice(0) // Take all entries and clear buffer
      const filePath = this.getLogFilePath()
      await this.ensureLogDir(filePath)

      const lines = entries.map((entry) => JSON.stringify(entry)).join('\n') + '\n'
      await fs.appendFile(filePath, lines, 'utf8')
    } catch (error) {
      // Silent failure - logging errors should never disrupt service
      console.warn('Logger: Failed to flush log entries:', error)
    }
  }

  /**
   * Ensure the log directory exists
   */
  private async ensureLogDir(filePath: string): Promise<void> {
    const dir = path.dirname(filePath)
    try {
      await fs.mkdir(dir, { recursive: true })
    } catch (error) {
      // Directory might already exist
      if ((error as any).code !== 'EEXIST') {
        throw error
      }
    }
  }

  /**
   * Shutdown the logger gracefully - flush remaining entries
   */
  async shutdown(): Promise<void> {
    if (this.flushInterval) {
      clearInterval(this.flushInterval)
      this.flushInterval = null
    }

    // Final flush
    await this.flush()
  }
}
