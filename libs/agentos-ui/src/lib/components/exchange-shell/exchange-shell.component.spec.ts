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
        { provide: CaseWorkspaceService, useValue: { watch: jest.fn(), enabled$: of(false) } },
      ],
    })
    const fixture = TestBed.createComponent(ExchangeShellComponent)
    fixture.detectChanges()
    const shell = fixture.componentInstance
    const state = TestBed.inject(ExchangeStateService)
    return { fixture, shell, state, api, route }
  }

  it('reveals the namespace filter after an external file is reported by the existing refresh', () => {
    const { fixture, api, state } = setup()
    const host = fixture.nativeElement as HTMLElement
    const namespaceInput = () => host.querySelector('[data-track="ExchangeDrawer-namespaceFilterInput"]')
    expect(namespaceInput()).toBeNull()
    api.browseNamespaceFilesExchange.mockReturnValue(of({ ...listing, totalEntries: 1, entries: [{ path: 'new.txt', name: 'new.txt', directory: false }] }))
    state.refreshNamespace()
    fixture.detectChanges()
    expect(namespaceInput()).not.toBeNull()
    fixture.destroy()
  })

  it('shows both sections and owns the case state until destruction', () => {
    const { fixture, state, api } = setup()
    const clear = jest.spyOn(state, 'clear')
    const host = fixture.nativeElement as HTMLElement
    expect(api.browseCaseFilesExchange).toHaveBeenCalledWith('c-1', '', 0)
    expect(api.browseNamespaceFilesExchange).toHaveBeenCalledWith('ns-1', '', 0)
    expect(host.querySelector('[data-track="ExchangeDrawer-caseSection"]')).not.toBeNull()
    expect(host.querySelector('[data-track="ExchangeDrawer-namespaceSection"]')).not.toBeNull()
    fixture.destroy()
    expect(clear).toHaveBeenCalledTimes(1)
  })

  it('clears its case state once when leaving the case route', () => {
    const { fixture, state, route } = setup()
    const clear = jest.spyOn(state, 'clear')
    route.next({ ns: 'ns-1' })
    expect(clear).toHaveBeenCalledTimes(1)
    fixture.destroy()
    expect(clear).toHaveBeenCalledTimes(1)
  })

  it('relays normal namespace row selection in home mode, including unchecking', () => {
    const { fixture, state, api, route } = setup()
    route.next({ ns: 'ns-1' })
    api.browseNamespaceFilesExchange.mockReturnValue(of({ ...listing, totalEntries: 1, entries: [{ path: 'docs/readme.md', name: 'readme.md', directory: false, size: 1 }] }))
    state.initializeForNamespace('ns-1')
    fixture.componentRef.setInput('selectable', true)
    fixture.detectChanges()
    const host = fixture.nativeElement as HTMLElement
    const toggle = jest.fn()
    fixture.componentInstance.referenceToggled.subscribe(toggle)
    const ref = { scope: NAMESPACE, path: 'docs/readme.md' }
    const checkbox = host.querySelector<HTMLInputElement>('input[aria-label="Select readme.md"]')!
    checkbox.click()
    expect(toggle).toHaveBeenLastCalledWith({ ref, referenced: true })
    expect(checkbox.checked).toBe(false)
    fixture.componentRef.setInput('referenced', [ref])
    fixture.detectChanges()
    expect(checkbox.checked).toBe(true)
    expect(host.querySelector('.exchange-drawer__selection-bar')?.textContent).toContain('1 in message')
    checkbox.click()
    expect(toggle).toHaveBeenLastCalledWith({ ref, referenced: false })
    fixture.destroy()
  })

  it('keeps namespace-only state and relays checked filtered files in the home composer', fakeAsync(() => {
    const { fixture, state, api, route } = setup()
    route.next({ ns: 'ns-1' })
    api.browseNamespaceFilesExchange.mockReturnValue(of({ ...listing, totalEntries: 1, entries: [{ path: 'docs', name: 'docs', directory: true }] }))
    state.initializeForNamespace('ns-1')
    api.getNamespaceFilesManifestExchange.mockReturnValue(of({ files: [file('docs/guide.md')] }))
    fixture.componentRef.setInput('active', true)
    fixture.componentRef.setInput('selectable', true)
    fixture.detectChanges()
    const host = fixture.nativeElement as HTMLElement
    expect(host.querySelector('[data-track="ExchangeDrawer-caseSection"]')).toBeNull()
    const input = host.querySelector<HTMLInputElement>('[data-track="ExchangeDrawer-namespaceFilterInput"]')!
    input.value = 'guide'
    input.dispatchEvent(new Event('input'))
    tick(150)
    fixture.detectChanges()
    const event = jest.fn()
    fixture.componentInstance.referenceToggled.subscribe(event)
    const checkbox = host.querySelector<HTMLInputElement>('input[aria-label="Select guide.md"]')!
    checkbox.click()
    expect(event).toHaveBeenCalledWith({ ref: { scope: NAMESPACE, path: 'docs/guide.md' }, referenced: true })
    fixture.componentRef.setInput('referenced', [{ scope: NAMESPACE, path: 'docs/guide.md' }])
    fixture.detectChanges()
    expect(checkbox.checked).toBe(true)
    fixture.destroy()
    expect(state.namespaceStatus()).toBe('ready')
  }))

  it.each([CASE, NAMESPACE])('allows a slow %s access revalidation to complete across successive polls', fakeAsync((scope) => {
    const { fixture, shell, state, api } = setup()
    const browse = scope === CASE ? api.browseCaseFilesExchange : api.browseNamespaceFilesExchange
    const get = scope === CASE ? api.getCaseFilesManifestExchange : api.getNamespaceFilesManifestExchange
    const oldListing = { ...listing, capability: 'READ_WRITE', totalEntries: 1, entries: [{ path: 'old.txt', name: 'old.txt', directory: false }] }
    browse.mockReturnValue(of(oldListing))
    if (scope === CASE) state.refreshCase()
    else state.refreshNamespace()
    get.mockReturnValueOnce(of({ files: [file('old.txt')] }))
      .mockReturnValueOnce(throwError(() => ({ status: 404 })))
      .mockReturnValue(of({ files: [file('new.txt')] }))
    fixture.componentRef.setInput('active', true)
    shell['onFilterChanged']({ scope, query: 'txt' })
    fixture.detectChanges()
    const count = scope === CASE ? state.caseFileCount : state.namespaceFileCount
    const write = scope === CASE ? state.canWriteCase : state.canWriteNamespace
    const otherCount = scope === CASE ? state.namespaceFileCount : state.caseFileCount
    expect(count()).toBe(1)
    expect(write()).toBe(true)
    tick(3000)
    fixture.detectChanges()
    expect(count()).toBe(0)
    expect(write()).toBe(false)
    expect(state.fileCount()).toBe(otherCount())
    const pending = new Subject<typeof oldListing>()
    browse.mockReturnValue(pending)
    tick(3000)
    fixture.detectChanges()
    const calls = browse.mock.calls.length
    expect(pending.observed).toBe(true)
    for (let i = 0; i < 3; i++) { tick(3000); fixture.detectChanges() }
    expect(browse).toHaveBeenCalledTimes(calls)
    expect(pending.observed).toBe(true)
    expect(count()).toBe(0)
    pending.next({ ...oldListing, entries: [{ path: 'new.txt', name: 'new.txt', directory: false }] })
    pending.complete()
    fixture.detectChanges()
    expect(count()).toBe(1)
    expect(write()).toBe(true)
    expect(state.fileCount()).toBe(1 + otherCount())
    expect((scope === CASE ? state.caseFiles() : state.namespaceFiles())[0]?.name).toBe('new.txt')
    fixture.destroy()
  }))

  it.each([CASE, NAMESPACE])('does not unmask %s on an obsolete successful browse after a renewed manifest denial', fakeAsync((scope) => {
    const { fixture, shell, state, api } = setup()
    const browse = scope === CASE ? api.browseCaseFilesExchange : api.browseNamespaceFilesExchange
    const get = scope === CASE ? api.getCaseFilesManifestExchange : api.getNamespaceFilesManifestExchange
    const pending = new Subject<typeof listing>()
    get.mockReturnValueOnce(of({ files: [] })).mockReturnValueOnce(throwError(() => ({ status: 404 })))
      .mockReturnValueOnce(of({ files: [] })).mockReturnValueOnce(throwError(() => ({ status: 403 })))
    fixture.componentRef.setInput('active', true)
    shell['onFilterChanged']({ scope, query: 'x' })
    fixture.detectChanges()
    tick(3000); fixture.detectChanges()
    browse.mockReturnValue(pending)
    tick(3000); fixture.detectChanges()
    tick(3000); fixture.detectChanges()
    pending.next({ ...listing, capability: 'READ_WRITE', totalEntries: 1 })
    pending.complete()
    fixture.detectChanges()
    expect(scope === CASE ? state.caseBrowseUnavailable() : state.namespaceBrowseUnavailable()).toBe(true)
    expect(scope === CASE ? state.caseFileCount() : state.namespaceFileCount()).toBe(0)
    expect(scope === CASE ? state.canWriteCase() : state.canWriteNamespace()).toBe(false)
    fixture.destroy()
  }))

  it.each([CASE, NAMESPACE])('does not expose a stale %s recovery after changing context', fakeAsync((scope) => {
    const { fixture, shell, state, api, route } = setup()
    const browse = scope === CASE ? api.browseCaseFilesExchange : api.browseNamespaceFilesExchange
    const get = scope === CASE ? api.getCaseFilesManifestExchange : api.getNamespaceFilesManifestExchange
    const pending = new Subject<typeof listing>()
    get.mockReturnValueOnce(of({ files: [] })).mockReturnValueOnce(throwError(() => ({ status: 404 })))
      .mockReturnValue(of({ files: [] }))
    fixture.componentRef.setInput('active', true)
    shell['onFilterChanged']({ scope, query: 'x' })
    fixture.detectChanges()
    tick(3000); fixture.detectChanges()
    browse.mockReturnValueOnce(pending).mockReturnValue(of({ ...listing, totalEntries: 2 }))
    tick(3000); fixture.detectChanges()
    route.next({ ns: 'ns-2', case: 'c-2' })
    fixture.detectChanges()
    pending.next({ ...listing, capability: 'READ_WRITE', totalEntries: 99 })
    pending.complete()
    fixture.detectChanges()
    expect(scope === CASE ? state.caseFileCount() : state.namespaceFileCount()).toBe(2)
    expect(scope === CASE ? state.canWriteCase() : state.canWriteNamespace()).toBe(false)
    fixture.destroy()
  }))

  it('does not expose the old case listing when namespace changes with the same case id', fakeAsync(() => {
    const { fixture, state, api, route } = setup()
    const pending = new Subject<typeof listing>()
    api.browseCaseFilesExchange.mockReturnValue(of({ ...listing, capability: 'READ_WRITE', totalEntries: 7 }))
    state.refreshCase()
    expect(state.caseFileCount()).toBe(7)
    api.browseCaseFilesExchange.mockReturnValue(pending)
    route.next({ ns: 'ns-2', case: 'c-1' })
    fixture.detectChanges()
    expect(state.caseFileCount()).toBe(0)
    expect(state.canWriteCase()).toBe(false)
    pending.next({ ...listing, totalEntries: 2 })
    fixture.detectChanges()
    expect(state.caseFileCount()).toBe(2)
    fixture.destroy()
  }))

  it.each([CASE, NAMESPACE])('does not clear shared %s counts or rights for a manifest network error', fakeAsync((scope) => {
    const { fixture, shell, state, api } = setup()
    const browse = scope === CASE ? api.browseCaseFilesExchange : api.browseNamespaceFilesExchange
    const get = scope === CASE ? api.getCaseFilesManifestExchange : api.getNamespaceFilesManifestExchange
    browse.mockReturnValue(of({ ...listing, capability: 'READ_WRITE', totalEntries: 4 }))
    if (scope === CASE) state.refreshCase()
    else state.refreshNamespace()
    get.mockReturnValueOnce(of({ files: [] })).mockReturnValueOnce(throwError(() => ({ status: 500 })))
    fixture.componentRef.setInput('active', true)
    shell['onFilterChanged']({ scope, query: 'x' })
    fixture.detectChanges()
    tick(3000); fixture.detectChanges()
    expect(scope === CASE ? state.caseBrowseUnavailable() : state.namespaceBrowseUnavailable()).toBe(false)
    expect(scope === CASE ? state.caseFileCount() : state.namespaceFileCount()).toBe(4)
    expect(scope === CASE ? state.canWriteCase() : state.canWriteNamespace()).toBe(true)
    fixture.destroy()
  }))

  it.each([CASE, NAMESPACE])('times out blocked %s scans, throttles repeated invalidations, and recovers in a new context', fakeAsync((scope) => {
    const { fixture, shell, state, api, route } = setup()
    const stuck = new Subject<{ files: ReturnType<typeof file>[] }>()
    const get = scope === CASE ? api.getCaseFilesManifestExchange : api.getNamespaceFilesManifestExchange
    get.mockReturnValueOnce(stuck).mockReturnValue(of({ files: [file('fresh.txt')] }))
    fixture.componentRef.setInput('active', true)
    shell['onFilterChanged']({ scope, query: 'txt' })
    fixture.detectChanges()
    for (let i = 0; i < 5; i++) {
      state.invalidateSearchManifest(scope)
      fixture.detectChanges()
      tick(2000)
    }
    route.next({ ns: 'ns-2', case: 'c-2' })
    fixture.detectChanges()
    tick(5000)
    fixture.detectChanges()
    expect(get).toHaveBeenCalledTimes(1)
    expect(stuck.observed).toBe(false)
    stuck.next({ files: [file('stale.txt')] })
    tick(14999)
    fixture.detectChanges()
    expect(get).toHaveBeenCalledTimes(1)
    tick(1)
    fixture.detectChanges()
    expect(get).toHaveBeenCalledTimes(2)
    expect(get).toHaveBeenLastCalledWith(scope === CASE ? 'c-2' : 'ns-2')
    const result = scope === CASE ? shell['caseManifest'] : shell['namespaceManifest']
    expect(result()?.[0]?.path).toBe('fresh.txt')
    stuck.next({ files: [file('stale.txt')] })
    expect(result()?.[0]?.path).toBe('fresh.txt')
    fixture.destroy()
  }))

  it.each([CASE, NAMESPACE])('recovers an active %s search after a timeout without an invalidation', fakeAsync((scope) => {
    const { fixture, shell, api } = setup()
    const stuck = new Subject<{ files: ReturnType<typeof file>[] }>()
    const get = scope === CASE ? api.getCaseFilesManifestExchange : api.getNamespaceFilesManifestExchange
    get.mockReturnValueOnce(stuck).mockReturnValue(of({ files: [file('recovered.txt')] }))
    fixture.componentRef.setInput('active', true)
    shell['onFilterChanged']({ scope, query: 'txt' })
    fixture.detectChanges()
    tick(15000)
    fixture.detectChanges()
    const status = scope === CASE ? shell['caseManifestStatus'] : shell['namespaceManifestStatus']
    // A polling tick at the timeout boundary may already queue the throttled retry.
    expect(['error', 'loading']).toContain(status())
    tick(14999)
    fixture.detectChanges()
    expect(get).toHaveBeenCalledTimes(1)
    tick(1)
    fixture.detectChanges()
    expect(get.mock.calls.length).toBeGreaterThanOrEqual(2)
    expect(get.mock.calls.length).toBeLessThanOrEqual(3)
    expect(status()).toBe('ready')
    stuck.next({ files: [file('late.txt')] })
    const result = scope === CASE ? shell['caseManifest'] : shell['namespaceManifest']
    expect(result()?.[0]?.path).toBe('recovered.txt')
    fixture.destroy()
  }))

  it.each([CASE, NAMESPACE])('hides old %s listing and actions on 404 and reloads it after access returns', fakeAsync((scope) => {
    const { fixture, shell, api } = setup()
    const get = scope === CASE ? api.getCaseFilesManifestExchange : api.getNamespaceFilesManifestExchange
    const browse = scope === CASE ? api.browseCaseFilesExchange : api.browseNamespaceFilesExchange
    browse.mockReturnValue(of({ ...listing, capability: 'READ_WRITE', entries: [{ path: 'old.txt', name: 'old.txt', directory: false }], totalEntries: 1 }))
    if (scope === CASE) TestBed.inject(ExchangeStateService).refreshCase()
    else TestBed.inject(ExchangeStateService).refreshNamespace()
    get.mockReturnValueOnce(of({ files: [file('old.txt')] })).mockReturnValueOnce(throwError(() => ({ status: 404 })))
      .mockReturnValue(of({ files: [file('new.txt')] }))
    fixture.componentRef.setInput('active', true)
    shell['onFilterChanged']({ scope, query: 'txt' })
    fixture.detectChanges()
    tick(3000)
    fixture.detectChanges()
    const host = fixture.nativeElement as HTMLElement
    const section = host.querySelector(`[data-track="ExchangeDrawer-${scope === CASE ? 'case' : 'namespace'}Section"]`)!
    expect(section.textContent).not.toContain('old.txt')
    expect(section.querySelector('agentos-exchange-file-list')).toBeNull()
    expect(section.querySelector('ds-icon-button[icon="upload_file"]')).toBeNull()
    expect(section.querySelector('ds-icon-button[icon="download"]')).toBeNull()
    browse.mockReturnValue(of({ ...listing, capability: 'READ', entries: [{ path: 'new.txt', name: 'new.txt', directory: false }], totalEntries: 1 }))
    tick(3000)
    fixture.detectChanges()
    shell['onFilterChanged']({ scope, query: '' })
    fixture.detectChanges()
    expect(section.textContent).toContain('new.txt')
    expect(section.textContent).not.toContain('old.txt')
    fixture.destroy()
  }))

  it.each([CASE, NAMESPACE])('keeps %s hidden if the manifest succeeds but browse revalidation fails', fakeAsync((scope) => {
    const { fixture, shell, api } = setup()
    const get = scope === CASE ? api.getCaseFilesManifestExchange : api.getNamespaceFilesManifestExchange
    const browse = scope === CASE ? api.browseCaseFilesExchange : api.browseNamespaceFilesExchange
    get.mockReturnValueOnce(of({ files: [file('old.txt')] })).mockReturnValueOnce(throwError(() => ({ status: 403 })))
      .mockReturnValue(of({ files: [file('new.txt')] }))
    fixture.componentRef.setInput('active', true)
    shell['onFilterChanged']({ scope, query: 'txt' })
    fixture.detectChanges()
    tick(3000)
    fixture.detectChanges()
    browse.mockReturnValue(throwError(() => ({ status: 500 })))
    tick(3000)
    fixture.detectChanges()
    expect(scope === CASE ? shell['caseBrowseUnavailable']() : shell['namespaceBrowseUnavailable']()).toBe(true)
    shell['onFilterChanged']({ scope, query: '' })
    fixture.detectChanges()
    shell['onFileSelected']({ scope, path: 'old.txt' })
    expect(shell['selectedFile']()).toBeNull()
    browse.mockReturnValue(of({ ...listing, entries: [{ path: 'new.txt', name: 'new.txt', directory: false }], totalEntries: 1 }))
    shell['onRetry']()
    fixture.detectChanges()
    expect(scope === CASE ? shell['caseBrowseUnavailable']() : shell['namespaceBrowseUnavailable']()).toBe(false)
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('new.txt')
    fixture.destroy()
  }))

  it.each([CASE, NAMESPACE])('retains a recovery control after %s browse returns 404 and resumes polling', fakeAsync((scope) => {
    const { fixture, shell, api } = setup()
    const get = scope === CASE ? api.getCaseFilesManifestExchange : api.getNamespaceFilesManifestExchange
    const browse = scope === CASE ? api.browseCaseFilesExchange : api.browseNamespaceFilesExchange
    get.mockReturnValueOnce(of({ files: [file('old.txt')] })).mockReturnValueOnce(throwError(() => ({ status: 404 })))
      .mockReturnValue(of({ files: [file('new.txt')] }))
    fixture.componentRef.setInput('active', true)
    shell['onFilterChanged']({ scope, query: 'txt' })
    fixture.detectChanges()
    tick(3000)
    fixture.detectChanges()
    browse.mockReturnValue(throwError(() => ({ status: 404 })))
    tick(3000)
    fixture.detectChanges()
    const host = fixture.nativeElement as HTMLElement
    const section = host.querySelector(`[data-track="ExchangeDrawer-${scope === CASE ? 'case' : 'namespace'}Section"]`)
    expect(section?.querySelector('[role=alert] button')).not.toBeNull()
    expect(section?.textContent).not.toContain('old.txt')
    browse.mockReturnValue(of({ ...listing, entries: [{ path: 'new.txt', name: 'new.txt', directory: false }], totalEntries: 1 }))
    tick(3000)
    fixture.detectChanges()
    expect(scope === CASE ? shell['caseBrowseUnavailable']() : shell['namespaceBrowseUnavailable']()).toBe(false)
    fixture.destroy()
  }))

  it('polls both visible active filters for external additions and deletions without reloading browse pages', fakeAsync(() => {
    const { fixture, shell, api } = setup()
    let caseFiles = [file('case/old.txt')]
    let namespaceFiles = [file('shared/old.txt')]
    api.getCaseFilesManifestExchange.mockImplementation(() => of({ files: caseFiles }))
    api.getNamespaceFilesManifestExchange.mockImplementation(() => of({ files: namespaceFiles }))
    fixture.componentRef.setInput('active', true)
    shell['onFilterChanged']({ scope: CASE, query: 'txt' })
    shell['onFilterChanged']({ scope: NAMESPACE, query: 'txt' })
    fixture.detectChanges()
    const caseBrowseCalls = api.browseCaseFilesExchange.mock.calls.length
    const namespaceBrowseCalls = api.browseNamespaceFilesExchange.mock.calls.length
    caseFiles = [file('case/new.txt')]
    namespaceFiles = [file('shared/new.txt')]
    tick(2999)
    expect(api.getCaseFilesManifestExchange).toHaveBeenCalledTimes(1)
    expect(api.getNamespaceFilesManifestExchange).toHaveBeenCalledTimes(1)
    tick(1)
    fixture.detectChanges()
    expect(shell['caseManifest']()?.map((item) => item.path)).toEqual(['case/new.txt'])
    expect(shell['namespaceManifest']()?.map((item) => item.path)).toEqual(['shared/new.txt'])
    expect(api.browseCaseFilesExchange).toHaveBeenCalledTimes(caseBrowseCalls)
    expect(api.browseNamespaceFilesExchange).toHaveBeenCalledTimes(namespaceBrowseCalls)
    fixture.destroy()
  }))

  it.each([CASE, NAMESPACE])('does not overlap slow %s polls and retries after a network error', fakeAsync((scope) => {
    const { fixture, shell, api } = setup()
    const pending = new Subject<{ files: ReturnType<typeof file>[] }>()
    const get = scope === CASE ? api.getCaseFilesManifestExchange : api.getNamespaceFilesManifestExchange
    get.mockReturnValueOnce(of({ files: [file('old.txt')] })).mockReturnValueOnce(pending)
      .mockReturnValueOnce(throwError(() => new Error('network'))).mockReturnValue(of({ files: [file('new.txt')] }))
    fixture.componentRef.setInput('active', true)
    shell['onFilterChanged']({ scope, query: 'txt' })
    fixture.detectChanges()
    tick(3000)
    fixture.detectChanges()
    const result = scope === CASE ? shell['caseManifest'] : shell['namespaceManifest']
    expect(result()?.[0]?.path).toBe('old.txt')
    tick(9000)
    fixture.detectChanges()
    expect(get).toHaveBeenCalledTimes(2)
    pending.next({ files: [file('changed.txt')] })
    tick(3000)
    fixture.detectChanges()
    expect(get).toHaveBeenCalledTimes(2)
    pending.complete()
    fixture.detectChanges()
    expect(result()?.[0]?.path).toBe('changed.txt')
    tick(3000)
    fixture.detectChanges()
    expect(get).toHaveBeenCalledTimes(3)
    tick(3000)
    fixture.detectChanges()
    expect(result()?.[0]?.path).toBe('new.txt')
    fixture.destroy()
  }))

  it.each([CASE, NAMESPACE])('rejects an obsolete %s scan after immediate invalidation', fakeAsync((scope) => {
    const { fixture, shell, state, api } = setup()
    const pending = new Subject<{ files: ReturnType<typeof file>[] }>()
    const get = scope === CASE ? api.getCaseFilesManifestExchange : api.getNamespaceFilesManifestExchange
    get.mockReturnValueOnce(of({ files: [file('old.txt')] })).mockReturnValueOnce(pending)
      .mockReturnValue(of({ files: [file('fresh.txt')] }))
    fixture.componentRef.setInput('active', true)
    shell['onFilterChanged']({ scope, query: 'txt' })
    fixture.detectChanges()
    tick(3000)
    fixture.detectChanges()
    expect(pending.observed).toBe(true)
    if (scope === CASE) state.refreshCase()
    else state.refreshNamespace()
    pending.next({ files: [file('stale.txt')] })
    fixture.detectChanges()
    expect(get).toHaveBeenCalledTimes(2)
    pending.complete()
    fixture.detectChanges()
    const result = scope === CASE ? shell['caseManifest'] : shell['namespaceManifest']
    expect(result()?.[0]?.path).toBe('fresh.txt')
    fixture.destroy()
  }))

  it.each([CASE, NAMESPACE])('ignores an old %s poll after context changes before the effect runs', fakeAsync((scope) => {
    const { fixture, shell, api, route } = setup()
    const pending = new Subject<{ files: ReturnType<typeof file>[] }>()
    const get = scope === CASE ? api.getCaseFilesManifestExchange : api.getNamespaceFilesManifestExchange
    get.mockReturnValueOnce(of({ files: [file('old.txt')] })).mockReturnValueOnce(pending)
      .mockReturnValue(of({ files: [file('fresh.txt')] }))
    fixture.componentRef.setInput('active', true)
    shell['onFilterChanged']({ scope, query: 'txt' })
    fixture.detectChanges()
    tick(3000)
    fixture.detectChanges()
    route.next({ ns: 'ns-2', case: 'c-2' })
    pending.next({ files: [file('stale.txt')] })
    fixture.detectChanges()
    expect(get).toHaveBeenCalledTimes(2)
    pending.complete()
    fixture.detectChanges()
    const result = scope === CASE ? shell['caseManifest'] : shell['namespaceManifest']
    expect(result()?.[0]?.path).toBe('fresh.txt')
    fixture.destroy()
  }))

  it('stops polling on clear, close, revoked access, context change and destruction', fakeAsync(() => {
    const { fixture, shell, api, route } = setup()
    fixture.componentRef.setInput('active', true)
    shell['onFilterChanged']({ scope: CASE, query: 'x' })
    shell['onFilterChanged']({ scope: NAMESPACE, query: 'x' })
    fixture.detectChanges()
    shell['onFilterChanged']({ scope: CASE, query: '' })
    fixture.detectChanges()
    tick(3000)
    fixture.detectChanges()
    expect(api.getCaseFilesManifestExchange).toHaveBeenCalledTimes(1)
    expect(api.getNamespaceFilesManifestExchange).toHaveBeenCalledTimes(2)
    fixture.componentRef.setInput('active', false)
    fixture.detectChanges()
    tick(9000)
    fixture.detectChanges()
    expect(api.getNamespaceFilesManifestExchange).toHaveBeenCalledTimes(2)
    fixture.componentRef.setInput('active', true)
    fixture.detectChanges()
    route.next({})
    tick(3000)
    fixture.detectChanges()
    expect(api.getNamespaceFilesManifestExchange).toHaveBeenCalledTimes(2)
    route.next({ ns: 'ns-2', case: 'c-2' })
    fixture.detectChanges()
    expect(api.getNamespaceFilesManifestExchange).toHaveBeenLastCalledWith('ns-2')
    api.browseNamespaceFilesExchange.mockReturnValue(throwError(() => ({ status: 403 })))
    TestBed.inject(ExchangeStateService).refreshNamespace()
    fixture.detectChanges()
    const calls = api.getNamespaceFilesManifestExchange.mock.calls.length
    tick(6000)
    fixture.detectChanges()
    expect(api.getNamespaceFilesManifestExchange).toHaveBeenCalledTimes(calls)
    fixture.destroy()
    tick(6000)
    expect(api.getNamespaceFilesManifestExchange).toHaveBeenCalledTimes(calls)
  }))

  it.each([CASE, NAMESPACE])('coalesces repeated invalidations during a blocked %s scan', fakeAsync((scope) => {
    const { fixture, shell, state, api } = setup()
    const pending = new Subject<{ files: ReturnType<typeof file>[] }>()
    const get = scope === CASE ? api.getCaseFilesManifestExchange : api.getNamespaceFilesManifestExchange
    get.mockReturnValueOnce(pending).mockReturnValue(of({ files: [file('fresh.txt')] }))
    fixture.componentRef.setInput('active', true)
    shell['onFilterChanged']({ scope, query: 'txt' })
    fixture.detectChanges()
    for (let i = 0; i < 10; i++) {
      state.invalidateSearchManifest(scope)
      fixture.detectChanges()
      tick(1000)
    }
    expect(get).toHaveBeenCalledTimes(1)
    pending.next({ files: [file('stale.txt')] })
    pending.complete()
    fixture.detectChanges()
    const result = scope === CASE ? shell['caseManifest'] : shell['namespaceManifest']
    expect(get).toHaveBeenCalledTimes(2)
    expect(result()?.[0]?.path).toBe('fresh.txt')
    fixture.destroy()
  }))

  it.each([CASE, NAMESPACE])('does not launch a queued %s scan after destruction', fakeAsync((scope) => {
    const { fixture, shell, state, api } = setup()
    const pending = new Subject<{ files: ReturnType<typeof file>[] }>()
    const get = scope === CASE ? api.getCaseFilesManifestExchange : api.getNamespaceFilesManifestExchange
    get.mockReturnValueOnce(pending).mockReturnValue(of({ files: [] }))
    shell['onFilterChanged']({ scope, query: 'x' })
    fixture.detectChanges()
    state.invalidateSearchManifest(scope)
    fixture.detectChanges()
    fixture.destroy()
    pending.next({ files: [] })
    pending.complete()
    tick(6000)
    expect(get).toHaveBeenCalledTimes(1)
  }))

  it.each([CASE, NAMESPACE])('drops queued %s work when the search is cleared before the scan completes', fakeAsync((scope) => {
    const { fixture, shell, state, api } = setup()
    const pending = new Subject<{ files: ReturnType<typeof file>[] }>()
    const get = scope === CASE ? api.getCaseFilesManifestExchange : api.getNamespaceFilesManifestExchange
    get.mockReturnValueOnce(pending).mockReturnValue(of({ files: [] }))
    shell['onFilterChanged']({ scope, query: 'x' })
    fixture.detectChanges()
    state.invalidateSearchManifest(scope)
    fixture.detectChanges()
    shell['onFilterChanged']({ scope, query: '' })
    fixture.detectChanges()
    pending.next({ files: [] })
    pending.complete()
    expect(get).toHaveBeenCalledTimes(1)
    fixture.destroy()
  }))

  it.each([CASE, NAMESPACE])('retains the other scope preview on a polled %s access failure', fakeAsync((scope) => {
    const { fixture, shell, api } = setup()
    const get = scope === CASE ? api.getCaseFilesManifestExchange : api.getNamespaceFilesManifestExchange
    const other = scope === CASE ? NAMESPACE : CASE
    const otherGet = other === CASE ? api.getCaseFilesManifestExchange : api.getNamespaceFilesManifestExchange
    get.mockReturnValueOnce(of({ files: [] })).mockReturnValueOnce(throwError(() => ({ status: 403 })))
    otherGet.mockReturnValue(of({ files: [file('other.txt')] }))
    fixture.componentRef.setInput('active', true)
    shell['onFilterChanged']({ scope, query: 'x' })
    shell['onFilterChanged']({ scope: other, query: 'other' })
    fixture.detectChanges()
    shell['onFileSelected']({ scope: other, path: 'other.txt' })
    fixture.detectChanges()
    tick(3000)
    fixture.detectChanges()
    expect(shell['selectedContent']()).toBe('body')
    expect(shell['selectedFile']()?.scope).toBe(other)
    fixture.destroy()
  }))

  it.each([CASE, NAMESPACE])('ignores a late content response after a polled %s access failure', fakeAsync((scope) => {
    const { fixture, shell, api } = setup()
    const get = scope === CASE ? api.getCaseFilesManifestExchange : api.getNamespaceFilesManifestExchange
    const getContent = scope === CASE ? api.getCaseFileContentExchange : api.getNamespaceFileContentExchange
    const content = new Subject<{ content: string }>()
    get.mockReturnValueOnce(of({ files: [file('private.txt')] })).mockReturnValueOnce(throwError(() => ({ status: 404 })))
    getContent.mockReturnValue(content)
    fixture.componentRef.setInput('active', true)
    shell['onFilterChanged']({ scope, query: 'private' })
    fixture.detectChanges()
    shell['onFileSelected']({ scope, path: 'private.txt' })
    fixture.detectChanges()
    tick(3000)
    fixture.detectChanges()
    content.next({ content: 'late private content' })
    expect(shell['selectedContent']()).toBeNull()
    fixture.destroy()
  }))

  it.each([CASE, NAMESPACE])('closes only the affected preview on a polled %s access failure', fakeAsync((scope) => {
    const { fixture, shell, api } = setup()
    const get = scope === CASE ? api.getCaseFilesManifestExchange : api.getNamespaceFilesManifestExchange
    const other = scope === CASE ? NAMESPACE : CASE
    get.mockReturnValueOnce(of({ files: [file('private.txt')] })).mockReturnValueOnce(throwError(() => ({ status: 404 })))
    fixture.componentRef.setInput('active', true)
    shell['onFilterChanged']({ scope, query: 'private' })
    fixture.detectChanges()
    shell['onFileSelected']({ scope, path: 'private.txt' })
    fixture.detectChanges()
    expect(shell['selectedContent']()).toBe('body')
    tick(3000)
    fixture.detectChanges()
    expect(shell['selectedFile']()).toBeNull()
    expect(shell['selectedContent']()).toBeNull()
    shell['onFileSelected']({ scope, path: 'private.txt' })
    expect(shell['selectedFile']()).toBeNull()
    shell['onFileSelected']({ scope: other, path: 'other.txt' })
    expect(shell['selectedFile']()?.scope).toBe(other)
    fixture.destroy()
  }))

  it.each([403, 404, 500, 0])('handles polled manifest status %i without misclassifying network errors', fakeAsync((status) => {
    const { fixture, shell, api } = setup()
    api.getCaseFilesManifestExchange.mockReturnValueOnce(of({ files: [file('private.txt')] }))
      .mockReturnValueOnce(throwError(() => ({ status })))
    fixture.componentRef.setInput('active', true)
    shell['onFilterChanged']({ scope: CASE, query: 'private' })
    fixture.detectChanges()
    shell['onFileSelected']({ scope: CASE, path: 'private.txt' })
    fixture.detectChanges()
    tick(3000)
    fixture.detectChanges()
    expect(shell['selectedContent']()).toBe(status === 403 || status === 404 ? null : 'body')
    fixture.destroy()
  }))

  it('loads only active scopes, caches independently and retries errors', fakeAsync(() => {
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
    tick(3000)
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
    fixture.destroy()
  }))

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
    old.complete()
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
    old.next({ files: [file('private-a.txt')] })
    old.complete()
    current.next({ files: [file('private-b.txt')] })
    fixture.detectChanges()
    old.next({ files: [file('private-a.txt')] })
    fixture.detectChanges()
    expect(shell['caseManifest']()?.map((item) => item.path)).toEqual(['private-b.txt'])
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('private-a.txt')
    fixture.destroy()
  })

  it.each([CASE, NAMESPACE])('routes %s search failure and retry through the drawer', fakeAsync((scope) => {
    const { fixture, api, state } = setup()
    if (scope === NAMESPACE) {
      api.browseNamespaceFilesExchange.mockReturnValue(of({ ...listing, entries: [{ path: 'root.txt', name: 'root.txt', directory: false }], totalEntries: 1 }))
      state.refreshNamespace()
      fixture.detectChanges()
    }
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
    tick(3000)
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
    old.complete()
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
    expect(api.getCaseFileContentExchange).toHaveBeenCalledTimes(1)
  })

  it.each([CASE, NAMESPACE])('previews and revokes an image URL for %s', () => {
    const { fixture, shell, state } = setup()
    const create = jest.spyOn(URL, 'createObjectURL').mockReturnValue('blob:preview')
    const revoke = jest.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
    jest.spyOn(state, 'getBlob').mockReturnValue(of(new Blob(['image'], { type: 'image/png' })))
    const ref = { scope, path: 'deep/image.png' }
    if (scope === CASE) shell['caseManifest'].set([file(ref.path)])
    else shell['namespaceManifest'].set([{ ...file(ref.path), scope: NAMESPACE }])
    shell['onFileSelected'](ref)
    expect(state.getBlob).toHaveBeenCalledWith(scope, ref.path)
    expect(shell['selectedImageUrl']()).toBe('blob:preview')
    shell['onViewerClosed']()
    expect(revoke).toHaveBeenCalledWith('blob:preview')
    fixture.destroy()
    create.mockRestore()
    revoke.mockRestore()
  })

  it.each([CASE, NAMESPACE])('ignores stale %s image responses after a newer selection of the same path', () => {
    const { fixture, shell, state } = setup()
    const old = new Subject<Blob>()
    const create = jest.spyOn(URL, 'createObjectURL').mockReturnValue('blob:new')
    const revoke = jest.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
    jest.spyOn(state, 'getBlob').mockReturnValueOnce(old).mockReturnValue(of(new Blob(['new'], { type: 'image/png' })))
    const ref = { scope, path: 'image.png' }
    if (scope === CASE) shell['caseManifest'].set([file(ref.path)])
    else shell['namespaceManifest'].set([{ ...file(ref.path), scope: NAMESPACE }])
    shell['onFileSelected'](ref)
    shell['onFileSelected'](ref)
    old.next(new Blob(['old'], { type: 'image/png' }))
    expect(create).toHaveBeenCalledTimes(1)
    expect(shell['selectedImageUrl']()).toBe('blob:new')
    fixture.destroy()
    create.mockRestore()
    revoke.mockRestore()
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
