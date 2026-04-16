import { Response } from 'express'
import { debugLog } from './log'

/**
 * Manages project-level SSE connections.
 * Clients connected here receive lightweight notifications (ThreadUpdateEvent)
 * for any thread in the project — useful for Mission Control auto-refresh
 * without requiring a per-thread SSE connection.
 *
 * Also manages a single global SSE stream that aggregates events from ALL projects,
 * enriching each event with a `projectName` field. Used by Global Mission Control
 * to avoid opening one connection per project (which saturates browser HTTP limits).
 */
export class ProjectEventManager {
  /** SSE connections indexed by projectName → Set<Response> */
  private readonly connections = new Map<string, Set<Response>>()

  /** Global SSE connections — receive events from all projects */
  private readonly globalConnections = new Set<Response>()

  /**
   * Callback invoked when a new client connects to a project SSE stream.
   * Used to replay live invite/thinking state for active threads.
   */
  onNewConnection?: (projectName: string, res: Response) => void

  /**
   * Callback invoked when a new client connects to the global SSE stream.
   * Used to replay active invite states for ALL running threads.
   */
  onNewGlobalConnection?: (res: Response) => void

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
   * Register a new global SSE connection.
   * Triggers onNewGlobalConnection so active invite states can be replayed.
   */
  addGlobalConnection(res: Response): void {
    this.globalConnections.add(res)
    debugLog('PROJECT_SSE', `New global SSE connection (total: ${this.globalConnections.size})`)
    this.onNewGlobalConnection?.(res)
  }

  /**
   * Remove a global SSE connection (on client disconnect).
   */
  removeGlobalConnection(res: Response): void {
    this.globalConnections.delete(res)
    debugLog('PROJECT_SSE', `Global SSE connection removed (remaining: ${this.globalConnections.size})`)
  }

  /**
   * Broadcast a serialisable event to all SSE clients of a project.
   * Also sends an enriched copy (with projectName) to all global connections.
   */
  broadcast(projectName: string, event: unknown): void {
    // Per-project connections
    const set = this.connections.get(projectName)
    if (set && set.size > 0) {
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

    // Global connections — enrich event with projectName
    if (this.globalConnections.size > 0) {
      const enriched = typeof event === 'object' && event !== null ? { ...(event as object), projectName } : event
      const globalData = `data: ${JSON.stringify(enriched)}\n\n`
      for (const res of this.globalConnections) {
        try {
          if (!res.writableEnded) {
            res.write(globalData)
          } else {
            this.globalConnections.delete(res)
          }
        } catch {
          this.globalConnections.delete(res)
        }
      }
    }
  }

  /**
   * Send a heartbeat event to all global SSE connections.
   * Called by ThreadCodayManager on its heartbeat interval.
   */
  sendGlobalHeartbeat(event: unknown): void {
    if (this.globalConnections.size === 0) return
    const data = `data: ${JSON.stringify(event)}\n\n`
    for (const res of this.globalConnections) {
      try {
        if (!res.writableEnded) {
          res.write(data)
        } else {
          this.globalConnections.delete(res)
        }
      } catch {
        this.globalConnections.delete(res)
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

    for (const res of this.globalConnections) {
      try {
        res.end()
      } catch {
        // ignore
      }
    }
    this.globalConnections.clear()
  }
}
