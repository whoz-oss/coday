import { TestBed } from '@angular/core/testing'
import {
  CaseWorkspaceViewStatusEnum,
  ExchangeControllerService,
  ExchangeDirectoryEntry,
  ExchangeDirectoryListing,
  ExchangeDirectoryListingCapabilityEnum,
  ExchangeFileEntryScopeEnum,
  ExchangeFolderApiService,
} from '@whoz-oss/agentos-api-client'
import { firstValueFrom, of, Subject, throwError } from 'rxjs'
import { ExchangeStateService } from './exchange-state.service'
import { CaseWorkspaceService } from './case-workspace.service'

describe('ExchangeStateService', () => {
  let controller: {
    browseCaseFilesExchange: jest.Mock
    browseNamespaceFilesExchange: jest.Mock
    deleteCaseFileExchange: jest.Mock
    deleteNamespaceFileExchange: jest.Mock
    downloadCaseFileExchange: jest.Mock
    downloadNamespaceFileExchange: jest.Mock
    getCaseFilesManifestExchange: jest.Mock
    getNamespaceFilesManifestExchange: jest.Mock
  }
  /** The hand-written folder client: uploads (with their directory), folder create / delete and the ZIP. */
  let folderApi: {
    uploadFile: jest.Mock
    createDirectory: jest.Mock
    deleteDirectory: jest.Mock
    downloadArchive: jest.Mock
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
      deleteCaseFileExchange: jest.fn(),
      deleteNamespaceFileExchange: jest.fn(),
      downloadCaseFileExchange: jest.fn(),
      downloadNamespaceFileExchange: jest.fn(),
      getCaseFilesManifestExchange: jest.fn(),
      getNamespaceFilesManifestExchange: jest.fn(),
    }
    folderApi = {
      uploadFile: jest.fn(),
      createDirectory: jest.fn(),
      deleteDirectory: jest.fn(),
      downloadArchive: jest.fn(),
    }
    workspaces = { watch: jest.fn().mockReturnValue(of({ view: { equipped: false } })) }
    TestBed.configureTestingModule({
      providers: [
        ExchangeStateService,
        { provide: ExchangeControllerService, useValue: controller },
        { provide: ExchangeFolderApiService, useValue: folderApi },
        { provide: CaseWorkspaceService, useValue: workspaces },
      ],
    })
    service = TestBed.inject(ExchangeStateService)
  })

  describe('recursive filter manifests', () => {
    it('loads lazily, caches independently, and invalidates only the refreshed scope', async () => {
      init()
      controller.getCaseFilesManifestExchange.mockReturnValue(of({ files: [{ path: 'deep/a.txt' }] }))
      controller.getNamespaceFilesManifestExchange.mockReturnValue(of({ files: [{ path: 'deep/b.txt' }] }))
      expect(controller.getCaseFilesManifestExchange).not.toHaveBeenCalled()
      await firstValueFrom(service.fileManifest(ExchangeFileEntryScopeEnum.CASE))
      await firstValueFrom(service.fileManifest(ExchangeFileEntryScopeEnum.NAMESPACE))
      service.refreshCase()
      await firstValueFrom(service.fileManifest(ExchangeFileEntryScopeEnum.CASE))
      await firstValueFrom(service.fileManifest(ExchangeFileEntryScopeEnum.NAMESPACE))
      expect(controller.getCaseFilesManifestExchange).toHaveBeenCalledTimes(2)
      expect(controller.getNamespaceFilesManifestExchange).toHaveBeenCalledTimes(1)
      service.initializeForCase('ns-1', 'c-2')
      await firstValueFrom(service.fileManifest(ExchangeFileEntryScopeEnum.CASE))
      await firstValueFrom(service.fileManifest(ExchangeFileEntryScopeEnum.NAMESPACE))
      expect(controller.getCaseFilesManifestExchange).toHaveBeenLastCalledWith('c-2')
      expect(controller.getNamespaceFilesManifestExchange).toHaveBeenCalledTimes(1)
    })

    it.each([ExchangeFileEntryScopeEnum.CASE, ExchangeFileEntryScopeEnum.NAMESPACE])(
      'shares a pending %s manifest and never replaces the refreshed cache with the old response',
      async (scope) => {
        init()
        const old = new Subject<{ capability: string; files: { path: string }[] }>()
        const fresh = new Subject<{ capability: string; files: { path: string }[] }>()
        const get = scope === ExchangeFileEntryScopeEnum.CASE
          ? controller.getCaseFilesManifestExchange
          : controller.getNamespaceFilesManifestExchange
        get.mockReturnValueOnce(old).mockReturnValueOnce(fresh)
        const first = firstValueFrom(service.fileManifest(scope))
        const second = firstValueFrom(service.fileManifest(scope))
        expect(get).toHaveBeenCalledTimes(1)
        if (scope === ExchangeFileEntryScopeEnum.CASE) service.refreshCase()
        else service.refreshNamespace()
        const afterRefresh = firstValueFrom(service.fileManifest(scope))
        expect(get).toHaveBeenCalledTimes(1)
        old.next({ capability: 'READ', files: [{ path: 'old.txt' }] })
        old.complete()
        expect((await first).map((item) => item.path)).toEqual(['old.txt'])
        expect((await second).map((item) => item.path)).toEqual(['old.txt'])
        expect(get).toHaveBeenCalledTimes(2)
        fresh.next({ capability: 'READ', files: [{ path: 'new.txt' }] })
        fresh.complete()
        expect((await afterRefresh).map((item) => item.path)).toEqual(['new.txt'])
        expect((await firstValueFrom(service.fileManifest(scope))).map((item) => item.path)).toEqual(['new.txt'])
        expect(get).toHaveBeenCalledTimes(2)
      }
    )

    it('invalidates only the scope changed by upload or delete', async () => {
      init()
      controller.getCaseFilesManifestExchange.mockReturnValue(of({ files: [] }))
      controller.getNamespaceFilesManifestExchange.mockReturnValue(of({ files: [] }))
      folderApi.uploadFile.mockReturnValue(of(caseFile))
      controller.deleteNamespaceFileExchange.mockReturnValue(of({ success: true }))
      await firstValueFrom(service.fileManifest(ExchangeFileEntryScopeEnum.CASE))
      await firstValueFrom(service.fileManifest(ExchangeFileEntryScopeEnum.NAMESPACE))
      await service.uploadFile(ExchangeFileEntryScopeEnum.CASE, new File(['x'], 'a.txt'))
      await firstValueFrom(service.fileManifest(ExchangeFileEntryScopeEnum.CASE))
      await firstValueFrom(service.fileManifest(ExchangeFileEntryScopeEnum.NAMESPACE))
      expect(controller.getCaseFilesManifestExchange).toHaveBeenCalledTimes(2)
      expect(controller.getNamespaceFilesManifestExchange).toHaveBeenCalledTimes(1)
      await service.deleteFile(ExchangeFileEntryScopeEnum.NAMESPACE, 'shared.md')
      await firstValueFrom(service.fileManifest(ExchangeFileEntryScopeEnum.CASE))
      await firstValueFrom(service.fileManifest(ExchangeFileEntryScopeEnum.NAMESPACE))
      expect(controller.getCaseFilesManifestExchange).toHaveBeenCalledTimes(2)
      expect(controller.getNamespaceFilesManifestExchange).toHaveBeenCalledTimes(2)
    })

    it('does not invalidate the new case when an old case mutation completes', async () => {
      init()
      const pending = new Subject<ExchangeDirectoryEntry>()
      folderApi.uploadFile.mockReturnValue(pending)
      const upload = service.uploadFile(ExchangeFileEntryScopeEnum.CASE, new File(['x'], 'a.txt'))
      service.initializeForCase('ns-1', 'c-2')
      const generation = service.caseManifestVersion()
      pending.next(caseFile)
      await upload
      expect(service.caseManifestVersion()).toBe(generation)
    })

    it('fails closed when the recursive manifest has no capability', async () => {
      init()
      controller.getCaseFilesManifestExchange.mockReturnValue(of({ capability: 'NONE', files: [{ path: 'private.txt' }] }))
      expect(await firstValueFrom(service.fileManifest(ExchangeFileEntryScopeEnum.CASE))).toEqual([])
    })

    it('retries a failed manifest', async () => {
      init()
      controller.getCaseFilesManifestExchange.mockReturnValueOnce(throwError(() => new Error('failure')))
        .mockReturnValueOnce(of({ files: [] }))
      await expect(firstValueFrom(service.fileManifest(ExchangeFileEntryScopeEnum.CASE))).rejects.toThrow('failure')
      expect(await firstValueFrom(service.fileManifest(ExchangeFileEntryScopeEnum.CASE))).toEqual([])
    })
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

  describe('namespaceManifest (file filter)', () => {
    const entry = (path: string) => ({
      path,
      filename: path.split('/').pop() as string,
      size: 1,
      lastModified: '2026-01-01T00:00:00Z',
      scope: ExchangeFileEntryScopeEnum.NAMESPACE,
    })
    const manifest = (...paths: string[]) => ({ capability: 'READ', files: paths.map(entry) })

    it('is lazy: nothing is fetched until it is asked for', () => {
      init()
      expect(controller.getNamespaceFilesManifestExchange).not.toHaveBeenCalled()
    })

    it('fetches the recursive manifest once and replays it to later callers', async () => {
      init()
      controller.getNamespaceFilesManifestExchange.mockReturnValue(of(manifest('a/b.md', 'c.md')))
      const first = await firstValueFrom(service.namespaceManifest())
      const second = await firstValueFrom(service.namespaceManifest())
      expect(first.map((f) => f.path)).toEqual(['a/b.md', 'c.md'])
      expect(second).toBe(first)
      expect(controller.getNamespaceFilesManifestExchange).toHaveBeenCalledTimes(1)
      expect(controller.getNamespaceFilesManifestExchange).toHaveBeenCalledWith('ns-1')
    })

    it.each([
      ['refreshManifest', () => service.refreshManifest()],
      ['refreshNamespace', () => service.refreshNamespace()],
      ['initializeForNamespace', () => service.initializeForNamespace('ns-1')],
      ['initializeForCase (new namespace)', () => service.initializeForCase('ns-2', 'c-2')],
      ['clear', () => service.clear()],
    ])('drops the cache on %s and bumps the version', async (_name, act) => {
      init()
      controller.getNamespaceFilesManifestExchange.mockReturnValue(of(manifest('a.md')))
      await firstValueFrom(service.namespaceManifest())
      const version = service.namespaceManifestVersion()
      act()
      expect(service.namespaceManifestVersion()).toBeGreaterThan(version)
      if (_name === 'clear') {
        await expect(firstValueFrom(service.namespaceManifest())).rejects.toThrow()
        return
      }
      controller.getNamespaceFilesManifestExchange.mockReturnValue(of(manifest('a.md', 'new.md')))
      expect((await firstValueFrom(service.namespaceManifest())).map((f) => f.path)).toEqual(['a.md', 'new.md'])
    })

    it('preserves the namespace manifest when only the case changes', async () => {
      init()
      controller.getNamespaceFilesManifestExchange.mockReturnValue(of(manifest('a.md')))
      await firstValueFrom(service.namespaceManifest())
      const version = service.namespaceManifestVersion()
      service.initializeForCase('ns-1', 'c-2')
      expect(service.namespaceManifestVersion()).toBe(version)
      expect((await firstValueFrom(service.namespaceManifest())).map((item) => item.path)).toEqual(['a.md'])
      expect(controller.getNamespaceFilesManifestExchange).toHaveBeenCalledTimes(1)
    })

    it('is not dropped by a case-only refresh or by browsing', async () => {
      init()
      controller.getNamespaceFilesManifestExchange.mockReturnValue(of(manifest('a.md')))
      await firstValueFrom(service.namespaceManifest())
      service.refreshCase()
      service.browseNamespace('docs')
      await firstValueFrom(service.namespaceManifest())
      expect(controller.getNamespaceFilesManifestExchange).toHaveBeenCalledTimes(1)
    })

    it('does not cache a failure: the next call asks again', async () => {
      init()
      controller.getNamespaceFilesManifestExchange.mockReturnValueOnce(throwError(() => ({ status: 500 })))
      await expect(firstValueFrom(service.namespaceManifest())).rejects.toEqual({ status: 500 })
      service.refreshNamespace()
      controller.getNamespaceFilesManifestExchange.mockReturnValue(of(manifest('a.md')))
      expect(await firstValueFrom(service.namespaceManifest())).toHaveLength(1)
    })

    it('fails without an active namespace', async () => {
      await expect(firstValueFrom(service.namespaceManifest())).rejects.toThrow()
    })
  })

  describe('getBlob (image preview)', () => {
    it.each([
      [ExchangeFileEntryScopeEnum.CASE, 'c-1'],
      [ExchangeFileEntryScopeEnum.NAMESPACE, 'ns-1'],
    ])('reads the bytes of a %s file through the download endpoint', async (scope, id) => {
      init()
      const blob = new Blob(['x'], { type: 'image/png' })
      controller.downloadCaseFileExchange.mockReturnValue(of(blob))
      controller.downloadNamespaceFileExchange.mockReturnValue(of(blob))
      const result = await firstValueFrom(service.getBlob(scope, 'img/a.png'))
      expect(result).toBe(blob)
      const download =
        scope === ExchangeFileEntryScopeEnum.CASE
          ? controller.downloadCaseFileExchange
          : controller.downloadNamespaceFileExchange
      expect(download).toHaveBeenCalledWith(id, 'img/a.png')
    })

    it('fails without an active scope', async () => {
      await expect(firstValueFrom(service.getBlob(ExchangeFileEntryScopeEnum.CASE, 'a.png'))).rejects.toThrow()
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
      states.next({ view: { equipped: true, status: CaseWorkspaceViewStatusEnum.REQUESTED } })
      expect(service.caseStatus()).toBe('preparing')
      expect(service.canWriteCase()).toBe(false)
      expect(service.caseFiles()).toEqual([])
      states.next({ view: { equipped: true, status: CaseWorkspaceViewStatusEnum.PREPARING } })
      expect(controller.browseCaseFilesExchange).toHaveBeenCalledTimes(1)
      states.next({ view: { equipped: true, status: CaseWorkspaceViewStatusEnum.READY } })
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
      states.next({ view: { equipped: true, status: CaseWorkspaceViewStatusEnum.PREPARING } })
      states.next({ view: { equipped: true, status: CaseWorkspaceViewStatusEnum.FAILED } })
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
      workspaces.watch.mockReturnValue(of({ view: { equipped: true, status: CaseWorkspaceViewStatusEnum.READY } }))
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
      states.next({ view: { equipped: true, status: CaseWorkspaceViewStatusEnum.PREPARING } })
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
      states.next({ view: { equipped: true, status: CaseWorkspaceViewStatusEnum.PREPARING } })
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
      folderApi.uploadFile.mockReturnValue(of(caseFile))
      controller.browseCaseFilesExchange.mockClear()
      const result = await service.uploadFile(ExchangeFileEntryScopeEnum.CASE, new File(['x'], 'a.txt'))
      expect(result.success).toBe(true)
      expect(folderApi.uploadFile).toHaveBeenCalledWith(
        ExchangeFileEntryScopeEnum.CASE,
        'c-1',
        '',
        expect.any(File)
      )
      expect(controller.browseCaseFilesExchange).toHaveBeenCalledWith('c-1', '', 0)
      expect(service.caseUploading()).toBe(false)
      // a case upload must not have flipped the namespace flag (per-scope isolation)
      expect(service.namespaceUploading()).toBe(false)
    })

    it('upload 409 → returns a friendly conflict error', async () => {
      init()
      folderApi.uploadFile.mockReturnValue(throwError(() => ({ status: 409 })))
      const result = await service.uploadFile(ExchangeFileEntryScopeEnum.CASE, new File(['x'], 'a.txt'))
      expect(result.success).toBe(false)
      expect(result.error).toBe('A file with this name already exists.')
    })

    it('upload 409 → prefers the server reason, because a preparing workspace shares that status', async () => {
      // Announcing a duplicate that does not exist sends people looking for a file they never
      // uploaded. The Git workspace answers 409 while it is still being prepared.
      init()
      folderApi.uploadFile.mockReturnValue(
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
      folderApi.uploadFile.mockReturnValue(
        throwError(() => ({ status: 400, error: { message: "File type not allowed for upload: 'x.exe'" } }))
      )
      const result = await service.uploadFile(ExchangeFileEntryScopeEnum.CASE, new File(['x'], 'x.exe'))
      expect(result.success).toBe(false)
      expect(result.error).toBe("File type not allowed for upload: 'x.exe'")
    })

    it('upload 400 without a body message falls back to a generic disallowed-type message', async () => {
      init()
      folderApi.uploadFile.mockReturnValue(throwError(() => ({ status: 400 })))
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
      folderApi.uploadFile.mockReturnValue(of(nsFile))
      controller.browseNamespaceFilesExchange.mockClear()
      const result = await service.uploadFile(ExchangeFileEntryScopeEnum.NAMESPACE, new File(['x'], 'shared.md'))
      expect(result.success).toBe(true)
      expect(folderApi.uploadFile).toHaveBeenCalledWith(
        ExchangeFileEntryScopeEnum.NAMESPACE,
        'ns-1',
        '',
        expect.any(File)
      )
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
      folderApi.uploadFile.mockReturnValue(of(nsFile))

      service.initializeForNamespace('ns-1')
      const result = await service.uploadFile(ExchangeFileEntryScopeEnum.NAMESPACE, new File(['x'], 'shared.md'))

      expect(result.success).toBe(true)
      expect(folderApi.uploadFile).toHaveBeenCalledWith(
        ExchangeFileEntryScopeEnum.NAMESPACE,
        'ns-1',
        '',
        expect.any(File)
      )
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

  describe('folder download (one ZIP)', () => {
    const saveBlobSpy = () =>
      jest
        .spyOn(service as unknown as { saveBlob: (body: Blob | string, filename: string) => void }, 'saveBlob')
        .mockImplementation(() => undefined)

    it.each([
      [ExchangeFileEntryScopeEnum.CASE, 'c-1', 'case-files.zip'],
      [ExchangeFileEntryScopeEnum.NAMESPACE, 'ns-1', 'namespace-files.zip'],
    ])('from the root of the %s scope: one archive call for the whole scope, one .zip', async (scope, id, name) => {
      init()
      const save = saveBlobSpy()
      folderApi.downloadArchive.mockReturnValue(of(new Blob(['zip'])))

      expect(await service.downloadFolder(scope)).toEqual({ success: true })

      expect(folderApi.downloadArchive).toHaveBeenCalledTimes(1)
      expect(folderApi.downloadArchive).toHaveBeenCalledWith(scope, id, '')
      expect(save).toHaveBeenCalledTimes(1)
      expect(save).toHaveBeenCalledWith(expect.any(Blob), name)
      // The recursive manifest and the per-file endpoints are no longer part of a download: that was the multi-download.
      expect(controller.getCaseFilesManifestExchange).not.toHaveBeenCalled()
      expect(controller.getNamespaceFilesManifestExchange).not.toHaveBeenCalled()
      expect(controller.downloadCaseFileExchange).not.toHaveBeenCalled()
      expect(controller.downloadNamespaceFileExchange).not.toHaveBeenCalled()
    })

    it.each([ExchangeFileEntryScopeEnum.CASE, ExchangeFileEntryScopeEnum.NAMESPACE])(
      'scopes the %s download to the folder being browsed and names the archive after it',
      async (scope) => {
        const browse =
          scope === ExchangeFileEntryScopeEnum.CASE
            ? controller.browseCaseFilesExchange
            : controller.browseNamespaceFilesExchange
        browse.mockImplementation((_id: string, path: string) =>
          of({ ...listing(ExchangeDirectoryListingCapabilityEnum.READ_WRITE, [caseFile]), path })
        )
        init()
        if (scope === ExchangeFileEntryScopeEnum.CASE) service.browseCase('docs/specs')
        else service.browseNamespace('docs/specs')
        const save = saveBlobSpy()
        folderApi.downloadArchive.mockReturnValue(of(new Blob(['zip'])))

        await service.downloadFolder(scope)

        expect(folderApi.downloadArchive).toHaveBeenCalledTimes(1)
        expect(folderApi.downloadArchive).toHaveBeenCalledWith(
          scope,
          scope === ExchangeFileEntryScopeEnum.CASE ? 'c-1' : 'ns-1',
          'docs/specs'
        )
        expect(save).toHaveBeenCalledWith(expect.any(Blob), 'specs.zip')
      }
    )

    it('keeps the original case id if navigation changes before the archive arrives', async () => {
      init()
      const archive = new Subject<Blob>()
      folderApi.downloadArchive.mockReturnValue(archive)
      saveBlobSpy()
      const result = service.downloadFolder(ExchangeFileEntryScopeEnum.CASE)
      service.initializeForCase('ns-2', 'c-2')
      archive.next(new Blob(['zip']))
      archive.complete()
      expect(await result).toEqual({ success: true })
      expect(folderApi.downloadArchive).toHaveBeenCalledWith(ExchangeFileEntryScopeEnum.CASE, 'c-1', '')
    })

    it('reports a failure and saves nothing when the archive cannot be fetched', async () => {
      init()
      const save = saveBlobSpy()
      const error = jest.spyOn(console, 'error').mockImplementation(() => undefined)
      folderApi.downloadArchive.mockReturnValue(throwError(() => ({ status: 500, message: 'boom' })))

      const result = await service.downloadFolder(ExchangeFileEntryScopeEnum.CASE)

      expect(result).toEqual({ success: false, error: 'boom' })
      expect(save).not.toHaveBeenCalled()
      error.mockRestore()
    })

    it('says so when the folder is gone (404)', async () => {
      init()
      saveBlobSpy()
      const error = jest.spyOn(console, 'error').mockImplementation(() => undefined)
      folderApi.downloadArchive.mockReturnValue(throwError(() => ({ status: 404 })))

      const result = await service.downloadFolder(ExchangeFileEntryScopeEnum.CASE, 'gone')

      expect(result).toEqual({ success: false, error: 'This folder no longer exists.' })
      error.mockRestore()
    })

    it('fails without an active scope', async () => {
      expect((await service.downloadFolder(ExchangeFileEntryScopeEnum.CASE)).success).toBe(false)
      expect(folderApi.downloadArchive).not.toHaveBeenCalled()
    })
  })

  describe('uploads into the browsed folder', () => {
    const browseInto = (scope: ExchangeFileEntryScopeEnum, path: string) => {
      const browse =
        scope === ExchangeFileEntryScopeEnum.CASE
          ? controller.browseCaseFilesExchange
          : controller.browseNamespaceFilesExchange
      browse.mockImplementation((_id: string, directory: string) =>
        of({ ...listing(ExchangeDirectoryListingCapabilityEnum.READ_WRITE), path: directory })
      )
      init()
      if (scope === ExchangeFileEntryScopeEnum.CASE) service.browseCase(path)
      else service.browseNamespace(path)
    }

    it.each([
      [ExchangeFileEntryScopeEnum.CASE, 'c-1'],
      [ExchangeFileEntryScopeEnum.NAMESPACE, 'ns-1'],
    ])('sends the browsed folder as the directory of a %s upload', async (scope, id) => {
      browseInto(scope, 'docs')
      folderApi.uploadFile.mockReturnValue(of(caseFile))
      const file = new File(['x'], 'report.txt')

      const result = await service.uploadFile(scope, file)

      expect(result.success).toBe(true)
      expect(folderApi.uploadFile).toHaveBeenCalledWith(scope, id, 'docs', file)
    })

    it('lets a caller upload to the root while a folder is browsed (composer attachments)', async () => {
      browseInto(ExchangeFileEntryScopeEnum.CASE, 'docs')
      folderApi.uploadFile.mockReturnValue(of(caseFile))
      const file = new File(['x'], 'report.txt')

      await service.uploadFile(ExchangeFileEntryScopeEnum.CASE, file, '')

      expect(folderApi.uploadFile).toHaveBeenCalledWith(ExchangeFileEntryScopeEnum.CASE, 'c-1', '', file)
    })

    it('does not borrow the folder of the other scope', async () => {
      browseInto(ExchangeFileEntryScopeEnum.CASE, 'docs')
      folderApi.uploadFile.mockReturnValue(of(nsFile))

      await service.uploadFile(ExchangeFileEntryScopeEnum.NAMESPACE, new File(['x'], 'shared.md'))

      expect(folderApi.uploadFile).toHaveBeenCalledWith(
        ExchangeFileEntryScopeEnum.NAMESPACE,
        'ns-1',
        '',
        expect.any(File)
      )
    })
  })

  describe('create folder', () => {
    it.each([
      [ExchangeFileEntryScopeEnum.CASE, 'c-1'],
      [ExchangeFileEntryScopeEnum.NAMESPACE, 'ns-1'],
    ])('creates the %s folder at the root and reloads only that scope', async (scope, id) => {
      init()
      folderApi.createDirectory.mockReturnValue(of({ path: 'docs', name: 'docs', directory: true }))
      controller.browseCaseFilesExchange.mockClear()
      controller.browseNamespaceFilesExchange.mockClear()

      const result = await service.createFolder(scope, ' docs ')

      expect(result).toEqual({ success: true })
      expect(folderApi.createDirectory).toHaveBeenCalledWith(scope, id, 'docs')
      const reloaded =
        scope === ExchangeFileEntryScopeEnum.CASE
          ? controller.browseCaseFilesExchange
          : controller.browseNamespaceFilesExchange
      const untouched =
        scope === ExchangeFileEntryScopeEnum.CASE
          ? controller.browseNamespaceFilesExchange
          : controller.browseCaseFilesExchange
      expect(reloaded).toHaveBeenCalledWith(id, '', 0)
      expect(untouched).not.toHaveBeenCalled()
    })

    it('creates the folder inside the folder being browsed', async () => {
      controller.browseCaseFilesExchange.mockImplementation((_id: string, directory: string) =>
        of({ ...listing(ExchangeDirectoryListingCapabilityEnum.READ_WRITE), path: directory })
      )
      init()
      service.browseCase('docs')
      folderApi.createDirectory.mockReturnValue(of({ path: 'docs/api', name: 'api', directory: true }))

      await service.createFolder(ExchangeFileEntryScopeEnum.CASE, 'api')

      expect(folderApi.createDirectory).toHaveBeenCalledWith(ExchangeFileEntryScopeEnum.CASE, 'c-1', 'docs/api')
    })

    it('answers a name already in use with a plain message on 409', async () => {
      init()
      folderApi.createDirectory.mockReturnValue(throwError(() => ({ status: 409 })))

      const result = await service.createFolder(ExchangeFileEntryScopeEnum.CASE, 'docs')

      expect(result).toEqual({ success: false, error: 'A file or folder with this name already exists.' })
    })

    it('prefers the server reason when the workspace is not usable yet (also a 409)', async () => {
      init()
      const reason = 'The workspace for this case is still being prepared. It will be ready in a moment.'
      folderApi.createDirectory.mockReturnValue(throwError(() => ({ status: 409, error: { message: reason } })))

      const result = await service.createFolder(ExchangeFileEntryScopeEnum.CASE, 'docs')

      expect(result).toEqual({ success: false, error: reason })
    })

    it('surfaces the server message on 400', async () => {
      init()
      folderApi.createDirectory.mockReturnValue(
        throwError(() => ({ status: 400, error: { message: 'Invalid path' } }))
      )

      const result = await service.createFolder(ExchangeFileEntryScopeEnum.CASE, '.git')

      expect(result).toEqual({ success: false, error: 'Invalid path' })
    })

    it('does not reload the new case when an old case folder is created after navigation', async () => {
      init()
      const pending = new Subject<ExchangeDirectoryEntry>()
      folderApi.createDirectory.mockReturnValue(pending)
      const creation = service.createFolder(ExchangeFileEntryScopeEnum.CASE, 'docs')
      service.initializeForCase('ns-1', 'c-2')
      const generation = service.caseManifestVersion()
      pending.next({ path: 'docs', name: 'docs', directory: true })
      await creation
      expect(service.caseManifestVersion()).toBe(generation)
    })

    it('fails without an active scope', async () => {
      expect((await service.createFolder(ExchangeFileEntryScopeEnum.CASE, 'docs')).success).toBe(false)
      expect(folderApi.createDirectory).not.toHaveBeenCalled()
    })
  })

  describe('delete folder', () => {
    it.each([
      [ExchangeFileEntryScopeEnum.CASE, 'c-1'],
      [ExchangeFileEntryScopeEnum.NAMESPACE, 'ns-1'],
    ])('deletes the %s folder and reloads only that scope', async (scope, id) => {
      init()
      folderApi.deleteDirectory.mockReturnValue(of({ success: true, message: 'ok' }))
      controller.browseCaseFilesExchange.mockClear()
      controller.browseNamespaceFilesExchange.mockClear()

      const result = await service.deleteFolder(scope, 'docs')

      expect(result).toEqual({ success: true })
      expect(folderApi.deleteDirectory).toHaveBeenCalledWith(scope, id, 'docs')
      const reloaded =
        scope === ExchangeFileEntryScopeEnum.CASE
          ? controller.browseCaseFilesExchange
          : controller.browseNamespaceFilesExchange
      const untouched =
        scope === ExchangeFileEntryScopeEnum.CASE
          ? controller.browseNamespaceFilesExchange
          : controller.browseCaseFilesExchange
      expect(reloaded).toHaveBeenCalledWith(id, '', 0)
      expect(untouched).not.toHaveBeenCalled()
    })

    it('steps up to the parent when the folder being browsed is the one deleted', async () => {
      controller.browseCaseFilesExchange.mockImplementation((_id: string, directory: string) =>
        of({ ...listing(ExchangeDirectoryListingCapabilityEnum.READ_WRITE), path: directory })
      )
      init()
      service.browseCase('docs/old')
      folderApi.deleteDirectory.mockReturnValue(of({ success: true, message: 'ok' }))

      await service.deleteFolder(ExchangeFileEntryScopeEnum.CASE, 'docs/old')

      expect(service.caseBrowsePath()).toBe('docs')
      expect(controller.browseCaseFilesExchange).toHaveBeenLastCalledWith('c-1', 'docs', 0)
    })

    it('answers a protected subtree (409) with a plain message and keeps the listing', async () => {
      init()
      folderApi.deleteDirectory.mockReturnValue(throwError(() => ({ status: 409 })))
      controller.browseCaseFilesExchange.mockClear()

      const result = await service.deleteFolder(ExchangeFileEntryScopeEnum.CASE, 'repo')

      expect(result).toEqual({
        success: false,
        error: 'This folder contains protected files and cannot be deleted.',
      })
      expect(controller.browseCaseFilesExchange).not.toHaveBeenCalled()
    })

    it('surfaces the server message on 400 and falls back to a generic one', async () => {
      init()
      folderApi.deleteDirectory.mockReturnValueOnce(
        throwError(() => ({ status: 400, error: { message: 'Not a directory' } }))
      )
      expect((await service.deleteFolder(ExchangeFileEntryScopeEnum.CASE, 'a.txt')).error).toBe('Not a directory')

      folderApi.deleteDirectory.mockReturnValueOnce(throwError(() => ({ status: 500 })))
      expect((await service.deleteFolder(ExchangeFileEntryScopeEnum.CASE, 'docs')).error).toBe(
        'Could not delete the folder.'
      )
    })
  })

  describe('folderFileCount (confirmation text)', () => {
    const entry = (path: string) => ({ path, filename: path.split('/').pop() as string, size: 1 })

    it('counts only the files under the folder, at any depth', async () => {
      init()
      controller.getCaseFilesManifestExchange.mockReturnValue(
        of({
          capability: 'READ_WRITE',
          files: ['docs/a.md', 'docs/api/b.md', 'docs-old/c.md', 'docs.md', 'other/d.md'].map(entry),
        })
      )

      expect(await firstValueFrom(service.folderFileCount(ExchangeFileEntryScopeEnum.CASE, 'docs'))).toBe(2)
      expect(await firstValueFrom(service.folderFileCount(ExchangeFileEntryScopeEnum.CASE, 'docs/api'))).toBe(1)
      expect(await firstValueFrom(service.folderFileCount(ExchangeFileEntryScopeEnum.CASE, 'empty'))).toBe(0)
    })

    it('reuses the cached recursive manifest of the scope', async () => {
      init()
      controller.getNamespaceFilesManifestExchange.mockReturnValue(
        of({ capability: 'READ', files: [entry('docs/a.md')] })
      )

      await firstValueFrom(service.folderFileCount(ExchangeFileEntryScopeEnum.NAMESPACE, 'docs'))
      await firstValueFrom(service.folderFileCount(ExchangeFileEntryScopeEnum.NAMESPACE, 'docs'))

      expect(controller.getNamespaceFilesManifestExchange).toHaveBeenCalledTimes(1)
    })

    it('errors when the manifest cannot be read, so the caller keeps its generic wording', async () => {
      init()
      controller.getCaseFilesManifestExchange.mockReturnValue(throwError(() => ({ status: 500 })))

      await expect(firstValueFrom(service.folderFileCount(ExchangeFileEntryScopeEnum.CASE, 'docs'))).rejects.toEqual({
        status: 500,
      })
    })
  })
})
