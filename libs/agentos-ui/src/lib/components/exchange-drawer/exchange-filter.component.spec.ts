import { fakeAsync, flushMicrotasks, TestBed, tick } from '@angular/core/testing'
import { MatDialog } from '@angular/material/dialog'
import { BehaviorSubject, of } from 'rxjs'
import { CaseWorkspaceService } from '../../services/case-workspace.service'
import { ExchangeEnvironmentService } from '../../services/exchange-environment.service'
import { ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { ExchangeDrawerComponent } from './exchange-drawer.component'

describe('ExchangeDrawerComponent file filters', () => {
  it.each(['case', 'namespace'])('announces %s search transitions and focuses the field after clear', fakeAsync((scope: string) => {
    TestBed.configureTestingModule({ imports: [ExchangeDrawerComponent] })
    const fixture = TestBed.createComponent(ExchangeDrawerComponent)
    document.body.appendChild(fixture.nativeElement)
    fixture.componentRef.setInput('caseStatus', 'ready')
    fixture.componentRef.setInput('namespaceStatus', 'ready')
    fixture.componentRef.setInput(`${scope}SectionVisible`, true)
    fixture.componentRef.setInput(`${scope}Files`, [{ path: 'root.txt', name: 'root.txt', directory: false }])
    fixture.componentRef.setInput(`${scope}ManifestStatus`, 'loading')
    fixture.detectChanges()
    const host = fixture.nativeElement as HTMLElement
    const input = host.querySelector<HTMLInputElement>('input[type=search]')!
    const status = host.querySelector<HTMLElement>('[role=status][aria-live=polite]')!
    expect(status.textContent).toBe('')
    input.value = 'guide'
    input.dispatchEvent(new Event('input'))
    tick(150)
    fixture.detectChanges()
    expect(status.textContent).toContain('Searching')
    fixture.componentRef.setInput(`${scope}Manifest`, [{ path: 'deep/guide.md', filename: 'guide.md', size: 1 }])
    fixture.componentRef.setInput(`${scope}ManifestStatus`, 'ready')
    fixture.detectChanges()
    expect(status.textContent).toContain('1 match for "guide"')
    fixture.componentRef.setInput(`${scope}Manifest`, [])
    fixture.detectChanges()
    expect(status.textContent).toContain('0 matches for "guide"')
    const clear = host.querySelector<HTMLButtonElement>('.exchange-drawer__filter-clear')!
    clear.focus()
    clear.click()
    fixture.detectChanges()
    expect(document.activeElement).toBe(input)
    expect(status.textContent).toContain('back to file list')
    input.value = 'guide'
    input.dispatchEvent(new Event('input'))
    tick(150)
    fixture.detectChanges()
    input.focus()
    const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
    input.dispatchEvent(escape)
    fixture.detectChanges()
    expect(escape.defaultPrevented).toBe(true)
    expect(document.activeElement).toBe(input)
    expect(status.textContent).toContain('back to file list')
    fixture.destroy()
    host.remove()
  }))

  it.each([ExchangeFileEntryScopeEnum.CASE, ExchangeFileEntryScopeEnum.NAMESPACE])('restores focus to the %s row when both scopes share a path', fakeAsync((scope) => {
    TestBed.configureTestingModule({ imports: [ExchangeDrawerComponent] })
    const fixture = TestBed.createComponent(ExchangeDrawerComponent)
    document.body.appendChild(fixture.nativeElement)
    for (const section of ['case', 'namespace']) {
      fixture.componentRef.setInput(`${section}Status`, 'ready')
      fixture.componentRef.setInput(`${section}SectionVisible`, true)
      fixture.componentRef.setInput(`${section}Files`, [{ path: 'shared.txt', name: 'shared.txt', directory: false, size: 1 }])
    }
    fixture.detectChanges()
    const host = fixture.nativeElement as HTMLElement
    fixture.componentInstance.fileSelected.subscribe((ref) => {
      fixture.componentRef.setInput('activeFile', ref)
      fixture.detectChanges()
    })
    fixture.componentInstance.viewerClosed.subscribe(() => {
      fixture.componentRef.setInput('activeFile', null)
    })
    host.querySelector<HTMLButtonElement>(`[data-track="ExchangeItem-file_${scope}:shared.txt"]`)!.click()
    host.querySelector<HTMLButtonElement>('[data-track="ExchangeContentViewer-back"]')!.click()
    // The parent updates state synchronously, but Angular renders the list on a later turn.
    flushMicrotasks()
    fixture.detectChanges()
    const target = host.querySelector<HTMLButtonElement>(`[data-track="ExchangeItem-file_${scope}:shared.txt"]`)
    expect(document.activeElement).toBe(target)
    fixture.destroy()
    host.remove()
  }))

  it.each([ExchangeFileEntryScopeEnum.CASE, ExchangeFileEntryScopeEnum.NAMESPACE])('returns focus to the %s filter after opening a filtered folder', fakeAsync((scope) => {
    TestBed.configureTestingModule({ imports: [ExchangeDrawerComponent] })
    const fixture = TestBed.createComponent(ExchangeDrawerComponent)
    document.body.appendChild(fixture.nativeElement)
    const section = scope === ExchangeFileEntryScopeEnum.CASE ? 'case' : 'namespace'
    fixture.componentRef.setInput('caseStatus', 'ready')
    fixture.componentRef.setInput('namespaceStatus', 'ready')
    fixture.componentRef.setInput(`${section}SectionVisible`, true)
    fixture.componentRef.setInput(`${section}Files`, [{ path: 'root.txt', name: 'root.txt', directory: false }])
    fixture.componentRef.setInput(`${section}ManifestStatus`, 'ready')
    fixture.componentRef.setInput(`${section}Manifest`, [{ path: 'deep/file.txt', filename: 'file.txt' }])
    fixture.detectChanges()
    const host = fixture.nativeElement as HTMLElement
    const input = host.querySelector<HTMLInputElement>('input[type=search]')!
    input.value = 'file'
    input.dispatchEvent(new Event('input'))
    tick(150)
    fixture.detectChanges()
    host.querySelector<HTMLButtonElement>('.exchange-drawer__tree .exchange-drawer__folder')!.click()
    fixture.detectChanges()
    flushMicrotasks()
    expect(document.activeElement).toBe(input)
    fixture.destroy()
    host.remove()
  }))

  it.each([ExchangeFileEntryScopeEnum.CASE, ExchangeFileEntryScopeEnum.NAMESPACE])('focuses the %s filter when the previewed file disappears', fakeAsync((scope) => {
    TestBed.configureTestingModule({ imports: [ExchangeDrawerComponent] })
    const fixture = TestBed.createComponent(ExchangeDrawerComponent)
    document.body.appendChild(fixture.nativeElement)
    const section = scope === ExchangeFileEntryScopeEnum.CASE ? 'case' : 'namespace'
    fixture.componentRef.setInput('caseStatus', 'ready')
    fixture.componentRef.setInput('namespaceStatus', 'ready')
    fixture.componentRef.setInput(`${section}SectionVisible`, true)
    fixture.componentRef.setInput(`${section}Files`, [{ path: 'gone.txt', name: 'gone.txt', directory: false }])
    fixture.detectChanges()
    const host = fixture.nativeElement as HTMLElement
    fixture.componentInstance.fileSelected.subscribe((ref) => {
      fixture.componentRef.setInput('activeFile', ref)
      fixture.detectChanges()
    })
    fixture.componentInstance.viewerClosed.subscribe(() => {
      fixture.componentRef.setInput('activeFile', null)
      fixture.componentRef.setInput(`${section}Files`, [])
    })
    host.querySelector<HTMLButtonElement>(`[data-track="ExchangeItem-file_${scope}:gone.txt"]`)!.click()
    host.querySelector<HTMLButtonElement>('[data-track="ExchangeContentViewer-back"]')!.click()
    flushMicrotasks()
    fixture.detectChanges()
    const fallback = scope === ExchangeFileEntryScopeEnum.NAMESPACE
      ? host.querySelector('.exchange-drawer__section-title') : host.querySelector('input[type=search]')
    expect(document.activeElement).toBe(fallback)
    fixture.destroy()
    host.remove()
  }))

  it('exposes distinct native controls for both scopes and their full paths', fakeAsync(() => {
    TestBed.configureTestingModule({ imports: [ExchangeDrawerComponent] })
    const fixture = TestBed.createComponent(ExchangeDrawerComponent)
    for (const scope of ['case', 'namespace']) {
      fixture.componentRef.setInput(`${scope}Status`, 'ready')
      fixture.componentRef.setInput(`${scope}SectionVisible`, true)
      fixture.componentRef.setInput(`${scope}Folders`, [{ path: 'docs', name: 'docs', directory: true }])
      fixture.componentRef.setInput(`${scope}ManifestStatus`, 'ready')
      fixture.componentRef.setInput(`${scope}Manifest`, [{ path: 'docs/api/guide.md', filename: 'guide.md', size: 1 }])
    }
    fixture.detectChanges()
    const host = fixture.nativeElement as HTMLElement
    expect(host.querySelector('[data-track="ExchangeDrawer-folder_CASE:docs"]')?.tagName).toBe('BUTTON')
    expect(host.querySelector('[data-track="ExchangeDrawer-folder_NAMESPACE:docs"]')?.tagName).toBe('BUTTON')
    const caseInput = host.querySelector<HTMLInputElement>('[data-track="ExchangeDrawer-caseFilterInput"]')!
    const namespaceInput = host.querySelector<HTMLInputElement>('[data-track="ExchangeDrawer-namespaceFilterInput"]')!
    for (const input of [caseInput, namespaceInput]) {
      input.value = 'guide'
      input.dispatchEvent(new Event('input'))
    }
    tick(150)
    fixture.detectChanges()
    for (const scope of ['CASE', 'NAMESPACE']) {
      expect(host.querySelector(`[data-track="ExchangeDrawer-filteredFolder_${scope}:docs/api"]`)?.tagName).toBe('BUTTON')
      expect(host.querySelector(`[data-track="ExchangeItem-file_${scope}:docs/api/guide.md"]`)?.tagName).toBe('BUTTON')
    }
    expect(host.querySelector('[data-track="ExchangeDrawer-caseClearFilter"]')?.tagName).toBe('BUTTON')
    expect(host.querySelector('[data-track="ExchangeDrawer-namespaceClearFilter"]')?.tagName).toBe('BUTTON')
    fixture.componentRef.setInput('activeFile', { scope: ExchangeFileEntryScopeEnum.CASE, path: 'docs/api/guide.md' })
    fixture.detectChanges()
    expect(host.querySelector('[data-track="ExchangeContentViewer-back"]')?.tagName).toBe('BUTTON')
    fixture.destroy()
  }))

  it.each(['case', 'namespace'])('renders full-depth results and restores %s navigation on clear', fakeAsync((scope: string) => {
    TestBed.configureTestingModule({ imports: [ExchangeDrawerComponent] })
    const fixture = TestBed.createComponent(ExchangeDrawerComponent)
    fixture.componentRef.setInput('caseStatus', 'ready')
    fixture.componentRef.setInput('namespaceStatus', 'ready')
    fixture.componentRef.setInput(`${scope}SectionVisible`, true)
    fixture.componentRef.setInput(`${scope}Files`, [{ path: 'current/a.txt', name: 'a.txt', directory: false }])
    fixture.componentRef.setInput(`${scope}Breadcrumb`, [{ label: 'current', path: 'current' }])
    fixture.componentRef.setInput(`${scope}HasMore`, true)
    fixture.componentRef.setInput(`${scope}ManifestStatus`, 'ready')
    fixture.componentRef.setInput(`${scope}Manifest`, [{ path: 'other/deep/Guide.md', filename: 'Guide.md', size: 5 }])
    const selected = jest.fn()
    const download = jest.fn()
    const remove = jest.fn()
    const opened = jest.fn()
    fixture.componentInstance.fileSelected.subscribe(selected)
    fixture.componentInstance.downloadRequested.subscribe(download)
    fixture.componentInstance.deleteRequested.subscribe(remove)
    fixture.componentInstance.folderOpened.subscribe(opened)
    fixture.componentRef.setInput(scope === 'case' ? 'canWriteCase' : 'canWriteNamespace', true)
    fixture.detectChanges()
    const el = fixture.nativeElement as HTMLElement
    const input = el.querySelector<HTMLInputElement>('input[type=search]')!
    input.value = 'OTHER/deep'
    input.dispatchEvent(new Event('input'))
    tick(149)
    fixture.detectChanges()
    expect(el.querySelector('.exchange-drawer__tree')).toBeNull()
    tick(1)
    fixture.detectChanges()
    expect(el.querySelectorAll('.exchange-drawer__tree > li')).toHaveLength(3)
    el.querySelector<HTMLButtonElement>('agentos-exchange-item .exchange-item__main')!.click()
    el.querySelector<HTMLButtonElement>('agentos-exchange-item ds-icon-button[icon=download] button')!.click()
    el.querySelector<HTMLButtonElement>('agentos-exchange-item ds-icon-button[icon=delete] button')!.click()
    const ref = { scope: scope === 'case' ? ExchangeFileEntryScopeEnum.CASE : ExchangeFileEntryScopeEnum.NAMESPACE, path: 'other/deep/Guide.md' }
    expect(selected).toHaveBeenCalledWith(ref)
    expect(download).toHaveBeenCalledWith(ref)
    expect(remove).toHaveBeenCalledWith(ref)
    el.querySelector<HTMLButtonElement>('.exchange-drawer__filter-clear')!.click()
    fixture.detectChanges()
    expect(el.querySelector('agentos-exchange-folder-nav')?.textContent).toContain('current')
    expect(el.textContent).toContain('Load more files')
    expect(opened).not.toHaveBeenCalled()
    fixture.destroy()
  }))

  it.each(['case', 'namespace'])('shows %s loading, error, retry, empty and truncation states', fakeAsync((scope: string) => {
    TestBed.configureTestingModule({ imports: [ExchangeDrawerComponent] })
    const fixture = TestBed.createComponent(ExchangeDrawerComponent)
    fixture.componentRef.setInput('caseStatus', 'ready')
    fixture.componentRef.setInput('namespaceStatus', 'ready')
    fixture.componentRef.setInput(`${scope}SectionVisible`, true)
    fixture.componentRef.setInput(`${scope}Files`, [{ path: 'root.txt', name: 'root.txt', directory: false }])
    fixture.detectChanges()
    const el = fixture.nativeElement as HTMLElement
    const input = el.querySelector<HTMLInputElement>('input[type=search]')!
    input.value = 'file'
    input.dispatchEvent(new Event('input'))
    tick(150)
    fixture.detectChanges()
    expect(el.querySelector('ds-spinner')).not.toBeNull()
    fixture.componentRef.setInput(`${scope}ManifestStatus`, 'error')
    fixture.detectChanges()
    const retry = jest.fn()
    fixture.componentInstance.filterRetry.subscribe(retry)
    el.querySelector<HTMLButtonElement>('[role=alert] button')!.click()
    expect(retry).toHaveBeenCalledWith(scope === 'case' ? ExchangeFileEntryScopeEnum.CASE : ExchangeFileEntryScopeEnum.NAMESPACE)
    fixture.componentRef.setInput(`${scope}ManifestStatus`, 'ready')
    fixture.componentRef.setInput(`${scope}Manifest`, [])
    fixture.detectChanges()
    expect(el.textContent).toContain('No files match')
    fixture.componentRef.setInput(`${scope}Manifest`, Array.from({ length: 501 }, (_, i) => ({ path: `dir/file-${i}.txt`, filename: `file-${i}.txt`, size: 1 })))
    fixture.detectChanges()
    expect(el.querySelectorAll('agentos-exchange-item')).toHaveLength(500)
    expect(el.textContent).toContain('Showing first 500 matches')
    expect(el.querySelector('[role=status][aria-live=polite]')?.textContent).toContain('501 matches')
    expect(el.querySelector('[role=status][aria-live=polite]')?.textContent).toContain('Showing first 500')
    fixture.destroy()
  }))

  it.each([499, 500, 501])('renders the 500-match boundary for %i matching files', fakeAsync((count: number) => {
    TestBed.configureTestingModule({ imports: [ExchangeDrawerComponent] })
    const fixture = TestBed.createComponent(ExchangeDrawerComponent)
    fixture.componentRef.setInput('caseStatus', 'ready')
    fixture.componentRef.setInput('namespaceStatus', 'ready')
    fixture.componentRef.setInput('caseSectionVisible', true)
    fixture.componentRef.setInput('caseManifestStatus', 'ready')
    fixture.componentRef.setInput('caseManifest', Array.from({ length: count }, (_, i) => ({
      path: `dir/file-${i}.txt`, filename: `file-${i}.txt`, size: 1,
    })))
    fixture.detectChanges()
    const el = fixture.nativeElement as HTMLElement
    const input = el.querySelector<HTMLInputElement>('input[type=search]')!
    input.value = 'file'
    input.dispatchEvent(new Event('input'))
    tick(150)
    fixture.detectChanges()
    expect(el.querySelectorAll('agentos-exchange-item')).toHaveLength(Math.min(count, 500))
    expect(el.textContent?.includes('Showing first 500 matches')).toBe(count > 500)
    fixture.destroy()
  }))

  it.each(['case', 'namespace'])('distinguishes %s browse from an empty search', fakeAsync((scope: string) => {
    TestBed.configureTestingModule({ imports: [ExchangeDrawerComponent] })
    const fixture = TestBed.createComponent(ExchangeDrawerComponent)
    fixture.componentRef.setInput('caseStatus', 'ready')
    fixture.componentRef.setInput('namespaceStatus', 'ready')
    fixture.componentRef.setInput(`${scope}SectionVisible`, true)
    fixture.componentRef.setInput(`${scope}ManifestStatus`, 'ready')
    fixture.detectChanges()
    const el = fixture.nativeElement as HTMLElement
    expect(el.textContent).toContain(scope === 'case' ? 'No case files' : 'No namespace files')
    fixture.componentRef.setInput(`${scope}Files`, [{ path: 'root.txt', name: 'root.txt', directory: false }])
    fixture.detectChanges()
    const input = el.querySelector<HTMLInputElement>('input[type=search]')!
    input.value = 'absent'
    input.dispatchEvent(new Event('input'))
    tick(150)
    fixture.detectChanges()
    expect(el.textContent).toContain('No files match')
    expect(el.textContent).not.toContain(scope === 'case' ? 'No case files' : 'No namespace files')
    fixture.destroy()
  }))

  it.each(['case', 'namespace'])('cancels superseded and cleared %s keystrokes', fakeAsync((scope: string) => {
    TestBed.configureTestingModule({ imports: [ExchangeDrawerComponent] })
    const fixture = TestBed.createComponent(ExchangeDrawerComponent)
    fixture.componentRef.setInput('caseStatus', 'ready')
    fixture.componentRef.setInput('namespaceStatus', 'ready')
    fixture.componentRef.setInput(`${scope}SectionVisible`, true)
    fixture.componentRef.setInput(`${scope}Files`, [{ path: 'root.txt', name: 'root.txt', directory: false }])
    fixture.detectChanges()
    const changed = jest.fn()
    fixture.componentInstance.filterChanged.subscribe(changed)
    const input = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('input[type=search]')!
    input.value = 'old'
    input.dispatchEvent(new Event('input'))
    tick(100)
    input.value = 'new'
    input.dispatchEvent(new Event('input'))
    tick(149)
    expect(changed).not.toHaveBeenCalled()
    tick(1)
    expect(changed).toHaveBeenCalledTimes(1)
    expect(changed).toHaveBeenCalledWith({
      scope: scope === 'case' ? ExchangeFileEntryScopeEnum.CASE : ExchangeFileEntryScopeEnum.NAMESPACE,
      query: 'new',
    })
    input.value = 'late'
    input.dispatchEvent(new Event('input'))
    input.value = ''
    input.dispatchEvent(new Event('input'))
    tick(150)
    expect(changed).toHaveBeenCalledTimes(2)
    expect(changed.mock.calls[1]?.[0].query).toBe('')
    fixture.destroy()
  }))

  it.each(['case', 'namespace'])('hides write actions but allows preview and download for read-only %s', fakeAsync((scope: string) => {
    TestBed.configureTestingModule({ imports: [ExchangeDrawerComponent] })
    const fixture = TestBed.createComponent(ExchangeDrawerComponent)
    fixture.componentRef.setInput('caseStatus', 'ready')
    fixture.componentRef.setInput('namespaceStatus', 'ready')
    fixture.componentRef.setInput(`${scope}SectionVisible`, true)
    fixture.componentRef.setInput(`${scope}Files`, [{ path: 'root.txt', name: 'root.txt', directory: false }])
    fixture.componentRef.setInput(`${scope}ManifestStatus`, 'ready')
    fixture.componentRef.setInput(`${scope}Manifest`, [{ path: 'deep/a.md', filename: 'a.md', size: 1 }])
    fixture.detectChanges()
    const el = fixture.nativeElement as HTMLElement
    const input = el.querySelector<HTMLInputElement>('input[type=search]')!
    input.value = 'a.md'
    input.dispatchEvent(new Event('input'))
    tick(150)
    fixture.detectChanges()
    expect(el.querySelector('ds-icon-button[icon=upload_file]')).toBeNull()
    expect(el.querySelector('agentos-exchange-item ds-icon-button[icon=delete]')).toBeNull()
    expect(el.querySelector('agentos-exchange-item .exchange-item__main')).not.toBeNull()
    expect(el.querySelector('agentos-exchange-item ds-icon-button[icon=download]')).not.toBeNull()
    fixture.destroy()
  }))

  it('navigates to a filtered folder and clears only its own query', fakeAsync(() => {
    TestBed.configureTestingModule({ imports: [ExchangeDrawerComponent] })
    const fixture = TestBed.createComponent(ExchangeDrawerComponent)
    for (const scope of ['case', 'namespace']) {
      fixture.componentRef.setInput(`${scope}Status`, 'ready')
      fixture.componentRef.setInput(`${scope}SectionVisible`, true)
      fixture.componentRef.setInput(`${scope}Files`, [{ path: 'root.txt', name: 'root.txt', directory: false }])
      fixture.componentRef.setInput(`${scope}ManifestStatus`, 'ready')
      fixture.componentRef.setInput(`${scope}Manifest`, [{ path: 'deep/a.md', filename: 'a.md', size: 1 }])
    }
    fixture.detectChanges()
    const el = fixture.nativeElement as HTMLElement
    for (const input of Array.from(el.querySelectorAll<HTMLInputElement>('input[type=search]'))) {
      input.value = 'a.md'
      input.dispatchEvent(new Event('input'))
    }
    tick(150)
    fixture.detectChanges()
    const opened = jest.fn()
    fixture.componentInstance.folderOpened.subscribe(opened)
    el.querySelector<HTMLButtonElement>('.exchange-drawer__tree .exchange-drawer__folder')!.click()
    fixture.detectChanges()
    expect(opened).toHaveBeenCalledWith({ scope: ExchangeFileEntryScopeEnum.CASE, path: 'deep' })
    expect(el.querySelectorAll<HTMLInputElement>('input[type=search]')[0]!.value).toBe('')
    expect(el.querySelectorAll<HTMLInputElement>('input[type=search]')[1]!.value).toBe('a.md')
    fixture.destroy()
  }))
  it('preserves Changes and Worktree and routes the filtered Git diff by full relative path', fakeAsync(() => {
    const environment = { equipped: true, status: 'READY', branch: 'topic', changes: {
      additions: 4, deletions: 2, files: [{ path: 'src/a.ts', status: 'MODIFIED' }, { path: 'src/b.ts', status: 'ADDED' }],
    } }
    const dialog = { open: jest.fn().mockReturnValue({ close: jest.fn() }) }
    const workspace = new BehaviorSubject({ view: { equipped: true, rootCaseId: 'c-1', status: 'READY' } })
    TestBed.configureTestingModule({ imports: [ExchangeDrawerComponent], providers: [
      { provide: ExchangeEnvironmentService, useValue: { get: jest.fn().mockReturnValue(of(environment)) } },
      { provide: CaseWorkspaceService, useValue: { enabled$: of(true), watch: () => workspace } },
      { provide: MatDialog, useValue: dialog },
    ] })
    const fixture = TestBed.createComponent(ExchangeDrawerComponent)
    fixture.componentRef.setInput('caseId', 'c-1')
    fixture.componentRef.setInput('environmentActive', true)
    fixture.componentRef.setInput('caseStatus', 'ready')
    fixture.componentRef.setInput('namespaceStatus', 'ready')
    fixture.componentRef.setInput('caseSectionVisible', true)
    fixture.componentRef.setInput('caseManifestStatus', 'ready')
    fixture.componentRef.setInput('caseManifest', [{ path: 'repo/src/a.ts', filename: 'a.ts', size: 1 }])
    fixture.detectChanges()
    tick(0)
    fixture.detectChanges()
    const el = fixture.nativeElement as HTMLElement
    const changes = el.querySelector<HTMLButtonElement>('agentos-exchange-environment button[aria-label="View changes file by file"]')!
    expect(changes.title).toContain('2 changed files')
    const input = el.querySelector<HTMLInputElement>('input[type=search]')!
    expect(changes.compareDocumentPosition(input) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    input.value = 'a.ts'
    input.dispatchEvent(new Event('input'))
    tick(150)
    fixture.detectChanges()
    expect(el.querySelector('agentos-exchange-environment')?.textContent).toContain('Worktree')
    expect(changes.title).toContain('2 changed files')
    expect(el.querySelector('.exchange-drawer__tree .exchange-drawer__git-dot')).not.toBeNull()
    expect(el.querySelector('agentos-exchange-item .exchange-item')?.getAttribute('data-git-status')).toBe('MODIFIED')
    el.querySelector<HTMLButtonElement>('agentos-exchange-item .exchange-item__git-status')!.click()
    expect(dialog.open).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({ data: expect.objectContaining({ path: 'src/a.ts' }) }))
    el.querySelector<HTMLButtonElement>('.exchange-drawer__filter-clear')!.click()
    fixture.detectChanges()
    expect(changes.title).toContain('2 changed files')
    expect(el.querySelector('agentos-exchange-environment')?.textContent).toContain('Worktree')
    expect(el.querySelector('agentos-exchange-environment')?.textContent).toContain('topic')
    fixture.destroy()
  }))

  it('keeps the two filters independent, restores browse on clear and retains the query across preview', fakeAsync(() => {
    TestBed.configureTestingModule({ imports: [ExchangeDrawerComponent] })
    const fixture = TestBed.createComponent(ExchangeDrawerComponent)
    for (const scope of ['case', 'namespace']) {
      fixture.componentRef.setInput(`${scope}Status`, 'ready')
      fixture.componentRef.setInput(`${scope}SectionVisible`, true)
      fixture.componentRef.setInput(`${scope}Files`, [{ path: 'root.txt', name: 'root.txt', directory: false, size: 1 }])
      fixture.componentRef.setInput(`${scope}ManifestStatus`, 'ready')
      fixture.componentRef.setInput(`${scope}Manifest`, [{ path: 'deep/docs/Guide.md', filename: 'Guide.md', size: 1 }])
    }
    fixture.detectChanges()
    const el = fixture.nativeElement as HTMLElement
    const inputs = () => el.querySelectorAll<HTMLInputElement>('input[type=search]')
    const changed = jest.fn()
    const opened = jest.fn()
    fixture.componentInstance.filterChanged.subscribe(changed)
    fixture.componentInstance.folderOpened.subscribe(opened)
    inputs()[0]!.value = 'guide'
    inputs()[0]!.dispatchEvent(new Event('input'))
    inputs()[1]!.value = 'docs'
    inputs()[1]!.dispatchEvent(new Event('input'))
    tick(150)
    fixture.detectChanges()
    expect(el.querySelectorAll('.exchange-drawer__tree')).toHaveLength(2)
    expect(changed).toHaveBeenCalledWith({ scope: ExchangeFileEntryScopeEnum.CASE, query: 'guide' })
    fixture.componentRef.setInput('activeFile', { scope: ExchangeFileEntryScopeEnum.CASE, path: 'deep/docs/Guide.md' })
    fixture.detectChanges()
    fixture.componentRef.setInput('activeFile', null)
    fixture.detectChanges()
    expect(inputs()[0]!.value).toBe('guide')
    inputs()[0]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    fixture.detectChanges()
    expect(inputs()[0]!.value).toBe('')
    expect(inputs()[1]!.value).toBe('docs')
    expect(el.querySelectorAll('.exchange-drawer__tree')).toHaveLength(1)
    expect(opened).not.toHaveBeenCalled()
  }))
})
