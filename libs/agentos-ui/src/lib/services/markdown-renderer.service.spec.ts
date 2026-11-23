import { TestBed } from '@angular/core/testing'
import { SafeHtml } from '@angular/platform-browser'
import { COPY_FEEDBACK_MS } from './clipboard.utils'
import { MarkdownRendererService } from './markdown-renderer.service'

describe('MarkdownRendererService code blocks', () => {
  let service: MarkdownRendererService
  let writeText: jest.Mock
  let host: HTMLElement

  function mount(markdown: string): HTMLElement {
    const html = service.render(markdown) as SafeHtml & { changingThisBreaksApplicationSecurity: string }
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
    const button = el.querySelector('.agentos-code-block > pre.agentos-chat-code-block + button.agentos-code-copy')
    expect(el.querySelector('.agentos-code-block > pre > code')?.textContent).toBe('const a = 1\n')
    expect(button?.getAttribute('type')).toBe('button')
    expect(button?.getAttribute('aria-label')).toBe('Copy code')
    expect(button?.querySelector('.material-icons')?.textContent).toBe('content_copy')
  })

  it('leaves inline code without a button', () => {
    const el = mount('use `npm test` here')
    expect(el.querySelector('code')?.textContent).toBe('npm test')
    expect(el.querySelector('.agentos-code-block, .agentos-code-copy')).toBeNull()
  })

  it('copies the exact code text, shows a check, then restores the icon', async () => {
    jest.useFakeTimers()
    const el = mount('```\nline 1\n  line 2\n```')
    const icon = el.querySelector('.agentos-code-copy .material-icons') as HTMLElement
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
