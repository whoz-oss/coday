import { fakeAsync, TestBed, tick } from '@angular/core/testing'
import { BehaviorSubject, of, Subject } from 'rxjs'
import { MatDialog } from '@angular/material/dialog'
import { ExchangeEnvironmentService, ExchangeEnvironment } from '../../services/exchange-environment.service'
import { CaseWorkspaceService } from '../../services/case-workspace.service'
import { ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { ExchangeDrawerComponent } from './exchange-drawer.component'

describe('ExchangeDrawerComponent namespace filter', () => {
  const manifestFile = (path: string) => ({
    path,
    filename: path.split('/').pop() as string,
    size: 10,
    lastModified: '2026-01-01T00:00:00Z',
    scope: ExchangeFileEntryScopeEnum.NAMESPACE,
  })
  const manifest = ['README.md', 'docs/api/guide.md', 'docs/api/spec.yaml', 'notes/todo.txt'].map(manifestFile)

  function setup() {
    TestBed.configureTestingModule({ imports: [ExchangeDrawerComponent] })
    const fixture = TestBed.createComponent(ExchangeDrawerComponent)
    fixture.componentRef.setInput('namespaceStatus', 'ready')
    fixture.componentRef.setInput('namespaceSectionVisible', true)
    fixture.componentRef.setInput('caseStatus', 'ready')
    fixture.componentRef.setInput('namespaceFiles', [
      { path: 'README.md', name: 'README.md', directory: false, size: 10 },
    ])
    fixture.componentRef.setInput('namespaceFolders', [{ path: 'docs', name: 'docs', directory: true }])
    const changed = jest.fn()
    const folderOpened = jest.fn()
    const fileSelected = jest.fn()
    fixture.componentInstance.namespaceFilterChanged.subscribe(changed)
    fixture.componentInstance.folderOpened.subscribe(folderOpened)
    fixture.componentInstance.fileSelected.subscribe(fileSelected)
    fixture.detectChanges()
    const el = fixture.nativeElement as HTMLElement
    const type = (value: string) => {
      const input = el.querySelector<HTMLInputElement>('input[type="search"]')!
      input.value = value
      input.dispatchEvent(new Event('input'))
      tick(150)
      fixture.detectChanges()
    }
    return { fixture, el, changed, folderOpened, fileSelected, type }
  }
  const text = (el: HTMLElement) => el.textContent?.replace(/\s+/g, ' ') ?? ''

  it('offers a filter field with a label once the section has content', () => {
    const { el } = setup()
    const input = el.querySelector<HTMLInputElement>('input[type="search"]')!
    expect(input.placeholder).toBe('Filter files…')
    expect(input.getAttribute('aria-label')).toBeTruthy()
  })

  it('does not offer the filter on an empty namespace', () => {
    TestBed.configureTestingModule({ imports: [ExchangeDrawerComponent] })
    const fixture = TestBed.createComponent(ExchangeDrawerComponent)
    fixture.componentRef.setInput('namespaceStatus', 'ready')
    fixture.componentRef.setInput('namespaceSectionVisible', true)
    fixture.componentRef.setInput('caseStatus', 'ready')
    fixture.detectChanges()
    expect(fixture.nativeElement.querySelector('input[type="search"]')).toBeNull()
  })

  it('debounces the query, then hides the browse view behind a spinner until the manifest arrives', fakeAsync(() => {
    const { fixture, el, changed } = setup()
    const input = el.querySelector<HTMLInputElement>('input[type="search"]')!
    input.value = 'gu'
    input.dispatchEvent(new Event('input'))
    tick(100)
    expect(changed).not.toHaveBeenCalled()
    tick(60)
    fixture.componentRef.setInput('namespaceManifestStatus', 'loading')
    fixture.detectChanges()
    expect(changed).toHaveBeenCalledWith('gu')
    expect(el.querySelector('ds-spinner')).not.toBeNull()
    expect(el.querySelector('agentos-exchange-file-list')).toBeNull()
    expect(el.querySelector('agentos-exchange-folder-nav')).toBeNull()
  }))

  it('shows matching files with their ancestor folders, indented', fakeAsync(() => {
    const { fixture, el, type } = setup()
    fixture.componentRef.setInput('namespaceManifest', manifest)
    fixture.componentRef.setInput('namespaceManifestStatus', 'ready')
    type('guide')
    const rows = Array.from(el.querySelectorAll('.exchange-drawer__tree > li'))
    expect(rows).toHaveLength(3)
    expect(rows[0]!.textContent).toContain('docs')
    expect(rows[1]!.textContent).toContain('api')
    expect(rows[2]!.querySelector('agentos-exchange-item')?.textContent).toContain('guide.md')
    expect((rows[2] as HTMLElement).style.paddingLeft).toBe('1.75rem')
    expect(el.querySelector('agentos-exchange-file-list')).toBeNull()
  }))

  it('previews, downloads and deletes a filtered file with its full path', fakeAsync(() => {
    const { fixture, el, fileSelected, type } = setup()
    fixture.componentRef.setInput('canWriteNamespace', true)
    fixture.componentRef.setInput('namespaceManifest', manifest)
    fixture.componentRef.setInput('namespaceManifestStatus', 'ready')
    const download = jest.fn()
    const remove = jest.fn()
    fixture.componentInstance.downloadRequested.subscribe(download)
    fixture.componentInstance.deleteRequested.subscribe(remove)
    type('spec')
    el.querySelector<HTMLButtonElement>('agentos-exchange-item .exchange-item__main')!.click()
    expect(fileSelected).toHaveBeenCalledWith({
      scope: ExchangeFileEntryScopeEnum.NAMESPACE,
      path: 'docs/api/spec.yaml',
    })
    el.querySelector<HTMLButtonElement>('agentos-exchange-item ds-icon-button[icon="download"] button')!.click()
    expect(download).toHaveBeenCalledWith({ scope: ExchangeFileEntryScopeEnum.NAMESPACE, path: 'docs/api/spec.yaml' })
    el.querySelector<HTMLButtonElement>('agentos-exchange-item ds-icon-button[icon="delete"] button')!.click()
    expect(remove).toHaveBeenCalledWith({ scope: ExchangeFileEntryScopeEnum.NAMESPACE, path: 'docs/api/spec.yaml' })
  }))

  it('offers copy relative path on filtered rows, copying the full path', fakeAsync(() => {
    const writeText = jest.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    const { fixture, el, type } = setup()
    fixture.componentRef.setInput('namespaceManifest', manifest)
    fixture.componentRef.setInput('namespaceManifestStatus', 'ready')
    type('spec')
    el.querySelector<HTMLButtonElement>('agentos-exchange-item button[title="Copy relative path"]')!.click()
    tick(0)
    expect(writeText).toHaveBeenCalledWith('docs/api/spec.yaml')
    tick(1500)
    Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true })
  }))

  it('says so when no file matches', fakeAsync(() => {
    const { fixture, el, type } = setup()
    fixture.componentRef.setInput('namespaceManifest', manifest)
    fixture.componentRef.setInput('namespaceManifestStatus', 'ready')
    type('zzz')
    expect(text(el)).toContain('No files match "zzz"')
    expect(el.querySelector('.exchange-drawer__tree')).toBeNull()
  }))

  it('reports a manifest failure with a retry', fakeAsync(() => {
    const { fixture, el, type } = setup()
    fixture.componentRef.setInput('namespaceManifestStatus', 'error')
    const retry = jest.fn()
    fixture.componentInstance.namespaceFilterRetry.subscribe(retry)
    type('x')
    expect(el.querySelector('[role="alert"]')?.textContent).toContain("Couldn't search namespace files")
    el.querySelector<HTMLButtonElement>('[role="alert"] button')!.click()
    expect(retry).toHaveBeenCalled()
  }))

  it('hints when the matches were cut', fakeAsync(() => {
    const { fixture, el, type } = setup()
    const many = Array.from({ length: 520 }, (_, i) => manifestFile(`bulk/file-${i}.txt`))
    fixture.componentRef.setInput('namespaceManifest', many)
    fixture.componentRef.setInput('namespaceManifestStatus', 'ready')
    type('file-')
    expect(text(el)).toContain('Showing first 500 matches')
    expect(el.querySelectorAll('agentos-exchange-item')).toHaveLength(500)
  }))

  it('clears through the button and restores the browse view untouched', fakeAsync(() => {
    const { fixture, el, changed, folderOpened, type } = setup()
    fixture.componentRef.setInput('namespaceManifest', manifest)
    fixture.componentRef.setInput('namespaceManifestStatus', 'ready')
    type('guide')
    expect(el.querySelector('agentos-exchange-file-list')).toBeNull()
    el.querySelector<HTMLButtonElement>('.exchange-drawer__filter-clear')!.click()
    fixture.detectChanges()
    expect(changed).toHaveBeenLastCalledWith('')
    expect(el.querySelector<HTMLInputElement>('input[type="search"]')!.value).toBe('')
    expect(el.querySelector('agentos-exchange-file-list')).not.toBeNull()
    expect(el.querySelector('agentos-exchange-folder-nav')).not.toBeNull()
    expect(el.querySelector('.exchange-drawer__tree')).toBeNull()
    expect(folderOpened).not.toHaveBeenCalled()
  }))

  it('clears on Escape without letting the key reach the panel', fakeAsync(() => {
    const { fixture, el, changed, type } = setup()
    fixture.componentRef.setInput('namespaceManifest', manifest)
    fixture.componentRef.setInput('namespaceManifestStatus', 'ready')
    type('guide')
    const event = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
    const stop = jest.spyOn(event, 'stopPropagation')
    el.querySelector('input[type="search"]')!.dispatchEvent(event)
    fixture.detectChanges()
    expect(stop).toHaveBeenCalled()
    expect(changed).toHaveBeenLastCalledWith('')
    expect(el.querySelector('agentos-exchange-file-list')).not.toBeNull()
  }))

  it('opening a folder of the tree clears the filter and browses into it', fakeAsync(() => {
    const { fixture, el, changed, folderOpened, type } = setup()
    fixture.componentRef.setInput('namespaceManifest', manifest)
    fixture.componentRef.setInput('namespaceManifestStatus', 'ready')
    type('guide')
    el.querySelector<HTMLButtonElement>('.exchange-drawer__tree .exchange-drawer__folder')!.click()
    fixture.detectChanges()
    expect(folderOpened).toHaveBeenCalledWith({ scope: ExchangeFileEntryScopeEnum.NAMESPACE, path: 'docs' })
    expect(changed).toHaveBeenLastCalledWith('')
    expect(el.querySelector('agentos-exchange-file-list')).not.toBeNull()
  }))

  it('keeps the query when the viewer opens and closes', fakeAsync(() => {
    const { fixture, el, type } = setup()
    fixture.componentRef.setInput('namespaceManifest', manifest)
    fixture.componentRef.setInput('namespaceManifestStatus', 'ready')
    type('guide')
    fixture.componentRef.setInput('activeFile', {
      scope: ExchangeFileEntryScopeEnum.NAMESPACE,
      path: 'docs/api/guide.md',
    })
    fixture.detectChanges()
    expect(el.querySelector('input[type="search"]')).toBeNull()
    fixture.componentRef.setInput('activeFile', null)
    fixture.detectChanges()
    expect(el.querySelector<HTMLInputElement>('input[type="search"]')!.value).toBe('guide')
    expect(el.querySelectorAll('.exchange-drawer__tree > li')).toHaveLength(3)
  }))
})

describe('ExchangeDrawerComponent referenced files', () => {
  const NS = ExchangeFileEntryScopeEnum.NAMESPACE
  const CASE = ExchangeFileEntryScopeEnum.CASE
  const entry = (path: string) => ({ path, name: path.split('/').pop() as string, directory: false, size: 1 })

  function setup(selectable = true) {
    TestBed.configureTestingModule({ imports: [ExchangeDrawerComponent] })
    const fixture = TestBed.createComponent(ExchangeDrawerComponent)
    fixture.componentRef.setInput('caseStatus', 'ready')
    fixture.componentRef.setInput('caseSectionVisible', true)
    fixture.componentRef.setInput('caseFiles', [entry('notes/todo.md')])
    fixture.componentRef.setInput('namespaceStatus', 'ready')
    fixture.componentRef.setInput('namespaceSectionVisible', true)
    fixture.componentRef.setInput('namespaceFiles', [entry('readme.md'), entry('guide.md')])
    fixture.componentRef.setInput('selectable', selectable)
    const toggled = jest.fn()
    fixture.componentInstance.referenceToggled.subscribe(toggled)
    fixture.detectChanges()
    const el = fixture.nativeElement as HTMLElement
    const box = (label: string) => el.querySelector<HTMLInputElement>(`input[aria-label="Select ${label}"]`)!
    const reference = (...refs: { scope: ExchangeFileEntryScopeEnum; path: string }[]) => {
      fixture.componentRef.setInput('referenced', refs)
      fixture.detectChanges()
    }
    const bar = () => el.querySelector<HTMLElement>('.exchange-drawer__selection-bar')
    const clear = () => Array.from(bar()!.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Clear')!
    return { fixture, el, toggled, box, reference, bar, clear }
  }

  it('shows neither checkboxes nor bar unless selectable', () => {
    const { el, bar, reference } = setup(false)
    reference({ scope: NS, path: 'readme.md' })
    expect(el.querySelector('input[type="checkbox"]')).toBeNull()
    expect(bar()).toBeNull()
  })

  it('checks exactly the referenced files, in both scopes', () => {
    const { box, reference } = setup()
    expect(box('readme.md').checked).toBe(false)
    reference({ scope: NS, path: 'readme.md' }, { scope: CASE, path: 'notes/todo.md' })
    expect(box('readme.md').checked).toBe(true)
    expect(box('todo.md').checked).toBe(true)
    expect(box('guide.md').checked).toBe(false)
    reference({ scope: NS, path: 'guide.md' })
    expect(box('readme.md').checked).toBe(false)
    expect(box('todo.md').checked).toBe(false)
    expect(box('guide.md').checked).toBe(true)
  })

  it('does not confuse the same path in the two scopes', () => {
    const { fixture, reference } = setup()
    fixture.componentRef.setInput('caseFiles', [entry('readme.md')])
    fixture.detectChanges()
    reference({ scope: CASE, path: 'readme.md' })
    const [caseBox, namespaceBox] = Array.from<HTMLInputElement>(
      fixture.nativeElement.querySelectorAll('input[aria-label="Select readme.md"]')
    )
    expect(caseBox!.checked).toBe(true)
    expect(namespaceBox!.checked).toBe(false)
  })

  it('emits referenced: true when an unchecked row is checked, with the scope-qualified file', () => {
    const { fixture, toggled, box } = setup()
    box('readme.md').click()
    box('todo.md').click()
    fixture.detectChanges()
    expect(toggled.mock.calls.map(([change]) => change)).toEqual([
      { ref: { scope: NS, path: 'readme.md' }, referenced: true },
      { ref: { scope: CASE, path: 'notes/todo.md' }, referenced: true },
    ])
  })

  it('emits referenced: false when a referenced row is unchecked', () => {
    const { toggled, box, reference } = setup()
    reference({ scope: NS, path: 'readme.md' })
    box('readme.md').click()
    expect(toggled).toHaveBeenCalledWith({ ref: { scope: NS, path: 'readme.md' }, referenced: false })
  })

  it('owns no state: a click the parent does not apply leaves the checkbox as it was', () => {
    const { fixture, box, reference } = setup()
    box('readme.md').click()
    fixture.detectChanges()
    expect(box('readme.md').checked).toBe(false)
    reference({ scope: NS, path: 'readme.md' })
    box('readme.md').click()
    fixture.detectChanges()
    expect(box('readme.md').checked).toBe(true)
  })

  it('counts the referenced files in the bar, which is only there when there are some', () => {
    const { bar, reference } = setup()
    expect(bar()).toBeNull()
    reference({ scope: NS, path: 'readme.md' })
    expect(bar()!.textContent).toContain('1 in message')
    reference({ scope: NS, path: 'readme.md' }, { scope: CASE, path: 'notes/todo.md' })
    expect(bar()!.textContent).toContain('2 in message')
    expect(bar()!.textContent).not.toContain('Add to message')
    reference()
    expect(bar()).toBeNull()
  })

  it('asks to take every referenced file out of the message on Clear, and nothing else', () => {
    const { toggled, reference, clear } = setup()
    reference({ scope: NS, path: 'readme.md' }, { scope: CASE, path: 'notes/todo.md' })
    clear().click()
    expect(toggled.mock.calls.map(([change]) => change)).toEqual([
      { ref: { scope: NS, path: 'readme.md' }, referenced: false },
      { ref: { scope: CASE, path: 'notes/todo.md' }, referenced: false },
    ])
  })

  it('keeps the checks across folder navigation', () => {
    const { fixture, bar, box, reference } = setup()
    reference({ scope: NS, path: 'readme.md' })
    fixture.componentRef.setInput('namespaceFiles', [entry('docs/other.md')])
    fixture.componentRef.setInput('namespaceBreadcrumb', [{ label: 'docs', path: 'docs' }])
    fixture.detectChanges()
    expect(bar()!.textContent).toContain('1 in message')
    expect(box('other.md').checked).toBe(false)
    fixture.componentRef.setInput('namespaceFiles', [entry('readme.md')])
    fixture.componentRef.setInput('namespaceBreadcrumb', [])
    fixture.detectChanges()
    expect(box('readme.md').checked).toBe(true)
  })

  it('checks and toggles rows of the filtered tree by their full path', fakeAsync(() => {
    const { fixture, el, toggled, bar, box, reference } = setup()
    fixture.componentRef.setInput('namespaceManifest', [
      { path: 'docs/api/spec.yaml', filename: 'spec.yaml', size: 1, lastModified: '2026-01-01T00:00:00Z', scope: NS },
    ])
    fixture.componentRef.setInput('namespaceManifestStatus', 'ready')
    const input = el.querySelector<HTMLInputElement>('input[type="search"]')!
    input.value = 'spec'
    input.dispatchEvent(new Event('input'))
    tick(150)
    fixture.detectChanges()
    expect(el.querySelector('.exchange-drawer__tree')).not.toBeNull()
    expect(box('spec.yaml').checked).toBe(false)
    box('spec.yaml').click()
    expect(toggled).toHaveBeenCalledWith({ ref: { scope: NS, path: 'docs/api/spec.yaml' }, referenced: true })
    reference({ scope: NS, path: 'docs/api/spec.yaml' })
    expect(box('spec.yaml').checked).toBe(true)
    el.querySelector<HTMLButtonElement>('.exchange-drawer__filter-clear')!.click()
    fixture.detectChanges()
    expect(bar()!.textContent).toContain('1 in message')
  }))

  it('hides the bar in the viewer, and shows it again on return', () => {
    const { fixture, bar, reference } = setup()
    reference({ scope: NS, path: 'readme.md' })
    fixture.componentRef.setInput('activeFile', { scope: NS, path: 'readme.md' })
    fixture.detectChanges()
    expect(bar()).toBeNull()
    fixture.componentRef.setInput('activeFile', null)
    fixture.detectChanges()
    expect(bar()!.textContent).toContain('1 in message')
  })
})

describe('ExchangeDrawerComponent directory controls', () => {
  it('distinguishes workspace preparation from file loading errors', () => {
    TestBed.configureTestingModule({ imports: [ExchangeDrawerComponent] })
    const fixture = TestBed.createComponent(ExchangeDrawerComponent)
    fixture.componentRef.setInput('caseSectionVisible', true)
    fixture.componentRef.setInput('caseStatus', 'preparing')
    fixture.detectChanges()
    expect(fixture.nativeElement.textContent).toContain('Preparing workspace')
    expect(fixture.nativeElement.querySelector('ds-spinner')).not.toBeNull()
    expect(fixture.nativeElement.querySelector('.exchange-drawer__retry')).toBeNull()

    fixture.componentRef.setInput('caseStatus', 'error')
    fixture.detectChanges()
    expect(fixture.nativeElement.textContent).not.toContain('Preparing workspace')
    expect(fixture.nativeElement.querySelector('ds-spinner')).toBeNull()
    expect(fixture.nativeElement.querySelector('.exchange-drawer__retry')).not.toBeNull()
  })

  it('renders reachable load-more controls for both scopes and keeps folder-only downloads available', async () => {
    await TestBed.configureTestingModule({ imports: [ExchangeDrawerComponent] }).compileComponents()
    const fixture = TestBed.createComponent(ExchangeDrawerComponent)
    for (const scope of ['case', 'namespace']) {
      fixture.componentRef.setInput(`${scope}Status`, 'ready')
      fixture.componentRef.setInput(`${scope}SectionVisible`, true)
      fixture.componentRef.setInput(`${scope}HasMore`, true)
      fixture.componentRef.setInput(`${scope}Folders`, [{ path: 'nested', name: 'nested', directory: true }])
    }
    const more = jest.fn()
    fixture.componentInstance.loadMoreRequested.subscribe(more)
    fixture.detectChanges()
    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[]
    const loadMore = buttons.filter((button) => button.textContent?.trim() === 'Load more files')
    expect(loadMore).toHaveLength(2)
    loadMore[0]!.click()
    loadMore[1]!.click()
    expect(more.mock.calls).toEqual([[ExchangeFileEntryScopeEnum.CASE], [ExchangeFileEntryScopeEnum.NAMESPACE]])
    expect(fixture.nativeElement.querySelector('[title="Download all case files"]')).not.toBeNull()
    expect(fixture.nativeElement.querySelector('[title="Download all namespace files"]')).not.toBeNull()
    fixture.componentRef.setInput('caseFolders', [])
    fixture.componentRef.setInput('caseBreadcrumb', [{ label: 'empty', path: 'empty' }])
    fixture.componentRef.setInput('namespaceLoadingMore', true)
    fixture.detectChanges()
    expect(loadMore[1]!.disabled).toBe(true)
    expect(fixture.nativeElement.querySelector('[title="Download all case files"]')).not.toBeNull()
  })
  it('mounts the Git/participant observer only while Files is visible and cancels it on close', fakeAsync(() => {
    const request = new Subject<ExchangeEnvironment>()
    const get = jest.fn().mockReturnValue(request)
    const workspace = new BehaviorSubject({
      view: { equipped: true, rootCaseId: 'root', status: 'READY' },
    })
    TestBed.configureTestingModule({
      imports: [ExchangeDrawerComponent],
      providers: [
        { provide: ExchangeEnvironmentService, useValue: { get } },
        { provide: CaseWorkspaceService, useValue: { enabled$: of(true), watch: () => workspace } },
        { provide: MatDialog, useValue: { open: jest.fn() } },
      ],
    })
    const fixture = TestBed.createComponent(ExchangeDrawerComponent)
    fixture.componentRef.setInput('caseId', 'root')
    fixture.componentRef.setInput('caseStatus', 'ready')
    fixture.componentRef.setInput('namespaceStatus', 'ready')
    fixture.componentRef.setInput('caseSectionVisible', true)
    fixture.detectChanges()
    tick(10000)
    expect(get).not.toHaveBeenCalled()
    fixture.componentRef.setInput('environmentActive', true)
    fixture.detectChanges()
    tick(0)
    expect(get).toHaveBeenCalledTimes(1)
    expect(request.observed).toBe(true)
    tick(20000)
    expect(get).toHaveBeenCalledTimes(1) // A slow request is never overlapped or restarted.
    request.next({ equipped: true, status: 'READY', branch: 'feature/test' })
    request.complete()
    fixture.detectChanges()
    expect(fixture.nativeElement.textContent).toContain('feature/test')
    expect(fixture.nativeElement.textContent).not.toContain('Refresh Git status')
    fixture.componentRef.setInput('canWriteCase', true)
    fixture.detectChanges()
    expect(fixture.nativeElement.textContent).toContain('Refresh Git status')
    tick(10000)
    const pending = new Subject<ExchangeEnvironment>()
    get.mockReturnValue(pending)
    tick(10000)
    expect(pending.observed).toBe(true)
    fixture.componentRef.setInput('environmentActive', false)
    fixture.detectChanges()
    expect(pending.observed).toBe(false)
    expect(workspace.observed).toBe(false)
    const calls = get.mock.calls.length
    tick(20000)
    expect(get).toHaveBeenCalledTimes(calls)
    expect(fixture.nativeElement.querySelector('agentos-exchange-environment')).toBeNull()
    fixture.destroy()
  }))
})
