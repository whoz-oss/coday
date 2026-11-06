import { fakeAsync, TestBed, tick } from '@angular/core/testing'
import { ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { COPY_FEEDBACK_MS } from '../../services/clipboard.utils'
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
