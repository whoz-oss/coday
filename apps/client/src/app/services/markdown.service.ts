import { Injectable, inject } from '@angular/core'
import { marked, Renderer } from 'marked'
import DOMPurify from 'dompurify'
import { WINDOW } from '../core/tokens/window'

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
  private readonly renderer: Renderer
  private readonly window = inject(WINDOW)

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
      const url = new URL(href, this.window.location.href)
      // If the hostname differs from current hostname, it's external
      return url.hostname !== this.window.location.hostname
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
   * Security: This method sanitizes the output HTML using DOMPurify to prevent XSS attacks.
   * This is critical since components use bypassSecurityTrustHtml for rendering.
   *
   * @param markdown The markdown string to parse
   * @returns Promise that resolves to sanitized HTML string
   */
  async parse(markdown: string): Promise<string> {
    // First, parse markdown to HTML
    const rawHtml = await marked.parse(markdown, {
      renderer: this.renderer,
      breaks: true, // Support line breaks
      gfm: true, // GitHub Flavored Markdown
    })

    // Then sanitize the HTML to prevent XSS
    // DOMPurify removes dangerous elements like <script>, <iframe>, event handlers, etc.
    // while preserving safe HTML elements and our custom external link icons
    const sanitizedHtml = DOMPurify.sanitize(rawHtml, {
      // Allow our custom external link icon span
      ADD_TAGS: ['span'],
      ADD_ATTR: ['aria-hidden', 'aria-label', 'target', 'rel'],
      // Keep links safe with target and rel attributes
      ALLOWED_URI_REGEXP: /^(?:(?:(?:f|ht)tps?|mailto|tel|callto|sms|cid|xmpp):|[^a-z]|[a-z+.\-]+(?:[^a-z+.\-:]|$))/i,
    })

    return sanitizedHtml
  }
}
