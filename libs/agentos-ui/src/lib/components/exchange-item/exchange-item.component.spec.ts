import { fakeAsync, TestBed, tick } from '@angular/core/testing'
import { COPY_FEEDBACK_MS } from '../../services/clipboard.utils'
import { ExchangeFileRow, ExchangeItemComponent } from './exchange-item.component'

describe('ExchangeItemComponent copy relative path', () => {
  const row: ExchangeFileRow = { path: 'docs/api/guide.md', filename: 'guide.md', meta: '', icon: 'description' }
  let writeText: jest.Mock

  beforeEach(() => {
    writeText = jest.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    TestBed.configureTestingModule({ imports: [ExchangeItemComponent] })
  })
  afterEach(() => Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true }))

  function setup(override: Partial<ExchangeFileRow> = {}) {
    const fixture = TestBed.createComponent(ExchangeItemComponent)
    fixture.componentRef.setInput('row', { ...row, ...override })
    fixture.detectChanges()
    const el = fixture.nativeElement as HTMLElement
    const buttons = () => Array.from(el.querySelectorAll<HTMLButtonElement>('.exchange-item__actions button'))
    return { fixture, el, buttons }
  }

  it('offers a copy action titled "Copy relative path" before download', () => {
    const { buttons } = setup()
    const [copy, download] = buttons()
    expect(copy?.title).toBe('Copy relative path')
    expect(copy?.textContent).toContain('content_copy')
    expect(download?.title).toBe('Download guide.md')
  })

  it('copies the row path, then shows "Copied" feedback and an aria-live message for ~1.5 s', fakeAsync(() => {
    const { fixture, el, buttons } = setup()
    buttons()[0]!.click()
    tick(0)
    fixture.detectChanges()
    expect(writeText).toHaveBeenCalledWith('docs/api/guide.md')
    expect(buttons()[0]!.title).toBe('Copied')
    expect(buttons()[0]!.textContent).toContain('check')
    const live = el.querySelector('[aria-live="polite"]')!
    expect(live.textContent?.trim()).toBe('Path copied')

    tick(COPY_FEEDBACK_MS)
    fixture.detectChanges()
    expect(buttons()[0]!.title).toBe('Copy relative path')
    expect(buttons()[0]!.textContent).toContain('content_copy')
    expect(live.textContent?.trim()).toBe('')
  }))

  it('shows "Copy failed" when the clipboard cannot be written', fakeAsync(() => {
    writeText.mockRejectedValue(new Error('denied'))
    const { fixture, el, buttons } = setup()
    buttons()[0]!.click()
    tick(0)
    fixture.detectChanges()
    expect(buttons()[0]!.title).toBe('Copy failed')
    expect(el.querySelector('[aria-live="polite"]')?.textContent?.trim()).toBe('')
    tick(COPY_FEEDBACK_MS)
  }))

  it('does not render the action for a missing (deleted) row', () => {
    const { el } = setup({ missing: true })
    expect(el.querySelector('.exchange-item__actions--hidden')).not.toBeNull()
  })

  it('copying does not preview the file', () => {
    const { fixture, buttons } = setup()
    const view = jest.fn()
    fixture.componentInstance.viewRequested.subscribe(view)
    buttons()[0]!.click()
    expect(view).not.toHaveBeenCalled()
  })
})
