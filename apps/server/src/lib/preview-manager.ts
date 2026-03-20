import { execFileSync } from 'child_process'
import { debugLog } from './log'

export type PreviewStatus = 'running' | 'stopped'

export interface PreviewState {
  status: PreviewStatus
  port?: number
  url?: string
}

/**
 * Sanitize a project name into a valid tmux session name.
 * Replaces any non-alphanumeric character with a dash.
 */
function toSessionName(projectName: string): string {
  return `preview-${projectName.replace(/[^a-zA-Z0-9]/g, '-')}`
}

/**
 * Singleton registry of preview sessions managed via tmux.
 * Each project gets a dedicated tmux session named `preview-<projectName>`.
 * Session lifecycle (start/stop/logs/status) is delegated entirely to tmux,
 * so Daemonay can inspect or control sessions independently.
 */
class PreviewManager {
  /**
   * Start a preview server for the given project.
   * Launches the command inside a detached tmux session.
   * PORT and HOST environment variables are injected into the command string when provided.
   */
  async start(projectName: string, projectRoot: string, command: string, host?: string): Promise<PreviewState> {
    // Stop any existing session first
    await this.stop(projectName)

    const sessionName = toSessionName(projectName)
    const resolvedHost = host && host !== '0.0.0.0' ? host : undefined

    // Inject HOST env var into the command string when a non-default host is requested
    const envPrefix = resolvedHost ? `HOST=${resolvedHost} ` : ''
    const fullCommand = `${envPrefix}${command}`

    debugLog('PREVIEW', `Starting tmux session '${sessionName}' in '${projectRoot}': ${fullCommand}`)

    execFileSync('tmux', ['new-session', '-d', '-s', sessionName, '-c', projectRoot, fullCommand])

    return { status: 'running' }
  }

  /**
   * Stop the preview server for the given project by killing its tmux session.
   */
  async stop(projectName: string): Promise<PreviewState> {
    const sessionName = toSessionName(projectName)
    if (this.sessionExists(sessionName)) {
      debugLog('PREVIEW', `Killing tmux session '${sessionName}'`)
      try {
        execFileSync('tmux', ['kill-session', '-t', sessionName])
      } catch {
        // Session may have already exited — ignore
      }
    }
    return { status: 'stopped' }
  }

  /**
   * Return the current status of the preview for a project.
   */
  async getStatus(projectName: string): Promise<PreviewState> {
    const sessionName = toSessionName(projectName)
    const running = this.sessionExists(sessionName)
    return { status: running ? 'running' : 'stopped' }
  }

  /**
   * Return recent logs from the preview tmux session via `tmux capture-pane`.
   */
  async getLogs(projectName: string): Promise<string> {
    const sessionName = toSessionName(projectName)
    if (!this.sessionExists(sessionName)) {
      return '(no logs — session is not running)'
    }
    try {
      const output = execFileSync('tmux', ['capture-pane', '-t', sessionName, '-p'], {
        encoding: 'utf8',
      })
      return output.trim() || '(no output yet)'
    } catch (err) {
      debugLog('PREVIEW', `Failed to capture pane for session '${sessionName}': ${err}`)
      return '(failed to retrieve logs)'
    }
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  private sessionExists(sessionName: string): boolean {
    try {
      execFileSync('tmux', ['has-session', '-t', sessionName], { stdio: 'ignore' })
      return true
    } catch {
      return false
    }
  }
}

/** Singleton instance shared across all route handlers */
export const previewManager = new PreviewManager()
