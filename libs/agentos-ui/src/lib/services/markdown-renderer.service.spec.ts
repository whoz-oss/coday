import { SecurityContext } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { DomSanitizer } from '@angular/platform-browser'
import { MarkdownRendererService } from './markdown-renderer.service'

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
