import { spawn } from 'child_process'
import { execFileSync } from 'child_process'
import * as os from 'os'
import * as path from 'path'
import { debugLog } from './log'

export type PreviewStatus = 'running' | 'stopped'

export interface PreviewState {
  status: PreviewStatus
  url?: string
}

/**
 * Derive the tmux session name for a given project root.
 * Convention: coday-dev-{basename(projectRoot)}
 * This is the same name the preview command must create.
 */
function toSessionName(projectRoot: string): string {
  return `coday-dev-${path.basename(projectRoot)}`
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
   * Start the preview command.
   * The command is responsible for creating a tmux session named
   * `coday-dev-{basename(projectRoot)}` — that session is the lifecycle handle.
   * We fire the command detached and poll for the session to appear.
   */
  async start(projectRoot: string, command: string): Promise<PreviewState> {
    await this.stop(projectRoot)

    const sessionName = toSessionName(projectRoot)
    debugLog('PREVIEW', `Launching preview command in '${projectRoot}': ${command}`)
    debugLog('PREVIEW', `Expecting tmux session: '${sessionName}'`)

    // Fire the command detached — it is responsible for creating the tmux session
    const child = spawn('sh', ['-c', command], {
      cwd: projectRoot,
      detached: true,
      stdio: 'ignore',
    })
    child.unref()

    // Poll for the session to appear (up to 30s)
    const displayHost = getLanAddress()
    const url = await this.waitForUrl(sessionName, displayHost, 30000)
    debugLog('PREVIEW', `Session started, url=${url ?? 'not yet available'}`)
    return { status: 'running', url }
  }

  async stop(projectRoot: string): Promise<PreviewState> {
    const sessionName = toSessionName(projectRoot)
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

  async getStatus(projectRoot: string): Promise<PreviewState> {
    const sessionName = toSessionName(projectRoot)
    if (!this.sessionExists(sessionName)) return { status: 'stopped' }
    const displayHost = getLanAddress()
    const url = extractUrlFromPane(`${sessionName}:server`, displayHost)
    return { status: 'running', url }
  }

  async getLogs(projectRoot: string): Promise<string> {
    const sessionName = toSessionName(projectRoot)
    if (!this.sessionExists(sessionName)) return '(no logs — session is not running)'
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

  private sessionExists(sessionName: string): boolean {
    try {
      execFileSync('tmux', ['has-session', '-t', sessionName], { stdio: 'ignore' })
      return true
    } catch {
      return false
    }
  }

  /**
   * Poll for the session to appear and log its bound URL.
   * Waits up to timeoutMs for both the session and the URL line.
   */
  private async waitForUrl(sessionName: string, displayHost: string, timeoutMs: number): Promise<string | undefined> {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      if (this.sessionExists(sessionName)) {
        const url = extractUrlFromPane(`${sessionName}:server`, displayHost)
        if (url) return url
      }
      await new Promise<void>((resolve) => setTimeout(resolve, 500))
    }
    return undefined
  }
}

export const previewManager = new PreviewManager()
