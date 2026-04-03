import { execFileSync } from 'child_process'
import * as path from 'path'
import { debugLog } from './log'

export type PreviewStatus = 'running' | 'stopped'

export interface PreviewState {
  status: PreviewStatus
}

/**
 * Derive the tmux session name for a given project root and entry.
 * Each entry gets its own session so multiple can run simultaneously.
 */
function toSessionName(projectRoot: string, entryName: string): string {
  const sanitized = entryName.replace(/[^a-zA-Z0-9_-]/g, '-')
  return `coday-dev-${path.basename(projectRoot)}-${sanitized}`
}

class PreviewManager {
  /**
   * Start a preview entry by creating its own tmux session.
   */
  async start(projectRoot: string, entryName: string, command: string): Promise<PreviewState> {
    const sessionName = toSessionName(projectRoot, entryName)

    // Kill existing session for this entry if any
    this.killSession(sessionName)

    debugLog('PREVIEW', `Creating tmux session '${sessionName}' in '${projectRoot}': ${command}`)

    execFileSync('tmux', [
      'new-session',
      '-d',
      '-s',
      sessionName,
      '-n',
      'server',
      '-x',
      '220',
      '-y',
      '50',
      '-c',
      projectRoot,
      'bash',
      '-lc',
      `exec ${command}`,
    ])

    debugLog('PREVIEW', `Session '${sessionName}' started`)
    return { status: 'running' }
  }

  async stop(projectRoot: string, entryName: string): Promise<PreviewState> {
    const sessionName = toSessionName(projectRoot, entryName)
    this.killSession(sessionName)
    return { status: 'stopped' }
  }

  async getStatus(projectRoot: string, entryName: string): Promise<PreviewState> {
    const sessionName = toSessionName(projectRoot, entryName)
    if (!this.sessionExists(sessionName)) return { status: 'stopped' }
    return { status: 'running' }
  }

  async getLogs(projectRoot: string, entryName: string): Promise<string> {
    const sessionName = toSessionName(projectRoot, entryName)
    if (!this.sessionExists(sessionName)) return '(not running)'
    try {
      const out = execFileSync('tmux', ['capture-pane', '-t', `${sessionName}:server`, '-p'], {
        encoding: 'utf8',
      })
      return out.trim() || '(no output yet)'
    } catch {
      return '(failed to retrieve logs)'
    }
  }

  // ---------------------------------------------------------------------------

  private killSession(sessionName: string): void {
    if (this.sessionExists(sessionName)) {
      debugLog('PREVIEW', `Killing tmux session '${sessionName}'`)
      try {
        execFileSync('tmux', ['kill-session', '-t', sessionName])
      } catch {
        // already gone
      }
    }
  }

  private sessionExists(sessionName: string): boolean {
    try {
      execFileSync('tmux', ['has-session', '-t', sessionName], { stdio: 'ignore' })
      return true
    } catch {
      return false
    }
  }
}

export const previewManager = new PreviewManager()
