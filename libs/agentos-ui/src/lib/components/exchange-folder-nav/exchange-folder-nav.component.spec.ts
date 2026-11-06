import { TestBed } from '@angular/core/testing'
import { ExchangeFolderNavComponent } from './exchange-folder-nav.component'

describe('ExchangeFolderNavComponent', () => {
  function setup(breadcrumb: { label: string; path: string }[]) {
    TestBed.configureTestingModule({ imports: [ExchangeFolderNavComponent] })
    const fixture = TestBed.createComponent(ExchangeFolderNavComponent)
    fixture.componentRef.setInput('breadcrumb', breadcrumb)
    const navigated = jest.fn()
    fixture.componentInstance.navigated.subscribe(navigated)
    fixture.detectChanges()
    return { fixture, navigated, el: fixture.nativeElement as HTMLElement }
  }

  it('offers no Back button at the scope root', () => {
    const { el } = setup([])
    expect(el.querySelector('.folder-nav__back')).toBeNull()
    expect(el.querySelector('[aria-current="location"]')?.textContent?.trim()).toBe('root')
  })

  it('goes back to the scope root from a first-level folder', () => {
    const { el, navigated } = setup([{ label: 'docs', path: 'docs' }])
    const back = el.querySelector<HTMLButtonElement>('.folder-nav__back')!
    expect(back.getAttribute('aria-label')).toBe('Back to root')
    back.click()
    expect(navigated).toHaveBeenCalledWith('')
  })

  it('goes back to the parent folder and keeps the current folder as a non-clickable label', () => {
    const { el, navigated } = setup([
      { label: 'docs', path: 'docs' },
      { label: 'api', path: 'docs/api' },
    ])
    const back = el.querySelector<HTMLButtonElement>('.folder-nav__back')!
    expect(back.getAttribute('aria-label')).toBe('Back to docs')
    back.click()
    expect(navigated).toHaveBeenCalledWith('docs')
    const current = el.querySelector('[aria-current="location"]')!
    expect(current.textContent?.trim()).toBe('api')
    expect(current.tagName).not.toBe('BUTTON')
  })
})
