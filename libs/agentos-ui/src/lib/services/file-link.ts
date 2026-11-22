/** A file reference found in an agent reply: a path, with the position to open when there is one. */
export interface FileReference {
  path: string
  line?: number
  column?: number
}

/**
 * An optional `/`, `./` or `../` prefix, then `[\w.@+-]` segments joined by `/`, the last one with a 1-10 character
 * extension, then an optional `:line` and `:line:column`. Group 1 is the path without its position.
 */
const FILE_REFERENCE_PATTERN = /^((?:\.{1,2}\/|\/)?(?:[\w.@+-]+\/)*[\w.@+-]*\.[A-Za-z0-9]{1,10})(?::(\d+)(?::(\d+))?)?$/

const HTML_ESCAPES: Readonly<Record<string, string>> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
}

/**
 * Parses the text of a code span as a file reference (`src/a.ts`, `Case.kt:21`, `/abs/x.kt:10:5`), or returns null.
 * A bare name needs a `:line` suffix, so `user.name` and `foo.bar` stay plain code.
 */
export function parseFileReference(text: string): FileReference | null {
  if (text.includes('://')) return null
  const match = FILE_REFERENCE_PATTERN.exec(text)
  if (!match) return null
  const path = match[1] ?? ''
  const line: string | undefined = match[2]
  const column: string | undefined = match[3]
  // A `.` or `..` segment is not a path, except the leading `./` or `../` the pattern already takes.
  const body = path.replace(/^(?:\.{1,2}\/|\/)/, '')
  if (body.split('/').some((segment) => /^\.+$/.test(segment))) return null
  if (line === undefined && !path.includes('/')) return null
  return {
    path,
    line: line === undefined ? undefined : Number(line),
    column: column === undefined ? undefined : Number(column),
  }
}

function isAbsolutePath(path: string): boolean {
  return path.startsWith('/')
}

/**
 * Root of the namespace project on this machine: the parent directory of its `configPath`, as the FILES integration
 * of the namespaces reads it (`{{NAMESPACE_CONFIG_PATH}}/..`). Null without an absolute configPath.
 */
export function projectRootOf(configPath: string | undefined): string | null {
  const path = configPath?.trim().replace(/\/+$/, '') ?? ''
  if (!isAbsolutePath(path)) return null
  return path.slice(0, path.lastIndexOf('/')) || '/'
}

/** Absolute paths are returned as they are; relative ones are joined to the project root with one `/`. */
export function resolveFilePath(root: string, path: string): string {
  if (isAbsolutePath(path)) return path
  const relative = path.replace(/^(?:\.\/)+/, '')
  return `${root.replace(/\/+$/, '')}/${relative}`
}

/** Fills an IDE URL template: `{file}` is the URL-encoded absolute path, `{line}` and `{column}` default to 1. */
export function buildIdeUrl(template: string, absPath: string, line?: number, column?: number): string {
  return template
    .replace(/\{file\}/g, () => encodeURIComponent(absPath))
    .replace(/\{line\}/g, () => String(line ?? 1))
    .replace(/\{column\}/g, () => String(column ?? 1))
}

export function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (char) => HTML_ESCAPES[char] ?? char)
}

/**
 * Markup of a file reference rendered as a code span. `escapedText` is the code text already HTML-escaped; the
 * attribute values are escaped here. The link keeps `href="#"` (IDE URLs are stripped by the sanitizer): the click
 * handler of the chat reads the data attributes and opens the IDE.
 */
export function renderFileCodespan(escapedText: string, ref: FileReference): string {
  const line = ref.line === undefined ? '' : ` data-line="${ref.line}"`
  const column = ref.column === undefined ? '' : ` data-column="${ref.column}"`
  return (
    `<a class="agentos-file-link" href="#" data-file="${escapeHtml(ref.path)}"${line}${column} ` +
    `title="Open in IntelliJ IDEA"><code>${escapedText}</code></a>`
  )
}
