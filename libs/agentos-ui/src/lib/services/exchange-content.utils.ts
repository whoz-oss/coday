/**
 * Pure helpers ported from the legacy file-exchange drawer
 * (apps/client/.../content-viewer.service.ts + file-exchange-drawer.component.ts).
 *
 * No Angular, no HTTP — safe to import anywhere in the exchange UI.
 */

export type ContentFormat = 'markdown' | 'json' | 'yaml' | 'text' | 'html' | 'image' | 'pdf'

/** MIME type a PDF blob must carry for the browser to render it instead of downloading it. */
export const PDF_MIME_TYPE = 'application/pdf'

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
    case 'pdf':
      return 'pdf'
    default:
      return 'text'
  }
}

/** True for PDF files, previewed by the browser's own viewer from an object URL (like images). */
export function isPdfFile(filename: string): boolean {
  return extensionOf(filename) === 'pdf'
}

/** True for the files previewed from the bytes of the download endpoint: images and PDFs. */
export function isBlobPreviewFile(filename: string): boolean {
  return isImageFile(filename) || isPdfFile(filename)
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

/**
 * Make sure a downloaded PDF blob carries `application/pdf`: with the generic type some servers answer with
 * (octet-stream), the browser would download the file instead of rendering it in an iframe.
 */
export function ensurePdfBlob(blob: Blob): Blob {
  return blob.type === PDF_MIME_TYPE ? blob : new Blob([blob], { type: PDF_MIME_TYPE })
}

/** The blob of a previewed image or PDF, with the MIME type its extension calls for. */
export function ensureTypedBlob(blob: Blob, filename: string): Blob {
  return isPdfFile(filename) ? ensurePdfBlob(blob) : ensureImageBlob(blob, filename)
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
 * A file can be previewed when its format is viewable (text, image or PDF) AND it is within the size limit.
 *
 * Accepts either shape of entry: the manifest calls the leaf `filename`, a directory listing calls
 * it `name`. A directory reports no size, and is never previewable.
 */
export function canViewFile(file: { filename?: string; name?: string; size?: number }): boolean {
  const leaf = file.filename ?? file.name ?? ''
  return file.size !== undefined && (isBlobPreviewFile(leaf) || isFormatViewable(leaf)) && isViewable(file.size)
}

/**
 * Sandbox of every iframe that renders a user/agent-authored HTML file.
 *
 * Scripts and popups are allowed so a report works; `allow-same-origin` and `allow-top-navigation` are NEVER
 * granted: the file runs in an opaque origin, with no access to the app's cookies, storage or DOM. Agents write
 * these files, so granting same-origin would turn any of them into an XSS running with the user's session.
 */
export const HTML_PREVIEW_SANDBOX = 'allow-scripts allow-popups allow-popups-to-escape-sandbox'

/** Escapes a string for use in HTML text and in a quoted attribute value. */
function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

/**
 * A standalone document that shows `content` in a sandboxed iframe, to open in a browser tab.
 *
 * The raw file must never be opened directly from a blob/data URL: a blob URL inherits the origin of the app and
 * its scripts would run with the user's session. This wrapper has no script of its own and delegates the rendering
 * to an iframe with the same sandbox as the in-app preview (`HTML_PREVIEW_SANDBOX`, no `allow-same-origin`).
 */
export function buildSandboxedHtmlDocument(content: string, title: string): string {
  return (
    '<!doctype html><html><head><meta charset="utf-8">' +
    `<title>${escapeHtml(title)}</title>` +
    '<style>html,body{margin:0;height:100%}iframe{border:0;width:100%;height:100%;display:block}</style>' +
    `</head><body><iframe sandbox="${HTML_PREVIEW_SANDBOX}" srcdoc="${escapeHtml(content)}"></iframe></body></html>`
  )
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
