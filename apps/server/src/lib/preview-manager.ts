import { spawn, ChildProcess } from 'child_process'
import { findAvailablePort } from './find-available-port'
import { debugLog } from './log'

export type PreviewStatus = 'running' | 'stopped'

export interface PreviewState {
  status: PreviewStatus
  port?: number
  url?: string
}

interface PreviewEntry {
  state: PreviewState
  process: ChildProcess
  logs: string[]
  lastActivityAt: number
}

const MAX_LOG_LINES = 200
const IDLE_TIMEOUT_MS = 15 * 60 * 1000 // 15 minutes
const IDLE_CHECK_INTERVAL_MS = 60 * 1000 // check every minute

/**
 * Singleton in-memory registry of active preview processes, keyed by project name.
 * Uses child_process.spawn — no tmux dependency required.
 * Processes live inside the Node server process and are auto-stopped after 15 minutes of inactivity.
 */
class PreviewManager {
  private readonly entries = new Map<string, PreviewEntry>()
  private idleCheckInterval: ReturnType<typeof setInterval> | null = null

  constructor() {
    this.idleCheckInterval = setInterval(() => this.reapIdleProcesses(), IDLE_CHECK_INTERVAL_MS)
    // Don't keep the Node process alive just for this interval
    this.idleCheckInterval.unref()
  }

  /**
   * Start a preview server for the given project.
   * Picks a free port starting from portStart, spawns the command as a child process,
   * streams stdout/stderr into a ring buffer, and records the state in memory.
   */
  async start(
    projectName: string,
    projectRoot: string,
    command: string,
    portStart: number,
    host: string
  ): Promise<PreviewState> {
    // Stop any existing process first
    await this.stop(projectName)

    const port = await findAvailablePort(portStart)
    const displayHost = host !== '0.0.0.0' ? host : 'localhost'
    const url = `http://${displayHost}:${port}`

    debugLog('PREVIEW', `Starting preview for '${projectName}' on port ${port}: ${command}`)

    const child = spawn(command, [], {
      cwd: projectRoot,
      env: { ...process.env, PORT: String(port), HOST: '0.0.0.0' },
      detached: false,
      shell: true,
    })

    const logs: string[] = []

    const appendLog = (data: Buffer) => {
      const lines = data.toString().split('\n')
      logs.push(...lines)
      if (logs.length > MAX_LOG_LINES) {
        logs.splice(0, logs.length - MAX_LOG_LINES)
      }
    }

    child.stdout?.on('data', appendLog)
    child.stderr?.on('data', appendLog)

    const state: PreviewState = { status: 'running', port, url }
    const entry: PreviewEntry = { state, process: child, logs, lastActivityAt: Date.now() }
    this.entries.set(projectName, entry)

    child.on('exit', (code) => {
      debugLog('PREVIEW', `Preview process for '${projectName}' exited with code ${code}`)
      const existing = this.entries.get(projectName)
      if (existing && existing.process === child) {
        existing.state = { status: 'stopped' }
      }
    })

    return state
  }

  /**
   * Stop the preview server for the given project.
   */
  async stop(projectName: string): Promise<PreviewState> {
    const entry = this.entries.get(projectName)
    if (entry && entry.state.status === 'running') {
      entry.process.kill('SIGINT')
      setTimeout(() => {
        if (!entry.process.killed) {
          entry.process.kill('SIGKILL')
        }
      }, 3000)
      entry.state = { status: 'stopped' }
    }
    return { status: 'stopped' }
  }

  /**
   * Return the current status of the preview for a project.
   */
  async getStatus(projectName: string): Promise<PreviewState> {
    const entry = this.entries.get(projectName)
    return entry?.state ?? { status: 'stopped' }
  }

  /**
   * Return recent logs from the preview process.
   */
  async getLogs(projectName: string): Promise<string> {
    const entry = this.entries.get(projectName)
    if (!entry || entry.logs.length === 0) {
      return '(no logs yet)'
    }
    return entry.logs.join('\n')
  }

  /**
   * Kill all running preview processes that have been idle for more than IDLE_TIMEOUT_MS.
   */
  private async reapIdleProcesses(): Promise<void> {
    const now = Date.now()
    for (const [projectName, entry] of this.entries) {
      if (entry.state.status === 'running' && now - entry.lastActivityAt > IDLE_TIMEOUT_MS) {
        debugLog(
          'PREVIEW',
          `Stopping idle preview for '${projectName}' (idle for ${Math.round(
            (now - entry.lastActivityAt) / 60000
          )} min)`
        )
        await this.stop(projectName)
      }
    }
  }
}

/** Singleton instance shared across all route handlers */
export const previewManager = new PreviewManager()
