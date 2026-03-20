import { execFileSync } from 'child_process'
import * as os from 'os'
import * as path from 'path'
import { debugLog } from './log'

export type PreviewStatus = 'running' | 'stopped'

export interface PreviewState {
  status: PreviewStatus
  url?: string
}

/** Max tmux session name length (tmux hard limit is ~50 chars). */
const MAX_SESSION_NAME = 50

/**
 * Derive a tmux session name from the project name.
 * Non-alphanumeric chars become dashes. If the result exceeds the tmux limit,
 * a short hash suffix is appended to keep it unique.
 */
function toSessionName(projectName: string): string {
  const sanitized = projectName.replace(/[^a-zA-Z0-9]/g, '-')
  const full = `preview-${sanitized}`
  if (full.length <= MAX_SESSION_NAME) return full
  let hash = 0
  for (let i = 0; i < projectName.length; i++) {
    hash = (Math.imul(31, hash) + projectName.charCodeAt(i)) | 0
  }
  const suffix = Math.abs(hash).toString(16).slice(0, 6)
  return `${full.slice(0, MAX_SESSION_NAME - 7)}-${suffix}`
}

/**
 * The machine's first non-loopback IPv4 address, used to build clickable URLs
 * that remote users can actually reach. Falls back to 'localhost'.
 */
function getLanAddress(): string {
  for (const ifaces of Object.values(os.networkInterfaces())) {
    for (const iface of ifaces ?? []) {
      if (iface.family === 'IPv4' && !iface.internal) return iface.address
    }
  }
  return 'localhost'
}

/**
 * Scan a tmux pane for the line the Coday server logs on startup:
 *   "Server is running on http://localhost:<port>"
 * Replaces 'localhost' with the machine's LAN address so the URL is
 * reachable from a remote browser.
 */
function extractUrlFromPane(pane: string, displayHost: string): string | undefined {
  try {
    const output = execFileSync('tmux', ['capture-pane', '-t', pane, '-p'], { encoding: 'utf8' })
    const match = output.match(/Server is running on http:\/\/localhost:(\d+)/)
    if (match?.[1]) return `http://${displayHost}:${match[1]}`
  } catch {
    // pane may not exist yet
  }
  return undefined
}

class PreviewManager {
  /**
   * Start the preview command in a dedicated tmux session.
   * The command is fully responsible for port selection.
   * We wait up to 30s for the inner dev server to log its bound URL.
   */
  async start(projectName: string, projectRoot: string, command: string): Promise<PreviewState> {
    await this.stop(projectName)

    const sessionName = toSessionName(projectName)
    debugLog('PREVIEW', `Starting tmux session '${sessionName}' in '${projectRoot}': ${command}`)

    // sh -c so pnpm scripts and shell syntax work correctly
    execFileSync('tmux', ['new-session', '-d', '-s', sessionName, '-c', projectRoot, 'sh', '-c', command])

    // Verify the session survived its first 500ms (catches instant failures)
    await new Promise<void>((resolve) => setTimeout(resolve, 500))
    if (!this.sessionExists(sessionName)) {
      throw new Error(`tmux session '${sessionName}' exited immediately — check the preview command`)
    }

    // Poll the inner dev session's server window until it logs its bound port.
    // The inner session is created by web-dev-tmux.sh as coday-dev-<dirname>.
    const displayHost = getLanAddress()
    const innerSession = `coday-dev-${path.basename(projectRoot)}`
    const url = await this.waitForUrl(innerSession, displayHost, 30000)
    debugLog('PREVIEW', `Session started, url=${url ?? 'not yet available'}`)
    return { status: 'running', url }
  }

  async stop(projectName: string): Promise<PreviewState> {
    const sessionName = toSessionName(projectName)
    if (this.sessionExists(sessionName)) {
      debugLog('PREVIEW', `Killing tmux session '${sessionName}'`)
      try {
        execFileSync('tmux', ['kill-session', '-t', sessionName])
      } catch {
        // already gone
      }
    }
    return { status: 'stopped' }
  }

  async getStatus(projectName: string, projectRoot?: string): Promise<PreviewState> {
    const sessionName = toSessionName(projectName)
    if (!this.sessionExists(sessionName)) return { status: 'stopped' }
    const displayHost = getLanAddress()
    const innerSession = projectRoot ? `coday-dev-${path.basename(projectRoot)}` : undefined
    const url = innerSession ? extractUrlFromPane(`${innerSession}:server`, displayHost) : undefined
    return { status: 'running', url }
  }

  async getLogs(projectName: string, projectRoot?: string): Promise<string> {
    const sessionName = toSessionName(projectName)
    if (!this.sessionExists(sessionName)) return '(no logs — session is not running)'
    // Prefer the inner session's server window for meaningful dev server output
    if (projectRoot) {
      const innerSession = `coday-dev-${path.basename(projectRoot)}`
      if (this.sessionExists(innerSession)) {
        try {
          const out = execFileSync('tmux', ['capture-pane', '-t', `${innerSession}:server`, '-p'], {
            encoding: 'utf8',
          })
          if (out.trim()) return out.trim()
        } catch {
          // fall through to outer session
        }
      }
    }
    try {
      const out = execFileSync('tmux', ['capture-pane', '-t', sessionName, '-p'], { encoding: 'utf8' })
      return out.trim() || '(no output yet)'
    } catch {
      return '(failed to retrieve logs)'
    }
  }

  // ---------------------------------------------------------------------------

  private sessionExists(sessionName: string): boolean {
    try {
      execFileSync('tmux', ['has-session', '-t', sessionName], { stdio: 'ignore' })
      return true
    } catch {
      return false
    }
  }

  /**
   * Poll the inner session's server window until it logs its bound URL,
   * or until the timeout is reached. Waits for the session to exist first.
   */
  private async waitForUrl(innerSession: string, displayHost: string, timeoutMs: number): Promise<string | undefined> {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      // Only attempt pane capture once the inner session actually exists
      if (this.sessionExists(innerSession)) {
        const url = extractUrlFromPane(`${innerSession}:server`, displayHost)
        if (url) return url
      }
      await new Promise<void>((resolve) => setTimeout(resolve, 500))
    }
    return undefined
  }
}

export const previewManager = new PreviewManager()
