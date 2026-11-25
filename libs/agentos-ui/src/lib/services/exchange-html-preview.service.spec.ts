import { TestBed } from '@angular/core/testing'
import { buildSandboxedHtmlDocument } from './exchange-content.utils'
import { ExchangeHtmlPreviewService, HTML_PREVIEW_URL_TTL_MS } from './exchange-html-preview.service'

/** jsdom has no Blob.text(). */
function readBlob(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result as string)
    reader.onerror = () => reject(reader.error)
    reader.readAsText(blob)
  })
}

describe('ExchangeHtmlPreviewService', () => {
  const original = { create: URL.createObjectURL, revoke: URL.revokeObjectURL }

  afterEach(() => {
    URL.createObjectURL = original.create
    URL.revokeObjectURL = original.revoke
    jest.useRealTimers()
    jest.restoreAllMocks()
  })

  it('opens a sandboxing wrapper (never the raw file) from a blob URL, then revokes it later', () => {
    jest.useFakeTimers()
    const create = jest.fn().mockReturnValue('blob:http://localhost/abc')
    const revoke = jest.fn()
    URL.createObjectURL = create
    URL.revokeObjectURL = revoke
    const open = jest.spyOn(window, 'open').mockReturnValue(null)

    TestBed.inject(ExchangeHtmlPreviewService).openInNewTab('<script>1</script>', 'report.html')

    const blob = create.mock.calls[0]![0] as Blob
    expect(blob.type).toBe('text/html')
    expect(open).toHaveBeenCalledWith('blob:http://localhost/abc', '_blank', 'noopener')
    expect(revoke).not.toHaveBeenCalled()

    jest.advanceTimersByTime(HTML_PREVIEW_URL_TTL_MS)
    expect(revoke).toHaveBeenCalledWith('blob:http://localhost/abc')
  })

  it('writes the sandboxed wrapper document into the blob', async () => {
    const create = jest.fn().mockReturnValue('blob:x')
    URL.createObjectURL = create
    URL.revokeObjectURL = jest.fn()
    jest.spyOn(window, 'open').mockReturnValue(null)

    TestBed.inject(ExchangeHtmlPreviewService).openInNewTab('<p>x</p>', 'a.html')

    const blob = create.mock.calls[0]![0] as Blob
    expect(await readBlob(blob)).toBe(buildSandboxedHtmlDocument('<p>x</p>', 'a.html'))
  })

  describe('openBlobInNewTab', () => {
    it('opens a PDF from its own object URL, noopener, and revokes that URL later', () => {
      jest.useFakeTimers()
      const create = jest.fn().mockReturnValue('blob:http://localhost/pdf')
      const revoke = jest.fn()
      URL.createObjectURL = create
      URL.revokeObjectURL = revoke
      const open = jest.spyOn(window, 'open').mockReturnValue(null)
      const pdf = new Blob(['%PDF'], { type: 'application/pdf' })

      TestBed.inject(ExchangeHtmlPreviewService).openBlobInNewTab(pdf)

      expect(create).toHaveBeenCalledWith(pdf)
      expect(open).toHaveBeenCalledWith('blob:http://localhost/pdf', '_blank', 'noopener')
      expect(revoke).not.toHaveBeenCalled()
      jest.advanceTimersByTime(HTML_PREVIEW_URL_TTL_MS)
      expect(revoke).toHaveBeenCalledWith('blob:http://localhost/pdf')
    })

    it.each(['text/html', 'image/svg+xml', 'application/octet-stream', ''])(
      'refuses a blob typed %j: it would run with the origin of the app',
      (type) => {
        const create = jest.fn()
        URL.createObjectURL = create
        URL.revokeObjectURL = jest.fn()
        const open = jest.spyOn(window, 'open').mockReturnValue(null)

        TestBed.inject(ExchangeHtmlPreviewService).openBlobInNewTab(new Blob(['<script>1</script>'], { type }))

        expect(create).not.toHaveBeenCalled()
        expect(open).not.toHaveBeenCalled()
      }
    )
  })
})
