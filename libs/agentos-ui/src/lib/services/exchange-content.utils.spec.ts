import {
  canViewFile,
  detectFormat,
  formatSize,
  getFileIcon,
  isFormatViewable,
  isViewable,
  MAX_VIEWABLE_SIZE,
  reindentJson,
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
  })

  describe('isFormatViewable', () => {
    it('rejects known binary extensions', () => {
      expect(isFormatViewable('a.pdf')).toBe(false)
      expect(isFormatViewable('a.png')).toBe(false)
      expect(isFormatViewable('a.zip')).toBe(false)
      expect(isFormatViewable('a.xlsx')).toBe(false)
    })
    it('accepts text-ish extensions', () => {
      expect(isFormatViewable('a.md')).toBe(true)
      expect(isFormatViewable('a.json')).toBe(true)
      expect(isFormatViewable('a.txt')).toBe(true)
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
      expect(canViewFile({ filename: 'a.png', size: 100 })).toBe(false)
      expect(canViewFile({ filename: 'a.md', size: MAX_VIEWABLE_SIZE + 1 })).toBe(false)
    })
  })

  describe('getFileIcon', () => {
    it('maps by extension with a fallback', () => {
      expect(getFileIcon('a.pdf')).toBe('picture_as_pdf')
      expect(getFileIcon('a.png')).toBe('image')
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
})
