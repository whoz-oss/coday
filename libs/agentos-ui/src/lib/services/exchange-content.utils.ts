import { ExchangeFileEntry } from '@whoz-oss/agentos-api-client'

/**
 * Pure helpers ported from the legacy file-exchange drawer
 * (apps/client/.../content-viewer.service.ts + file-exchange-drawer.component.ts).
 *
 * No Angular, no HTTP — safe to import anywhere in the exchange UI.
 */

export type ContentFormat = 'markdown' | 'json' | 'yaml' | 'text' | 'html'

/** Maximum size (bytes) a file may have to be previewed inline. 20 MB, per legacy. */
export const MAX_VIEWABLE_SIZE = 20 * 1024 * 1024

/** Binary extensions that are never rendered inline (download only). */
const BINARY_EXTENSIONS = [
  'pdf',
  'png',
  'jpg',
  'jpeg',
  'gif',
  'webp',
  'zip',
  'tar',
  'gz',
  'exe',
  'bin',
  'doc',
  'docx',
  'xls',
  'xlsx',
  'ppt',
  'pptx',
]

function extensionOf(filename: string): string {
  return filename.split('.').pop()?.toLowerCase() ?? ''
}

/** Material icon name for a file, derived from its extension. */
export function getFileIcon(filename: string): string {
  switch (extensionOf(filename)) {
    case 'pdf':
      return 'picture_as_pdf'
    case 'csv':
    case 'xlsx':
    case 'xls':
      return 'table_chart'
    case 'txt':
    case 'md':
      return 'description'
    case 'jpg':
    case 'jpeg':
    case 'png':
    case 'gif':
    case 'webp':
      return 'image'
    case 'zip':
    case 'tar':
    case 'gz':
      return 'folder_zip'
    default:
      return 'insert_drive_file'
  }
}

/** Human-readable file size, e.g. 1536 → "1.5 KB". */
export function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

/** Locale date + time for an ISO timestamp (graceful on invalid input). */
export function formatDate(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  return date.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/** Detect the render format from a filename extension. */
export function detectFormat(filename: string): ContentFormat {
  switch (extensionOf(filename)) {
    case 'md':
    case 'markdown':
      return 'markdown'
    case 'json':
      return 'json'
    case 'yaml':
    case 'yml':
      return 'yaml'
    case 'html':
    case 'htm':
      return 'html'
    default:
      return 'text'
  }
}

/** True when the format (by extension) can be shown inline — binary types are excluded. */
export function isFormatViewable(filename: string): boolean {
  return !BINARY_EXTENSIONS.includes(extensionOf(filename))
}

/** True when a file is small enough to preview inline (≤ 20 MB). */
export function isViewable(fileSize: number): boolean {
  return fileSize <= MAX_VIEWABLE_SIZE
}

/** A file can be previewed when its format is viewable AND it is within the size limit. */
export function canViewFile(file: Pick<ExchangeFileEntry, 'filename' | 'size'>): boolean {
  return isFormatViewable(file.filename) && isViewable(file.size)
}

/** Pretty-print JSON content with 2-space indentation; returns the input unchanged on parse error. */
export function reindentJson(content: string): string {
  try {
    return JSON.stringify(JSON.parse(content), null, 2)
  } catch {
    return content
  }
}
