import type { BrowserWindow as BrowserWindowType } from 'electron'
import { log } from './logger'

/**
 * Parse a deep-link URL and extract the project name and thread ID.
 * The URL format is: `<protocol>://project/<projectName>/thread/<threadId>`
 *
 * @param url - The full deep-link URL
 * @param protocolName - The protocol name without '://' (e.g. 'coday' or 'coday-twin')
 * @returns Parsed parts or null if the URL does not match the expected format
 */
export function parseDeepLink(url: string, protocolName: string): { projectName: string; threadId: string } | null {
  const escaped = protocolName.replace(/-/g, '\\-')
  const pattern = new RegExp(`${escaped}://project/([^/]+)/thread/([^/?#]+)`)
  const match = url.match(pattern)
  if (!match) return null
  return { projectName: match[1]!, threadId: match[2]! }
}

/**
 * Navigate the main window to the deep-link target URL.
 * Also restores and focuses the window.
 */
export function navigateToDeepLink(
  mainWindow: BrowserWindowType,
  serverUrl: string,
  projectName: string,
  threadId: string
): void {
  const targetUrl = `${serverUrl}/project/${projectName}/thread/${threadId}`
  log('INFO', 'Navigating to:', targetUrl)
  void mainWindow.loadURL(targetUrl)
  if (mainWindow.isMinimized()) mainWindow.restore()
  mainWindow.show()
  mainWindow.focus()
}
