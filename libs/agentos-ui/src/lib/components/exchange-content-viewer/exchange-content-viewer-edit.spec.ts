import { ComponentFixture, TestBed } from '@angular/core/testing'
import { ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { Subject } from 'rxjs'
import { ComposerAutocompleteService } from '../composer-autocomplete/composer-autocomplete.service'
import { AgentStateService } from '../../services/agent-state.service'
import { PromptStateService } from '../../services/prompt-state.service'
import { ExchangeSelectionRequest } from '../../services/exchange-selection-request.service'
import { ExchangeFileRef, ExchangeSelectionAction } from '../../services/exchange-state.service'
import { USER_PREFERENCES_PORT } from '../../services/user-preferences.service'
import { ExchangeContentViewerComponent } from './exchange-content-viewer.component'

const mockConvert = jest.fn()
jest.mock('../../services/exchange-markdown-convert', () => ({
  htmlToMarkdown: (root: HTMLElement) => mockConvert(root),
}))

describe('ExchangeContentViewerComponent in-place editing, composer and proposals', () => {
  let fixture: ComponentFixture<ExchangeContentViewerComponent>
  let shouldSend: jest.Mock
  const ref = (path: string): ExchangeFileRef => ({ scope: ExchangeFileEntryScopeEnum.CASE, path })

  function create(path = 'notes/todo.md', content: string | null = 'one two three', canEdit = true): void {
    fixture = TestBed.createComponent(ExchangeContentViewerComponent)
    fixture.componentRef.setInput('file', ref(path))
    fixture.componentRef.setInput('content', content)
    fixture.componentRef.setInput('status', 'ready')
    fixture.componentRef.setInput('canEdit', canEdit)
    fixture.componentRef.setInput('namespaceId', 'ns-1')
    fixture.detectChanges()
  }

  const el = (): HTMLElement => fixture.nativeElement
  const button = (title: string): HTMLButtonElement | null => el().querySelector(`button[title="${title}"]`)
  const textarea = (): HTMLTextAreaElement | null => el().querySelector('textarea.exchange-content-viewer__editor')
  const md = (): HTMLElement | null => el().querySelector('.exchange-content-viewer__markdown')
  const iframe = (): HTMLIFrameElement | null => el().querySelector('iframe')
  const composerInput = (): HTMLTextAreaElement | null => el().querySelector('.selection-composer__input')
  const quote = (): HTMLElement | null => el().querySelector('.exchange-content-viewer__quote')
  const card = (): HTMLElement | null => el().querySelector('.exchange-content-viewer__card')
  const cardButton = (label: string): HTMLButtonElement | undefined =>
    Array.from(card()?.querySelectorAll('button') ?? []).find((b) => b.textContent?.trim() === label)
  const request = (overrides: Partial<ExchangeSelectionRequest> = {}): ExchangeSelectionRequest => ({
    id: 'r1',
    ref: ref('notes/todo.md'),
    from: 4,
    to: 7,
    selection: 'two',
    instruction: '@Writer fix it',
    status: 'waiting',
    ...overrides,
  })

  /** Selects [text] inside the rendered markdown, the way the user does with the mouse. */
  function selectInMarkdown(text: string): void {
    const root = md()!
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      const at = (node.nodeValue ?? '').indexOf(text)
      if (at < 0) continue
      const range = document.createRange()
      range.setStart(node, at)
      range.setEnd(node, at + text.length)
      const selection = window.getSelection()!
      selection.removeAllRanges()
      selection.addRange(range)
      document.dispatchEvent(new Event('selectionchange'))
      fixture.detectChanges()
      return
    }
    throw new Error(`"${text}" not found`)
  }

  function typeInComposer(text: string): void {
    const input = composerInput()!
    input.value = text
    input.dispatchEvent(new Event('input'))
    fixture.detectChanges()
  }

  beforeEach(() => {
    mockConvert.mockReset()
    shouldSend = jest.fn((event: KeyboardEvent) => event.key === 'Enter' && !event.shiftKey)
    TestBed.configureTestingModule({
      imports: [ExchangeContentViewerComponent],
      providers: [
        { provide: USER_PREFERENCES_PORT, useValue: { shouldSend } },
        { provide: PromptStateService, useValue: { listEffective: jest.fn() } },
        { provide: AgentStateService, useValue: { listEffective: jest.fn().mockReturnValue(new Subject()) } },
      ],
    })
  })

  afterEach(() => window.getSelection()?.removeAllRanges())

  describe('no Source / Preview toggle', () => {
    it.each(['notes/todo.md', 'page.html', 'data.json', 'a.txt'])('has no toggle for the editable file %s', (path) => {
      create(path, 'x')
      expect(el().querySelector('.exchange-content-viewer__toggle')).toBeNull()
      const labels = Array.from(el().querySelectorAll('button')).map((b) => b.textContent?.trim())
      expect(labels).not.toContain('Source')
      expect(labels).not.toContain('Preview')
    })
  })

  describe('markdown edited in place', () => {
    it('renders the document in a contenteditable element, no textarea', () => {
      create('notes/todo.md', '# Title\n\nhello')
      expect(textarea()).toBeNull()
      expect(md()!.getAttribute('contenteditable')).toBe('true')
      expect(md()!.querySelector('h1')!.textContent).toBe('Title')
    })

    it('is not editable when read-only', () => {
      create('notes/todo.md', 'hello', false)
      expect(md()!.hasAttribute('contenteditable')).toBe(false)
    })

    it('is clean until the user types, tracked from the input events', () => {
      create('notes/todo.md', 'hello')
      expect(button('Save (Ctrl+S)')!.disabled).toBe(true)
      md()!.dispatchEvent(new Event('input'))
      fixture.detectChanges()
      expect(el().textContent).toContain('unsaved')
      expect(button('Save (Ctrl+S)')!.disabled).toBe(false)
    })

    it('converts the edited document back to markdown on save only', async () => {
      mockConvert.mockResolvedValue('# Edited\n')
      create('notes/todo.md', '# Title')
      const saved: { ref: ExchangeFileRef; content: string }[] = []
      fixture.componentInstance.saveRequested.subscribe((event) => saved.push(event))
      md()!.dispatchEvent(new Event('input'))
      fixture.detectChanges()
      expect(mockConvert).not.toHaveBeenCalled()

      button('Save (Ctrl+S)')!.click()
      await Promise.resolve()
      await Promise.resolve()

      expect(mockConvert).toHaveBeenCalledWith(md())
      expect(saved).toEqual([{ ref: ref('notes/todo.md'), content: '# Edited\n' }])
    })

    it('does not re-render the document on a keystroke, so the caret keeps its place', () => {
      create('notes/todo.md', 'hello')
      const paragraph = md()!.querySelector('p')!
      paragraph.firstChild!.nodeValue = 'hello typed'
      md()!.dispatchEvent(new Event('input'))
      fixture.detectChanges()
      expect(md()!.querySelector('p')).toBe(paragraph)
      expect(md()!.textContent).toContain('hello typed')
    })

    it('re-renders when the saved content changes, but keeps edits it has not saved', () => {
      create('notes/todo.md', 'hello')
      fixture.componentRef.setInput('content', 'agent edit')
      fixture.detectChanges()
      expect(md()!.textContent).toContain('agent edit')

      md()!.querySelector('p')!.firstChild!.nodeValue = 'mine'
      md()!.dispatchEvent(new Event('input'))
      fixture.componentRef.setInput('content', 'another agent edit')
      fixture.detectChanges()
      expect(md()!.textContent).toContain('mine')
    })

    it('keeps the document it saved, and is clean again, when the saved content comes back', async () => {
      mockConvert.mockResolvedValue('saved text\n')
      create('notes/todo.md', 'hello')
      md()!.querySelector('p')!.firstChild!.nodeValue = 'typed'
      md()!.dispatchEvent(new Event('input'))
      fixture.detectChanges()
      button('Save (Ctrl+S)')!.click()
      await Promise.resolve()
      await Promise.resolve()
      const paragraph = md()!.querySelector('p')!
      fixture.componentRef.setInput('content', 'saved text\n')
      fixture.detectChanges()
      expect(md()!.querySelector('p')).toBe(paragraph)
      expect(el().textContent).not.toContain('unsaved')
    })

    it('reverts to the loaded content', () => {
      create('notes/todo.md', 'hello')
      md()!.querySelector('p')!.firstChild!.nodeValue = 'typed'
      md()!.dispatchEvent(new Event('input'))
      fixture.detectChanges()
      button('Revert changes')!.click()
      fixture.detectChanges()
      expect(md()!.textContent).toContain('hello')
      expect(el().textContent).not.toContain('unsaved')
    })

    it('asks before leaving with unsaved edits', () => {
      create('notes/todo.md', 'hello')
      const closed = jest.fn()
      fixture.componentInstance.closeRequested.subscribe(closed)
      md()!.dispatchEvent(new Event('input'))
      fixture.detectChanges()
      button('Back to files')!.click()
      fixture.detectChanges()
      expect(closed).not.toHaveBeenCalled()
      ;(el().querySelector('.ds-confirm-dialog__button--confirm') as HTMLButtonElement).click()
      expect(closed).toHaveBeenCalledTimes(1)
    })

    it('saves on Ctrl+S, and shows the conflict banner with a reload', async () => {
      mockConvert.mockResolvedValue('x\n')
      create('notes/todo.md', 'hello')
      const saved = jest.fn()
      fixture.componentInstance.saveRequested.subscribe(saved)
      md()!.dispatchEvent(new Event('input'))
      const event = new KeyboardEvent('keydown', { key: 's', ctrlKey: true, cancelable: true })
      md()!.dispatchEvent(event)
      await Promise.resolve()
      await Promise.resolve()
      expect(event.defaultPrevented).toBe(true)
      expect(saved).toHaveBeenCalledTimes(1)

      fixture.componentRef.setInput('saveStatus', 'conflict')
      fixture.componentRef.setInput('saveError', 'The file was modified since it was opened.')
      fixture.detectChanges()
      const reload = jest.fn()
      fixture.componentInstance.reloadRequested.subscribe(reload)
      ;(el().querySelector('[role="alert"] button') as HTMLButtonElement).click()
      expect(reload).toHaveBeenCalledTimes(1)
    })
  })

  describe('plain text formats edited in place', () => {
    it('keeps a textarea, styled as the document, with raw content', () => {
      create('data.json', '{"a":1}')
      expect(textarea()!.value).toBe('{"a":1}')
      expect(el().querySelector('.exchange-content-viewer__editor-wrap')).not.toBeNull()
    })

    it('tracks dirty state and saves the raw text', () => {
      create('data.json', '{"a":1}')
      const saved: { ref: ExchangeFileRef; content: string }[] = []
      fixture.componentInstance.saveRequested.subscribe((event) => saved.push(event))
      textarea()!.value = '{"a":2}'
      textarea()!.dispatchEvent(new Event('input'))
      fixture.detectChanges()
      expect(el().textContent).toContain('unsaved')
      button('Save (Ctrl+S)')!.click()
      expect(saved).toEqual([{ ref: ref('data.json'), content: '{"a":2}' }])
    })
  })

  describe('html edited in place through the bridge', () => {
    const post = (data: unknown, source: MessageEventSource | null = iframe()!.contentWindow): void => {
      window.dispatchEvent(new MessageEvent('message', { data, source }))
      fixture.detectChanges()
    }

    it('has no textarea, and keeps the sandbox without same-origin', () => {
      create('page.html', '<p>x</p>')
      expect(textarea()).toBeNull()
      expect(iframe()!.getAttribute('sandbox')).toBe('allow-scripts allow-popups allow-popups-to-escape-sandbox')
      expect(iframe()!.getAttribute('sandbox')).not.toContain('allow-same-origin')
    })

    it('turns the edit mode on in the frame once it is ready', () => {
      create('page.html', '<p>x</p>')
      const send = jest.spyOn(iframe()!.contentWindow!, 'postMessage')
      post({ type: 'agentos-exchange-ready' })
      expect(send).toHaveBeenCalledWith({ type: 'agentos-exchange-edit', enabled: true }, '*')
    })

    it('does not turn the edit mode on for a read-only file', () => {
      create('page.html', '<p>x</p>', false)
      const send = jest.spyOn(iframe()!.contentWindow!, 'postMessage')
      post({ type: 'agentos-exchange-ready' })
      expect(send).toHaveBeenCalledWith({ type: 'agentos-exchange-edit', enabled: false }, '*')
    })

    it('accepts the edited html from the frame only, and becomes dirty', () => {
      create('page.html', '<p>x</p>')
      post({ type: 'agentos-exchange-html', html: '<!DOCTYPE html>\n<html><p>other</p></html>' }, window)
      expect(el().textContent).not.toContain('unsaved')
      post({ type: 'agentos-exchange-html', html: '<!DOCTYPE html>\n<html><p>typed</p></html>' })
      expect(el().textContent).toContain('unsaved')
    })

    it.each([
      ['a non-string html', { type: 'agentos-exchange-html', html: 42 }],
      ['no data', null],
      ['an unknown type', { type: 'agentos-exchange-other', html: '<p>a</p>' }],
    ])('ignores a message with %s', (_label, data) => {
      create('page.html', '<p>x</p>')
      post(data)
      expect(el().textContent).not.toContain('unsaved')
    })

    it('saves the latest html received from the bridge, as it is', () => {
      create('page.html', '<p>x</p>')
      const saved: { ref: ExchangeFileRef; content: string }[] = []
      fixture.componentInstance.saveRequested.subscribe((event) => saved.push(event))
      post({ type: 'agentos-exchange-html', html: 'first' })
      post({ type: 'agentos-exchange-html', html: '<!DOCTYPE html>\n<html><body><p>latest</p></body></html>' })
      button('Save (Ctrl+S)')!.click()
      expect(saved).toEqual([
        { ref: ref('page.html'), content: '<!DOCTYPE html>\n<html><body><p>latest</p></body></html>' },
      ])
    })

    it('saves when the bridge relays Ctrl+S', async () => {
      create('page.html', '<p>x</p>')
      const saved = jest.fn()
      fixture.componentInstance.saveRequested.subscribe(saved)
      post({ type: 'agentos-exchange-html', html: '<p>typed</p>' })
      post({ type: 'agentos-exchange-save' })
      await Promise.resolve()
      expect(saved).toHaveBeenCalledTimes(1)
    })

    it('does not reload the frame while the user types', () => {
      create('page.html', '<p>x</p>')
      const srcdoc = iframe()!.getAttribute('srcdoc')
      post({ type: 'agentos-exchange-html', html: '<p>typed</p>' })
      expect(iframe()!.getAttribute('srcdoc')).toBe(srcdoc)
    })
  })

  describe('the composer', () => {
    it('is not shown without a selection, and appears as soon as there is one', () => {
      create()
      expect(composerInput()).toBeNull()
      selectInMarkdown('two')
      expect(composerInput()).not.toBeNull()
      expect(composerInput()!.placeholder).toBe('Ask an agent about the selection… type @ to pick an agent')
      expect(el().querySelector('.selection-composer ds-icon-button[icon="send"]')).not.toBeNull()
      expect(el().querySelector('.exchange-content-viewer__ask')).toBeNull()
    })

    it('has no agent dropdown or select', () => {
      create()
      selectInMarkdown('two')
      expect(el().querySelector('select')).toBeNull()
      expect(el().querySelector('mat-select, ds-select')).toBeNull()
    })

    it('has the look of the chat composer: its directive, and opaque tokens', () => {
      create()
      selectInMarkdown('two')
      expect(el().querySelector('.selection-composer')!.classList.contains('ds-blueprint')).toBe(true)
    })

    it('suggests the agents when @ is typed, through the autocomplete service', () => {
      create()
      selectInMarkdown('two')
      const service = fixture.debugElement
        .query((d) => d.name === 'agentos-selection-composer')
        .injector.get(ComposerAutocompleteService)
      const onInput = jest.spyOn(service, 'onInput')
      typeInComposer('please @')
      expect(onInput).toHaveBeenCalledWith('please @', expect.anything())
      typeInComposer('no mention here')
      expect(onInput).toHaveBeenCalledTimes(1)
    })

    it('sends the payload on Enter, following the user preference', () => {
      create('notes/todo.md', 'one two three')
      const actions: ExchangeSelectionAction[] = []
      fixture.componentInstance.selectionActionRequested.subscribe((action) => actions.push(action))
      selectInMarkdown('two')
      typeInComposer('@Writer translate')
      composerInput()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }))
      expect(shouldSend).toHaveBeenCalled()
      expect(actions).toEqual([
        {
          ref: ref('notes/todo.md'),
          selection: 'two',
          from: 4,
          to: 7,
          instruction: '@Writer translate',
          unsavedChanges: false,
        },
      ])
    })

    it('does not send on Shift+Enter, nor an empty instruction', () => {
      create()
      const run = jest.fn()
      fixture.componentInstance.selectionActionRequested.subscribe(run)
      selectInMarkdown('two')
      typeInComposer('text')
      composerInput()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', shiftKey: true, cancelable: true }))
      typeInComposer('   ')
      composerInput()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }))
      expect(run).not.toHaveBeenCalled()
    })

    it('sends with the send button', () => {
      create()
      const run = jest.fn()
      fixture.componentInstance.selectionActionRequested.subscribe(run)
      selectInMarkdown('two')
      typeInComposer('go')
      ;(el().querySelector('.selection-composer ds-icon-button button') as HTMLButtonElement).click()
      expect(run).toHaveBeenCalledWith(expect.objectContaining({ instruction: 'go', selection: 'two' }))
    })

    it('closes on Escape, without sending, and drops the selection', () => {
      create()
      const run = jest.fn()
      fixture.componentInstance.selectionActionRequested.subscribe(run)
      selectInMarkdown('two')
      composerInput()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', cancelable: true }))
      fixture.detectChanges()
      expect(composerInput()).toBeNull()
      expect(run).not.toHaveBeenCalled()
    })

    it('flags a selection taken from unsaved edits', () => {
      create()
      const actions: ExchangeSelectionAction[] = []
      fixture.componentInstance.selectionActionRequested.subscribe((action) => actions.push(action))
      md()!.dispatchEvent(new Event('input'))
      selectInMarkdown('two')
      typeInComposer('fix')
      composerInput()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }))
      expect(actions[0]!.unsavedChanges).toBe(true)
    })

    it('works from a textarea selection, with its lines and offsets', () => {
      create('data.json', 'one\ntwo\nthree')
      const actions: ExchangeSelectionAction[] = []
      fixture.componentInstance.selectionActionRequested.subscribe((action) => actions.push(action))
      textarea()!.setSelectionRange(4, 13)
      textarea()!.dispatchEvent(new Event('select'))
      fixture.detectChanges()
      typeInComposer('translate')
      composerInput()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }))
      expect(actions[0]).toEqual(
        expect.objectContaining({ selection: 'two\nthree', startLine: 2, endLine: 3, from: 4, to: 13 })
      )
    })

    it('shows the selection in a quote above the composer', () => {
      create()
      selectInMarkdown('two')
      expect(quote()!.textContent).toContain('two')
    })

    it('relays the selection posted by the html frame', () => {
      create('page.html', '<p>hello world</p>', false)
      window.dispatchEvent(
        new MessageEvent('message', {
          data: { type: 'agentos-exchange-selection', text: '  hello  ' },
          source: iframe()!.contentWindow,
        })
      )
      fixture.detectChanges()
      expect(composerInput()).not.toBeNull()
      expect(quote()!.textContent).toContain('hello')
    })
  })

  describe('the highlight', () => {
    function sendFromComposer(): void {
      typeInComposer('@Writer fix')
      composerInput()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }))
      fixture.detectChanges()
    }

    it('wraps the frozen selection in a mark when the browser has no CSS highlights', () => {
      create()
      selectInMarkdown('two')
      composerInput()!.dispatchEvent(new FocusEvent('focusin', { bubbles: true }))
      fixture.detectChanges()
      TestBed.flushEffects?.()
      fixture.detectChanges()
      expect(md()!.querySelector('mark.agentos-selection')?.textContent).toBe('two')
    })

    it('stays after focusing the composer, which collapses the live selection', () => {
      create()
      selectInMarkdown('two')
      el()
        .querySelector('.exchange-content-viewer__dock')!
        .dispatchEvent(new Event('mousedown', { bubbles: true }))
      window.getSelection()!.removeAllRanges()
      document.dispatchEvent(new Event('selectionchange'))
      fixture.detectChanges()
      expect(composerInput()).not.toBeNull()
      expect(quote()!.textContent).toContain('two')
    })

    it('stays, with the quote and a waiting line, after sending', () => {
      create()
      selectInMarkdown('two')
      sendFromComposer()
      fixture.componentRef.setInput('request', request())
      fixture.detectChanges()
      expect(quote()!.textContent).toContain('two')
      expect(el().querySelector('.exchange-content-viewer__waiting')!.textContent).toContain('Waiting for @Writer')
      expect(composerInput()).toBeNull()
    })

    it('says the agent when none was mentioned', () => {
      create()
      selectInMarkdown('two')
      sendFromComposer()
      fixture.componentRef.setInput('request', request({ instruction: 'fix it' }))
      fixture.detectChanges()
      expect(el().querySelector('.exchange-content-viewer__waiting')!.textContent).toContain('Waiting for the agent')
    })

    it('is dismissed by the close button of the quote, clearing the request', () => {
      create()
      selectInMarkdown('two')
      sendFromComposer()
      fixture.componentRef.setInput('request', request())
      fixture.detectChanges()
      const cleared = jest.fn()
      fixture.componentInstance.requestCleared.subscribe(cleared)
      ;(el().querySelector('.exchange-content-viewer__quote ds-icon-button button') as HTMLButtonElement).click()
      expect(cleared).toHaveBeenCalledWith('r1')
    })
  })

  describe('the agent proposal', () => {
    const proposed = (overrides: Partial<ExchangeSelectionRequest> = {}) =>
      request({ status: 'proposed', proposal: 'TWO', answer: 'Upper case is clearer.', ...overrides })

    function withProposal(content = 'one two three', overrides: Partial<ExchangeSelectionRequest> = {}): void {
      create('notes/todo.md', content)
      selectInMarkdown('two')
      fixture.componentRef.setInput('request', request(overrides))
      fixture.detectChanges()
      fixture.componentRef.setInput('request', proposed(overrides))
      fixture.detectChanges()
    }

    it('shows an annotation card with the explanation and a word diff', () => {
      withProposal()
      expect(card()!.textContent).toContain('Upper case is clearer.')
      expect(card()!.querySelector('del')!.textContent).toBe('two')
      expect(card()!.querySelector('ins')!.textContent).toBe('TWO')
      expect(cardButton('Accept')).toBeDefined()
      expect(cardButton('Reject')).toBeDefined()
      expect(cardButton('Ask again')).toBeDefined()
    })

    it('is shown while the request waits for nothing: no card yet', () => {
      create()
      selectInMarkdown('two')
      fixture.componentRef.setInput('request', request())
      fixture.detectChanges()
      expect(card()).toBeNull()
    })

    it('Accept replaces the frozen range in the draft, makes it dirty, never saves, and clears the request', () => {
      withProposal()
      const saved = jest.fn()
      const cleared = jest.fn()
      fixture.componentInstance.saveRequested.subscribe(saved)
      fixture.componentInstance.requestCleared.subscribe(cleared)

      cardButton('Accept')!.click()
      fixture.detectChanges()

      expect(md()!.textContent).toContain('one TWO three')
      expect(el().textContent).toContain('unsaved')
      expect(saved).not.toHaveBeenCalled()
      expect(cleared).toHaveBeenCalledWith('r1')
    })

    it('Accept replaces the passage in a text file at its offsets', () => {
      create('a.txt', 'one two three')
      textarea()!.setSelectionRange(4, 7)
      textarea()!.dispatchEvent(new Event('select'))
      fixture.detectChanges()
      typeInComposer('fix')
      composerInput()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }))
      fixture.componentRef.setInput('request', proposed({ ref: ref('a.txt') }))
      fixture.detectChanges()

      cardButton('Accept')!.click()
      fixture.detectChanges()

      expect(textarea()!.value).toBe('one TWO three')
      expect(el().textContent).toContain('unsaved')
    })

    it('Accept searches the text when the passage moved', () => {
      create('a.txt', 'one two three')
      textarea()!.setSelectionRange(4, 7)
      textarea()!.dispatchEvent(new Event('select'))
      fixture.detectChanges()
      typeInComposer('fix')
      composerInput()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }))
      textarea()!.value = 'zero one two three'
      textarea()!.dispatchEvent(new Event('input'))
      fixture.componentRef.setInput('request', proposed({ ref: ref('a.txt') }))
      fixture.detectChanges()

      cardButton('Accept')!.click()
      fixture.detectChanges()

      expect(textarea()!.value).toBe('zero one TWO three')
    })

    it('shows an error, and keeps the card, when the passage changed', () => {
      create('a.txt', 'one two three')
      textarea()!.setSelectionRange(4, 7)
      textarea()!.dispatchEvent(new Event('select'))
      fixture.detectChanges()
      typeInComposer('fix')
      composerInput()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }))
      textarea()!.value = 'one 2 three'
      textarea()!.dispatchEvent(new Event('input'))
      fixture.componentRef.setInput('request', proposed({ ref: ref('a.txt') }))
      fixture.detectChanges()
      const cleared = jest.fn()
      fixture.componentInstance.requestCleared.subscribe(cleared)

      cardButton('Accept')!.click()
      fixture.detectChanges()

      expect(card()!.querySelector('[role="alert"]')!.textContent).toContain('The passage changed, re-select it')
      expect(textarea()!.value).toBe('one 2 three')
      expect(cleared).not.toHaveBeenCalled()
    })

    it('Accept posts a replace to the bridge of an html page', () => {
      create('page.html', '<p>one two three</p>')
      const send = jest.spyOn(iframe()!.contentWindow!, 'postMessage')
      window.dispatchEvent(
        new MessageEvent('message', {
          data: { type: 'agentos-exchange-selection', text: 'two' },
          source: iframe()!.contentWindow,
        })
      )
      fixture.detectChanges()
      typeInComposer('fix')
      composerInput()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }))
      fixture.componentRef.setInput('request', proposed({ ref: ref('page.html') }))
      fixture.detectChanges()

      cardButton('Accept')!.click()

      expect(send).toHaveBeenCalledWith({ type: 'agentos-exchange-replace', from: 'two', to: 'TWO' }, '*')
    })

    it('Reject clears the request and the highlight', () => {
      withProposal()
      const cleared = jest.fn()
      fixture.componentInstance.requestCleared.subscribe(cleared)
      cardButton('Reject')!.click()
      fixture.detectChanges()
      expect(cleared).toHaveBeenCalledWith('r1')
      expect(md()!.querySelector('mark.agentos-selection')).toBeNull()
      expect(quote()).toBeNull()
    })

    it('Ask again reopens the composer prefilled with the instruction', () => {
      withProposal()
      const cleared = jest.fn()
      fixture.componentInstance.requestCleared.subscribe(cleared)
      cardButton('Ask again')!.click()
      fixture.componentRef.setInput('request', null)
      fixture.detectChanges()
      expect(cleared).toHaveBeenCalledWith('r1')
      expect(composerInput()!.value).toBe('@Writer fix it')
    })

    it('shows an answer with only a Dismiss', () => {
      create()
      selectInMarkdown('two')
      fixture.componentRef.setInput('request', request({ status: 'answered', answer: 'Nothing to change.' }))
      fixture.detectChanges()
      expect(card()!.textContent).toContain('Nothing to change.')
      expect(cardButton('Accept')).toBeUndefined()
      const cleared = jest.fn()
      fixture.componentInstance.requestCleared.subscribe(cleared)
      cardButton('Dismiss')!.click()
      expect(cleared).toHaveBeenCalledWith('r1')
    })

    it('shows a failure with a Dismiss', () => {
      create()
      selectInMarkdown('two')
      fixture.componentRef.setInput('request', request({ status: 'failed', answer: 'Model unavailable' }))
      fixture.detectChanges()
      expect(card()!.querySelector('[role="alert"]')!.textContent).toContain('Model unavailable')
      expect(cardButton('Dismiss')).toBeDefined()
    })

    it('restores the highlight of a pending request when the file is opened again', () => {
      create()
      fixture.componentRef.setInput('request', request())
      fixture.detectChanges()
      expect(quote()!.textContent).toContain('two')
      expect(el().querySelector('.exchange-content-viewer__waiting')).not.toBeNull()
    })
  })
})
