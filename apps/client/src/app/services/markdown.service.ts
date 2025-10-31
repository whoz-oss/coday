import { Injectable } from '@angular/core'
import { marked, Renderer } from 'marked'

/**
 * Service to configure and provide markdown rendering with custom link handling
 *
 * Features:
 * - External links open in new tab with target="_blank"
 * - External links have rel="noopener noreferrer" for security
 * - External links display an icon to indicate they open in a new tab
 * - Accessible aria-labels for screen readers
 */
@Injectable({
  providedIn: 'root',
})
export class MarkdownService {
  private renderer: Renderer

  constructor() {
    this.renderer = new Renderer()
    this.configureRenderer()
  }

  /**
   * Configure the marked renderer with custom link handling
   */
  private configureRenderer(): void {
    // Store the original link renderer
    const originalLinkRenderer = this.renderer.link.bind(this.renderer)

    // Override the link renderer
    // The parameter is a Tokens.Link object with properties: type, raw, href, title, text, tokens
    this.renderer.link = (token): string => {
      // Determine if link is external
      const isExternal = this.isExternalLink(token.href)

      // Get base HTML from original renderer
      let html = originalLinkRenderer(token)

      if (isExternal) {
        // Add target="_blank" and security attributes
        html = html.replace('<a ', '<a target="_blank" rel="noopener noreferrer" ')

        // Create aria-label for accessibility
        const ariaLabel = token.title
          ? `${token.text} - ${token.title} (opens in new tab)`
          : `${token.text} (opens in new tab)`
        html = html.replace('<a ', `<a aria-label="${this.escapeHtml(ariaLabel)}" `)

        // Add external link icon after the link text
        // Using Unicode external link icon (U+2197 - North East Arrow)
        // Wrapped in a span for styling control
        const externalIcon = '<span class="external-link-icon" aria-hidden="true">↗</span>'
        html = html.replace('</a>', `${externalIcon}</a>`)
      }

      return html
    }
  }

  /**
   * Determine if a URL is external
   *
   * A link is considered external if:
   * - It starts with http:// or https:// and doesn't point to the current domain
   * - It starts with // (protocol-relative URL)
   *
   * Internal links (starting with /, #, or relative paths) are not considered external
   */
  private isExternalLink(href: string): boolean {
    if (!href) return false

    // Relative links and anchors are not external
    if (href.startsWith('/') || href.startsWith('#') || href.startsWith('?')) {
      return false
    }

    // Protocol-relative URLs are considered external
    if (href.startsWith('//')) {
      return true
    }

    // Check if it's an absolute URL
    try {
      const url = new URL(href, window.location.href)
      // If the hostname differs from current hostname, it's external
      return url.hostname !== window.location.hostname
    } catch {
      // If URL parsing fails, treat as internal for safety
      return false
    }
  }

  /**
   * Escape HTML special characters for use in attributes
   */
  private escapeHtml(text: string): string {
    const map: Record<string, string> = {
      '&': '&amp;',
      '<': '&lt;',
      '>': '&gt;',
      '"': '&quot;',
      "'": '&#39;',
    }
    return text.replace(/[&<>"']/g, (char) => map[char] || char)
  }

  /**
   * Parse markdown to HTML with custom renderer
   *
   * @param markdown The markdown string to parse
   * @returns Promise that resolves to HTML string
   */
  async parse(markdown: string): Promise<string> {
    return marked.parse(markdown, { renderer: this.renderer })
  }
}
