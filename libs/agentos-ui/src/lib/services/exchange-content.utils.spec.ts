import {
  buildSandboxedHtmlDocument,
  canViewFile,
  detectFormat,
  ensureImageBlob,
  ensurePdfBlob,
  ensureTypedBlob,
  isBlobPreviewFile,
  isImageFile,
  isPdfFile,
  exchangeMutationScope,
  formatSize,
  getFileIcon,
  isFormatViewable,
  isEditableFormat,
  isViewable,
  MAX_VIEWABLE_SIZE,
  reindentJson,
  selectionLineRange,
} from './exchange-content.utils'

describe('exchange-content.utils', () => {
  describe('detectFormat', () => {
    it('maps extensions to formats', () => {
      expect(detectFormat('a.md')).toBe('markdown')
      expect(detectFormat('a.json')).toBe('json')
      expect(detectFormat('a.yaml')).toBe('yaml')
      expect(detectFormat('a.yml')).toBe('yaml')
      expect(detectFormat('a.html')).toBe('html')
      expect(detectFormat('a.log')).toBe('text')
    })
    it.each(['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp', 'ico'])('detects .%s as image', (ext) => {
      expect(detectFormat(`a.${ext}`)).toBe('image')
      expect(detectFormat(`A.${ext.toUpperCase()}`)).toBe('image')
      expect(isImageFile(`dir/a.${ext}`)).toBe(true)
    })
    it('does not treat zip or text as image', () => {
      expect(isImageFile('a.pdf')).toBe(false)
      expect(isImageFile('a.zip')).toBe(false)
    })
    it('detects pdf, whatever the case', () => {
      expect(detectFormat('a.pdf')).toBe('pdf')
      expect(detectFormat('dir/A.PDF')).toBe('pdf')
      expect(isPdfFile('a.pdf')).toBe(true)
      expect(isPdfFile('A.PDF')).toBe(true)
      expect(isPdfFile('a.pdf.txt')).toBe(false)
      expect(isPdfFile('a.png')).toBe(false)
      expect(isBlobPreviewFile('a.pdf')).toBe(true)
      expect(isBlobPreviewFile('a.png')).toBe(true)
      expect(isBlobPreviewFile('a.html')).toBe(false)
      expect(isBlobPreviewFile('a.zip')).toBe(false)
    })
  })

  describe('ensurePdfBlob / ensureTypedBlob', () => {
    it('re-wraps a generic or untyped blob as application/pdf, so the browser renders it', () => {
      expect(ensurePdfBlob(new Blob(['%PDF'], { type: 'application/octet-stream' })).type).toBe('application/pdf')
      expect(ensurePdfBlob(new Blob(['%PDF'])).type).toBe('application/pdf')
    })
    it('keeps a blob that is already a pdf', () => {
      const blob = new Blob(['%PDF'], { type: 'application/pdf' })
      expect(ensurePdfBlob(blob)).toBe(blob)
    })
    it('types by extension: pdf as pdf, images as images', () => {
      expect(ensureTypedBlob(new Blob(['x']), 'a.pdf').type).toBe('application/pdf')
      expect(ensureTypedBlob(new Blob(['x']), 'a.svg').type).toBe('image/svg+xml')
      const png = new Blob(['x'], { type: 'image/png' })
      expect(ensureTypedBlob(png, 'a.png')).toBe(png)
    })
  })

  describe('ensureImageBlob', () => {
    it('re-wraps a generic blob with the SVG MIME type', () => {
      const wrapped = ensureImageBlob(new Blob(['<svg/>'], { type: 'application/octet-stream' }), 'a.svg')
      expect(wrapped.type).toBe('image/svg+xml')
    })
    it('re-wraps an untyped blob with the extension MIME type', () => {
      expect(ensureImageBlob(new Blob(['x']), 'a.jpg').type).toBe('image/jpeg')
    })
    it('keeps a blob that already has an image type', () => {
      const blob = new Blob(['x'], { type: 'image/png' })
      expect(ensureImageBlob(blob, 'a.png')).toBe(blob)
    })
  })

  describe('isFormatViewable', () => {
    it('rejects known binary extensions', () => {
      expect(isFormatViewable('a.pdf')).toBe(false)
      expect(isFormatViewable('a.png')).toBe(false)
      expect(isFormatViewable('a.zip')).toBe(false)
      expect(isFormatViewable('a.xlsx')).toBe(false)
      expect(isFormatViewable('a.svg')).toBe(false)
    })
    it('accepts text-ish extensions', () => {
      expect(isFormatViewable('a.md')).toBe(true)
      expect(isFormatViewable('a.json')).toBe(true)
      expect(isFormatViewable('a.txt')).toBe(true)
    })
  })

  describe('isEditableFormat', () => {
    it('accepts the text formats served by the text endpoint', () => {
      for (const name of ['a.md', 'a.json', 'a.yaml', 'a.yml', 'a.txt', 'a.csv', 'a.html', 'a.log', 'Makefile']) {
        expect(isEditableFormat(name)).toBe(true)
      }
    })
    it('never accepts images, PDF or other binaries', () => {
      for (const name of ['a.png', 'a.pdf', 'a.zip', 'a.xlsx', 'a.docx', 'a.mp3', 'a.ico', 'A.PNG']) {
        expect(isEditableFormat(name)).toBe(false)
      }
    })
  })

  describe('isViewable (size)', () => {
    it('caps at 20 MB', () => {
      expect(isViewable(MAX_VIEWABLE_SIZE)).toBe(true)
      expect(isViewable(MAX_VIEWABLE_SIZE + 1)).toBe(false)
    })
  })

  describe('canViewFile', () => {
    it('requires both viewable format and size within limit', () => {
      expect(canViewFile({ filename: 'a.md', size: 100 })).toBe(true)
      expect(canViewFile({ filename: 'a.png', size: 100 })).toBe(true)
      expect(canViewFile({ name: 'a.svg', size: 100 })).toBe(true)
      expect(canViewFile({ filename: 'a.png', size: MAX_VIEWABLE_SIZE + 1 })).toBe(false)
      expect(canViewFile({ filename: 'a.pdf', size: 100 })).toBe(true)
      expect(canViewFile({ name: 'a.pdf', size: MAX_VIEWABLE_SIZE })).toBe(true)
      expect(canViewFile({ filename: 'a.pdf', size: MAX_VIEWABLE_SIZE + 1 })).toBe(false)
      expect(canViewFile({ filename: 'a.pdf' })).toBe(false)
      expect(canViewFile({ filename: 'a.zip', size: 100 })).toBe(false)
      expect(canViewFile({ filename: 'a.md', size: MAX_VIEWABLE_SIZE + 1 })).toBe(false)
    })
  })

  describe('buildSandboxedHtmlDocument', () => {
    const iframeOf = (html: string) => new DOMParser().parseFromString(html, 'text/html').querySelector('iframe')!

    it('wraps the content in a sandboxed iframe that can run scripts but never shares the origin', () => {
      const html = buildSandboxedHtmlDocument('<p>hi</p>', 'report.html')
      const sandbox = iframeOf(html).getAttribute('sandbox')!
      expect(sandbox).toContain('allow-scripts')
      expect(sandbox).not.toContain('allow-same-origin')
      expect(sandbox).not.toContain('allow-top-navigation')
      expect(html.startsWith('<!doctype html>')).toBe(true)
    })

    it('escapes the content for the srcdoc attribute, and gives it back intact once parsed', () => {
      const content = `<a href="x" title='y'>T&C</a><script>if (a < b && c > d) alert("x")</script>`
      const html = buildSandboxedHtmlDocument(content, 'a.html')
      // Nothing of the content can end the attribute or open a tag in the wrapper.
      expect(html).toContain('srcdoc="&lt;a href=&quot;x&quot; title=&#39;y&#39;&gt;T&amp;C&lt;/a&gt;')
      expect(html.match(/<script/g)).toBeNull()
      expect(iframeOf(html).getAttribute('srcdoc')).toBe(content)
    })

    it('escapes the title', () => {
      const html = buildSandboxedHtmlDocument('x', '"><script>alert(1)</script>&.html')
      const doc = new DOMParser().parseFromString(html, 'text/html')
      expect(doc.title).toBe('"><script>alert(1)</script>&.html')
      expect(doc.querySelector('script')).toBeNull()
      expect(html).toContain('<title>&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;&amp;.html</title>')
    })

    it('has no script of its own', () => {
      expect(buildSandboxedHtmlDocument('', 't')).not.toContain('<script')
    })
  })

  describe('getFileIcon', () => {
    it('maps by extension with a fallback', () => {
      expect(getFileIcon('a.pdf')).toBe('picture_as_pdf')
      expect(getFileIcon('a.png')).toBe('image')
      expect(getFileIcon('a.svg')).toBe('image')
      expect(getFileIcon('a.bmp')).toBe('image')
      expect(getFileIcon('a.zip')).toBe('folder_zip')
      expect(getFileIcon('a.md')).toBe('description')
      expect(getFileIcon('a.unknown')).toBe('insert_drive_file')
    })
  })

  describe('formatSize', () => {
    it('formats B / KB / MB', () => {
      expect(formatSize(512)).toBe('512 B')
      expect(formatSize(1536)).toBe('1.5 KB')
      expect(formatSize(5 * 1024 * 1024)).toBe('5.0 MB')
    })
  })

  describe('reindentJson', () => {
    it('pretty-prints valid JSON and leaves invalid JSON untouched', () => {
      expect(reindentJson('{"a":1}')).toBe('{\n  "a": 1\n}')
      expect(reindentJson('not json')).toBe('not json')
    })
  })

  describe('selectionLineRange', () => {
    const text = 'one\ntwo\nthree\nfour'
    it('computes 1-based lines of a selection', () => {
      expect(selectionLineRange(text, 0, 3)).toEqual({ startLine: 1, endLine: 1 })
      expect(selectionLineRange(text, 4, 13)).toEqual({ startLine: 2, endLine: 3 })
      expect(selectionLineRange(text, 14, 18)).toEqual({ startLine: 4, endLine: 4 })
    })
    it('does not count the next line for a selection ending on a line break', () => {
      expect(selectionLineRange(text, 4, 8)).toEqual({ startLine: 2, endLine: 2 })
    })
  })

  describe('exchangeMutationScope', () => {
    it('maps mutating case/namespace exchange tools to their scope', () => {
      expect(exchangeMutationScope('case-exchange__editFiles')).toBe('case')
      expect(exchangeMutationScope('case-exchange__remove')).toBe('case')
      expect(exchangeMutationScope('case-exchange__moveFile')).toBe('case')
      expect(exchangeMutationScope('namespace-exchange__editFiles')).toBe('namespace')
      expect(exchangeMutationScope('namespace-exchange__remove')).toBe('namespace')
    })

    it('returns null for read-only, unknown, or missing tool names', () => {
      expect(exchangeMutationScope('case-exchange__readFile')).toBeNull()
      expect(exchangeMutationScope('case-exchange__listFiles')).toBeNull()
      expect(exchangeMutationScope('some-other-tool')).toBeNull()
      expect(exchangeMutationScope('')).toBeNull()
      expect(exchangeMutationScope(undefined)).toBeNull()
    })
  })
})
