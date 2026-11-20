import { TestBed } from '@angular/core/testing'
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog'
import { ExchangeHtmlPreviewService } from '../../services/exchange-html-preview.service'
import { EXCHANGE_HTML_FULL_VIEW_DOWNLOAD, ExchangeHtmlFullViewComponent } from './exchange-html-full-view.component'

describe('ExchangeHtmlFullViewComponent', () => {
  const CONTENT = '<h1>Report</h1><script>window.x = 1</script>'
  let close: jest.Mock
  let openInNewTab: jest.Mock

  function setup() {
    close = jest.fn()
    openInNewTab = jest.fn()
    TestBed.configureTestingModule({
      imports: [ExchangeHtmlFullViewComponent],
      providers: [
        { provide: MAT_DIALOG_DATA, useValue: { content: CONTENT, filename: 'report.html' } },
        { provide: MatDialogRef, useValue: { close } },
        { provide: ExchangeHtmlPreviewService, useValue: { openInNewTab } },
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
  })
})
