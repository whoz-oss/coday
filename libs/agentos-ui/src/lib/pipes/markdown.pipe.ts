import { inject, Pipe, PipeTransform } from '@angular/core'
import { DomSanitizer, SafeHtml } from '@angular/platform-browser'
import DOMPurify from 'dompurify'
import { marked, Renderer } from 'marked'

/**
 * MarkdownPipe — converts a markdown string to sanitized SafeHtml.
 *
 * Pure pipe: re-runs only when the input reference changes.
 * Uses marked (GFM + line breaks) + DOMPurify sanitization.
 * External links get target="_blank" and rel="noopener noreferrer".
 *
 * Usage in template:
 *   <div [innerHTML]="text | agentosMarkdown"></div>
 */
@Pipe({
  name: 'agentosMarkdown',
  standalone: true,
  pure: true,
})
export class MarkdownPipe implements PipeTransform {
  private readonly sanitizer = inject(DomSanitizer)

  private readonly renderer: Renderer = this.buildRenderer()

  transform(value: string | null | undefined): SafeHtml {
    if (!value) return ''

    // marked.parse with async:false returns string synchronously
    const rawHtml = marked.parse(value, {
      renderer: this.renderer,
      breaks: true,
      gfm: true,
      async: false,
    }) as string

    const clean = DOMPurify.sanitize(rawHtml, {
      ADD_TAGS: ['span'],
      ADD_ATTR: ['aria-hidden', 'aria-label', 'target', 'rel'],
      ALLOWED_URI_REGEXP: /^(?:(?:(?:f|ht)tps?|mailto|tel|callto|sms|cid|xmpp):|[^a-z]|[a-z+.-]+(?:[^a-z+.-:]|$))/i,
    })

    // bypassSecurityTrustHtml is safe here: DOMPurify already stripped dangerous content.
    return this.sanitizer.bypassSecurityTrustHtml(clean)
  }

  private buildRenderer(): Renderer {
    const renderer = new Renderer()
    const originalLink = renderer.link.bind(renderer)

    renderer.link = (token): string => {
      let html = originalLink(token)
      if (this.isExternal(token.href)) {
        html = html
          .replace('<a ', '<a target="_blank" rel="noopener noreferrer" ')
          .replace('</a>', '<span class="external-link-icon" aria-hidden="true">↗</span></a>')
      }
      return html
    }

    return renderer
  }

  private isExternal(href: string): boolean {
    if (!href || href.startsWith('/') || href.startsWith('#') || href.startsWith('?')) return false
    if (href.startsWith('//')) return true
    try {
      return new URL(href, window.location.href).hostname !== window.location.hostname
    } catch {
      return false
    }
  }
}
