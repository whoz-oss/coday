import { ApplicationRef, ComponentRef, createComponent, EnvironmentInjector } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { FileChipComponent } from './file-chip.component'

describe('FileChipComponent', () => {
  function makeComponent(inputs: Record<string, unknown>): ComponentRef<FileChipComponent> {
    const ref = createComponent(FileChipComponent, { environmentInjector: TestBed.inject(EnvironmentInjector) })
    TestBed.inject(ApplicationRef).attachView(ref.hostView)
    for (const [name, value] of Object.entries(inputs)) ref.setInput(name, value)
    ref.changeDetectorRef.detectChanges()
    return ref
  }

  const host = (ref: ComponentRef<FileChipComponent>) => ref.location.nativeElement as HTMLElement

  afterEach(() => TestBed.resetTestingModule())

  it('shows the icon, the name, the kind and the exchange of a file of the case', () => {
    const ref = makeComponent({ name: 'todo.md', path: 'notes/todo.md', scope: ExchangeFileEntryScopeEnum.CASE })

    expect(host(ref).querySelector('.file-chip__icon')?.textContent).toContain('description')
    expect(host(ref).querySelector('.file-chip__name')?.textContent).toContain('todo.md')
    expect(host(ref).querySelector('.file-chip__kind')?.textContent).toContain('MD')
    expect(host(ref).querySelector('.file-chip__badge')?.textContent).toContain('case')
  })

  it('has the icon of the namespace for a file of the namespace', () => {
    const ref = makeComponent({
      name: 'readme.md',
      path: 'docs/readme.md',
      scope: ExchangeFileEntryScopeEnum.NAMESPACE,
    })

    expect(host(ref).querySelector('.file-chip__icon')?.textContent).toContain('folder_shared')
    expect(host(ref).querySelector('.file-chip__badge')?.textContent).toContain('namespace')
  })

  it('gives the scope-qualified path as the title of the name, the name when it has no path', () => {
    const withPath = makeComponent({
      name: 'readme.md',
      path: 'docs/readme.md',
      scope: ExchangeFileEntryScopeEnum.NAMESPACE,
    })
    const withoutPath = makeComponent({ name: 'a.pdf', scope: ExchangeFileEntryScopeEnum.CASE })

    expect(host(withPath).querySelector('.file-chip__name')?.getAttribute('title')).toBe('namespace:docs/readme.md')
    expect(host(withoutPath).querySelector('.file-chip__name')?.getAttribute('title')).toBe('case:a.pdf')
  })

  it('cuts a long name in the middle and keeps the whole one in the title', () => {
    const name = 'a-very-long-file-name-that-never-ends-2026.pdf'
    const ref = makeComponent({ name, scope: ExchangeFileEntryScopeEnum.CASE })
    const shown = host(ref).querySelector('.file-chip__name')!

    expect(shown.textContent).toContain('…')
    expect(shown.textContent!.trim().endsWith('2026.pdf')).toBe(true)
    expect(shown.getAttribute('title')).toBe(`case:${name}`)
  })

  it('is read-only by default: no button', () => {
    const ref = makeComponent({ name: 'a.pdf', scope: ExchangeFileEntryScopeEnum.CASE })

    expect(host(ref).querySelector('button')).toBeNull()
    expect(host(ref).querySelector('.file-chip--readonly')).not.toBeNull()
  })

  it('ends with a remove button when removable, which emits removed', () => {
    const ref = makeComponent({ name: 'a.pdf', scope: ExchangeFileEntryScopeEnum.CASE, removable: true })
    const removed = jest.fn()
    ref.instance.removed.subscribe(removed)
    const button = host(ref).querySelector<HTMLButtonElement>('button')!

    expect(button.title).toBe('Remove a.pdf')
    expect(host(ref).querySelector('.file-chip--readonly')).toBeNull()
    button.click()

    expect(removed).toHaveBeenCalledTimes(1)
  })

  it('disables its remove button when disabled', () => {
    const ref = makeComponent({
      name: 'a.pdf',
      scope: ExchangeFileEntryScopeEnum.CASE,
      removable: true,
      disabled: true,
    })

    expect(host(ref).querySelector<HTMLButtonElement>('button')!.disabled).toBe(true)
  })
})
