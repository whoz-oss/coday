import { fakeAsync, TestBed, tick } from '@angular/core/testing'
import { ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
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
    fixture.componentRef.setInput('scope', ExchangeFileEntryScopeEnum.NAMESPACE)
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

  it('exposes the full file name as a tooltip on the row, as the visible name can be truncated', () => {
    const { el } = setup({ filename: 'quarterly-report-final-version-2026-03-v12.xlsx' })
    expect(el.querySelector<HTMLButtonElement>('.exchange-item__main')?.title).toBe(
      'quarterly-report-final-version-2026-03-v12.xlsx'
    )
  })

  it('copying does not preview the file', () => {
    const { fixture, buttons } = setup()
    const view = jest.fn()
    fixture.componentInstance.viewRequested.subscribe(view)
    buttons()[0]!.click()
    expect(view).not.toHaveBeenCalled()
  })
})

describe('ExchangeItemComponent selection', () => {
  const row: ExchangeFileRow = { path: 'docs/guide.md', filename: 'guide.md', meta: '', icon: 'description' }

  function setup(override: Partial<ExchangeFileRow> = {}, selectable = true, selected = false) {
    TestBed.configureTestingModule({ imports: [ExchangeItemComponent] })
    const fixture = TestBed.createComponent(ExchangeItemComponent)
    fixture.componentRef.setInput('row', { ...row, ...override })
    fixture.componentRef.setInput('scope', ExchangeFileEntryScopeEnum.NAMESPACE)
    fixture.componentRef.setInput('selectable', selectable)
    fixture.componentRef.setInput('selected', selected)
    const toggled = jest.fn()
    const view = jest.fn()
    fixture.componentInstance.selectionToggled.subscribe(toggled)
    fixture.componentInstance.viewRequested.subscribe(view)
    fixture.detectChanges()
    const checkbox = () =>
      (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('input[type="checkbox"]')
    return { fixture, checkbox, toggled, view }
  }

  it('renders no checkbox unless selectable', () => {
    expect(setup({}, false).checkbox()).toBeNull()
  })

  it('renders a labelled checkbox that reflects `selected`', () => {
    const { checkbox } = setup({}, true, true)
    expect(checkbox()?.getAttribute('aria-label')).toBe('Select guide.md')
    expect(checkbox()?.checked).toBe(true)
  })

  it('emits selectionToggled without previewing the file', () => {
    const { checkbox, toggled, view } = setup()
    checkbox()!.click()
    expect(toggled).toHaveBeenCalledTimes(1)
    expect(view).not.toHaveBeenCalled()
  })

  it('never offers a checkbox on a missing (deleted) row', () => {
    expect(setup({ missing: true }).checkbox()).toBeNull()
  })
})
