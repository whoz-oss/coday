import { ComponentFixture, TestBed } from '@angular/core/testing'
import { ListFooterComponent } from './list-footer.component'

describe('ListFooterComponent', () => {
  let fixture: ComponentFixture<ListFooterComponent>
  const el = () => fixture.nativeElement as HTMLElement
  const button = () => el().querySelector<HTMLButtonElement>('button')
  const icon = () => button()!.querySelector<HTMLElement>('mat-icon')!
  const label = () => button()!.textContent!.replace(icon().textContent!, '').trim()
  const status = () => el().querySelector<HTMLElement>('[role="status"]')!

  function render(inputs: Partial<Record<'hasMore' | 'loading' | 'error' | 'loaded' | 'total', boolean | number>>) {
    fixture = TestBed.createComponent(ListFooterComponent)
    const all = { hasMore: true, loading: false, error: false, loaded: 100, total: 124, ...inputs }
    for (const [name, value] of Object.entries(all)) fixture.componentRef.setInput(name, value)
    fixture.detectChanges()
  }

  it('shows the count and requests the next page on click', () => {
    render({})
    const requested = jest.fn()
    fixture.componentInstance.loadMoreRequested.subscribe(requested)

    expect(status().textContent).toContain('Showing 100 of 124')
    button()!.click()

    expect(button()!.classList).toContain('ds-button')
    expect(label()).toBe('Load more')
    expect(icon().textContent).toBe('expand_more')
    expect(icon().getAttribute('aria-hidden')).toBe('true')
    expect(icon().classList).not.toContain('list-footer__spin')
    expect(button()!.disabled).toBe(false)
    expect(requested).toHaveBeenCalledTimes(1)
  })

  it('disables the button while loading', () => {
    render({ loading: true })
    const requested = jest.fn()
    fixture.componentInstance.loadMoreRequested.subscribe(requested)
    button()!.click()

    expect(requested).not.toHaveBeenCalled()
    expect(button()!.disabled).toBe(true)
    expect(label()).toBe('Loading…')
    expect(icon().textContent).toBe('refresh')
    expect(icon().classList).toContain('list-footer__spin')
    expect(status().textContent).toContain('Loading…')
  })

  it.each([
    ['a load-more', {}],
    ['the initial page', { hasMore: false, loaded: 0, total: 0 }],
  ])('shows a retry after %s failed', (_, inputs) => {
    render({ error: true, ...inputs })

    expect(status().textContent).toContain('Loading failed.')
    expect(status().querySelector('.settings-table__error')).not.toBeNull()
    expect(label()).toBe('Retry')
    expect(icon().textContent).toBe('refresh')
    expect(icon().classList).not.toContain('list-footer__spin')
  })

  it.each([
    ['all rows loaded', { hasMore: false }],
    ['initial load running', { loading: true, loaded: 0, total: 0 }],
  ])('hides the button but keeps the status region when %s', (_, inputs) => {
    render(inputs)

    expect(button()).toBeNull()
    expect(status()).not.toBeNull()
  })

  it('moves the focus to the status line when the focused button goes away', () => {
    render({})
    button()!.focus()
    expect(document.activeElement).toBe(button())

    fixture.componentRef.setInput('hasMore', false)
    fixture.detectChanges()

    expect(button()).toBeNull()
    expect(document.activeElement).toBe(status())
  })

  it('moves the focus to the status line when the focused button gets disabled', () => {
    render({})
    button()!.focus()
    expect(document.activeElement).toBe(button())

    fixture.componentRef.setInput('loading', true)
    fixture.detectChanges()

    expect(button()!.disabled).toBe(true)
    expect(document.activeElement).toBe(status())
  })

  it('does not steal the focus when the button was not focused', () => {
    render({})
    const outside = document.createElement('input')
    document.body.append(outside)
    outside.focus()

    fixture.componentRef.setInput('hasMore', false)
    fixture.detectChanges()

    expect(document.activeElement).toBe(outside)
    outside.remove()
  })
})
