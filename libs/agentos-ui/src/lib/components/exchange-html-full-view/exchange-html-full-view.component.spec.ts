import { TestBed } from '@angular/core/testing'
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog'
import { ExchangeHtmlPreviewService } from '../../services/exchange-html-preview.service'
import {
  EXCHANGE_FULL_VIEW_PANEL_CLASS,
  EXCHANGE_HTML_FULL_VIEW_DOWNLOAD,
  ExchangeHtmlFullViewComponent,
  ExchangeHtmlFullViewData,
} from './exchange-html-full-view.component'

describe('ExchangeHtmlFullViewComponent', () => {
  const CONTENT = '<h1>Report</h1><script>window.x = 1</script>'
  const PDF = new Blob(['%PDF-1.4'], { type: 'application/pdf' })
  const HTML_DATA: ExchangeHtmlFullViewData = { kind: 'html', content: CONTENT, filename: 'report.html' }
  const PDF_DATA: ExchangeHtmlFullViewData = {
    kind: 'pdf',
    url: 'blob:http://localhost/pdf',
    blob: PDF,
    filename: 'report.pdf',
  }
  let close: jest.Mock
  let openInNewTab: jest.Mock
  let openBlobInNewTab: jest.Mock

  function setup(data: ExchangeHtmlFullViewData = HTML_DATA) {
    close = jest.fn()
    openInNewTab = jest.fn()
    openBlobInNewTab = jest.fn()
    TestBed.configureTestingModule({
      imports: [ExchangeHtmlFullViewComponent],
      providers: [
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: { close } },
        { provide: ExchangeHtmlPreviewService, useValue: { openInNewTab, openBlobInNewTab } },
      ],
    })
    const fixture = TestBed.createComponent(ExchangeHtmlFullViewComponent)
    fixture.detectChanges()
    const el = fixture.nativeElement as HTMLElement
    const button = (title: string) => el.querySelector<HTMLButtonElement>(`header button[title="${title}"]`)!
    return { el, button }
  }

  it('shows the file name and a sandboxed iframe with the scripts intact', () => {
    const { el } = setup()
    expect(el.querySelector('header')!.textContent).toContain('report.html')
    const iframe = el.querySelector('iframe')!
    const sandbox = iframe.getAttribute('sandbox')!
    expect(sandbox).toContain('allow-scripts')
    expect(sandbox).not.toContain('allow-same-origin')
    expect(sandbox).not.toContain('allow-top-navigation')
    expect(iframe.getAttribute('srcdoc')).toBe(CONTENT)
    expect(iframe.title).toBe('Full view of report.html')
  })

  it('closes without a result', () => {
    const { button } = setup()
    button('Close full view').click()
    expect(close).toHaveBeenCalledWith()
  })

  it('hands the download back to the opener through the close result', () => {
    const { button } = setup()
    button('Download report.html').click()
    expect(close).toHaveBeenCalledWith(EXCHANGE_HTML_FULL_VIEW_DOWNLOAD)
  })

  it('opens the file in a new tab through the service', () => {
    const { button } = setup()
    button('Open in new tab').click()
    expect(openInNewTab).toHaveBeenCalledWith(CONTENT, 'report.html')
    expect(openBlobInNewTab).not.toHaveBeenCalled()
  })

  describe('for a PDF', () => {
    it('renders the blob in an iframe that is NOT sandboxed (the PDF viewer would not run), without srcdoc', () => {
      const { el } = setup(PDF_DATA)
      const iframe = el.querySelector('iframe')!
      expect(el.querySelectorAll('iframe')).toHaveLength(1)
      expect(iframe.hasAttribute('sandbox')).toBe(false)
      expect(iframe.hasAttribute('srcdoc')).toBe(false)
      expect(iframe.getAttribute('src')).toBe('blob:http://localhost/pdf')
      expect(iframe.title).toBe('Full view of report.pdf')
      expect(el.querySelector('header')!.textContent).toContain('report.pdf')
    })

    it('opens a new tab from the blob, not from the URL the shell owns', () => {
      const { button } = setup(PDF_DATA)
      button('Open in new tab').click()
      expect(openBlobInNewTab).toHaveBeenCalledWith(PDF)
      expect(openInNewTab).not.toHaveBeenCalled()
    })

    it('closes and downloads like an HTML file', () => {
      const { button } = setup(PDF_DATA)
      button('Download report.pdf').click()
      expect(close).toHaveBeenLastCalledWith(EXCHANGE_HTML_FULL_VIEW_DOWNLOAD)
      button('Close full view').click()
      expect(close).toHaveBeenLastCalledWith()
    })

    it('renders nothing for a URL that is not a blob: URL', () => {
      const { el } = setup({ ...PDF_DATA, url: 'https://evil.example/x.pdf' })
      expect(el.querySelector('iframe')).toBeNull()
    })
  })

  it('exports the panel class the dialog is opened with', () => {
    expect(EXCHANGE_FULL_VIEW_PANEL_CLASS).toBe('exchange-html-full-view-panel')
  })
})
