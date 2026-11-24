import { SecurityContext } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { DomSanitizer, SafeHtml } from '@angular/platform-browser'
import { MarkdownRendererService } from './markdown-renderer.service'
import { COPY_FEEDBACK_MS } from './clipboard.utils'

describe('MarkdownRendererService codespan', () => {
  let service: MarkdownRendererService
  let sanitizer: DomSanitizer

  /** The sanitized HTML of a markdown string, as it reaches innerHTML (file links on unless told otherwise). */
  const html = (markdown: string, fileLinks = true): string =>
    (sanitizer.sanitize(SecurityContext.HTML, service.render(markdown, { fileLinks })) ?? '').trim()

  beforeEach(() => {
    TestBed.configureTestingModule({})
    service = TestBed.inject(MarkdownRendererService)
    sanitizer = TestBed.inject(DomSanitizer)
  })

  afterEach(() => TestBed.resetTestingModule())

  it('renders a file reference code span as a link to the file', () => {
    expect(html('Edit `src/a.ts`')).toBe(
      '<p>Edit <a class="agentos-file-link" href="#" data-file="src/a.ts" title="Open in IntelliJ IDEA">' +
        '<code>src/a.ts</code></a></p>'
    )
  })

  it('carries the line and column of a file reference', () => {
    expect(html('See `/abs/x.kt:10:5`')).toContain(
      'data-file="/abs/x.kt" data-line="10" data-column="5" title="Open in IntelliJ IDEA"><code>/abs/x.kt:10:5</code></a>'
    )
  })

  it('renders a file reference as plain code without file links', () => {
    expect(html('Edit `src/a.ts:3`', false)).toBe('<p>Edit <code>src/a.ts:3</code></p>')
    expect(sanitizer.sanitize(SecurityContext.HTML, service.render('`src/a.ts`'))).not.toContain('agentos-file-link')
  })

  it('renders a code span that is not a file reference exactly as before', () => {
    expect(html('Run `npm run build`')).toBe('<p>Run <code>npm run build</code></p>')
    expect(html('The `user.name` field')).toBe('<p>The <code>user.name</code> field</p>')
  })

  it('never turns the text of a code span into markup', () => {
    const rendered = html('Try `<script>alert(1)</script>` and `a.ts<img src=x onerror=alert(1)>`')

    expect(rendered).not.toContain('<script')
    expect(rendered).not.toContain('<img')
    expect(rendered).toContain('<code>&lt;script&gt;alert(1)&lt;/script&gt;</code>')
    expect(rendered).toContain('<code>a.ts&lt;img src=x onerror=alert(1)&gt;</code>')
  })

  it('leaves fenced code blocks unchanged', () => {
    expect(html('```\nsrc/a.ts\n```')).toBe('<pre class="agentos-chat-code-block"><code>src/a.ts\n</code></pre>')
  })
})

describe('MarkdownRendererService code blocks', () => {
  let service: MarkdownRendererService
  let writeText: jest.Mock
  let host: HTMLElement

  function mount(markdown: string, options?: { copyButton?: boolean }): HTMLElement {
    const html = service.render(markdown, options) as SafeHtml & { changingThisBreaksApplicationSecurity: string }
    host.innerHTML = html.changingThisBreaksApplicationSecurity
    return host
  }

  async function click(target: Element): Promise<void> {
    target.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await Promise.resolve()
    await Promise.resolve()
  }

  beforeEach(() => {
    writeText = jest.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    service = TestBed.inject(MarkdownRendererService)
    host = document.createElement('div')
    document.body.appendChild(host)
  })

  afterEach(() => {
    host.remove()
    jest.useRealTimers()
  })

  it('renders a fenced block as wrapper, pre/code and a copy button', () => {
    const el = mount('```ts\nconst a = 1\n```')
    const button = el.querySelector('.ds-md-code > pre + button.ds-md-code__copy')
    expect(el.querySelector('.ds-md-code > pre > code')?.textContent).toBe('const a = 1\n')
    expect(button?.getAttribute('type')).toBe('button')
    expect(button?.getAttribute('aria-label')).toBe('Copy code')
    expect(button?.querySelector('.material-icons')?.textContent).toBe('content_copy')
  })

  it('renders the wrapper without a button when the copy button is off', () => {
    const el = mount('```\ncode\n```', { copyButton: false })
    expect(el.querySelector('.ds-md-code > pre > code')?.textContent).toBe('code\n')
    expect(el.querySelector('button')).toBeNull()
  })

  it('honours the option per call, in either order', () => {
    const block = '```\ncode\n```'
    expect(mount(block, { copyButton: false }).querySelector('.ds-md-code__copy')).toBeNull()
    expect(mount(block).querySelector('.ds-md-code__copy')).not.toBeNull()
    expect(mount(block, { copyButton: true }).querySelector('.ds-md-code__copy')).not.toBeNull()
    expect(mount(block, { copyButton: false }).querySelector('.ds-md-code__copy')).toBeNull()
  })

  it('leaves inline code without a button', () => {
    const el = mount('use `npm test` here')
    expect(el.querySelector('code')?.textContent).toBe('npm test')
    expect(el.querySelector('.ds-md-code, .ds-md-code__copy')).toBeNull()
  })

  it('copies the exact code text, shows a check, then restores the icon', async () => {
    jest.useFakeTimers()
    const el = mount('```\nline 1\n  line 2\n```')
    const icon = el.querySelector('.ds-md-code__copy .material-icons') as HTMLElement
    await click(icon)
    expect(writeText).toHaveBeenCalledWith('line 1\n  line 2')
    expect(icon.textContent).toBe('check')
    jest.advanceTimersByTime(COPY_FEEDBACK_MS)
    expect(icon.textContent).toBe('content_copy')
  })

  it('does nothing for clicks elsewhere', async () => {
    const el = mount('```\ncode\n```\n\nplain')
    await click(el.querySelector('pre') as HTMLElement)
    await click(document.body)
    expect(writeText).not.toHaveBeenCalled()
  })
})
