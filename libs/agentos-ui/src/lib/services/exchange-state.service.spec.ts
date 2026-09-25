import { TestBed } from '@angular/core/testing'
import {
  ExchangeControllerService,
  ExchangeDirectoryEntry,
  ExchangeDirectoryListing,
  ExchangeDirectoryListingCapabilityEnum,
  ExchangeFileEntryScopeEnum,
} from '@whoz-oss/agentos-api-client'
import { of, Subject, throwError } from 'rxjs'
import { ExchangeStateService } from './exchange-state.service'
import { CaseWorkspaceService } from './case-workspace.service'

describe('ExchangeStateService', () => {
  let controller: {
    browseCaseFilesExchange: jest.Mock
    browseNamespaceFilesExchange: jest.Mock
    uploadCaseFileExchange: jest.Mock
    deleteCaseFileExchange: jest.Mock
    uploadNamespaceFileExchange: jest.Mock
    deleteNamespaceFileExchange: jest.Mock
    downloadCaseFileExchange: jest.Mock
    downloadNamespaceFileExchange: jest.Mock
    getCaseFilesManifestExchange: jest.Mock
    getNamespaceFilesManifestExchange: jest.Mock
  }
  let service: ExchangeStateService
  let workspaces: { watch: jest.Mock }

  const caseFile: ExchangeDirectoryEntry = {
    path: 'a.txt',
    name: 'a.txt',
    size: 10,
    lastModified: '2026-01-01T00:00:00Z',
    directory: false,
  }
  const nsFile: ExchangeDirectoryEntry = {
    path: 'shared.md',
    name: 'shared.md',
    size: 20,
    lastModified: '2026-01-02T00:00:00Z',
    directory: false,
  }

  /**
   * One directory level, as the browse endpoints return it. `totalEntries` counts this level
   * only: the counts the service exposes describe what is being browsed, not the whole tree.
   */
  function listing(
    capability: ExchangeDirectoryListingCapabilityEnum,
    entries: ExchangeDirectoryEntry[] = []
  ): ExchangeDirectoryListing {
    return { path: '', entries, totalEntries: entries.length, page: 0, pageSize: 100, hasMore: false, capability }
  }

  function init(): void {
    service.initializeForCase('ns-1', 'c-1')
  }

  beforeEach(() => {
    controller = {
      browseCaseFilesExchange: jest.fn().mockReturnValue(of(listing(ExchangeDirectoryListingCapabilityEnum.NONE))),
      browseNamespaceFilesExchange: jest.fn().mockReturnValue(of(listing(ExchangeDirectoryListingCapabilityEnum.NONE))),
      uploadCaseFileExchange: jest.fn(),
      deleteCaseFileExchange: jest.fn(),
      uploadNamespaceFileExchange: jest.fn(),
      deleteNamespaceFileExchange: jest.fn(),
      downloadCaseFileExchange: jest.fn(),
      downloadNamespaceFileExchange: jest.fn(),
      getCaseFilesManifestExchange: jest.fn(),
      getNamespaceFilesManifestExchange: jest.fn(),
    }
    workspaces = { watch: jest.fn().mockReturnValue(of({ view: { equipped: false } })) }
    TestBed.configureTestingModule({
      providers: [
        ExchangeStateService,
        { provide: ExchangeControllerService, useValue: controller },
        { provide: CaseWorkspaceService, useValue: workspaces },
      ],
    })
    service = TestBed.inject(ExchangeStateService)
  })

  describe('download', () => {
    it('resolves { success: false } with the error when the request fails (no silent swallow)', async () => {
      controller.downloadCaseFileExchange.mockReturnValue(throwError(() => ({ message: 'network down' })))
      init()

      const result = await service.downloadFile(ExchangeFileEntryScopeEnum.CASE, 'a.txt')

      expect(result.success).toBe(false)
      expect(result.error).toBe('network down')
    })
  })

  describe('case scope capability mapping', () => {
    it('READ_WRITE → ready, files listed, write allowed, section visible', () => {
      controller.browseCaseFilesExchange.mockReturnValue(
        of(listing(ExchangeDirectoryListingCapabilityEnum.READ_WRITE, [caseFile]))
      )
      init()
      expect(service.caseStatus()).toBe('ready')
      expect(service.caseFiles()).toEqual([caseFile])
      expect(service.canWriteCase()).toBe(true)
      expect(service.caseSectionVisible()).toBe(true)
      expect(workspaces.watch).not.toHaveBeenCalled()
    })

    it('READ → read-only (no write), section visible', () => {
      controller.browseCaseFilesExchange.mockReturnValue(
        of(listing(ExchangeDirectoryListingCapabilityEnum.READ, [caseFile]))
      )
      init()
      expect(service.caseStatus()).toBe('ready')
      expect(service.canWriteCase()).toBe(false)
      expect(service.caseSectionVisible()).toBe(true)
    })
  })

  describe('fail-closed', () => {
    it('403 → forbidden: section hidden, NONE capability, empty list', () => {
      controller.browseCaseFilesExchange.mockReturnValue(throwError(() => ({ status: 403 })))
      init()
      expect(service.caseStatus()).toBe('forbidden')
      expect(service.caseSectionVisible()).toBe(false)
      expect(service.canWriteCase()).toBe(false)
      expect(service.caseFiles()).toEqual([])
    })

    it('404 → forbidden (zero disclosure)', () => {
      controller.browseNamespaceFilesExchange.mockReturnValue(throwError(() => ({ status: 404 })))
      init()
      expect(service.namespaceStatus()).toBe('forbidden')
      expect(service.namespaceSectionVisible()).toBe(false)
    })

    it('500 → error: section still visible, but no write affordance', () => {
      controller.browseCaseFilesExchange.mockReturnValue(throwError(() => ({ status: 500 })))
      init()
      expect(service.caseStatus()).toBe('error')
      expect(service.caseSectionVisible()).toBe(true)
      expect(service.canWriteCase()).toBe(false)
    })
  })

  describe('worktree preparation', () => {
    it('uses the shared workspace stream until ready, then loads files and releases its subscription', () => {
      const states = new Subject<{ view: { equipped: boolean; status: string } }>()
      workspaces.watch.mockReturnValue(states)
      controller.browseCaseFilesExchange
        .mockReturnValueOnce(throwError(() => ({ status: 409 })))
        .mockReturnValue(of(listing(ExchangeDirectoryListingCapabilityEnum.READ_WRITE, [caseFile])))
      init()
      states.next({ view: { equipped: true, status: 'REQUESTED' } })
      expect(service.caseStatus()).toBe('preparing')
      expect(service.canWriteCase()).toBe(false)
      expect(service.caseFiles()).toEqual([])
      states.next({ view: { equipped: true, status: 'PREPARING' } })
      expect(controller.browseCaseFilesExchange).toHaveBeenCalledTimes(1)
      states.next({ view: { equipped: true, status: 'READY' } })
      expect(service.caseStatus()).toBe('ready')
      expect(service.caseFiles()).toEqual([caseFile])
      expect(service.canWriteCase()).toBe(true)
      expect(workspaces.watch).toHaveBeenCalledTimes(1)
      expect(states.observed).toBe(false)
      expect(controller.browseCaseFilesExchange).toHaveBeenCalledTimes(2)
    })

    it('stops waiting and shows an error if preparation fails', () => {
      const states = new Subject<{ view: { equipped: boolean; status: string } }>()
      workspaces.watch.mockReturnValue(states)
      controller.browseCaseFilesExchange.mockReturnValue(throwError(() => ({ status: 409 })))
      init()
      states.next({ view: { equipped: true, status: 'PREPARING' } })
      states.next({ view: { equipped: true, status: 'FAILED' } })
      expect(service.caseStatus()).toBe('error')
      expect(service.canWriteCase()).toBe(false)
      expect(states.observed).toBe(false)
    })

    it.each(['FAILED', 'DELETING', 'UNKNOWN'])('does not mistake %s for preparation', (status) => {
      controller.browseCaseFilesExchange.mockReturnValue(throwError(() => ({ status: 409 })))
      workspaces.watch.mockReturnValue(of({ view: { equipped: true, status } }))
      init()
      expect(service.caseStatus()).toBe('error')
      expect(service.canWriteCase()).toBe(false)
      expect(workspaces.watch).toHaveBeenCalledTimes(1)
    })

    it('retries once if readiness wins the race, without looping on another conflict', () => {
      controller.browseCaseFilesExchange.mockReturnValue(throwError(() => ({ status: 409 })))
      workspaces.watch.mockReturnValue(of({ view: { equipped: true, status: 'READY' } }))
      init()
      expect(service.caseStatus()).toBe('error')
      expect(controller.browseCaseFilesExchange).toHaveBeenCalledTimes(2)
      expect(workspaces.watch).toHaveBeenCalledTimes(1)
    })

    it('hides the scope when the shared state reports revoked workspace access', () => {
      const states = new Subject<import('./case-workspace.service').WorkspaceState>()
      workspaces.watch.mockReturnValue(states)
      controller.browseCaseFilesExchange.mockReturnValue(throwError(() => ({ status: 409 })))
      init()
      states.next({ view: { equipped: true, status: 'PREPARING' } })
      states.next({ view: null, errorStatus: 403 })
      expect(service.caseStatus()).toBe('forbidden')
      expect(service.caseSectionVisible()).toBe(false)
      expect(service.canWriteCase()).toBe(false)
      expect(states.observed).toBe(false)
    })

    it.each(['case', 'home', 'clear'])('releases preparation state when navigating to %s', (destination) => {
      const states = new Subject<{ view: { equipped: boolean; status: string } }>()
      workspaces.watch.mockReturnValue(states)
      controller.browseCaseFilesExchange.mockReturnValue(throwError(() => ({ status: 409 })))
      init()
      states.next({ view: { equipped: true, status: 'PREPARING' } })
      expect(states.observed).toBe(true)
      controller.browseCaseFilesExchange.mockReturnValue(
        of(listing(ExchangeDirectoryListingCapabilityEnum.READ_WRITE, [caseFile]))
      )
      if (destination === 'case') service.initializeForCase('ns-1', 'c-2')
      else if (destination === 'home') service.initializeForNamespace('ns-1')
      else service.clear()
      expect(states.observed).toBe(false)
      expect(workspaces.watch).toHaveBeenCalledTimes(1)
      if (destination === 'case') {
        expect(service.caseStatus()).toBe('ready')
        expect(controller.browseCaseFilesExchange).toHaveBeenLastCalledWith('c-2', '', 0)
      } else {
        expect(service.caseStatus()).toBe('loading')
        expect(service.caseFiles()).toEqual([])
      }
    })
  })

  describe('namespace scope capability', () => {
    it('READ (simple member) → visible, not writable', () => {
      controller.browseNamespaceFilesExchange.mockReturnValue(
        of(listing(ExchangeDirectoryListingCapabilityEnum.READ, [nsFile]))
      )
      init()
      expect(service.namespaceStatus()).toBe('ready')
      expect(service.namespaceFiles()).toEqual([nsFile])
      expect(service.canWriteNamespace()).toBe(false)
    })

    it('READ_WRITE (namespace admin) → writable, section visible', () => {
      controller.browseNamespaceFilesExchange.mockReturnValue(
        of(listing(ExchangeDirectoryListingCapabilityEnum.READ_WRITE, [nsFile]))
      )
      init()
      expect(service.namespaceStatus()).toBe('ready')
      expect(service.canWriteNamespace()).toBe(true)
      expect(service.namespaceSectionVisible()).toBe(true)
    })
  })

  describe('counts', () => {
    it('fileCount is the sum of both scopes', () => {
      controller.browseCaseFilesExchange.mockReturnValue(
        of(listing(ExchangeDirectoryListingCapabilityEnum.READ_WRITE, [caseFile]))
      )
      controller.browseNamespaceFilesExchange.mockReturnValue(
        of(listing(ExchangeDirectoryListingCapabilityEnum.READ, [nsFile]))
      )
      init()
      expect(service.caseFileCount()).toBe(1)
      expect(service.namespaceFileCount()).toBe(1)
      expect(service.fileCount()).toBe(2)
    })
  })

  describe('writes refresh the case manifest', () => {
    it('upload success → reloads manifest and clears caseUploading', async () => {
      init()
      controller.uploadCaseFileExchange.mockReturnValue(of(caseFile))
      controller.browseCaseFilesExchange.mockClear()
      const result = await service.uploadFile(ExchangeFileEntryScopeEnum.CASE, new File(['x'], 'a.txt'))
      expect(result.success).toBe(true)
      expect(controller.uploadCaseFileExchange).toHaveBeenCalledWith('c-1', expect.any(File))
      expect(controller.browseCaseFilesExchange).toHaveBeenCalledWith('c-1', '', 0)
      expect(service.caseUploading()).toBe(false)
      // a case upload must not have flipped the namespace flag (per-scope isolation)
      expect(service.namespaceUploading()).toBe(false)
    })

    it('upload 409 → returns a friendly conflict error', async () => {
      init()
      controller.uploadCaseFileExchange.mockReturnValue(throwError(() => ({ status: 409 })))
      const result = await service.uploadFile(ExchangeFileEntryScopeEnum.CASE, new File(['x'], 'a.txt'))
      expect(result.success).toBe(false)
      expect(result.error).toBe('A file with this name already exists.')
    })

    it('upload 409 → prefers the server reason, because a preparing workspace shares that status', async () => {
      // Announcing a duplicate that does not exist sends people looking for a file they never
      // uploaded. The Git workspace answers 409 while it is still being prepared.
      init()
      controller.uploadCaseFileExchange.mockReturnValue(
        throwError(() => ({
          status: 409,
          error: { message: 'The workspace for this case is still being prepared. It will be ready in a moment.' },
        }))
      )
      const result = await service.uploadFile(ExchangeFileEntryScopeEnum.CASE, new File(['x'], 'a.txt'))
      expect(result.success).toBe(false)
      expect(result.error).toBe('The workspace for this case is still being prepared. It will be ready in a moment.')
    })

    it('upload 400 → surfaces the disallowed file type error from the backend', async () => {
      init()
      controller.uploadCaseFileExchange.mockReturnValue(
        throwError(() => ({ status: 400, error: { message: "File type not allowed for upload: 'x.exe'" } }))
      )
      const result = await service.uploadFile(ExchangeFileEntryScopeEnum.CASE, new File(['x'], 'x.exe'))
      expect(result.success).toBe(false)
      expect(result.error).toBe("File type not allowed for upload: 'x.exe'")
    })

    it('upload 400 without a body message falls back to a generic disallowed-type message', async () => {
      init()
      controller.uploadCaseFileExchange.mockReturnValue(throwError(() => ({ status: 400 })))
      const result = await service.uploadFile(ExchangeFileEntryScopeEnum.CASE, new File(['x'], 'x.exe'))
      expect(result.error).toBe('This file type is not allowed.')
    })

    it('delete success → reloads manifest', async () => {
      init()
      controller.deleteCaseFileExchange.mockReturnValue(of({ success: true, message: 'ok' }))
      controller.browseCaseFilesExchange.mockClear()
      const result = await service.deleteFile(ExchangeFileEntryScopeEnum.CASE, 'a.txt')
      expect(result.success).toBe(true)
      expect(controller.deleteCaseFileExchange).toHaveBeenCalledWith('c-1', 'a.txt')
      expect(controller.browseCaseFilesExchange).toHaveBeenCalledWith('c-1', '', 0)
    })
  })

  describe('namespace writes (admin)', () => {
    it('upload success → calls the namespace endpoint and reloads', async () => {
      init()
      controller.uploadNamespaceFileExchange.mockReturnValue(of(nsFile))
      controller.browseNamespaceFilesExchange.mockClear()
      const result = await service.uploadFile(ExchangeFileEntryScopeEnum.NAMESPACE, new File(['x'], 'shared.md'))
      expect(result.success).toBe(true)
      expect(controller.uploadNamespaceFileExchange).toHaveBeenCalledWith('ns-1', expect.any(File))
      expect(controller.browseNamespaceFilesExchange).toHaveBeenCalledWith('ns-1', '', 0)
    })

    it('delete success → calls the namespace endpoint and reloads', async () => {
      init()
      controller.deleteNamespaceFileExchange.mockReturnValue(of({ success: true, message: 'ok' }))
      controller.browseNamespaceFilesExchange.mockClear()
      const result = await service.deleteFile(ExchangeFileEntryScopeEnum.NAMESPACE, 'shared.md')
      expect(result.success).toBe(true)
      expect(controller.deleteNamespaceFileExchange).toHaveBeenCalledWith('ns-1', 'shared.md')
      expect(controller.browseNamespaceFilesExchange).toHaveBeenCalledWith('ns-1', '', 0)
    })
  })

  describe('initializeForNamespace (composer outside any case)', () => {
    it('fetches only the namespace manifest and enables canWriteNamespace on READ_WRITE', () => {
      controller.browseCaseFilesExchange.mockClear()
      controller.browseNamespaceFilesExchange.mockReturnValue(
        of(listing(ExchangeDirectoryListingCapabilityEnum.READ_WRITE, [nsFile]))
      )

      service.initializeForNamespace('ns-1')

      expect(controller.browseNamespaceFilesExchange).toHaveBeenCalledWith('ns-1', '', 0)
      expect(controller.browseCaseFilesExchange).not.toHaveBeenCalled()
      expect(service.canWriteNamespace()).toBe(true)
    })

    it('uploads to the namespace without any case initialised', async () => {
      controller.uploadNamespaceFileExchange.mockReturnValue(of(nsFile))

      service.initializeForNamespace('ns-1')
      const result = await service.uploadFile(ExchangeFileEntryScopeEnum.NAMESPACE, new File(['x'], 'shared.md'))

      expect(result.success).toBe(true)
      expect(controller.uploadNamespaceFileExchange).toHaveBeenCalledWith('ns-1', expect.any(File))
    })

    it('clear() then initializeForNamespace re-inits cleanly (chat to home transition)', () => {
      init()
      service.clear()
      controller.browseNamespaceFilesExchange.mockReturnValue(
        of(listing(ExchangeDirectoryListingCapabilityEnum.READ_WRITE, [nsFile]))
      )

      service.initializeForNamespace('ns-1')

      expect(service.namespaceStatus()).toBe('ready')
      expect(service.canWriteNamespace()).toBe(true)
    })
  })

  describe('refreshCase (agent file activity)', () => {
    it('keeps the current folder and entries visible while a background refresh is pending', () => {
      const initial = { ...listing(ExchangeDirectoryListingCapabilityEnum.READ_WRITE, [caseFile]), path: 'repo' }
      controller.browseCaseFilesExchange.mockReturnValue(of(initial))
      init()
      service.browseCase('repo')
      const pending = new Subject<ExchangeDirectoryListing>()
      controller.browseCaseFilesExchange.mockReturnValue(pending)

      service.refreshCase()

      expect(service.caseStatus()).toBe('ready')
      expect(service.caseBrowsePath()).toBe('repo')
      expect(service.caseFiles()).toEqual([caseFile])
      pending.next({ ...initial, entries: [caseFile, nsFile], totalEntries: 2 })
      expect(service.caseFiles()).toEqual([caseFile, nsFile])
    })

    it.each(['case', 'namespace'])('clears the previous listing when navigating to another %s folder', (scope) => {
      controller.browseCaseFilesExchange.mockReturnValue(
        of(listing(ExchangeDirectoryListingCapabilityEnum.READ_WRITE, [caseFile]))
      )
      controller.browseNamespaceFilesExchange.mockReturnValue(
        of(listing(ExchangeDirectoryListingCapabilityEnum.READ, [nsFile]))
      )
      init()
      const pending = new Subject<ExchangeDirectoryListing>()
      if (scope === 'case') {
        controller.browseCaseFilesExchange.mockReturnValue(pending)
        service.browseCase('repo/src')
        expect(service.caseStatus()).toBe('loading')
        expect(service.caseFiles()).toEqual([])
      } else {
        controller.browseNamespaceFilesExchange.mockReturnValue(pending)
        service.browseNamespace('repo/src')
        expect(service.namespaceStatus()).toBe('loading')
        expect(service.namespaceFiles()).toEqual([])
      }
    })

    it('clears a cached listing immediately when a refresh reveals revoked access', () => {
      controller.browseCaseFilesExchange.mockReturnValue(
        of(listing(ExchangeDirectoryListingCapabilityEnum.READ_WRITE, [caseFile]))
      )
      init()
      const pending = new Subject<ExchangeDirectoryListing>()
      controller.browseCaseFilesExchange.mockReturnValue(pending)
      service.refreshCase()
      pending.error({ status: 403 })
      expect(service.caseSectionVisible()).toBe(false)
      expect(service.caseFiles()).toEqual([])
      expect(service.canWriteCase()).toBe(false)
    })

    it('does not retain the previous case while loading a different case', () => {
      controller.browseCaseFilesExchange.mockReturnValue(
        of(listing(ExchangeDirectoryListingCapabilityEnum.READ_WRITE, [caseFile]))
      )
      init()
      controller.browseCaseFilesExchange.mockReturnValue(new Subject<ExchangeDirectoryListing>())
      service.initializeForCase('ns-1', 'c-2')
      expect(service.caseStatus()).toBe('loading')
      expect(service.caseFiles()).toEqual([])
    })

    it('refetches only the case manifest, leaving the read-only namespace scope untouched', () => {
      init()
      controller.browseCaseFilesExchange.mockClear()
      controller.browseNamespaceFilesExchange.mockClear()

      service.refreshCase()

      expect(controller.browseCaseFilesExchange).toHaveBeenCalledWith('c-1', '', 0)
      expect(controller.browseNamespaceFilesExchange).not.toHaveBeenCalled()
    })

    it('refreshNamespace refetches only the namespace manifest (agent namespace mutation)', () => {
      init()
      controller.browseCaseFilesExchange.mockClear()
      controller.browseNamespaceFilesExchange.mockClear()

      service.refreshNamespace()

      expect(controller.browseNamespaceFilesExchange).toHaveBeenCalledWith('ns-1', '', 0)
      expect(controller.browseCaseFilesExchange).not.toHaveBeenCalled()
    })

    it('refreshManifest still refetches both scopes', () => {
      init()
      controller.browseCaseFilesExchange.mockClear()
      controller.browseNamespaceFilesExchange.mockClear()

      service.refreshManifest()

      expect(controller.browseCaseFilesExchange).toHaveBeenCalledWith('c-1', '', 0)
      expect(controller.browseNamespaceFilesExchange).toHaveBeenCalledWith('ns-1', '', 0)
    })
  })
  describe('directory navigation', () => {
    it.each([ExchangeFileEntryScopeEnum.CASE, ExchangeFileEntryScopeEnum.NAMESPACE])(
      'loads later pages and preserves them on a background refresh for %s',
      (scope) => {
        const browse =
          scope === ExchangeFileEntryScopeEnum.CASE
            ? controller.browseCaseFilesExchange
            : controller.browseNamespaceFilesExchange
        browse.mockImplementation((_id: string, path: string, page: number) =>
          of({
            ...listing(ExchangeDirectoryListingCapabilityEnum.READ, [{ ...caseFile, path: `file-${page}.txt` }]),
            path,
            page,
            totalEntries: 2,
            hasMore: page === 0,
          })
        )
        init()
        service.loadMore(scope)
        const files = scope === ExchangeFileEntryScopeEnum.CASE ? service.caseFiles : service.namespaceFiles
        const more = scope === ExchangeFileEntryScopeEnum.CASE ? service.caseHasMore : service.namespaceHasMore
        expect(files().map((file) => file.path)).toEqual(['file-0.txt', 'file-1.txt'])
        expect(more()).toBe(false)
        if (scope === ExchangeFileEntryScopeEnum.CASE) service.refreshCase()
        else service.refreshNamespace()
        expect(files()).toHaveLength(2)
        if (scope === ExchangeFileEntryScopeEnum.CASE) service.browseCase('new-folder')
        else service.browseNamespace('new-folder')
        expect(browse).toHaveBeenLastCalledWith(
          scope === ExchangeFileEntryScopeEnum.CASE ? 'c-1' : 'ns-1',
          'new-folder',
          0
        )
      }
    )

    it.each([ExchangeFileEntryScopeEnum.CASE, ExchangeFileEntryScopeEnum.NAMESPACE])(
      'returns to an authorized root when the current folder disappears in %s',
      (scope) => {
        const browse =
          scope === ExchangeFileEntryScopeEnum.CASE
            ? controller.browseCaseFilesExchange
            : controller.browseNamespaceFilesExchange
        browse.mockImplementation((_id: string, path: string) =>
          path
            ? throwError(() => ({ status: 404 }))
            : of(listing(ExchangeDirectoryListingCapabilityEnum.READ, [caseFile]))
        )
        init()
        if (scope === ExchangeFileEntryScopeEnum.CASE) service.browseCase('gone')
        else service.browseNamespace('gone')
        const status = scope === ExchangeFileEntryScopeEnum.CASE ? service.caseStatus : service.namespaceStatus
        const path = scope === ExchangeFileEntryScopeEnum.CASE ? service.caseBrowsePath : service.namespaceBrowsePath
        expect(status()).toBe('ready')
        expect(path()).toBe('')
        service.refreshManifest()
        expect(browse).toHaveBeenLastCalledWith(scope === ExchangeFileEntryScopeEnum.CASE ? 'c-1' : 'ns-1', '', 0)
      }
    )

    it.each([403, 404])('still hides a scope when the root also refuses access with %s', (status) => {
      init()
      controller.browseCaseFilesExchange.mockImplementation((_id: string, path: string) =>
        throwError(() => ({ status: path ? 404 : status }))
      )
      service.browseCase('private')
      expect(service.caseSectionVisible()).toBe(false)
      expect(service.caseFiles()).toEqual([])
      expect(service.canWriteCase()).toBe(false)
    })

    it('cancels a pending next page on case navigation', () => {
      const pending = new Subject<ExchangeDirectoryListing>()
      controller.browseCaseFilesExchange.mockImplementation((id: string, _path: string, page: number) => {
        if (id === 'c-1' && page === 1) return pending
        return of({ ...listing(ExchangeDirectoryListingCapabilityEnum.READ, [caseFile]), hasMore: id === 'c-1' })
      })
      init()
      service.loadMore(ExchangeFileEntryScopeEnum.CASE)
      expect(service.caseLoadingMore()).toBe(true)
      service.initializeForCase('ns-1', 'c-2')
      pending.next({ ...listing(ExchangeDirectoryListingCapabilityEnum.READ, [nsFile]), page: 1 })
      expect(service.caseFiles()).toEqual([caseFile])
      expect(service.caseLoadingMore()).toBe(false)
    })
  })

  describe('complete download', () => {
    it.each([ExchangeFileEntryScopeEnum.CASE, ExchangeFileEntryScopeEnum.NAMESPACE])(
      'uses the recursive manifest rather than the displayed directory for %s',
      async (scope) => {
        init()
        const manifest =
          scope === ExchangeFileEntryScopeEnum.CASE
            ? controller.getCaseFilesManifestExchange
            : controller.getNamespaceFilesManifestExchange
        const download =
          scope === ExchangeFileEntryScopeEnum.CASE
            ? controller.downloadCaseFileExchange
            : controller.downloadNamespaceFileExchange
        manifest.mockReturnValue(of({ files: [{ path: 'not-on-this-page/deep.txt' }] }))
        download.mockReturnValue(of(new Blob(['content'])))
        jest
          .spyOn(service as unknown as { saveBlob: (body: Blob | string, filename: string) => void }, 'saveBlob')
          .mockImplementation(() => undefined)
        expect(await service.downloadAll(scope)).toEqual({ success: true, failedCount: 0 })
        expect(download).toHaveBeenCalledWith(
          scope === ExchangeFileEntryScopeEnum.CASE ? 'c-1' : 'ns-1',
          'not-on-this-page/deep.txt'
        )
      }
    )

    it('keeps the original case id if navigation changes before the manifest response', async () => {
      init()
      const manifest = new Subject<{ files: { path: string }[] }>()
      controller.getCaseFilesManifestExchange.mockReturnValue(manifest)
      controller.downloadCaseFileExchange.mockReturnValue(of(new Blob(['content'])))
      jest
        .spyOn(service as unknown as { saveBlob: (body: Blob | string, filename: string) => void }, 'saveBlob')
        .mockImplementation(() => undefined)
      const result = service.downloadAll(ExchangeFileEntryScopeEnum.CASE)
      service.initializeForCase('ns-2', 'c-2')
      manifest.next({ files: [{ path: 'deep/file.txt' }] })
      manifest.complete()
      await result
      expect(controller.downloadCaseFileExchange).toHaveBeenCalledWith('c-1', 'deep/file.txt')
    })

    it('reports an incomplete download if listing the full scope fails', async () => {
      init()
      controller.getCaseFilesManifestExchange.mockReturnValue(throwError(() => ({ status: 403 })))
      expect((await service.downloadAll(ExchangeFileEntryScopeEnum.CASE)).success).toBe(false)
      expect(controller.downloadCaseFileExchange).not.toHaveBeenCalled()
    })
  })
})
