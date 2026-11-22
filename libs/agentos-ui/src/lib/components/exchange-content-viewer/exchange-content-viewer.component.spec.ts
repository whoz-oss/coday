import { ComponentFixture, fakeAsync, TestBed, tick } from '@angular/core/testing'
import { MatDialog } from '@angular/material/dialog'
import { ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { of } from 'rxjs'
import { COPY_FEEDBACK_MS } from '../../services/clipboard.utils'
import { ExchangeHtmlPreviewService } from '../../services/exchange-html-preview.service'
import { ExchangeFileRef, ExchangeSelectionAction } from '../../services/exchange-state.service'
import {
  EXCHANGE_FULL_VIEW_PANEL_CLASS,
  EXCHANGE_HTML_FULL_VIEW_DOWNLOAD,
  ExchangeHtmlFullViewComponent,
} from '../exchange-html-full-view/exchange-html-full-view.component'
import { ExchangeContentViewerComponent } from './exchange-content-viewer.component'

describe('ExchangeContentViewerComponent copy relative path', () => {
  afterEach(() => Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true }))

  it('copies the file path from the header, next to download, with feedback', fakeAsync(() => {
    const writeText = jest.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    TestBed.configureTestingModule({ imports: [ExchangeContentViewerComponent] })
    const fixture = TestBed.createComponent(ExchangeContentViewerComponent)
    fixture.componentRef.setInput('file', { scope: ExchangeFileEntryScopeEnum.NAMESPACE, path: 'docs/api/guide.md' })
    fixture.componentRef.setInput('status', 'ready')
    fixture.componentRef.setInput('content', 'hi')
    fixture.detectChanges()
    const el = fixture.nativeElement as HTMLElement
    const header = () => Array.from(el.querySelectorAll<HTMLButtonElement>('.exchange-content-viewer__header button'))
    const copy = () => header().find((b) => b.title === 'Copy relative path' || b.title === 'Copied')!
    expect(header().map((b) => b.title)).toEqual(['Back to files', 'Copy relative path', 'Download guide.md'])

    copy().click()
    tick(0)
    fixture.detectChanges()
    expect(writeText).toHaveBeenCalledWith('docs/api/guide.md')
    expect(copy().title).toBe('Copied')
    expect(el.querySelector('[aria-live="polite"]')?.textContent?.trim()).toBe('Path copied')
    tick(COPY_FEEDBACK_MS)
    fixture.detectChanges()
    expect(copy().title).toBe('Copy relative path')
  }))
})

describe('ExchangeContentViewerComponent html preview', () => {
  const REPORT = '<h1>Report</h1><script>document.title = "ran"</script>'
  let openInNewTab: jest.Mock
  let open: jest.Mock
  let closedWith: unknown

  function setup(
    path = 'reports/report.html',
    inputs: { status?: 'loading' | 'ready' | 'error'; canView?: boolean; content?: string | null } = {}
  ) {
    openInNewTab = jest.fn()
    closedWith = undefined
    open = jest.fn().mockImplementation(() => ({ afterClosed: () => of(closedWith) }))
    TestBed.configureTestingModule({
      imports: [ExchangeContentViewerComponent],
      providers: [
        { provide: ExchangeHtmlPreviewService, useValue: { openInNewTab } },
        { provide: MatDialog, useValue: { open } },
      ],
    })
    const fixture = TestBed.createComponent(ExchangeContentViewerComponent)
    fixture.componentRef.setInput('file', { scope: ExchangeFileEntryScopeEnum.CASE, path })
    fixture.componentRef.setInput('status', inputs.status ?? 'ready')
    fixture.componentRef.setInput('canView', inputs.canView ?? true)
    fixture.componentRef.setInput('content', inputs.content === undefined ? REPORT : inputs.content)
    fixture.detectChanges()
    const el = fixture.nativeElement as HTMLElement
    const button = (title: string) =>
      el.querySelector<HTMLButtonElement>(`.exchange-content-viewer__header button[title="${title}"]`)
    return { fixture, el, button }
  }

  it('runs the file in a sandbox that allows scripts but never the app origin', () => {
    const { el } = setup()
    const iframe = el.querySelector('iframe')!
    const sandbox = iframe.getAttribute('sandbox')!
    expect(sandbox).toContain('allow-scripts')
    expect(sandbox).toContain('allow-popups')
    expect(sandbox).not.toContain('allow-same-origin')
    expect(sandbox).not.toContain('allow-top-navigation')
    expect(iframe.title).toBe('Preview of report.html')
  })

  it('does not let Angular strip the scripts of the srcdoc', () => {
    const { el } = setup()
    const srcdoc = el.querySelector('iframe')!.getAttribute('srcdoc')!
    expect(srcdoc).toContain(REPORT)
    expect(srcdoc).toContain('<script>document.title')
  })

  it('offers the full view and the new tab next to the other actions, for a ready html file', () => {
    const { el } = setup()
    const titles = Array.from(el.querySelectorAll<HTMLButtonElement>('.exchange-content-viewer__header button')).map(
      (b) => b.title
    )
    expect(titles).toEqual([
      'Back to files',
      'Open full view',
      'Open in new tab',
      'Copy relative path',
      'Download report.html',
    ])
  })

  it.each(['notes.md', 'notes.txt', 'data.json', 'conf.yaml', 'logo.png'])('does not offer them for %s', (path) => {
    const { button } = setup(path, { content: 'x' })
    expect(button('Open full view')).toBeNull()
    expect(button('Open in new tab')).toBeNull()
  })

  it.each([
    ['while loading', { status: 'loading' as const }],
    ['on error', { status: 'error' as const }],
    ['when the file cannot be viewed', { canView: false }],
    ['without content', { content: null }],
  ])('does not offer them %s', (_label, inputs) => {
    const { button } = setup('report.html', inputs)
    expect(button('Open full view')).toBeNull()
    expect(button('Open in new tab')).toBeNull()
  })

  it('opens the file in a new tab through the service, with its content and name', () => {
    const { button } = setup()
    button('Open in new tab')!.click()
    expect(openInNewTab).toHaveBeenCalledWith(REPORT, 'report.html')
  })

  it('opens the full view in a dialog that covers the viewport', () => {
    const { button } = setup()
    button('Open full view')!.click()
    expect(open).toHaveBeenCalledTimes(1)
    const [component, config] = open.mock.calls[0]!
    expect(component).toBe(ExchangeHtmlFullViewComponent)
    expect(config).toEqual(
      expect.objectContaining({
        data: { kind: 'html', content: REPORT, filename: 'report.html' },
        width: '100vw',
        height: '100vh',
        maxWidth: '100vw',
        maxHeight: '100vh',
      })
    )
    // The global styles of the full view are all scoped under this class: without it the app's 80vh cap wins.
    expect(config.panelClass).toBe(EXCHANGE_FULL_VIEW_PANEL_CLASS)
  })

  it('downloads the file when the full view asks for it, and only then', () => {
    const { fixture, button } = setup()
    const requested = jest.fn()
    fixture.componentInstance.downloadRequested.subscribe(requested)

    button('Open full view')!.click()
    expect(requested).not.toHaveBeenCalled()

    closedWith = EXCHANGE_HTML_FULL_VIEW_DOWNLOAD
    button('Open full view')!.click()
    expect(requested).toHaveBeenCalledWith({ scope: ExchangeFileEntryScopeEnum.CASE, path: 'reports/report.html' })
  })
})

describe('ExchangeContentViewerComponent pdf preview', () => {
  const URL_OF_PDF = 'blob:http://localhost/pdf'
  const PDF = new Blob(['%PDF-1.4'], { type: 'application/pdf' })
  let openInNewTab: jest.Mock
  let openBlobInNewTab: jest.Mock
  let open: jest.Mock
  let closedWith: unknown

  function setup(
    path = 'reports/report.pdf',
    inputs: {
      status?: 'loading' | 'ready' | 'error'
      canView?: boolean
      blobUrl?: string | null
      blob?: Blob | null
    } = {}
  ) {
    openInNewTab = jest.fn()
    openBlobInNewTab = jest.fn()
    closedWith = undefined
    open = jest.fn().mockImplementation(() => ({ afterClosed: () => of(closedWith) }))
    TestBed.configureTestingModule({
      imports: [ExchangeContentViewerComponent],
      providers: [
        { provide: ExchangeHtmlPreviewService, useValue: { openInNewTab, openBlobInNewTab } },
        { provide: MatDialog, useValue: { open } },
      ],
    })
    const fixture = TestBed.createComponent(ExchangeContentViewerComponent)
    fixture.componentRef.setInput('file', { scope: ExchangeFileEntryScopeEnum.CASE, path })
    fixture.componentRef.setInput('status', inputs.status ?? 'ready')
    fixture.componentRef.setInput('canView', inputs.canView ?? true)
    fixture.componentRef.setInput('blobUrl', inputs.blobUrl === undefined ? URL_OF_PDF : inputs.blobUrl)
    fixture.componentRef.setInput('blob', inputs.blob === undefined ? PDF : inputs.blob)
    fixture.detectChanges()
    const el = fixture.nativeElement as HTMLElement
    const button = (title: string) =>
      el.querySelector<HTMLButtonElement>(`.exchange-content-viewer__header button[title="${title}"]`)
    return { fixture, el, button }
  }

  it('renders the blob in an iframe without sandbox (the PDF viewer would not run in one) nor srcdoc', () => {
    const { el } = setup()
    const iframe = el.querySelector('iframe')!
    expect(el.querySelectorAll('iframe')).toHaveLength(1)
    expect(iframe.hasAttribute('sandbox')).toBe(false)
    expect(iframe.hasAttribute('srcdoc')).toBe(false)
    expect(iframe.getAttribute('src')).toBe(URL_OF_PDF)
    expect(iframe.title).toBe('Preview of report.pdf')
    expect(el.querySelector('pre')).toBeNull()
  })

  it('shows a spinner, and no iframe, while the PDF URL is not ready yet', () => {
    const { el } = setup('report.pdf', { blobUrl: null, blob: null })
    expect(el.querySelector('iframe')).toBeNull()
    expect(el.querySelector('ds-spinner')).not.toBeNull()
  })

  it('never gives an iframe a URL that is not a blob: URL', () => {
    const { el } = setup('report.pdf', { blobUrl: 'https://evil.example/x.pdf' })
    expect(el.querySelector('iframe')).toBeNull()
    expect(el.querySelector('ds-spinner')).not.toBeNull()
  })

  it.each([
    ['on error', { status: 'error' as const }],
    ['when the file cannot be viewed', { canView: false }],
  ])('offers the download instead of the PDF %s', (_label, inputs) => {
    const { fixture, el } = setup('report.pdf', inputs)
    const requested = jest.fn()
    fixture.componentInstance.downloadRequested.subscribe(requested)
    expect(el.querySelector('iframe')).toBeNull()
    const cta = Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Download instead')!
    cta.click()
    expect(requested).toHaveBeenCalledWith({ scope: ExchangeFileEntryScopeEnum.CASE, path: 'report.pdf' })
  })

  it('offers the full view and the new tab for a ready PDF', () => {
    const { el } = setup()
    const titles = Array.from(el.querySelectorAll<HTMLButtonElement>('.exchange-content-viewer__header button')).map(
      (b) => b.title
    )
    expect(titles).toEqual([
      'Back to files',
      'Open full view',
      'Open in new tab',
      'Copy relative path',
      'Download report.pdf',
    ])
  })

  it.each([
    ['while loading', { status: 'loading' as const }],
    ['on error', { status: 'error' as const }],
    ['when the file cannot be viewed', { canView: false }],
    ['without URL', { blobUrl: null }],
    ['without bytes', { blob: null }],
  ])('does not offer them %s', (_label, inputs) => {
    const { button } = setup('report.pdf', inputs)
    expect(button('Open full view')).toBeNull()
    expect(button('Open in new tab')).toBeNull()
  })

  it('opens a new tab from the blob through the service, never HTML and never the shell URL', () => {
    const { button } = setup()
    button('Open in new tab')!.click()
    expect(openBlobInNewTab).toHaveBeenCalledWith(PDF)
    expect(openInNewTab).not.toHaveBeenCalled()
  })

  it('opens the full view in a dialog with the PDF data', () => {
    const { button } = setup()
    button('Open full view')!.click()
    const [component, config] = open.mock.calls[0]!
    expect(component).toBe(ExchangeHtmlFullViewComponent)
    expect(config).toEqual(
      expect.objectContaining({
        data: { kind: 'pdf', url: URL_OF_PDF, blob: PDF, filename: 'report.pdf' },
        width: '100vw',
        height: '100vh',
        maxWidth: '100vw',
        maxHeight: '100vh',
        panelClass: EXCHANGE_FULL_VIEW_PANEL_CLASS,
      })
    )
  })

  it('downloads the PDF when the full view asks for it', () => {
    const { fixture, button } = setup()
    const requested = jest.fn()
    fixture.componentInstance.downloadRequested.subscribe(requested)
    closedWith = EXCHANGE_HTML_FULL_VIEW_DOWNLOAD
    button('Open full view')!.click()
    expect(requested).toHaveBeenCalledWith({ scope: ExchangeFileEntryScopeEnum.CASE, path: 'reports/report.pdf' })
  })

  it('keeps HTML in the sandbox: a blob URL given for an html file is ignored', () => {
    const { el, button } = setup('page.html', { blobUrl: URL_OF_PDF })
    // No content: nothing to open, and nothing is rendered from the blob.
    expect(el.querySelector('iframe')!.getAttribute('sandbox')).toContain('allow-scripts')
    expect(el.querySelector('iframe')!.hasAttribute('src')).toBe(false)
    expect(button('Open full view')).toBeNull()
    expect(button('Open in new tab')).toBeNull()
  })
})

describe('ExchangeContentViewerComponent images', () => {
  function setup(path: string, blobUrl: string | null) {
    TestBed.configureTestingModule({ imports: [ExchangeContentViewerComponent] })
    const fixture = TestBed.createComponent(ExchangeContentViewerComponent)
    fixture.componentRef.setInput('file', { scope: ExchangeFileEntryScopeEnum.NAMESPACE, path })
    fixture.componentRef.setInput('status', 'ready')
    fixture.componentRef.setInput('blobUrl', blobUrl)
    fixture.detectChanges()
    return { fixture, el: fixture.nativeElement as HTMLElement }
  }

  it('renders the image, linked to its original size, instead of a text preview', () => {
    const { el } = setup('assets/logo.png', 'blob:http://localhost/abc')
    const img = el.querySelector<HTMLImageElement>('img.exchange-content-viewer__image')!
    expect(img.getAttribute('src')).toBe('blob:http://localhost/abc')
    expect(img.alt).toBe('logo.png')
    const link = img.closest('a')!
    expect(link.getAttribute('href')).toBe('blob:http://localhost/abc')
    expect(link.target).toBe('_blank')
    expect(link.rel).toBe('noopener')
    expect(link.title).toBe('Open original size')
    expect(el.querySelector('pre')).toBeNull()
  })

  it('shows a spinner while the image URL is not ready yet', () => {
    const { el } = setup('logo.png', null)
    expect(el.querySelector('img')).toBeNull()
    expect(el.querySelector('ds-spinner')).not.toBeNull()
  })

  it('falls back to the download CTA, without a broken image, when the image cannot be decoded', () => {
    const { fixture, el } = setup('logo.png', 'blob:http://localhost/bad')
    const requested = jest.fn()
    fixture.componentInstance.downloadRequested.subscribe(requested)
    el.querySelector('img')!.dispatchEvent(new Event('error'))
    fixture.detectChanges()
    expect(el.querySelector('img')).toBeNull()
    expect(el.textContent).toContain('Preview not available')
    const cta = Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Download instead')!
    cta.click()
    expect(requested).toHaveBeenCalledWith({ scope: ExchangeFileEntryScopeEnum.NAMESPACE, path: 'logo.png' })
  })

  it('tries again when a new image URL arrives after a failure', () => {
    const { fixture, el } = setup('logo.png', 'blob:http://localhost/bad')
    el.querySelector('img')!.dispatchEvent(new Event('error'))
    fixture.detectChanges()
    fixture.componentRef.setInput('blobUrl', 'blob:http://localhost/good')
    fixture.detectChanges()
    expect(el.querySelector('img')?.getAttribute('src')).toBe('blob:http://localhost/good')
  })

  it('keeps text files on the text path', () => {
    TestBed.configureTestingModule({ imports: [ExchangeContentViewerComponent] })
    const fixture = TestBed.createComponent(ExchangeContentViewerComponent)
    fixture.componentRef.setInput('file', { scope: ExchangeFileEntryScopeEnum.CASE, path: 'a.txt' })
    fixture.componentRef.setInput('status', 'ready')
    fixture.componentRef.setInput('content', 'hello')
    fixture.detectChanges()
    expect(fixture.nativeElement.querySelector('img')).toBeNull()
    expect(fixture.nativeElement.querySelector('pre')?.textContent).toBe('hello')
  })
})

describe('ExchangeContentViewerComponent editing and selection', () => {
  let fixture: ComponentFixture<ExchangeContentViewerComponent>
  const ref = (path: string): ExchangeFileRef => ({ scope: ExchangeFileEntryScopeEnum.CASE, path })

  function create(path = 'notes/todo.md', content: string | null = 'one\ntwo\nthree', canEdit = true): void {
    fixture = TestBed.createComponent(ExchangeContentViewerComponent)
    fixture.componentRef.setInput('file', ref(path))
    fixture.componentRef.setInput('content', content)
    fixture.componentRef.setInput('status', 'ready')
    fixture.componentRef.setInput('canEdit', canEdit)
    fixture.detectChanges()
  }

  const el = (): HTMLElement => fixture.nativeElement
  // ds-icon-button renders its title on the inner <button>, not on the host element.
  const button = (title: string): HTMLButtonElement | null => el().querySelector(`button[title="${title}"]`)
  const editButton = (): HTMLButtonElement | null => el().querySelector('button[title^="Edit "]')
  const editor = (): HTMLTextAreaElement | null => el().querySelector('textarea.exchange-content-viewer__editor')
  const saveButton = (): HTMLButtonElement | null => button('Save (Ctrl+S)')
  const revertButton = (): HTMLButtonElement | null => button('Revert changes')
  const askButton = (): HTMLButtonElement | null => el().querySelector('.exchange-content-viewer__ask')
  const instruction = (): HTMLTextAreaElement | null => el().querySelector('.exchange-content-viewer__instruction')
  const toggle = (label: 'Source' | 'Preview'): HTMLButtonElement | undefined =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('.exchange-content-viewer__toggle button')).find(
      (b) => b.textContent?.trim() === label
    )
  const iframe = (): HTMLIFrameElement | null => el().querySelector('iframe')

  /** An editable file opens in its editor with no click: this only returns it. */
  function startEdit(): HTMLTextAreaElement {
    return editor()!
  }

  function type(text: string): void {
    const area = editor()!
    area.value = text
    area.dispatchEvent(new Event('input'))
    fixture.detectChanges()
  }

  function select(start: number, end: number): void {
    const area = editor()!
    area.setSelectionRange(start, end)
    area.dispatchEvent(new Event('select'))
    fixture.detectChanges()
  }

  beforeEach(() => TestBed.configureTestingModule({ imports: [ExchangeContentViewerComponent] }))

  describe('opens in the editor', () => {
    it.each(['notes/todo.md', 'data.json', 'a.txt'])(
      'shows the textarea for the editable file %s, with no Edit button',
      (path) => {
        create(path, 'x')
        expect(editor()).not.toBeNull()
        expect(editor()!.value).toBe('x')
        expect(editButton()).toBeNull()
        expect(el().querySelector('pre')).toBeNull()
      }
    )

    it('shows the preview of a read-only file', () => {
      create('notes/todo.md', 'hello', false)
      expect(editor()).toBeNull()
      expect(saveButton()).toBeNull()
      expect(el().querySelector('.exchange-content-viewer__markdown')!.textContent).toContain('hello')
      create('a.txt', 'plain', false)
      expect(editor()).toBeNull()
      expect(el().querySelector('pre')!.textContent).toBe('plain')
    })

    it.each(['logo.png', 'doc.pdf', 'archive.zip'])('has no editor for the binary file %s', (name) => {
      create(name)
      expect(editor()).toBeNull()
    })

    it('has no editor until the content is loaded, nor when the file cannot be viewed', () => {
      create('notes/todo.md')
      fixture.componentRef.setInput('status', 'loading')
      fixture.detectChanges()
      expect(editor()).toBeNull()
      fixture.componentRef.setInput('status', 'ready')
      fixture.componentRef.setInput('canView', false)
      fixture.detectChanges()
      expect(editor()).toBeNull()
    })
  })

  describe('source / preview toggle', () => {
    it('defaults to the source of an editable md, and toggles to its preview showing the draft', () => {
      create('notes/todo.md', '# Title')
      expect(toggle('Source')!.getAttribute('aria-pressed')).toBe('true')
      expect(toggle('Preview')!.getAttribute('aria-pressed')).toBe('false')
      type('# Draft')
      toggle('Preview')!.click()
      fixture.detectChanges()
      expect(editor()).toBeNull()
      expect(el().querySelector('.exchange-content-viewer__markdown')!.textContent).toContain('Draft')
      expect(toggle('Preview')!.getAttribute('aria-pressed')).toBe('true')
      toggle('Source')!.click()
      fixture.detectChanges()
      expect(editor()!.value).toBe('# Draft')
    })

    it('toggles an editable html between its source and a sandboxed preview of the draft', () => {
      create('page.html', '<p>x</p>')
      expect(iframe()).toBeNull()
      type('<p>draft</p>')
      toggle('Preview')!.click()
      fixture.detectChanges()
      expect(iframe()!.getAttribute('sandbox')).not.toContain('allow-same-origin')
      expect(iframe()!.getAttribute('srcdoc')).toContain('<p>draft</p>')
    })

    it('has no toggle for a read-only file, nor for other formats', () => {
      create('page.html', '<p>x</p>', false)
      expect(toggle('Source')).toBeUndefined()
      expect(iframe()).not.toBeNull()
      create('data.json', '{}')
      expect(toggle('Source')).toBeUndefined()
    })
  })

  describe('editing', () => {
    it('opens a textarea on the raw content, not the re-indented JSON', () => {
      create('data.json', '{"a":1}')
      expect(startEdit().value).toBe('{"a":1}')
    })

    it('is clean until the text changes, and Save is disabled', () => {
      create()
      startEdit()
      expect(el().textContent).not.toContain('unsaved')
      expect(saveButton()!.disabled).toBe(true)
      type('changed')
      expect(el().textContent).toContain('unsaved')
      expect(saveButton()!.disabled).toBe(false)
      type('one\ntwo\nthree')
      expect(el().textContent).not.toContain('unsaved')
    })

    it('emits the raw edited content on save', () => {
      create('data.json', '{"a":1}')
      const saved: { ref: ExchangeFileRef; content: string }[] = []
      fixture.componentInstance.saveRequested.subscribe((event) => saved.push(event))
      startEdit()
      type('{"a":2}')
      saveButton()!.click()
      expect(saved).toEqual([{ ref: ref('data.json'), content: '{"a":2}' }])
    })

    it.each([
      ['Ctrl', { ctrlKey: true }],
      ['Cmd', { metaKey: true }],
    ])('saves on %s+S, and prevents the browser save', (_name, modifier) => {
      create()
      const saved = jest.fn()
      fixture.componentInstance.saveRequested.subscribe(saved)
      startEdit()
      type('changed')
      const event = new KeyboardEvent('keydown', { key: 's', cancelable: true, ...modifier })
      editor()!.dispatchEvent(event)
      expect(event.defaultPrevented).toBe(true)
      expect(saved).toHaveBeenCalledWith({ ref: ref('notes/todo.md'), content: 'changed' })
    })

    it('does not save a clean editor on Ctrl+S', () => {
      create()
      const saved = jest.fn()
      fixture.componentInstance.saveRequested.subscribe(saved)
      startEdit()
      editor()!.dispatchEvent(new KeyboardEvent('keydown', { key: 's', ctrlKey: true, cancelable: true }))
      expect(saved).not.toHaveBeenCalled()
    })

    it('does not drop edits on Escape', () => {
      create()
      startEdit()
      type('changed')
      editor()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
      fixture.detectChanges()
      expect(editor()!.value).toBe('changed')
    })

    it('stays in the editor after a save, with the new content as its baseline', () => {
      create()
      type('changed')
      expect(saveButton()!.disabled).toBe(false)
      fixture.componentRef.setInput('content', 'changed')
      fixture.detectChanges()
      expect(editor()!.value).toBe('changed')
      expect(el().textContent).not.toContain('unsaved')
      expect(saveButton()!.disabled).toBe(true)
      expect(revertButton()).toBeNull()
    })

    it('follows content that moves under an untouched editor, but keeps a dirty draft', () => {
      create()
      fixture.componentRef.setInput('content', 'agent edit')
      fixture.detectChanges()
      expect(editor()!.value).toBe('agent edit')
      type('mine')
      fixture.componentRef.setInput('content', 'another edit')
      fixture.detectChanges()
      expect(editor()!.value).toBe('mine')
    })

    it('reverts to the loaded content, only offering it when dirty', () => {
      create()
      expect(revertButton()).toBeNull()
      type('changed')
      revertButton()!.click()
      fixture.detectChanges()
      expect(editor()!.value).toBe('one\ntwo\nthree')
      expect(el().textContent).not.toContain('unsaved')
      expect(revertButton()).toBeNull()
    })

    it('asks before leaving the file with unsaved edits, and leaves when they are discarded', () => {
      create()
      const closed = jest.fn()
      fixture.componentInstance.closeRequested.subscribe(closed)
      startEdit()
      type('changed')
      button('Back to files')!.click()
      fixture.detectChanges()
      expect(closed).not.toHaveBeenCalled()
      ;(el().querySelector('.ds-confirm-dialog__button--confirm') as HTMLButtonElement).click()
      fixture.detectChanges()
      expect(closed).toHaveBeenCalledTimes(1)
    })

    it('leaves a clean file without asking', () => {
      create()
      const closed = jest.fn()
      fixture.componentInstance.closeRequested.subscribe(closed)
      button('Back to files')!.click()
      expect(closed).toHaveBeenCalledTimes(1)
    })

    it('loads another file from its own content, dropping the previous draft', () => {
      create()
      startEdit()
      type('changed')
      fixture.componentRef.setInput('file', ref('other.md'))
      fixture.componentRef.setInput('content', 'other')
      fixture.detectChanges()
      expect(editor()!.value).toBe('other')
    })

    it('reports its dirty state to the parent', () => {
      create()
      const states: boolean[] = []
      fixture.componentInstance.dirtyChanged.subscribe((dirty) => states.push(dirty))
      fixture.detectChanges()
      startEdit()
      type('changed')
      expect(states[states.length - 1]).toBe(true)
      type('one\ntwo\nthree')
      expect(states[states.length - 1]).toBe(false)
    })

    it('shows a conflict with a way to reload the latest version', () => {
      create()
      startEdit()
      type('changed')
      fixture.componentRef.setInput('saveStatus', 'conflict')
      fixture.componentRef.setInput('saveError', 'The file was modified since it was opened.')
      fixture.detectChanges()
      const alert = el().querySelector('[role="alert"]')!
      expect(alert.textContent).toContain('modified since it was opened')
      const reload = jest.fn()
      fixture.componentInstance.reloadRequested.subscribe(reload)
      ;(alert.querySelector('button') as HTMLButtonElement).click()
      fixture.detectChanges()
      expect(reload).toHaveBeenCalledTimes(1)
      expect(editor()!.value).toBe('one\ntwo\nthree')
    })

    it('keeps the sandboxed HTML preview without same-origin for a read-only html file', () => {
      create('page.html', '<p>x</p>', false)
      expect(iframe()!.getAttribute('sandbox')).not.toContain('allow-same-origin')
      expect(editor()).toBeNull()
    })
  })

  describe('html preview selection bridge', () => {
    const post = (data: unknown, source: MessageEventSource | null = iframe()!.contentWindow): void => {
      window.dispatchEvent(new MessageEvent('message', { data, source }))
      fixture.detectChanges()
    }
    const message = (text: unknown) => ({ type: 'agentos-exchange-selection', text })

    function previewHtml(canEdit = false): void {
      create('page.html', '<p>hello world</p>', canEdit)
      if (canEdit) {
        toggle('Preview')!.click()
        fixture.detectChanges()
      }
    }

    it('injects the bridge in the srcdoc of the preview only', () => {
      previewHtml()
      const srcdoc = iframe()!.getAttribute('srcdoc')!
      expect(srcdoc).toContain('<p>hello world</p>')
      expect(srcdoc).toContain('agentos-exchange-selection')
    })

    it('shows the action for a selection posted by the iframe, and emits it without lines', () => {
      previewHtml()
      const actions: ExchangeSelectionAction[] = []
      fixture.componentInstance.selectionActionRequested.subscribe((action) => actions.push(action))
      expect(askButton()).toBeNull()
      post(message('  hello  '))
      expect(askButton()).not.toBeNull()
      askButton()!.click()
      fixture.detectChanges()
      // The selection collapses when the instruction is focused: it must not clear the captured one.
      post(message(''))
      instruction()!.value = 'Summarise'
      instruction()!.dispatchEvent(new Event('input'))
      fixture.detectChanges()
      ;(el().querySelector('[title="Run the agent on the selection"]') as HTMLButtonElement).click()
      expect(actions).toEqual([
        { ref: ref('page.html'), selection: 'hello', instruction: 'Summarise', unsavedChanges: false },
      ])
    })

    it('works from the preview of an editable html, flagging unsaved edits', () => {
      create('page.html', '<p>x</p>')
      type('<p>draft</p>')
      toggle('Preview')!.click()
      fixture.detectChanges()
      const actions: ExchangeSelectionAction[] = []
      fixture.componentInstance.selectionActionRequested.subscribe((action) => actions.push(action))
      post(message('draft'))
      askButton()!.click()
      fixture.detectChanges()
      instruction()!.value = 'Fix'
      instruction()!.dispatchEvent(new Event('input'))
      instruction()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }))
      expect(actions[0]).toEqual(expect.objectContaining({ selection: 'draft', unsavedChanges: true }))
    })

    it('clears the selection on an empty text while the form is closed', () => {
      previewHtml()
      post(message('hello'))
      expect(askButton()).not.toBeNull()
      post(message(''))
      expect(askButton()).toBeNull()
    })

    it('caps the length of the selection', () => {
      previewHtml()
      const actions: ExchangeSelectionAction[] = []
      fixture.componentInstance.selectionActionRequested.subscribe((action) => actions.push(action))
      post(message('a'.repeat(150_000)))
      askButton()!.click()
      fixture.detectChanges()
      instruction()!.value = 'Go'
      instruction()!.dispatchEvent(new Event('input'))
      instruction()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }))
      expect(actions[0]!.selection).toHaveLength(100_000)
    })

    it.each([
      ['another source', () => message('hello'), window as MessageEventSource],
      ['a wrong type', () => ({ type: 'other', text: 'hello' }), undefined],
      ['a non-string text', () => message(42), undefined],
      ['no data', () => null, undefined],
    ])('ignores a message from %s', (_label, data, source) => {
      previewHtml()
      post(data(), source ?? iframe()!.contentWindow)
      expect(askButton()).toBeNull()
    })
  })

  describe('selection to agent (editor)', () => {
    it('offers the action for a selection in the editor and emits its lines', () => {
      create('notes/todo.md', 'one\ntwo\nthree')
      const actions: ExchangeSelectionAction[] = []
      fixture.componentInstance.selectionActionRequested.subscribe((action) => actions.push(action))
      startEdit()
      expect(askButton()).toBeNull()
      select(4, 13) // "two\nthree"
      expect(askButton()).not.toBeNull()

      askButton()!.click()
      fixture.detectChanges()
      const area = instruction()!
      area.value = '  Translate  '
      area.dispatchEvent(new Event('input'))
      fixture.detectChanges()
      ;(el().querySelector('[title="Run the agent on the selection"]') as HTMLButtonElement).click()
      fixture.detectChanges()

      expect(actions).toEqual([
        {
          ref: ref('notes/todo.md'),
          selection: 'two\nthree',
          startLine: 2,
          endLine: 3,
          instruction: 'Translate',
          unsavedChanges: false,
        },
      ])
      expect(instruction()).toBeNull()
    })

    it('flags a selection taken from unsaved edits', () => {
      create('notes/todo.md', 'one\ntwo')
      const actions: ExchangeSelectionAction[] = []
      fixture.componentInstance.selectionActionRequested.subscribe((action) => actions.push(action))
      startEdit()
      type('one\ntwo\nthree')
      select(8, 13)
      askButton()!.click()
      fixture.detectChanges()
      instruction()!.value = 'Fix'
      instruction()!.dispatchEvent(new Event('input'))
      instruction()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }))

      expect(actions[0]).toEqual(expect.objectContaining({ selection: 'three', startLine: 3, unsavedChanges: true }))
    })

    it('keeps the selection captured when the action opened, and runs nothing without an instruction', () => {
      create('notes/todo.md', 'one\ntwo\nthree')
      const run = jest.fn()
      fixture.componentInstance.selectionActionRequested.subscribe(run)
      startEdit()
      select(0, 3)
      askButton()!.click()
      fixture.detectChanges()
      // The focus move to the instruction clears the live selection: the captured one must survive.
      editor()!.setSelectionRange(0, 0)
      expect((el().querySelector('[title="Run the agent on the selection"]') as HTMLButtonElement).disabled).toBe(true)
      instruction()!.value = 'Go'
      instruction()!.dispatchEvent(new Event('input'))
      instruction()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }))
      expect(run).toHaveBeenCalledWith(expect.objectContaining({ selection: 'one', startLine: 1, endLine: 1 }))
    })

    it('cancels the action on Escape without emitting', () => {
      create('notes/todo.md', 'one\ntwo')
      const run = jest.fn()
      fixture.componentInstance.selectionActionRequested.subscribe(run)
      startEdit()
      select(0, 3)
      askButton()!.click()
      fixture.detectChanges()
      instruction()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', cancelable: true }))
      fixture.detectChanges()
      expect(instruction()).toBeNull()
      expect(run).not.toHaveBeenCalled()
    })

    it('offers the action for a selection in the preview, without lines', () => {
      create('notes/todo.md', 'hello world', false)
      const actions: ExchangeSelectionAction[] = []
      fixture.componentInstance.selectionActionRequested.subscribe((action) => actions.push(action))
      const paragraph = el().querySelector('.exchange-content-viewer__markdown p, .exchange-content-viewer__markdown')!
      const range = document.createRange()
      range.selectNodeContents(paragraph)
      const selection = window.getSelection()!
      selection.removeAllRanges()
      selection.addRange(range)
      document.dispatchEvent(new Event('selectionchange'))
      fixture.detectChanges()
      expect(askButton()).not.toBeNull()

      askButton()!.click()
      fixture.detectChanges()
      instruction()!.value = 'Summarise'
      instruction()!.dispatchEvent(new Event('input'))
      instruction()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }))

      expect(actions).toHaveLength(1)
      expect(actions[0]!.selection).toContain('hello world')
      expect(actions[0]!.startLine).toBeUndefined()
      expect(actions[0]!.unsavedChanges).toBe(false)
      selection.removeAllRanges()
    })

    it('ignores a selection made outside the viewer', () => {
      create('notes/todo.md', 'hello world', false)
      const outside = document.createElement('p')
      outside.textContent = 'elsewhere'
      document.body.appendChild(outside)
      const range = document.createRange()
      range.selectNodeContents(outside)
      const selection = window.getSelection()!
      selection.removeAllRanges()
      selection.addRange(range)
      document.dispatchEvent(new Event('selectionchange'))
      fixture.detectChanges()
      expect(askButton()).toBeNull()
      selection.removeAllRanges()
      outside.remove()
    })
  })
})
