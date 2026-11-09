/**
 * Pure helpers ported from the legacy file-exchange drawer
 * (apps/client/.../content-viewer.service.ts + file-exchange-drawer.component.ts).
 *
 * No Angular, no HTTP — safe to import anywhere in the exchange UI.
 */

export type ContentFormat = 'markdown' | 'json' | 'yaml' | 'text' | 'html' | 'image'

/** Maximum size (bytes) a file may have to be previewed inline. 20 MB, per legacy. */
export const MAX_VIEWABLE_SIZE = 20 * 1024 * 1024

/**
 * Image formats a browser renders natively in an <img>, with the MIME type to give their blob.
 * They are binary for the text endpoint, but previewed through the download endpoint instead.
 */
const IMAGE_MIME_TYPES: Record<string, string> = {
  png: 'image/png',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  gif: 'image/gif',
  webp: 'image/webp',
  svg: 'image/svg+xml',
  bmp: 'image/bmp',
  ico: 'image/x-icon',
}

/** Binary extensions that are never rendered as text (images have their own preview path). */
const BINARY_EXTENSIONS = [
  'pdf',
  'png',
  'jpg',
  'jpeg',
  'gif',
  'webp',
  'svg',
  'bmp',
  'ico',
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
    case 'svg':
    case 'bmp':
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
  if (isImageFile(filename)) return 'image'
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

/** True for the image extensions the preview renders natively. */
export function isImageFile(filename: string): boolean {
  return extensionOf(filename) in IMAGE_MIME_TYPES
}

/**
 * Make sure a downloaded image blob carries an image MIME type, so an <img> renders it.
 *
 * The download endpoint answers with a generic type (e.g. octet-stream) for some files, which an
 * <img> refuses for SVG in particular. Re-wrapping only happens when the type is not already an image.
 */
export function ensureImageBlob(blob: Blob, filename: string): Blob {
  const expected = IMAGE_MIME_TYPES[extensionOf(filename)]
  if (!expected) return blob
  const isSvg = expected === 'image/svg+xml'
  const typeOk = isSvg ? blob.type === expected : blob.type.startsWith('image/')
  return typeOk ? blob : new Blob([blob], { type: expected })
}

/** True when the format (by extension) can be shown as text — binary types are excluded. */
export function isFormatViewable(filename: string): boolean {
  return !BINARY_EXTENSIONS.includes(extensionOf(filename))
}

/** True when a file is small enough to preview inline (≤ 20 MB). */
export function isViewable(fileSize: number): boolean {
  return fileSize <= MAX_VIEWABLE_SIZE
}

/**
 * A file can be previewed when its format is viewable (text or image) AND it is within the size limit.
 *
 * Accepts either shape of entry: the manifest calls the leaf `filename`, a directory listing calls
 * it `name`. A directory reports no size, and is never previewable.
 */
export function canViewFile(file: { filename?: string; name?: string; size?: number }): boolean {
  const leaf = file.filename ?? file.name ?? ''
  return file.size !== undefined && (isImageFile(leaf) || isFormatViewable(leaf)) && isViewable(file.size)
}

/** Pretty-print JSON content with 2-space indentation; returns the input unchanged on parse error. */
export function reindentJson(content: string): string {
  try {
    return JSON.stringify(JSON.parse(content), null, 2)
  } catch {
    return content
  }
}

/**
 * The exchange scope a mutating file-plugin tool (create/edit/remove/move) acts on, or null when
 * the tool is not an exchange mutation. Drives the live manifest refresh in the case chat.
 * Tool names follow `<case|namespace>-exchange__<tool>`.
 */
export function exchangeMutationScope(toolName?: string): 'case' | 'namespace' | null {
  const match = /^(case|namespace)-exchange__(editFiles|remove|moveFile)$/.exec(toolName ?? '')
  return match ? (match[1] as 'case' | 'namespace') : null
}
