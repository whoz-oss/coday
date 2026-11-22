import { inject, Injectable } from '@angular/core'
import { DomSanitizer, SafeHtml } from '@angular/platform-browser'
import DOMPurify from 'dompurify'
import { marked, Renderer } from 'marked'
import { escapeHtml, parseFileReference, renderFileCodespan } from './file-link'

export interface MarkdownRenderOptions {
  /** Render the code spans that look like file references as IDE links (only when they can be opened). */
  fileLinks?: boolean
}

/** Single sanitized Markdown pipeline shared by chat messages and delegation outcomes. */
@Injectable({ providedIn: 'root' })
export class MarkdownRendererService {
  private readonly sanitizer = inject(DomSanitizer)
  private readonly plainRenderer = this.buildRenderer(false)
  private readonly fileLinkRenderer = this.buildRenderer(true)

  render(text: string, options: MarkdownRenderOptions = {}): SafeHtml {
    if (!text) return ''
    const renderer = options.fileLinks ? this.fileLinkRenderer : this.plainRenderer
    const html = marked.parse(text, { renderer, breaks: true, gfm: true, async: false }) as string
    return this.sanitizer.bypassSecurityTrustHtml(
      DOMPurify.sanitize(html, {
        ADD_TAGS: ['span'],
        ADD_ATTR: ['aria-hidden', 'aria-label', 'target', 'rel'],
        ALLOWED_URI_REGEXP: /^(?:(?:(?:f|ht)tps?|mailto|tel|callto|sms|cid|xmpp):|[^a-z]|[a-z+.-]+(?:[^a-z+.-:]|$))/i,
      })
    )
  }
  private buildRenderer(fileLinks: boolean): Renderer {
    const renderer = new Renderer()
    const link = renderer.link.bind(renderer)
    const code = renderer.code.bind(renderer)
    const table = renderer.table.bind(renderer)
    const codespan = renderer.codespan.bind(renderer)
    renderer.code = (token): string => code(token).replace('<pre>', '<pre class="agentos-chat-code-block">')
    // marked passes the raw code text: it is escaped here, as the default codespan renderer would do.
    renderer.codespan = (token): string => {
      const ref = fileLinks ? parseFileReference(token.text) : null
      return ref ? renderFileCodespan(escapeHtml(token.text), ref) : codespan(token)
    }
    // The wrapper scrolls a wide table sideways instead of squeezing its columns or clipping it (styled by .ds-md-table).
    renderer.table = (token): string => `<div class="ds-md-table">${table(token)}</div>`
    renderer.link = (token): string => {
      let html = link(token)
      if (this.isExternal(token.href))
        html = html
          .replace('<a ', '<a target="_blank" rel="noopener noreferrer" ')
          .replace('</a>', '<span class="external-link-icon" aria-hidden="true">↗</span></a>')
      return html
    }
    return renderer
  }
  private isExternal(href: string): boolean {
    if (!href || href.startsWith('/') || href.startsWith('#') || href.startsWith('?')) return false
    try {
      return new URL(href, window.location.href).hostname !== window.location.hostname
    } catch {
      return href.startsWith('//')
    }
  }
}
