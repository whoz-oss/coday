import { TestBed } from '@angular/core/testing'
import { ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { ExchangeStateService } from '../../services/exchange-state.service'
import { ComposerAttachmentsService } from './composer-attachments.service'
import { MAX_ATTACHMENTS } from './composer-attachments.utils'

describe('ComposerAttachmentsService', () => {
  let exchangeState: { uploadFile: jest.Mock }
  let service: ComposerAttachmentsService

  function files(count: number, prefix = 'file'): File[] {
    return Array.from({ length: count }, (_, i) => new File(['x'], `${prefix}-${i}.txt`))
  }

  function dragEvent(types: string[] = ['Files'], dropped: File[] = []): DragEvent {
    return {
      preventDefault: jest.fn(),
      dataTransfer: { types, files: dropped, dropEffect: 'none' },
      relatedTarget: null,
    } as unknown as DragEvent
  }

  beforeEach(() => {
    exchangeState = { uploadFile: jest.fn().mockResolvedValue({ success: true }) }
    TestBed.configureTestingModule({
      providers: [ComposerAttachmentsService, { provide: ExchangeStateService, useValue: exchangeState }],
    })
    service = TestBed.inject(ComposerAttachmentsService)
  })

  describe('staging', () => {
    it('adds files as pending attachments', () => {
      service.addFiles(files(2))
      expect(service.attachments().map((a) => a.status)).toEqual(['pending', 'pending'])
      expect(service.hasAttachments()).toBe(true)
      expect(service.limitError()).toBeNull()
    })

    it('caps at the maximum and surfaces the limit message', () => {
      service.addFiles(files(12))
      expect(service.attachments()).toHaveLength(MAX_ATTACHMENTS)
      expect(service.limitError()).toBe(`You can attach up to ${MAX_ATTACHMENTS} files per message.`)
    })

    it('enforces the cap across successive adds', () => {
      service.addFiles(files(8, 'first'))
      service.addFiles(files(5, 'second'))
      expect(service.attachments()).toHaveLength(MAX_ATTACHMENTS)
      expect(service.limitError()).not.toBeNull()
    })

    it('remove() drops the chip and clears the limit message', () => {
      service.addFiles(files(12))
      expect(service.limitError()).not.toBeNull()
      const id = service.attachments()[0]!.id
      service.remove(id)
      expect(service.attachments()).toHaveLength(9)
      expect(service.attachments().some((a) => a.id === id)).toBe(false)
      expect(service.limitError()).toBeNull()
    })

    it('staging and removal are frozen while an upload batch is in flight', () => {
      service.addFiles(files(1))
      service.isUploading.set(true)

      service.addFiles(files(2, 'late'))
      service.remove(service.attachments()[0]!.id)

      expect(service.attachments()).toHaveLength(1)
    })

    it('reset() clears everything', () => {
      service.addFiles(files(3))
      service.reset()
      expect(service.attachments()).toEqual([])
      expect(service.hasAttachments()).toBe(false)
      expect(service.limitError()).toBeNull()
    })
  })

  describe('drag & drop', () => {
    it('dragenter with files sets the highlight', () => {
      const event = dragEvent()
      service.onDragEnter(event)
      expect(service.isDragOver()).toBe(true)
      expect(event.preventDefault).toHaveBeenCalled()
    })

    it('dragenter without files is ignored (text selection drag)', () => {
      const event = dragEvent(['text/plain'])
      service.onDragEnter(event)
      expect(service.isDragOver()).toBe(false)
      expect(event.preventDefault).not.toHaveBeenCalled()
    })

    it('crossing a child (enter then leave pair) keeps the highlight; leaving for real clears it', () => {
      // Container enter, then a child boundary: child enter bubbles before the previous
      // element's leave, so the depth counter never touches zero until the real exit.
      // (Depth-based on purpose: WebKit reports relatedTarget as null on dragleave.)
      service.onDragEnter(dragEvent())
      service.onDragEnter(dragEvent())
      service.onDragLeave()
      expect(service.isDragOver()).toBe(true)

      service.onDragLeave()
      expect(service.isDragOver()).toBe(false)
    })

    it('drop adds the files and clears the highlight', () => {
      service.onDragEnter(dragEvent())
      const event = dragEvent(['Files'], files(2))

      service.onDrop(event)

      expect(service.isDragOver()).toBe(false)
      expect(service.attachments()).toHaveLength(2)
      expect(event.preventDefault).toHaveBeenCalled()
    })
  })

  describe('paste', () => {
    function pasteEvent(pasted: File[] = []): ClipboardEvent {
      return {
        preventDefault: jest.fn(),
        clipboardData: { files: pasted },
      } as unknown as ClipboardEvent
    }

    it('pasting files stages them and prevents the default paste', () => {
      const event = pasteEvent(files(2))

      service.onPaste(event)

      expect(service.attachments().map((a) => a.file.name)).toEqual(['file-0.txt', 'file-1.txt'])
      expect(event.preventDefault).toHaveBeenCalled()
    })

    it('pasting text only is left to the browser: nothing staged, default not prevented', () => {
      const event = pasteEvent()

      service.onPaste(event)

      expect(service.hasAttachments()).toBe(false)
      expect(event.preventDefault).not.toHaveBeenCalled()
    })

    it('document paste with nothing focused stages files', () => {
      const event = { ...pasteEvent(files(1)), target: document.body } as unknown as ClipboardEvent

      service.onDocumentPaste(event)

      expect(service.attachments()).toHaveLength(1)
      expect(event.preventDefault).toHaveBeenCalled()
    })

    it('document paste is not staged twice when the composer already handled it', () => {
      const event = {
        ...pasteEvent(files(1)),
        defaultPrevented: true,
        target: document.body,
      } as unknown as ClipboardEvent

      service.onDocumentPaste(event)

      expect(service.hasAttachments()).toBe(false)
    })

    it('document paste aimed at another text field is left to that field', () => {
      const otherField = document.createElement('input')
      const event = { ...pasteEvent(files(1)), target: otherField } as unknown as ClipboardEvent

      service.onDocumentPaste(event)

      expect(service.hasAttachments()).toBe(false)
      expect(event.preventDefault).not.toHaveBeenCalled()
    })

    it('pasting files during an upload batch is ignored by the staging guard', async () => {
      let resolveUpload: (value: { success: boolean }) => void = () => undefined
      exchangeState.uploadFile.mockReturnValueOnce(new Promise((resolve) => (resolveUpload = resolve)))
      service.addFiles(files(1))
      const batch = service.uploadAllAndBuildMention(ExchangeFileEntryScopeEnum.CASE)

      service.onPaste(pasteEvent(files(1, 'late')))
      resolveUpload({ success: true })
      await batch

      expect(service.attachments().map((a) => a.file.name)).toEqual(['file-0.txt'])
    })
  })

  describe('uploadAllAndBuildMention', () => {
    it('uploads sequentially, marks everything uploaded and returns the mention', async () => {
      service.addFiles([new File(['x'], 'a.pdf'), new File(['x'], 'b.xlsx')])

      const mention = await service.uploadAllAndBuildMention(ExchangeFileEntryScopeEnum.CASE)

      expect(mention).toBe('[Files attached to the case exchange: a.pdf, b.xlsx]')
      expect(service.attachments().map((a) => a.status)).toEqual(['uploaded', 'uploaded'])
      expect(exchangeState.uploadFile).toHaveBeenCalledTimes(2)
      expect(exchangeState.uploadFile).toHaveBeenCalledWith(ExchangeFileEntryScopeEnum.CASE, expect.any(File))
      expect(service.isUploading()).toBe(false)
    })

    it('continues past a failure, returns null and maps the error on the failed chip', async () => {
      service.addFiles([new File(['x'], 'dup.pdf'), new File(['x'], 'ok.txt')])
      exchangeState.uploadFile
        .mockResolvedValueOnce({ success: false, error: 'A file with this name already exists.' })
        .mockResolvedValueOnce({ success: true })

      const mention = await service.uploadAllAndBuildMention(ExchangeFileEntryScopeEnum.CASE)

      expect(mention).toBeNull()
      const [failed, succeeded] = service.attachments()
      expect(failed!.status).toBe('error')
      expect(failed!.error).toBe('A file with this name already exists.')
      expect(succeeded!.status).toBe('uploaded')
      expect(exchangeState.uploadFile).toHaveBeenCalledTimes(2)
    })

    it('a retry skips the files already uploaded (no self-inflicted conflict)', async () => {
      service.addFiles([new File(['x'], 'dup.pdf'), new File(['x'], 'ok.txt')])
      exchangeState.uploadFile
        .mockResolvedValueOnce({ success: false, error: 'A file with this name already exists.' })
        .mockResolvedValue({ success: true })
      await service.uploadAllAndBuildMention(ExchangeFileEntryScopeEnum.CASE)
      exchangeState.uploadFile.mockClear()
      exchangeState.uploadFile.mockResolvedValue({ success: true })

      const mention = await service.uploadAllAndBuildMention(ExchangeFileEntryScopeEnum.CASE)

      expect(exchangeState.uploadFile).toHaveBeenCalledTimes(1)
      expect(mention).toBe('[Files attached to the case exchange: dup.pdf, ok.txt]')
    })

    it('a reset during the batch (case switch) aborts: null returned, remaining files not uploaded', async () => {
      service.addFiles([new File(['x'], 'a.pdf'), new File(['x'], 'b.pdf')])
      exchangeState.uploadFile.mockImplementationOnce(async () => {
        // Simulates reinitialise() on a mid-upload case switch.
        service.reset()
        return { success: true }
      })

      const mention = await service.uploadAllAndBuildMention(ExchangeFileEntryScopeEnum.CASE)

      expect(mention).toBeNull()
      expect(exchangeState.uploadFile).toHaveBeenCalledTimes(1)
    })

    it('a retry under a different scope yields a mixed-scope mention', async () => {
      service.addFiles([new File(['x'], 'a.pdf'), new File(['x'], 'dup.pdf')])
      exchangeState.uploadFile
        .mockResolvedValueOnce({ success: true })
        .mockResolvedValueOnce({ success: false, error: 'A file with this name already exists.' })
      await service.uploadAllAndBuildMention(ExchangeFileEntryScopeEnum.CASE)
      exchangeState.uploadFile.mockResolvedValue({ success: true })

      const mention = await service.uploadAllAndBuildMention(ExchangeFileEntryScopeEnum.NAMESPACE)

      expect(mention).toBe(
        '[Files attached to the case exchange: a.pdf]\n[Files attached to the namespace exchange: dup.pdf]'
      )
    })

    it('toggles isUploading around the batch', async () => {
      service.addFiles([new File(['x'], 'a.pdf')])
      let uploadingDuringCall = false
      exchangeState.uploadFile.mockImplementation(async () => {
        uploadingDuringCall = service.isUploading()
        return { success: true }
      })

      await service.uploadAllAndBuildMention(ExchangeFileEntryScopeEnum.CASE)

      expect(uploadingDuringCall).toBe(true)
      expect(service.isUploading()).toBe(false)
    })
  })
})
