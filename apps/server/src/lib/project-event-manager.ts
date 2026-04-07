import { Response } from 'express'
import { debugLog } from './log'

/**
 * Manages project-level SSE connections.
 * Clients connected here receive lightweight notifications (ThreadUpdateEvent)
 * for any thread in the project — useful for Mission Control auto-refresh
 * without requiring a per-thread SSE connection.
 */
export class ProjectEventManager {
  /** SSE connections indexed by projectName → Set<Response> */
  private readonly connections = new Map<string, Set<Response>>()

  /**
   * Callback invoked when a new client connects to a project SSE stream.
   * Used to replay live invite/thinking state for active threads.
   */
  onNewConnection?: (projectName: string, res: Response) => void

  /**
   * Register a new SSE connection for a project.
   * Triggers onNewConnection so active thread statuses can be replayed.
   */
  addConnection(projectName: string, res: Response): void {
    let set = this.connections.get(projectName)
    if (!set) {
      set = new Set()
      this.connections.set(projectName, set)
    }
    set.add(res)
    debugLog('PROJECT_SSE', `New connection for project ${projectName} (total: ${set.size})`)
    // Replay active thread statuses for the new client
    this.onNewConnection?.(projectName, res)
  }

  /**
   * Remove a SSE connection (on client disconnect).
   */
  removeConnection(projectName: string, res: Response): void {
    const set = this.connections.get(projectName)
    if (!set) return
    set.delete(res)
    debugLog('PROJECT_SSE', `Connection removed for project ${projectName} (remaining: ${set.size})`)
    if (set.size === 0) {
      this.connections.delete(projectName)
    }
  }

  /**
   * Broadcast a serialisable event to all SSE clients of a project.
   */
  broadcast(projectName: string, event: unknown): void {
    const set = this.connections.get(projectName)
    if (!set || set.size === 0) return

    const data = `data: ${JSON.stringify(event)}\n\n`
    for (const res of set) {
      try {
        if (!res.writableEnded) {
          res.write(data)
        } else {
          set.delete(res)
        }
      } catch {
        set.delete(res)
      }
    }
  }

  /**
   * Close all connections (graceful shutdown).
   */
  shutdown(): void {
    for (const [, set] of this.connections) {
      for (const res of set) {
        try {
          res.end()
        } catch {
          // ignore
        }
      }
    }
    this.connections.clear()
  }
}
