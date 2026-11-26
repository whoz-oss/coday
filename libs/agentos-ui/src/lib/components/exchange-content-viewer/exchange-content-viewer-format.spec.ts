import { ComponentFixture, TestBed } from '@angular/core/testing'
import { ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { Subject } from 'rxjs'
import { AgentStateService } from '../../services/agent-state.service'
import { ExchangeSelectionRequest } from '../../services/exchange-selection-request.service'
import { ExchangeFileRef, ExchangeSelectionAction } from '../../services/exchange-state.service'
import { PromptStateService } from '../../services/prompt-state.service'
import { USER_PREFERENCES_PORT } from '../../services/user-preferences.service'
import { buildSelectionMessage } from './exchange-selection-message'
import { ExchangeContentViewerComponent } from './exchange-content-viewer.component'

const mockConvert = jest.fn()
// The save conversion is mocked like in the other viewer specs; the selection markdown, the rendering of a proposal,
// the paste conversion and every command are real (real turndown, real marked + DOMPurify).
jest.mock('../../services/exchange-markdown-convert', () => ({
  ...jest.requireActual('../../services/exchange-markdown-convert'),
  htmlToMarkdown: (root: HTMLElement) => mockConvert(root),
}))

async function flush(): Promise<void> {
  for (let i = 0; i < 5; i++) await new Promise((resolve) => setTimeout(resolve, 0))
}

describe('ExchangeContentViewerComponent markdown formatting, selection markdown and rendered proposals', () => {
  let fixture: ComponentFixture<ExchangeContentViewerComponent>
  const ref = (path: string): ExchangeFileRef => ({ scope: ExchangeFileEntryScopeEnum.CASE, path })

  function create(content: string, canEdit = true): void {
    fixture = TestBed.createComponent(ExchangeContentViewerComponent)
    fixture.componentRef.setInput('file', ref('notes/todo.md'))
    fixture.componentRef.setInput('content', content)
    fixture.componentRef.setInput('status', 'ready')
    fixture.componentRef.setInput('canEdit', canEdit)
    fixture.componentRef.setInput('namespaceId', 'ns-1')
    fixture.detectChanges()
  }

  const el = (): HTMLElement => fixture.nativeElement
  const md = (): HTMLElement => el().querySelector('.exchange-content-viewer__markdown') as HTMLElement
  /** The markup of the document, without the line break marked leaves after a block. */
  const html = (): string => md().innerHTML.trim()
  /** The document as the user would have typed it (a lone `#` renders as a heading, so it cannot come from a file). */
  const setDoc = (markup: string): void => {
    md().innerHTML = markup
  }
  const composerInput = (): HTMLTextAreaElement | null => el().querySelector('.selection-composer__input')
  const card = (): HTMLElement | null => el().querySelector('.exchange-content-viewer__card')
  const cardButton = (label: string): HTMLButtonElement | undefined =>
    Array.from(card()?.querySelectorAll('button') ?? []).find((b) => b.textContent?.trim() === label)
  const toolbarButton = (title: string): HTMLButtonElement | null =>
    el().querySelector(`.exchange-content-viewer__toolbar button[title="${title}"]`)

  function textNode(text: string): Text {
    const walker = document.createTreeWalker(md(), NodeFilter.SHOW_TEXT)
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      if ((node.nodeValue ?? '').includes(text)) return node as Text
    }
    throw new Error(`"${text}" not found`)
  }

  function selectRange(from: string, fromOffset: number, to: string, toOffset: number): void {
    const range = document.createRange()
    range.setStart(textNode(from), fromOffset)
    range.setEnd(textNode(to), toOffset)
    const selection = window.getSelection()!
    selection.removeAllRanges()
    selection.addRange(range)
  }

  /** Selects [text] with the mouse (drag, then release), which opens the composer. */
  function selectText(text: string): void {
    const node = textNode(text)
    const at = (node.nodeValue ?? '').indexOf(text)
    selectRange(text, at, text, at + text.length)
    document.dispatchEvent(new Event('selectionchange'))
    md().dispatchEvent(new MouseEvent('mouseup', { bubbles: true }))
    fixture.detectChanges()
  }

  function key(init: KeyboardEventInit): KeyboardEvent {
    const event = new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...init })
    md().dispatchEvent(event)
    fixture.detectChanges()
    return event
  }

  function caretIn(text: string, offset: number): void {
    selectRange(text, offset, text, offset)
  }

  const request = (overrides: Partial<ExchangeSelectionRequest> = {}): ExchangeSelectionRequest => ({
    id: 'r1',
    ref: ref('notes/todo.md'),
    from: 0,
    to: 0,
    selection: '',
    instruction: '@Writer fix it',
    status: 'waiting',
    ...overrides,
  })

  beforeEach(() => {
    mockConvert.mockReset()
    TestBed.configureTestingModule({
      imports: [ExchangeContentViewerComponent],
      providers: [
        { provide: USER_PREFERENCES_PORT, useValue: { shouldSend: (e: KeyboardEvent) => e.key === 'Enter' } },
        { provide: PromptStateService, useValue: { listEffective: jest.fn() } },
        { provide: AgentStateService, useValue: { listEffective: jest.fn().mockReturnValue(new Subject()) } },
      ],
    })
  })

  afterEach(() => window.getSelection()?.removeAllRanges())

  describe('selection markdown', () => {
    it('computes the markdown of a selection spanning an h1 and a paragraph, and sends it to the agent', async () => {
      create('# Title\n\nparagraph text')
      const actions: ExchangeSelectionAction[] = []
      fixture.componentInstance.selectionActionRequested.subscribe((action) => actions.push(action))
      selectRange('Title', 0, 'paragraph text', 9)
      document.dispatchEvent(new Event('selectionchange'))
      md().dispatchEvent(new MouseEvent('mouseup', { bubbles: true }))
      fixture.detectChanges()
      const input = composerInput()!
      input.value = '@Writer rewrite'
      input.dispatchEvent(new Event('input'))
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }))
      await flush()

      expect(actions).toHaveLength(1)
      expect(actions[0]!.selectionMarkdown).toBe('# Title\n\nparagraph')
      // the flattened text stays, for the highlight and the anchoring
      expect(actions[0]!.selection).toContain('Title')
      expect(actions[0]!.selection).not.toContain('#')
      const message = buildSelectionMessage(actions[0]!, 'req1')
      expect(message).toContain('```markdown\n# Title\n\nparagraph\n```')
      expect(message).toContain('the proposal must be markdown too')
    })
  })

  describe('accepting a markdown proposal', () => {
    async function withProposal(content: string, select: () => void, proposal: string): Promise<void> {
      create(content)
      select()
      document.dispatchEvent(new Event('selectionchange'))
      md().dispatchEvent(new MouseEvent('mouseup', { bubbles: true }))
      fixture.detectChanges()
      await flush()
      const frozenText = window.getSelection()?.toString() || ''
      fixture.componentRef.setInput('request', request({ selection: frozenText || 'x', status: 'waiting' }))
      fixture.detectChanges()
      fixture.componentRef.setInput(
        'request',
        request({ selection: frozenText || 'x', status: 'proposed', proposal, answer: 'Done.' })
      )
      fixture.detectChanges()
    }

    it('renders a multi-block proposal as h1 + p elements, not as "# " text', async () => {
      await withProposal(
        '# Old title\n\nold paragraph\n\nafter',
        () => selectRange('Old title', 0, 'old paragraph', 13),
        '# New title\n\nnew paragraph'
      )
      cardButton('Accept')!.click()
      fixture.detectChanges()

      expect(md().querySelector('h1')!.textContent).toBe('New title')
      expect(md().querySelectorAll('h1')).toHaveLength(1)
      expect(md().textContent).not.toContain('# ')
      expect(Array.from(md().querySelectorAll('p')).map((p) => p.textContent)).toEqual(['new paragraph', 'after'])
      expect(el().textContent).toContain('unsaved')
    })

    it('keeps an inline proposal inline in the surrounding paragraph', async () => {
      await withProposal('one two three', () => selectRange('one two three', 4, 'one two three', 7), '**TWO**')
      cardButton('Accept')!.click()
      fixture.detectChanges()
      expect(html()).toBe('<p>one <strong>TWO</strong> three</p>')
    })

    it('diffs the markdown of the selection against the proposal', async () => {
      create('# Title\n\nparagraph text')
      selectRange('Title', 0, 'paragraph text', 9)
      document.dispatchEvent(new Event('selectionchange'))
      md().dispatchEvent(new MouseEvent('mouseup', { bubbles: true }))
      fixture.detectChanges()
      await flush()
      fixture.componentRef.setInput('request', request({ selection: 'Title paragraph', status: 'waiting' }))
      fixture.detectChanges()
      fixture.componentRef.setInput(
        'request',
        request({ selection: 'Title paragraph', status: 'proposed', proposal: '## Title\n\nparagraph', answer: 'ok' })
      )
      fixture.detectChanges()
      const inserted = Array.from(card()!.querySelectorAll('ins')).map((n) => n.textContent)
      const deleted = Array.from(card()!.querySelectorAll('del')).map((n) => n.textContent)
      expect(deleted.join('')).toContain('#')
      expect(inserted.join('')).toContain('##')
    })
  })

  describe('keyboard shortcuts', () => {
    it('Ctrl+B makes the selection bold, and the draft dirty', () => {
      create('one two three')
      selectRange('one two three', 4, 'one two three', 7)
      const event = key({ key: 'b', ctrlKey: true })
      expect(event.defaultPrevented).toBe(true)
      expect(html()).toBe('<p>one <strong>two</strong> three</p>')
      expect(el().textContent).toContain('unsaved')
    })

    it('Cmd+I italicises, Ctrl+E makes code', () => {
      create('one two three')
      selectRange('one two three', 4, 'one two three', 7)
      key({ key: 'i', metaKey: true })
      expect(html()).toBe('<p>one <em>two</em> three</p>')
      selectRange('one ', 0, 'one ', 3)
      key({ key: 'e', ctrlKey: true })
      expect(md().querySelector('code')!.textContent).toBe('one')
    })

    it('Ctrl+Alt+1 makes the paragraph a heading, Ctrl+Alt+0 a paragraph again', () => {
      create('hello world')
      caretIn('hello world', 3)
      key({ key: '1', code: 'Digit1', ctrlKey: true, altKey: true })
      expect(html()).toBe('<h1>hello world</h1>')
      key({ key: '0', code: 'Digit0', ctrlKey: true, altKey: true })
      expect(html()).toBe('<p>hello world</p>')
    })

    it('Ctrl+Shift+8 and Ctrl+Shift+7 make lists', () => {
      create('item')
      caretIn('item', 1)
      key({ key: '*', code: 'Digit8', ctrlKey: true, shiftKey: true })
      expect(md().querySelector('ul > li')!.textContent).toBe('item')
      key({ key: '&', code: 'Digit7', ctrlKey: true, shiftKey: true })
      expect(md().querySelector('ol > li')!.textContent).toBe('item')
    })

    it('does not format a read-only document', () => {
      create('one two three', false)
      selectRange('one two three', 4, 'one two three', 7)
      md().dispatchEvent(new KeyboardEvent('keydown', { key: 'b', ctrlKey: true, bubbles: true, cancelable: true }))
      expect(md().querySelector('strong')).toBeNull()
      expect(el().querySelector('.exchange-content-viewer__toolbar')).toBeNull()
    })
  })

  describe('markdown input rules', () => {
    it('"# " at the start of a paragraph becomes an h1 on the space key', () => {
      create('x')
      setDoc('<p>#</p>')
      caretIn('#', 1)
      const event = key({ key: ' ' })
      expect(event.defaultPrevented).toBe(true)
      expect(md().querySelector('h1')).not.toBeNull()
      expect(md().textContent).not.toContain('#')
    })

    it('"- " becomes a bullet list', () => {
      create('x')
      setDoc('<p>-</p>')
      caretIn('-', 1)
      key({ key: ' ' })
      expect(md().querySelector('ul > li')).not.toBeNull()
    })

    it('**x** becomes strong when the closing marker is typed', () => {
      create('x')
      setDoc('<p>a **x**</p>')
      caretIn('a **x**', 7)
      md().dispatchEvent(new InputEvent('input', { inputType: 'insertText', data: '*', bubbles: true }))
      fixture.detectChanges()
      expect(md().querySelector('strong')!.textContent).toBe('x')
      expect(md().textContent).not.toContain('**')
    })
  })

  describe('the formatting toolbar', () => {
    it('is shown for an editable markdown file only, with the expected buttons', () => {
      create('hello')
      for (const title of [
        'Heading 1 (Ctrl+Alt+1)',
        'Heading 2 (Ctrl+Alt+2)',
        'Bold (Ctrl+B)',
        'Italic (Ctrl+I)',
        'Bulleted list (Ctrl+Shift+8)',
        'Numbered list (Ctrl+Shift+7)',
        'Link (Ctrl+K)',
        'Code (Ctrl+E)',
        'Paragraph (Ctrl+Alt+0)',
      ]) {
        expect(toolbarButton(title)).not.toBeNull()
      }
    })

    it('keeps the selection on mousedown', () => {
      create('hello')
      const event = new MouseEvent('mousedown', { bubbles: true, cancelable: true })
      toolbarButton('Bold (Ctrl+B)')!.dispatchEvent(event)
      expect(event.defaultPrevented).toBe(true)
    })

    it('applies a format to the selection and marks the draft dirty', () => {
      create('one two three')
      selectRange('one two three', 4, 'one two three', 7)
      toolbarButton('Bold (Ctrl+B)')!.click()
      fixture.detectChanges()
      expect(html()).toBe('<p>one <strong>two</strong> three</p>')
      expect(el().textContent).toContain('unsaved')
    })

    it('is not there for a read-only file', () => {
      create('hello', false)
      expect(el().querySelector('.exchange-content-viewer__toolbar')).toBeNull()
    })
  })

  describe('the link input', () => {
    const linkInput = (): HTMLInputElement | null => el().querySelector('.exchange-content-viewer__link-input')
    const type = (value: string): void => {
      linkInput()!.value = value
      linkInput()!.dispatchEvent(new Event('input'))
      fixture.detectChanges()
    }
    const enter = (): void => {
      linkInput()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }))
      fixture.detectChanges()
    }

    it('opens on Ctrl+K (no window.prompt) and links the selection', () => {
      const prompt = jest.spyOn(window, 'prompt')
      create('see docs now')
      selectRange('see docs now', 4, 'see docs now', 8)
      key({ key: 'k', ctrlKey: true })
      expect(linkInput()).not.toBeNull()
      type('https://example.com')
      enter()
      expect(md().querySelector('a')!.getAttribute('href')).toBe('https://example.com')
      expect(md().querySelector('a')!.textContent).toBe('docs')
      expect(linkInput()).toBeNull()
      expect(prompt).not.toHaveBeenCalled()
      expect(el().textContent).toContain('unsaved')
    })

    it('rejects javascript: and keeps the input open with the reason', () => {
      create('see docs now')
      selectRange('see docs now', 4, 'see docs now', 8)
      toolbarButton('Link (Ctrl+K)')!.click()
      fixture.detectChanges()
      type('javascript:alert(1)')
      enter()
      expect(md().querySelector('a')).toBeNull()
      expect(linkInput()).not.toBeNull()
      expect(el().querySelector('.exchange-content-viewer__link-error')!.textContent).toContain('Only http(s)')
    })

    it('closes on Escape without linking', () => {
      create('see docs now')
      selectRange('see docs now', 4, 'see docs now', 8)
      key({ key: 'k', ctrlKey: true })
      linkInput()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', cancelable: true }))
      fixture.detectChanges()
      expect(linkInput()).toBeNull()
      expect(md().querySelector('a')).toBeNull()
    })
  })

  describe('paste', () => {
    function paste(data: Record<string, string>): Event {
      const event = new Event('paste', { bubbles: true, cancelable: true }) as Event & { clipboardData: unknown }
      event.clipboardData = { getData: (type: string) => data[type] ?? '' }
      md().dispatchEvent(event)
      return event
    }

    it('pastes plain text as plain text', () => {
      create('one two')
      selectRange('one two', 3, 'one two', 3)
      const event = paste({ 'text/plain': ' and a half' })
      expect(event.defaultPrevented).toBe(true)
      expect(html()).toBe('<p>one and a half two</p>')
    })

    it('renders pasted markdown, keeping its formatting', () => {
      create('start')
      selectRange('start', 5, 'start', 5)
      paste({ 'text/plain': '\n\n# Pasted\n\n- a\n- b' })
      fixture.detectChanges()
      expect(md().querySelector('h1')!.textContent).toBe('Pasted')
      expect(md().querySelectorAll('ul > li')).toHaveLength(2)
      expect(md().textContent).not.toContain('# ')
      expect(el().textContent).toContain('unsaved')
    })

    it('sanitises and normalises pasted HTML: it goes through turndown and the sanitised pipeline', async () => {
      create('start')
      selectRange('start', 5, 'start', 5)
      paste({
        'text/html':
          '<p onclick="alert(1)" style="color:red">Hello <b>bold</b><script>alert(1)</script><img src=x onerror=alert(1)></p>',
        'text/plain': 'Hello bold',
      })
      await flush()
      fixture.detectChanges()
      const html = md().innerHTML
      expect(html).not.toContain('onclick')
      expect(html).not.toContain('onerror')
      expect(html).not.toContain('<script')
      expect(html).not.toContain('style=')
      expect(md().querySelector('strong')!.textContent).toBe('bold')
      expect(md().textContent).toContain('Hello')
    })
  })

  describe('round trip on save', () => {
    it('converts the formatted document with the save conversion (mocked here, real in the services spec)', async () => {
      mockConvert.mockResolvedValue('# Title\n')
      create('Title')
      caretIn('Title', 2)
      key({ key: '1', code: 'Digit1', ctrlKey: true, altKey: true })
      const saved: { content: string }[] = []
      fixture.componentInstance.saveRequested.subscribe((event) => saved.push(event))
      el().querySelector<HTMLButtonElement>('button[title="Save (Ctrl+S)"]')!.click()
      await flush()
      expect(mockConvert).toHaveBeenCalledWith(md())
      expect(md().querySelector('h1')).not.toBeNull()
      expect(saved).toEqual([{ ref: ref('notes/todo.md'), content: '# Title\n' }])
    })
  })
})
