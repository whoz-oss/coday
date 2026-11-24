import { inject, Injectable } from '@angular/core'
import { DomSanitizer, SafeHtml } from '@angular/platform-browser'
import DOMPurify from 'dompurify'
import { marked, Renderer } from 'marked'
import { COPY_FEEDBACK_MS, copyText } from './clipboard.utils'
import { escapeHtml, parseFileReference, renderFileCodespan } from './file-link'

const CODE_CLASS = 'ds-md-code'
const COPY_BUTTON_CLASS = 'ds-md-code__copy'

export interface MarkdownRenderOptions {
  /** Render the code spans that look like file references as IDE links (only when they can be opened). */
  fileLinks?: boolean
  /** Offer a copy button on fenced code blocks (default true). */
  copyButton?: boolean
}

function isExternal(href: string): boolean {
  if (!href || href.startsWith('/') || href.startsWith('#') || href.startsWith('?')) return false
  try {
    return new URL(href, window.location.href).hostname !== window.location.hostname
  } catch {
    return href.startsWith('//')
  }
}

function buildRenderer(fileLinks: boolean, copyButton: boolean): Renderer {
  const renderer = new Renderer()
  const link = renderer.link.bind(renderer)
  const code = renderer.code.bind(renderer)
  const table = renderer.table.bind(renderer)
  const codespan = renderer.codespan.bind(renderer)
  renderer.code = (token): string =>
    `<div class="${CODE_CLASS}">` +
    code(token).replace('<pre>', '<pre class="agentos-chat-code-block">') +
    (copyButton
      ? `<button type="button" aria-label="Copy code" class="${COPY_BUTTON_CLASS}">` +
        '<span class="material-icons" aria-hidden="true">content_copy</span></button>'
      : '') +
    '</div>'
  // marked passes the raw code text: it is escaped here, as the default codespan renderer would do.
  renderer.codespan = (token): string => {
    const ref = fileLinks ? parseFileReference(token.text) : null
    return ref ? renderFileCodespan(escapeHtml(token.text), ref) : codespan(token)
  }
  // The wrapper scrolls a wide table sideways instead of squeezing its columns or clipping it (styled by .ds-md-table).
  renderer.table = (token): string => `<div class="ds-md-table">${table(token)}</div>`
  renderer.link = (token): string => {
    let html = link(token)
    if (isExternal(token.href))
      html = html
        .replace('<a ', '<a target="_blank" rel="noopener noreferrer" ')
        .replace('</a>', '<span class="external-link-icon" aria-hidden="true">↗</span></a>')
    return html
  }
  return renderer
}

// Fixed renderers (one per option combination) keep rendering stateless.
const renderers = {
  plain: buildRenderer(false, false),
  copy: buildRenderer(false, true),
  fileLinks: buildRenderer(true, false),
  fileLinksCopy: buildRenderer(true, true),
}

function pickRenderer(fileLinks: boolean, copyButton: boolean): Renderer {
  if (fileLinks) return copyButton ? renderers.fileLinksCopy : renderers.fileLinks
  return copyButton ? renderers.copy : renderers.plain
}

function onCopyClick(event: Event): void {
  const button = (event.target as Element | null)?.closest?.<HTMLElement>(`.${COPY_BUTTON_CLASS}`)
  if (!button) return
  const code = button.closest(`.${CODE_CLASS}`)?.querySelector('pre')?.textContent
  if (code == null) return
  // marked appends a newline that is not part of the code.
  void copyText(code.replace(/\n$/, '')).then((ok) => {
    const icon = button.querySelector('.material-icons')
    if (!ok || !icon) return
    icon.textContent = 'check'
    setTimeout(() => (icon.textContent = 'content_copy'), COPY_FEEDBACK_MS)
  })
}

let copyListenerInstalled = false

/**
 * Delegated listener: streaming replaces the innerHTML, so the button cannot hold its own listener.
 * Installed once, on the first render that outputs a copy button, so it also works when
 * renderSanitizedMarkdown() is used without MarkdownRendererService.
 */
function ensureCopyListener(): void {
  if (copyListenerInstalled || typeof document === 'undefined') return
  copyListenerInstalled = true
  document.addEventListener('click', onCopyClick)
}

/**
 * THE sanitized Markdown pipeline (marked, then DOMPurify) as an HTML string, shared by the chat messages, the
 * delegation outcomes and the editor of the exchange viewer (which needs the markup itself, not a trusted value).
 */
export function renderSanitizedMarkdown(text: string, options: MarkdownRenderOptions = {}): string {
  if (!text) return ''
  const { fileLinks = false, copyButton = true } = options
  if (copyButton) ensureCopyListener()
  const html = marked.parse(text, {
    renderer: pickRenderer(fileLinks, copyButton),
    breaks: true,
    gfm: true,
    async: false,
  }) as string
  return DOMPurify.sanitize(html, {
    ADD_TAGS: ['span'],
    ADD_ATTR: ['aria-hidden', 'aria-label', 'target', 'rel'],
    ALLOWED_URI_REGEXP: /^(?:(?:(?:f|ht)tps?|mailto|tel|callto|sms|cid|xmpp):|[^a-z]|[a-z+.-]+(?:[^a-z+.-:]|$))/i,
  })
}

/** Single sanitized Markdown pipeline shared by chat messages and delegation outcomes. */
@Injectable({ providedIn: 'root' })
export class MarkdownRendererService {
  private readonly sanitizer = inject(DomSanitizer)

  render(text: string, options: MarkdownRenderOptions = {}): SafeHtml {
    if (!text) return ''
    return this.sanitizer.bypassSecurityTrustHtml(renderSanitizedMarkdown(text, options))
  }
}
