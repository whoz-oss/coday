import { TestBed } from '@angular/core/testing'
import {
  ExchangeControllerService,
  ExchangeFileEntry,
  ExchangeFileEntryScopeEnum,
  ExchangeManifest,
  ExchangeManifestCapabilityEnum,
} from '@whoz-oss/agentos-api-client'
import { of, throwError } from 'rxjs'
import { ExchangeStateService } from './exchange-state.service'

describe('ExchangeStateService', () => {
  let controller: {
    getCaseFilesManifestExchange: jest.Mock
    getNamespaceFilesManifestExchange: jest.Mock
    uploadCaseFileExchange: jest.Mock
    deleteCaseFileExchange: jest.Mock
    uploadNamespaceFileExchange: jest.Mock
    deleteNamespaceFileExchange: jest.Mock
  }
  let service: ExchangeStateService

  const caseFile: ExchangeFileEntry = {
    path: 'a.txt',
    filename: 'a.txt',
    size: 10,
    lastModified: '2026-01-01T00:00:00Z',
    scope: ExchangeFileEntryScopeEnum.CASE,
  }
  const nsFile: ExchangeFileEntry = {
    path: 'shared.md',
    filename: 'shared.md',
    size: 20,
    lastModified: '2026-01-02T00:00:00Z',
    scope: ExchangeFileEntryScopeEnum.NAMESPACE,
  }

  function manifest(capability: ExchangeManifestCapabilityEnum, files: ExchangeFileEntry[] = []): ExchangeManifest {
    return { files, capability }
  }

  function init(): void {
    service.initializeForCase('ns-1', 'c-1')
  }

  beforeEach(() => {
    controller = {
      getCaseFilesManifestExchange: jest.fn().mockReturnValue(of(manifest(ExchangeManifestCapabilityEnum.NONE))),
      getNamespaceFilesManifestExchange: jest.fn().mockReturnValue(of(manifest(ExchangeManifestCapabilityEnum.NONE))),
      uploadCaseFileExchange: jest.fn(),
      deleteCaseFileExchange: jest.fn(),
      uploadNamespaceFileExchange: jest.fn(),
      deleteNamespaceFileExchange: jest.fn(),
    }
    TestBed.configureTestingModule({
      providers: [ExchangeStateService, { provide: ExchangeControllerService, useValue: controller }],
    })
    service = TestBed.inject(ExchangeStateService)
  })

  describe('case scope capability mapping', () => {
    it('READ_WRITE → ready, files listed, write allowed, section visible', () => {
      controller.getCaseFilesManifestExchange.mockReturnValue(
        of(manifest(ExchangeManifestCapabilityEnum.READ_WRITE, [caseFile]))
      )
      init()
      expect(service.caseStatus()).toBe('ready')
      expect(service.caseFiles()).toEqual([caseFile])
      expect(service.canWriteCase()).toBe(true)
      expect(service.caseSectionVisible()).toBe(true)
    })

    it('READ → read-only (no write), section visible', () => {
      controller.getCaseFilesManifestExchange.mockReturnValue(
        of(manifest(ExchangeManifestCapabilityEnum.READ, [caseFile]))
      )
      init()
      expect(service.caseStatus()).toBe('ready')
      expect(service.canWriteCase()).toBe(false)
      expect(service.caseSectionVisible()).toBe(true)
    })
  })

  describe('fail-closed', () => {
    it('403 → forbidden: section hidden, NONE capability, empty list', () => {
      controller.getCaseFilesManifestExchange.mockReturnValue(throwError(() => ({ status: 403 })))
      init()
      expect(service.caseStatus()).toBe('forbidden')
      expect(service.caseSectionVisible()).toBe(false)
      expect(service.canWriteCase()).toBe(false)
      expect(service.caseFiles()).toEqual([])
    })

    it('404 → forbidden (zero disclosure)', () => {
      controller.getNamespaceFilesManifestExchange.mockReturnValue(throwError(() => ({ status: 404 })))
      init()
      expect(service.namespaceStatus()).toBe('forbidden')
      expect(service.namespaceSectionVisible()).toBe(false)
    })

    it('500 → error: section still visible, but no write affordance', () => {
      controller.getCaseFilesManifestExchange.mockReturnValue(throwError(() => ({ status: 500 })))
      init()
      expect(service.caseStatus()).toBe('error')
      expect(service.caseSectionVisible()).toBe(true)
      expect(service.canWriteCase()).toBe(false)
    })
  })

  describe('namespace scope capability', () => {
    it('READ (simple member) → visible, not writable', () => {
      controller.getNamespaceFilesManifestExchange.mockReturnValue(
        of(manifest(ExchangeManifestCapabilityEnum.READ, [nsFile]))
      )
      init()
      expect(service.namespaceStatus()).toBe('ready')
      expect(service.namespaceFiles()).toEqual([nsFile])
      expect(service.canWriteNamespace()).toBe(false)
    })

    it('READ_WRITE (namespace admin) → writable, section visible', () => {
      controller.getNamespaceFilesManifestExchange.mockReturnValue(
        of(manifest(ExchangeManifestCapabilityEnum.READ_WRITE, [nsFile]))
      )
      init()
      expect(service.namespaceStatus()).toBe('ready')
      expect(service.canWriteNamespace()).toBe(true)
      expect(service.namespaceSectionVisible()).toBe(true)
    })
  })

  describe('counts', () => {
    it('fileCount is the sum of both scopes', () => {
      controller.getCaseFilesManifestExchange.mockReturnValue(
        of(manifest(ExchangeManifestCapabilityEnum.READ_WRITE, [caseFile]))
      )
      controller.getNamespaceFilesManifestExchange.mockReturnValue(
        of(manifest(ExchangeManifestCapabilityEnum.READ, [nsFile]))
      )
      init()
      expect(service.caseFileCount()).toBe(1)
      expect(service.namespaceFileCount()).toBe(1)
      expect(service.fileCount()).toBe(2)
    })
  })

  describe('writes refresh the case manifest', () => {
    it('upload success → reloads manifest and clears isUploading', async () => {
      init()
      controller.uploadCaseFileExchange.mockReturnValue(of(caseFile))
      controller.getCaseFilesManifestExchange.mockClear()
      const result = await service.uploadFile(ExchangeFileEntryScopeEnum.CASE, new File(['x'], 'a.txt'))
      expect(result.success).toBe(true)
      expect(controller.uploadCaseFileExchange).toHaveBeenCalledWith('c-1', expect.any(File))
      expect(controller.getCaseFilesManifestExchange).toHaveBeenCalledWith('c-1')
      expect(service.isUploading()).toBe(false)
    })

    it('upload 409 → returns a friendly conflict error', async () => {
      init()
      controller.uploadCaseFileExchange.mockReturnValue(throwError(() => ({ status: 409 })))
      const result = await service.uploadFile(ExchangeFileEntryScopeEnum.CASE, new File(['x'], 'a.txt'))
      expect(result.success).toBe(false)
      expect(result.error).toBe('A file with this name already exists.')
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
      controller.getCaseFilesManifestExchange.mockClear()
      const result = await service.deleteFile(ExchangeFileEntryScopeEnum.CASE, 'a.txt')
      expect(result.success).toBe(true)
      expect(controller.deleteCaseFileExchange).toHaveBeenCalledWith('c-1', 'a.txt')
      expect(controller.getCaseFilesManifestExchange).toHaveBeenCalledWith('c-1')
    })
  })

  describe('namespace writes (admin)', () => {
    it('upload success → calls the namespace endpoint and reloads', async () => {
      init()
      controller.uploadNamespaceFileExchange.mockReturnValue(of(nsFile))
      controller.getNamespaceFilesManifestExchange.mockClear()
      const result = await service.uploadFile(ExchangeFileEntryScopeEnum.NAMESPACE, new File(['x'], 'shared.md'))
      expect(result.success).toBe(true)
      expect(controller.uploadNamespaceFileExchange).toHaveBeenCalledWith('ns-1', expect.any(File))
      expect(controller.getNamespaceFilesManifestExchange).toHaveBeenCalledWith('ns-1')
    })

    it('delete success → calls the namespace endpoint and reloads', async () => {
      init()
      controller.deleteNamespaceFileExchange.mockReturnValue(of({ success: true, message: 'ok' }))
      controller.getNamespaceFilesManifestExchange.mockClear()
      const result = await service.deleteFile(ExchangeFileEntryScopeEnum.NAMESPACE, 'shared.md')
      expect(result.success).toBe(true)
      expect(controller.deleteNamespaceFileExchange).toHaveBeenCalledWith('ns-1', 'shared.md')
      expect(controller.getNamespaceFilesManifestExchange).toHaveBeenCalledWith('ns-1')
    })
  })

  describe('refreshCase (agent file activity)', () => {
    it('refetches only the case manifest, leaving the read-only namespace scope untouched', () => {
      init()
      controller.getCaseFilesManifestExchange.mockClear()
      controller.getNamespaceFilesManifestExchange.mockClear()

      service.refreshCase()

      expect(controller.getCaseFilesManifestExchange).toHaveBeenCalledWith('c-1')
      expect(controller.getNamespaceFilesManifestExchange).not.toHaveBeenCalled()
    })

    it('refreshNamespace refetches only the namespace manifest (agent namespace mutation)', () => {
      init()
      controller.getCaseFilesManifestExchange.mockClear()
      controller.getNamespaceFilesManifestExchange.mockClear()

      service.refreshNamespace()

      expect(controller.getNamespaceFilesManifestExchange).toHaveBeenCalledWith('ns-1')
      expect(controller.getCaseFilesManifestExchange).not.toHaveBeenCalled()
    })

    it('refreshManifest still refetches both scopes', () => {
      init()
      controller.getCaseFilesManifestExchange.mockClear()
      controller.getNamespaceFilesManifestExchange.mockClear()

      service.refreshManifest()

      expect(controller.getCaseFilesManifestExchange).toHaveBeenCalledWith('c-1')
      expect(controller.getNamespaceFilesManifestExchange).toHaveBeenCalledWith('ns-1')
    })
  })
})
