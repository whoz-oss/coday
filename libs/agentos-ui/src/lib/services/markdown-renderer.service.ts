import { inject, Injectable } from '@angular/core'
import { DomSanitizer, SafeHtml } from '@angular/platform-browser'
import DOMPurify from 'dompurify'
import { marked, Renderer } from 'marked'
import { COPY_FEEDBACK_MS, copyText } from './clipboard.utils'

const COPY_BUTTON_CLASS = 'agentos-code-copy'

/** Single sanitized Markdown pipeline shared by chat messages and delegation outcomes. */
@Injectable({ providedIn: 'root' })
export class MarkdownRendererService {
  private readonly sanitizer = inject(DomSanitizer)
  private readonly renderer = this.buildRenderer()

  constructor() {
    // One delegated listener: streaming replaces the innerHTML, so nothing can be bound to the button itself.
    if (typeof document !== 'undefined') document.addEventListener('click', (event) => this.onClick(event))
  }

  render(text: string): SafeHtml {
    if (!text) return ''
    const html = marked.parse(text, { renderer: this.renderer, breaks: true, gfm: true, async: false }) as string
    return this.sanitizer.bypassSecurityTrustHtml(
      DOMPurify.sanitize(html, {
        ADD_TAGS: ['span'],
        ADD_ATTR: ['aria-hidden', 'aria-label', 'target', 'rel'],
        ALLOWED_URI_REGEXP: /^(?:(?:(?:f|ht)tps?|mailto|tel|callto|sms|cid|xmpp):|[^a-z]|[a-z+.-]+(?:[^a-z+.-:]|$))/i,
      })
    )
  }
  private buildRenderer(): Renderer {
    const renderer = new Renderer()
    const link = renderer.link.bind(renderer)
    const code = renderer.code.bind(renderer)
    const table = renderer.table.bind(renderer)
    renderer.code = (token): string =>
      '<div class="agentos-code-block">' +
      code(token).replace('<pre>', '<pre class="agentos-chat-code-block">') +
      `<button type="button" aria-label="Copy code" class="${COPY_BUTTON_CLASS}">` +
      '<span class="material-icons" aria-hidden="true">content_copy</span></button></div>'
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
  private onClick(event: Event): void {
    const button = (event.target as Element | null)?.closest?.<HTMLElement>(`.${COPY_BUTTON_CLASS}`)
    if (!button) return
    const code = button.closest('.agentos-code-block')?.querySelector('pre')?.textContent
    if (code == null) return
    // marked ends every code block with one newline that is not part of the code.
    void copyText(code.replace(/\n$/, '')).then((ok) => {
      const icon = button.querySelector('.material-icons')
      if (!ok || !icon) return
      icon.textContent = 'check'
      setTimeout(() => (icon.textContent = 'content_copy'), COPY_FEEDBACK_MS)
    })
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
