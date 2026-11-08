import { fakeAsync, TestBed, tick } from '@angular/core/testing'
import { ActivatedRoute } from '@angular/router'
import { ExchangeControllerService, ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { BehaviorSubject, of, Subject, throwError } from 'rxjs'
import { CaseWorkspaceService } from '../../services/case-workspace.service'
import { MAX_VIEWABLE_SIZE } from '../../services/exchange-content.utils'
import { ExchangeStateService } from '../../services/exchange-state.service'
import { ExchangeShellComponent } from './exchange-shell.component'

const CASE = ExchangeFileEntryScopeEnum.CASE
const NAMESPACE = ExchangeFileEntryScopeEnum.NAMESPACE
const file = (path: string, size = 10) => ({ path, filename: path.split('/').pop()!, size, scope: CASE, lastModified: '' })
const listing = { path: '', entries: [], totalEntries: 0, page: 0, pageSize: 100, hasMore: false, capability: 'READ' }

describe('ExchangeShellComponent filters', () => {
  function setup() {
    const route = new BehaviorSubject<Record<string, string>>({ ns: 'ns-1', case: 'c-1' })
    const api = {
      browseCaseFilesExchange: jest.fn().mockReturnValue(of(listing)),
      browseNamespaceFilesExchange: jest.fn().mockReturnValue(of(listing)),
      getCaseFilesManifestExchange: jest.fn().mockReturnValue(of({ files: [] })),
      getNamespaceFilesManifestExchange: jest.fn().mockReturnValue(of({ files: [] })),
      getCaseFileContentExchange: jest.fn().mockReturnValue(of({ content: 'body' })),
      getNamespaceFileContentExchange: jest.fn().mockReturnValue(of({ content: 'body' })),
    }
    TestBed.configureTestingModule({
      imports: [ExchangeShellComponent],
      providers: [
        { provide: ActivatedRoute, useValue: { queryParams: route } },
        { provide: ExchangeControllerService, useValue: api },
        { provide: CaseWorkspaceService, useValue: { watch: jest.fn() } },
      ],
    })
    const fixture = TestBed.createComponent(ExchangeShellComponent)
    fixture.detectChanges()
    const shell = fixture.componentInstance
    const state = TestBed.inject(ExchangeStateService)
    return { fixture, shell, state, api, route }
  }

  it('loads only active scopes, caches independently and retries errors', () => {
    const { fixture, shell, state, api } = setup()
    expect(api.getCaseFilesManifestExchange).not.toHaveBeenCalled()
    expect(api.getNamespaceFilesManifestExchange).not.toHaveBeenCalled()
    api.getCaseFilesManifestExchange.mockReturnValueOnce(throwError(() => new Error('network')))
      .mockReturnValue(of({ files: [file('deep/a.md')] }))
    shell['onFilterChanged']({ scope: CASE, query: 'a' })
    fixture.detectChanges()
    expect(shell['caseManifestStatus']()).toBe('error')
    shell['onFilterRetry'](CASE)
    fixture.detectChanges()
    expect(shell['caseManifestStatus']()).toBe('ready')
    shell['onFilterChanged']({ scope: NAMESPACE, query: 'shared' })
    fixture.detectChanges()
    expect(api.getNamespaceFilesManifestExchange).toHaveBeenCalledTimes(1)
    state.refreshCase()
    fixture.detectChanges()
    expect(api.getCaseFilesManifestExchange).toHaveBeenCalledTimes(3)
    expect(api.getNamespaceFilesManifestExchange).toHaveBeenCalledTimes(1)
    shell['onFilterChanged']({ scope: CASE, query: '' })
    fixture.detectChanges()
    expect(shell['caseManifestStatus']()).toBe('idle')
    expect(shell['namespaceManifestStatus']()).toBe('ready')
  })

  it('ignores old case responses even before the new effect runs and keeps namespace data', () => {
    const { fixture, shell, api, route } = setup()
    const old = new Subject<{ files: ReturnType<typeof file>[] }>()
    const current = new Subject<{ files: ReturnType<typeof file>[] }>()
    api.getCaseFilesManifestExchange.mockImplementation((id: string) => id === 'c-1' ? old : current)
    api.getNamespaceFilesManifestExchange.mockReturnValue(of({ files: [file('shared.md')] }))
    shell['onFilterChanged']({ scope: CASE, query: 'md' })
    shell['onFilterChanged']({ scope: NAMESPACE, query: 'md' })
    fixture.detectChanges()
    route.next({ ns: 'ns-1', case: 'c-2' })
    old.next({ files: [file('old.md')] })
    expect(shell['caseManifest']()).toBeNull()
    fixture.detectChanges()
    current.next({ files: [file('new.md')] })
    fixture.detectChanges()
    expect(shell['caseManifest']()?.map((item) => item.path)).toEqual(['new.md'])
    expect(shell['namespaceManifest']()?.map((item) => item.path)).toEqual(['shared.md'])
    expect(api.getNamespaceFilesManifestExchange).toHaveBeenCalledTimes(1)
  })

  it('keeps the new case results when the old case answers last', () => {
    const { fixture, shell, api, route } = setup()
    const old = new Subject<{ files: ReturnType<typeof file>[] }>()
    const current = new Subject<{ files: ReturnType<typeof file>[] }>()
    api.getCaseFilesManifestExchange.mockImplementation((id: string) => id === 'c-1' ? old : current)
    shell['onFilterChanged']({ scope: CASE, query: 'private' })
    fixture.detectChanges()
    route.next({ ns: 'ns-1', case: 'c-2' })
    fixture.detectChanges()
    current.next({ files: [file('private-b.txt')] })
    fixture.detectChanges()
    old.next({ files: [file('private-a.txt')] })
    fixture.detectChanges()
    expect(shell['caseManifest']()?.map((item) => item.path)).toEqual(['private-b.txt'])
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('private-a.txt')
    fixture.destroy()
  })

  it.each([CASE, NAMESPACE])('routes %s search failure and retry through the drawer', fakeAsync((scope) => {
    const { fixture, api } = setup()
    const request = new Subject<{ files: ReturnType<typeof file>[] }>()
    const get = scope === CASE ? api.getCaseFilesManifestExchange : api.getNamespaceFilesManifestExchange
    get.mockReturnValueOnce(request).mockReturnValue(of({ files: [file('deep/result.txt')] }))
    const el = fixture.nativeElement as HTMLElement
    const label = scope === CASE ? 'Filter case files by name or path' : 'Filter namespace files by name or path'
    const input = el.querySelector<HTMLInputElement>(`input[aria-label="${label}"]`)!
    input.value = 'result'
    input.dispatchEvent(new Event('input'))
    tick(150)
    fixture.detectChanges()
    expect(el.querySelector('ds-spinner')).not.toBeNull()
    expect(el.textContent).not.toContain('No files match')
    request.error({ status: 500 })
    fixture.detectChanges()
    expect(el.querySelector('[role=alert]')?.textContent).toContain("Couldn't search")
    expect(el.textContent).not.toContain('No files match')
    el.querySelector<HTMLButtonElement>('[role=alert] button')!.click()
    fixture.detectChanges()
    expect(el.textContent).toContain('result.txt')
    expect(get).toHaveBeenCalledTimes(2)
    fixture.destroy()
  }))

  it('refreshes active results after a case upload and delete without refetching namespace', fakeAsync(() => {
    const { fixture, state, shell, api } = setup()
    let present = false
    Object.assign(api, {
      uploadCaseFileExchange: jest.fn().mockImplementation(() => {
        present = true
        return of({})
      }),
      deleteCaseFileExchange: jest.fn().mockImplementation(() => {
        present = false
        return of({})
      }),
    })
    api.getCaseFilesManifestExchange.mockImplementation(() => of({
      files: present ? [file('deep/new.txt')] : [],
    }))
    api.getNamespaceFilesManifestExchange.mockReturnValue(of({ files: [file('shared.txt')] }))
    shell['onFilterChanged']({ scope: CASE, query: 'new' })
    shell['onFilterChanged']({ scope: NAMESPACE, query: 'shared' })
    fixture.detectChanges()
    expect(shell['caseManifest']()).toEqual([])
    void state.uploadFile(CASE, new File(['x'], 'new.txt'))
    fixture.detectChanges()
    expect(shell['caseManifest']()?.map((item) => item.path)).toEqual(['deep/new.txt'])
    expect(api.getNamespaceFilesManifestExchange).toHaveBeenCalledTimes(1)
    void state.deleteFile(CASE, 'deep/new.txt')
    fixture.detectChanges()
    expect(shell['caseManifest']()).toEqual([])
    expect(api.getNamespaceFilesManifestExchange).toHaveBeenCalledTimes(1)
    expect(shell['namespaceManifest']()?.map((item) => item.path)).toEqual(['shared.txt'])
    tick()
    fixture.destroy()
  }))

  it('refreshes a filtered namespace after mutations without invalidating the case cache', fakeAsync(() => {
    const { fixture, state, shell, api } = setup()
    let present = false
    Object.assign(api, {
      uploadNamespaceFileExchange: jest.fn().mockImplementation(() => {
        present = true
        return of({})
      }),
      deleteNamespaceFileExchange: jest.fn().mockImplementation(() => {
        present = false
        return of({})
      }),
    })
    api.getCaseFilesManifestExchange.mockReturnValue(of({ files: [file('case/kept.txt')] }))
    api.getNamespaceFilesManifestExchange.mockImplementation(() => of({
      files: present ? [file('shared/added.txt')] : [],
    }))
    shell['onFilterChanged']({ scope: CASE, query: 'kept' })
    shell['onFilterChanged']({ scope: NAMESPACE, query: 'added' })
    fixture.detectChanges()
    void state.uploadFile(NAMESPACE, new File(['x'], 'added.txt'))
    fixture.detectChanges()
    expect(shell['namespaceManifest']()?.map((item) => item.path)).toEqual(['shared/added.txt'])
    expect(api.getCaseFilesManifestExchange).toHaveBeenCalledTimes(1)
    void state.deleteFile(NAMESPACE, 'shared/added.txt')
    fixture.detectChanges()
    expect(shell['namespaceManifest']()).toEqual([])
    expect(shell['caseManifest']()?.map((item) => item.path)).toEqual(['case/kept.txt'])
    expect(api.getCaseFilesManifestExchange).toHaveBeenCalledTimes(1)
    tick()
    fixture.destroy()
  }))

  it('routes filtered deep-file actions through the shell and returns to the same search', fakeAsync(() => {
    const { fixture, state, api } = setup()
    api.browseCaseFilesExchange.mockReturnValue(of({ ...listing, capability: 'READ_WRITE' }))
    api.getCaseFilesManifestExchange.mockReturnValue(of({ files: [file('deep/report.md')] }))
    state.refreshCase()
    const download = jest.spyOn(state, 'downloadFile').mockResolvedValue({ success: true })
    const remove = jest.spyOn(state, 'deleteFile').mockResolvedValue({ success: true })
    fixture.detectChanges()
    const el = fixture.nativeElement as HTMLElement
    const input = el.querySelector<HTMLInputElement>('input[aria-label="Filter case files by name or path"]')!
    input.value = 'report'
    input.dispatchEvent(new Event('input'))
    tick(150)
    fixture.detectChanges()
    el.querySelector<HTMLButtonElement>('agentos-exchange-item .exchange-item__main')!.click()
    fixture.detectChanges()
    expect(api.getCaseFileContentExchange).toHaveBeenCalledWith('c-1', 'deep/report.md')
    expect(el.textContent).toContain('body')
    el.querySelector<HTMLButtonElement>('ds-icon-button[title="Back to files"] button')!.click()
    fixture.detectChanges()
    expect(el.querySelector<HTMLInputElement>('input[aria-label="Filter case files by name or path"]')?.value).toBe('report')
    el.querySelector<HTMLButtonElement>('agentos-exchange-item ds-icon-button[icon=download] button')!.click()
    tick()
    expect(download).toHaveBeenCalledWith(CASE, 'deep/report.md')
    el.querySelector<HTMLButtonElement>('agentos-exchange-item ds-icon-button[icon=delete] button')!.click()
    fixture.detectChanges()
    expect(el.querySelector('[role=dialog]')?.textContent).toContain('report.md')
    el.querySelector<HTMLButtonElement>('.ds-confirm-dialog__button--confirm')!.click()
    tick()
    expect(remove).toHaveBeenCalledWith(CASE, 'deep/report.md')
    fixture.destroy()
  }))

  it('ignores a late namespace manifest when changing namespace, including before effects run', () => {
    const { fixture, shell, api, route } = setup()
    const old = new Subject<{ files: ReturnType<typeof file>[] }>()
    const current = new Subject<{ files: ReturnType<typeof file>[] }>()
    api.getNamespaceFilesManifestExchange.mockImplementation((id: string) => id === 'ns-1' ? old : current)
    shell['onFilterChanged']({ scope: NAMESPACE, query: 'secret' })
    fixture.detectChanges()
    route.next({ ns: 'ns-2', case: 'c-1' })
    old.next({ files: [file('old-secret.md')] })
    expect(shell['namespaceManifest']()).toBeNull()
    fixture.detectChanges()
    current.next({ files: [file('new-secret.md')] })
    fixture.detectChanges()
    expect(shell['namespaceManifest']()?.map((item) => item.path)).toEqual(['new-secret.md'])
    expect(api.getNamespaceFilesManifestExchange).toHaveBeenLastCalledWith('ns-2')
  })

  it('ignores a late namespace preview at the same path after switching namespace', () => {
    const { fixture, shell, api, route } = setup()
    const old = new Subject<{ content: string }>()
    api.getNamespaceFilesManifestExchange.mockReturnValue(of({ files: [file('deep/shared.md')] }))
    api.getNamespaceFileContentExchange.mockReturnValueOnce(old).mockReturnValue(of({ content: 'new namespace' }))
    shell['onFilterChanged']({ scope: NAMESPACE, query: 'shared' })
    fixture.detectChanges()
    shell['onFileSelected']({ scope: NAMESPACE, path: 'deep/shared.md' })
    route.next({ ns: 'ns-2', case: 'c-1' })
    fixture.detectChanges()
    shell['onFileSelected']({ scope: NAMESPACE, path: 'deep/shared.md' })
    old.next({ content: 'old namespace' })
    expect(shell['selectedContent']()).toBe('new namespace')
    expect(api.getNamespaceFileContentExchange).toHaveBeenLastCalledWith('ns-2', 'deep/shared.md')
  })

  it('keeps two loaded browse pages and the current folder through actual filter and Escape events', fakeAsync(() => {
    const { fixture, state, api } = setup()
    api.browseCaseFilesExchange.mockImplementation((_id: string, path: string, page: number) => of({
      ...listing, path, page, hasMore: page === 0, totalEntries: 2,
      entries: [{ path: `${path}/page-${page}.txt`, name: `page-${page}.txt`, directory: false, size: 1 }],
    }))
    api.getCaseFilesManifestExchange.mockReturnValue(of({ files: [file('elsewhere/needle.txt')] }))
    state.browseCase('docs')
    state.loadMore(CASE)
    fixture.detectChanges()
    const el = fixture.nativeElement as HTMLElement
    const input = el.querySelector<HTMLInputElement>('input[aria-label="Filter case files by name or path"]')!
    input.value = 'needle'
    input.dispatchEvent(new Event('input'))
    tick(150)
    fixture.detectChanges()
    expect(el.textContent).toContain('needle.txt')
    expect(el.textContent).not.toContain('page-0.txt')
    const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
    input.dispatchEvent(escape)
    fixture.detectChanges()
    expect(escape.defaultPrevented).toBe(true)
    expect(state.caseBrowsePath()).toBe('docs')
    expect(el.textContent).toContain('page-0.txt')
    expect(el.textContent).toContain('page-1.txt')
    expect(el.textContent).toContain('docs')
    fixture.destroy()
  }))

  it.each([CASE, NAMESPACE])('hides a revoked %s scope while its recursive request remains in flight', (scope) => {
    const { fixture, state, shell, api } = setup()
    const pending = new Subject<{ files: ReturnType<typeof file>[] }>()
    const browse = scope === CASE ? api.browseCaseFilesExchange : api.browseNamespaceFilesExchange
    const manifest = scope === CASE ? api.getCaseFilesManifestExchange : api.getNamespaceFilesManifestExchange
    manifest.mockReturnValue(pending)
    shell['onFilterChanged']({ scope, query: 'private' })
    fixture.detectChanges()
    browse.mockReturnValue(throwError(() => ({ status: 403 })))
    if (scope === CASE) state.refreshCase()
    else state.refreshNamespace()
    pending.next({ files: [file('private-sentinel.md')] })
    fixture.detectChanges()
    const el = fixture.nativeElement as HTMLElement
    const label = scope === CASE ? 'Filter case files by name or path' : 'Filter namespace files by name or path'
    expect(el.querySelector(`input[aria-label="${label}"]`)).toBeNull()
    expect(el.textContent).not.toContain('private-sentinel.md')
    fixture.destroy()
  })

  it.each([CASE, NAMESPACE])('closes the %s preview when browse access is revoked', (scope) => {
    const { fixture, state, shell, api } = setup()
    const getManifest = scope === CASE ? api.getCaseFilesManifestExchange : api.getNamespaceFilesManifestExchange
    const browse = scope === CASE ? api.browseCaseFilesExchange : api.browseNamespaceFilesExchange
    getManifest.mockReturnValue(of({ files: [file('deep/private.md')] }))
    shell['onFilterChanged']({ scope, query: 'private' })
    fixture.detectChanges()
    shell['onFileSelected']({ scope, path: 'deep/private.md' })
    fixture.detectChanges()
    expect(shell['selectedContent']()).toBe('body')
    browse.mockReturnValue(throwError(() => ({ status: 403 })))
    if (scope === CASE) state.refreshCase()
    else state.refreshNamespace()
    fixture.detectChanges()
    expect(shell['selectedFile']()).toBeNull()
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('body')
    fixture.destroy()
  })

  it.each([CASE, NAMESPACE])('ignores a late %s preview response after access is revoked', (scope) => {
    const { fixture, state, shell, api } = setup()
    const pending = new Subject<{ content: string }>()
    const getManifest = scope === CASE ? api.getCaseFilesManifestExchange : api.getNamespaceFilesManifestExchange
    const getContent = scope === CASE ? api.getCaseFileContentExchange : api.getNamespaceFileContentExchange
    const browse = scope === CASE ? api.browseCaseFilesExchange : api.browseNamespaceFilesExchange
    getManifest.mockReturnValue(of({ files: [file('deep/private.md')] }))
    getContent.mockReturnValue(pending)
    shell['onFilterChanged']({ scope, query: 'private' })
    fixture.detectChanges()
    shell['onFileSelected']({ scope, path: 'deep/private.md' })
    fixture.detectChanges()
    browse.mockReturnValue(throwError(() => ({ status: 403 })))
    if (scope === CASE) state.refreshCase()
    else state.refreshNamespace()
    // The response can arrive before Angular has run the revocation effect.
    pending.next({ content: 'private-sentinel' })
    expect(shell['selectedContent']()).toBeNull()
    fixture.detectChanges()
    expect(shell['selectedFile']()).toBeNull()
    expect(shell['selectedContent']()).toBeNull()
    pending.next({ content: 'private-sentinel' })
    fixture.detectChanges()
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('private-sentinel')
    fixture.destroy()
  })

  it.each([CASE, NAMESPACE])('preserves the other scope preview and filter when %s access is revoked', (scope) => {
    const { fixture, state, shell, api } = setup()
    const other = scope === CASE ? NAMESPACE : CASE
    const browse = scope === CASE ? api.browseCaseFilesExchange : api.browseNamespaceFilesExchange
    const getOtherManifest = other === CASE ? api.getCaseFilesManifestExchange : api.getNamespaceFilesManifestExchange
    getOtherManifest.mockReturnValue(of({ files: [file('deep/allowed.md')] }))
    shell['onFilterChanged']({ scope: other, query: 'allowed' })
    fixture.detectChanges()
    shell['onFileSelected']({ scope: other, path: 'deep/allowed.md' })
    fixture.detectChanges()
    browse.mockReturnValue(throwError(() => ({ status: 403 })))
    if (scope === CASE) state.refreshCase()
    else state.refreshNamespace()
    fixture.detectChanges()
    expect(shell['selectedFile']()).toEqual({ scope: other, path: 'deep/allowed.md' })
    expect(shell['selectedContent']()).toBe('body')
    expect(other === CASE ? shell['caseQuery']() : shell['namespaceQuery']()).toBe('allowed')
    fixture.destroy()
  })

  it('uses deep case manifest metadata for preview and enforces size and format limits', () => {
    const { fixture, shell, api } = setup()
    api.getCaseFilesManifestExchange.mockReturnValue(of({ files: [
      file('deep/readme.md'), file('deep/large.md', MAX_VIEWABLE_SIZE + 1), file('deep/image.png'),
    ] }))
    shell['onFilterChanged']({ scope: CASE, query: 'deep' })
    fixture.detectChanges()
    shell['onFileSelected']({ scope: CASE, path: 'deep/readme.md' })
    expect(api.getCaseFileContentExchange).toHaveBeenCalledWith('c-1', 'deep/readme.md')
    expect(shell['canViewSelected']()).toBe(true)
    shell['onFileSelected']({ scope: CASE, path: 'deep/large.md' })
    shell['onFileSelected']({ scope: CASE, path: 'deep/image.png' })
    expect(shell['canViewSelected']()).toBe(false)
    expect(api.getCaseFileContentExchange).toHaveBeenCalledTimes(1)
  })

  it('ignores a late preview for the same path after switching cases', () => {
    const { fixture, shell, api, route } = setup()
    const old = new Subject<{ content: string }>()
    api.getCaseFilesManifestExchange.mockReturnValue(of({ files: [file('deep/readme.md')] }))
    api.getCaseFileContentExchange.mockReturnValueOnce(old).mockReturnValue(of({ content: 'new' }))
    shell['onFilterChanged']({ scope: CASE, query: 'readme' })
    fixture.detectChanges()
    shell['onFileSelected']({ scope: CASE, path: 'deep/readme.md' })
    route.next({ ns: 'ns-1', case: 'c-2' })
    fixture.detectChanges()
    shell['onFileSelected']({ scope: CASE, path: 'deep/readme.md' })
    old.next({ content: 'old' })
    expect(shell['selectedContent']()).toBe('new')
  })
})
