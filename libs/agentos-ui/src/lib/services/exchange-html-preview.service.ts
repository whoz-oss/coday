import { Injectable } from '@angular/core'
import { buildSandboxedHtmlDocument } from './exchange-content.utils'

/** How long the blob URL of a wrapper document lives: revoking too early can break the load of the new tab. */
export const HTML_PREVIEW_URL_TTL_MS = 60_000

/**
 * Opens an HTML file in a new browser tab, without downloading it.
 *
 * Kept in a tiny injectable so the window / Blob side effects can be mocked in tests.
 */
@Injectable({ providedIn: 'root' })
export class ExchangeHtmlPreviewService {
  /**
   * Must be called synchronously from a click handler, or popup blockers will refuse the new tab.
   *
   * The tab shows a wrapper document (see `buildSandboxedHtmlDocument`), never the raw content: a blob URL
   * shares the origin of the app, so the file's own scripts would run with the user's session.
   */
  openInNewTab(content: string, filename: string): void {
    const html = buildSandboxedHtmlDocument(content, filename)
    const url = URL.createObjectURL(new Blob([html], { type: 'text/html' }))
    window.open(url, '_blank', 'noopener')
    setTimeout(() => URL.revokeObjectURL(url), HTML_PREVIEW_URL_TTL_MS)
  }
}
