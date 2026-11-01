/**
 * Open a URL in the user's default browser, fire-and-forget. Only the interactive Nx mode asks for it
 * (the launcher sets CODAY_OPEN_BROWSER=1); managed mode and the desktop apps never do.
 *
 * No dependency: the platform opener is spawned detached. The platform and the spawn function are injectable so
 * a spec can check the command per platform without launching anything. NO SIDE EFFECTS AT IMPORT TIME.
 */

import { spawn } from 'child_process'

export interface OpenCommand {
  command: string
  args: string[]
}

/** The opener of the platform, or undefined when there is none we know of. */
export function openCommand(url: string, platform: NodeJS.Platform = process.platform): OpenCommand | undefined {
  switch (platform) {
    case 'darwin':
      return { command: 'open', args: [url] }
    case 'linux':
      return { command: 'xdg-open', args: [url] }
    case 'win32':
      // `start` is a cmd builtin; its first quoted argument is the window title, hence the empty one.
      return { command: 'cmd', args: ['/c', 'start', '', url] }
    default:
      return undefined
  }
}

export interface OpenBrowserOptions {
  platform?: NodeJS.Platform
  spawnFn?: typeof spawn
  /** Called (at most once per call) with a one-line reason when the browser could not be opened. */
  onError?: (message: string) => void
}

/** Never throws, never waits. Returns true when the opener was spawned (it may still fail asynchronously). */
export function openBrowser(url: string, options: OpenBrowserOptions = {}): boolean {
  const { platform = process.platform, spawnFn = spawn, onError = () => undefined } = options
  const opener = openCommand(url, platform)
  if (!opener) {
    onError(`Could not open the browser: unsupported platform ${platform}. Open ${url} yourself.`)
    return false
  }
  let reported = false
  const report = (reason: string): void => {
    if (reported) return
    reported = true
    onError(`Could not open the browser (${reason}). Open ${url} yourself.`)
  }
  try {
    const child = spawnFn(opener.command, opener.args, { detached: true, stdio: 'ignore', windowsHide: true })
    // A missing opener (no xdg-open on a headless box) is reported asynchronously: swallow it.
    child.on('error', (error) => report(error.message))
    child.unref()
    return true
  } catch (error) {
    report(error instanceof Error ? error.message : String(error))
    return false
  }
}
