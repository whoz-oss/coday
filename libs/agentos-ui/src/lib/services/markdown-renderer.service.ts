import { inject, Injectable } from '@angular/core'
import { DomSanitizer, SafeHtml } from '@angular/platform-browser'
import DOMPurify from 'dompurify'
import { marked, Renderer } from 'marked'

/** Single sanitized Markdown pipeline shared by chat messages and delegation outcomes. */
@Injectable({ providedIn: 'root' })
export class MarkdownRendererService {
  private readonly sanitizer = inject(DomSanitizer)
  private readonly renderer = this.buildRenderer()

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
    renderer.code = (token): string => code(token).replace('<pre>', '<pre class="agentos-chat-code-block">')
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
