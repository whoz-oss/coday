import { fakeAsync, TestBed, tick } from '@angular/core/testing'
import { MatDialog } from '@angular/material/dialog'
import { ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { of } from 'rxjs'
import { COPY_FEEDBACK_MS } from '../../services/clipboard.utils'
import { ExchangeHtmlPreviewService } from '../../services/exchange-html-preview.service'
import {
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
    expect(el.querySelector('iframe')!.getAttribute('srcdoc')).toBe(REPORT)
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
        data: { content: REPORT, filename: 'report.html' },
        width: '100vw',
        height: '100vh',
        maxWidth: '100vw',
        maxHeight: '100vh',
      })
    )
    expect(config.panelClass).toBeTruthy()
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

describe('ExchangeContentViewerComponent images', () => {
  function setup(path: string, imageUrl: string | null) {
    TestBed.configureTestingModule({ imports: [ExchangeContentViewerComponent] })
    const fixture = TestBed.createComponent(ExchangeContentViewerComponent)
    fixture.componentRef.setInput('file', { scope: ExchangeFileEntryScopeEnum.NAMESPACE, path })
    fixture.componentRef.setInput('status', 'ready')
    fixture.componentRef.setInput('imageUrl', imageUrl)
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
    fixture.componentRef.setInput('imageUrl', 'blob:http://localhost/good')
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
