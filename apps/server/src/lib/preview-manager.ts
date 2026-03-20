import { execFileSync } from 'child_process'
import * as os from 'os'
import * as path from 'path'
import { debugLog } from './log'

export type PreviewStatus = 'running' | 'stopped'

export interface PreviewState {
  status: PreviewStatus
  port?: number
  url?: string
}

// tmux session names have a practical limit; we cap at 50 chars and hash the overflow.
const MAX_SESSION_NAME_LENGTH = 50

function toSessionName(projectName: string): string {
  const sanitized = projectName.replace(/[^a-zA-Z0-9]/g, '-')
  const full = `preview-${sanitized}`
  if (full.length <= MAX_SESSION_NAME_LENGTH) return full
  // Stable numeric hash to keep the name unique when truncated
  let hash = 0
  for (let i = 0; i < projectName.length; i++) {
    hash = (Math.imul(31, hash) + projectName.charCodeAt(i)) | 0
  }
  const suffix = Math.abs(hash).toString(16).slice(0, 6)
  const prefix = full.slice(0, MAX_SESSION_NAME_LENGTH - 7)
  return `${prefix}-${suffix}`
}

/**
 * Compute the client-facing server port using the same formula as web-dev-tmux.sh:
 *   offset      = sum of ASCII codes of the project directory basename  mod 100
 *   server port = 4100 + offset   <- what the browser connects to
 *   client port = 5100 + offset   <- internal Angular dev server (1000 apart to avoid HMR conflicts)
 *
 * This is intentionally deterministic so the URL can be shown immediately,
 * without waiting for the server process to report its port.
 */
function computeServerPort(projectRoot: string): number {
  const dirName = path.basename(projectRoot)
  let offset = 0
  for (let i = 0; i < dirName.length; i++) {
    offset += dirName.charCodeAt(i)
  }
  return 4100 + (offset % 100)
}

/**
 * Resolve the host to show in the clickable URL.
 * - If a specific host is configured (and it is not the wildcard "0.0.0.0"),
 *   use it verbatim so users on other machines can reach the server.
 * - Otherwise auto-detect the machine's first non-loopback IPv4 address,
 *   which makes the URL reachable from a browser even when the dev machine
 *   is accessed remotely.  Falls back to 'localhost' if nothing is found.
 */
function resolveDisplayHost(configuredHost: string | undefined): string {
  if (configuredHost && configuredHost !== '0.0.0.0') return configuredHost
  for (const ifaces of Object.values(os.networkInterfaces())) {
    for (const iface of ifaces ?? []) {
      if (iface.family === 'IPv4' && !iface.internal) {
        return iface.address
      }
    }
  }
  return 'localhost'
}

class PreviewManager {
  /**
   * Start the preview command inside a dedicated tmux session.
   * Any previously running session for the same project is killed first.
   */
  async start(projectName: string, projectRoot: string, command: string, host?: string): Promise<PreviewState> {
    await this.stop(projectName)

    const sessionName = toSessionName(projectName)
    debugLog('PREVIEW', `Starting tmux session '${sessionName}' in '${projectRoot}': ${command}`)

    execFileSync('tmux', ['new-session', '-d', '-s', sessionName, '-c', projectRoot, 'sh', '-c', command])

    // Brief wait then verify the session is still alive — a bad command exits immediately.
    await new Promise<void>((resolve) => setTimeout(resolve, 500))
    if (!this.sessionExists(sessionName)) {
      throw new Error(`tmux session '${sessionName}' exited immediately — check the preview command`)
    }

    const port = computeServerPort(projectRoot)
    const url = `http://${resolveDisplayHost(host)}:${port}`
    debugLog('PREVIEW', `Session '${sessionName}' started, url=${url}`)
    return { status: 'running', url, port }
  }

  async stop(projectName: string): Promise<PreviewState> {
    const sessionName = toSessionName(projectName)
    if (this.sessionExists(sessionName)) {
      debugLog('PREVIEW', `Killing tmux session '${sessionName}'`)
      try {
        execFileSync('tmux', ['kill-session', '-t', sessionName])
      } catch {
        // Session may have already exited between the check and the kill — that's fine.
      }
    }
    return { status: 'stopped' }
  }

  async getStatus(projectName: string, projectRoot?: string, host?: string): Promise<PreviewState> {
    const sessionName = toSessionName(projectName)
    if (!this.sessionExists(sessionName)) return { status: 'stopped' }
    if (projectRoot) {
      const port = computeServerPort(projectRoot)
      const url = `http://${resolveDisplayHost(host)}:${port}`
      return { status: 'running', url, port }
    }
    return { status: 'running' }
  }

  /**
   * Capture recent terminal output for the preview.
   *
   * web-dev-tmux.sh creates an *inner* tmux session named coday-dev-<dirname>
   * with a dedicated 'server' window.  When that inner session exists we read
   * from it directly, because the outer preview session only shows the short
   * launcher script output and then blocks silently.
   */
  async getLogs(projectName: string, projectRoot?: string): Promise<string> {
    const sessionName = toSessionName(projectName)
    if (!this.sessionExists(sessionName)) {
      return '(no logs — session is not running)'
    }
    if (projectRoot) {
      const innerSession = `coday-dev-${path.basename(projectRoot)}`
      if (this.sessionExists(innerSession)) {
        try {
          const output = execFileSync('tmux', ['capture-pane', '-t', `${innerSession}:server`, '-p'], {
            encoding: 'utf8',
          })
          if (output.trim()) return output.trim()
        } catch {
          // Inner session exists but window capture failed — fall through to outer session.
        }
      }
    }
    try {
      const output = execFileSync('tmux', ['capture-pane', '-t', sessionName, '-p'], { encoding: 'utf8' })
      return output.trim() || '(no output yet)'
    } catch (err) {
      debugLog('PREVIEW', `Failed to capture pane for '${sessionName}': ${err}`)
      return '(failed to retrieve logs)'
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
