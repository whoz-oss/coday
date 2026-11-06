import { TestBed } from '@angular/core/testing'
import { ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { ExchangeContentViewerComponent } from './exchange-content-viewer.component'

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
