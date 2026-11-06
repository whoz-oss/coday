import { TestBed } from '@angular/core/testing'
import { FileSuggestion } from '../composer-autocomplete/file-mention'
import { FileAutocompleteComponent } from './file-autocomplete.component'

const FILES: FileSuggestion[] = [
  { scope: 'namespace', path: 'docs/readme.md', filename: 'readme.md' },
  { scope: 'case', path: 'report.pdf', filename: 'report.pdf' },
]

describe('FileAutocompleteComponent', () => {
  // jsdom does not implement scrollIntoView, which keyboard navigation calls on the active row.
  const originalScrollIntoView = Element.prototype.scrollIntoView
  beforeEach(() => {
    Element.prototype.scrollIntoView = jest.fn()
  })

  function render(files: FileSuggestion[] = FILES) {
    const fixture = TestBed.createComponent(FileAutocompleteComponent)
    fixture.componentRef.setInput('files', files)
    fixture.detectChanges()
    const chosen: FileSuggestion[] = []
    let dismissed = 0
    fixture.componentInstance.selected.subscribe((file) => chosen.push(file))
    fixture.componentInstance.dismissed.subscribe(() => dismissed++)
    return {
      fixture,
      component: fixture.componentInstance,
      chosen,
      dismissals: () => dismissed,
      host: fixture.nativeElement as HTMLElement,
    }
  }

  afterEach(() => {
    Element.prototype.scrollIntoView = originalScrollIntoView
    TestBed.resetTestingModule()
  })

  it('renders an option per file with its icon, name, dimmed directory and scope badge', () => {
    const { host } = render()

    expect(host.querySelector('[role="listbox"]')).not.toBeNull()
    const options = host.querySelectorAll('[role="option"]')
    expect(options).toHaveLength(2)
    expect(options[0]!.querySelector('.file-autocomplete__name')?.textContent).toBe('readme.md')
    expect(options[0]!.querySelector('.file-autocomplete__path')?.textContent).toBe('docs')
    expect(options[0]!.querySelector('.file-autocomplete__scope')?.textContent).toBe('namespace')
    expect(options[0]!.querySelector('.file-autocomplete__icon')?.textContent).toBe('description')
    expect(options[1]!.querySelector('.file-autocomplete__scope')?.textContent).toBe('case')
    expect(options[1]!.querySelector('.file-autocomplete__icon')?.textContent).toBe('picture_as_pdf')
  })

  it('marks the first option active, and follows ArrowDown / ArrowUp within the list', () => {
    const { component, fixture, host } = render()
    const selected = () =>
      Array.from(host.querySelectorAll('[role="option"]')).map((option) => option.getAttribute('aria-selected'))
    expect(selected()).toEqual(['true', 'false'])

    component.navigate('ArrowDown')
    component.navigate('ArrowDown')
    fixture.detectChanges()
    expect(selected()).toEqual(['false', 'true'])

    component.navigate('ArrowUp')
    fixture.detectChanges()
    expect(selected()).toEqual(['true', 'false'])
  })

  it('emits the active file on Enter', () => {
    const { component, chosen } = render()

    component.navigate('ArrowDown')
    component.navigate('Enter')

    expect(chosen).toEqual([FILES[1]])
  })

  it('emits the clicked file', () => {
    const { host, chosen } = render()

    ;(host.querySelectorAll('[role="option"]')[1] as HTMLElement).click()

    expect(chosen).toEqual([FILES[1]])
  })

  it('emits dismissed on Escape', () => {
    const { component, dismissals } = render()

    component.navigate('Escape')

    expect(dismissals()).toBe(1)
  })

  it('builds the scope-qualified completion', () => {
    const { component } = render()

    expect(component.completionFor(FILES[0]!)).toBe('namespace:docs/readme.md ')
    expect(component.completionFor(FILES[1]!)).toBe('case:report.pdf ')
  })

  it('returns to the first option when the list changes', () => {
    const { component, fixture } = render()
    component.navigate('ArrowDown')

    fixture.componentRef.setInput('files', [FILES[1]!, FILES[0]!])
    fixture.detectChanges()
    component.navigate('Enter')

    expect(fixture.componentInstance['activeIndex']()).toBe(0)
  })
})
