import { ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { ExchangeScope } from '../../services/exchange-state.service'

/**
 * Pure helpers for the composer file-attachment feature (no Angular, no HTTP),
 * mirroring the style of `services/exchange-content.utils.ts`.
 */

/** Maximum number of files attachable to a single message. */
export const MAX_ATTACHMENTS = 10

// ── The lines a message carries for its files: the one place where their format lives ──────────────────────────
//
// A message has no attachment metadata: the composer appends technical lines to its text, which is what the agent
// reads to know which files to consult.
//
//   [Files attached to the case exchange: a.pdf, b.png]
//   [Files attached to the namespace exchange: c.md]
//   [Files referenced: namespace:docs/readme.md, case:notes/todo.md]
//
// They form one block, a line each, joined by a newline, and the block is separated from the text by a blank line
// (or is the whole content when there is no text). Writers (buildAttachmentMention, buildReferenceMention, the
// composers) and readers (splitAttachmentMentions) all go through the helpers below: change the format here only.

const ATTACHED_OPEN = '[Files attached to the '
const ATTACHED_MIDDLE = ' exchange: '
const REFERENCED_OPEN = '[Files referenced: '
const LINE_CLOSE = ']'
const ITEM_SEPARATOR = ', '
const LINE_SEPARATOR = '\n'
const BLOCK_SEPARATOR = '\n\n'

/** Word that names an exchange in the lines of a message. */
export type MentionScopeWord = 'case' | 'namespace'

const MENTION_SCOPES: ExchangeScope[] = [ExchangeFileEntryScopeEnum.CASE, ExchangeFileEntryScopeEnum.NAMESPACE]

export function mentionScopeWord(scope: ExchangeScope): MentionScopeWord {
  return scope === ExchangeFileEntryScopeEnum.CASE ? 'case' : 'namespace'
}

/** `[Files attached to the case exchange: a.pdf, b.png]` */
export function attachedMentionLine(scope: ExchangeScope, names: string[]): string {
  return `${ATTACHED_OPEN}${mentionScopeWord(scope)}${ATTACHED_MIDDLE}${names.join(ITEM_SEPARATOR)}${LINE_CLOSE}`
}

/** One entry of a referenced-files line: `namespace:docs/readme.md`. */
export function referencedMentionEntry(scope: ExchangeScope, path: string): string {
  return `${mentionScopeWord(scope)}:${path}`
}

/** `[Files referenced: namespace:docs/readme.md, case:notes/todo.md]` */
export function referencedMentionLine(entries: string[]): string {
  return `${REFERENCED_OPEN}${entries.join(ITEM_SEPARATOR)}${LINE_CLOSE}`
}

/** The lines of a message, as one block. */
export function joinMentionLines(lines: string[]): string {
  return lines.join(LINE_SEPARATOR)
}

/**
 * The content of a message: its text, then the block of file lines. Without a block the text is the content, without
 * text the block is.
 */
export function joinAttachmentMentions(body: string, mentions: string): string {
  if (!mentions) return body
  return body ? `${body}${BLOCK_SEPARATOR}${mentions}` : mentions
}

/** A file a message carries, as its lines tell it. */
export interface MentionedFile {
  /** Name shown on the chip: the file name, without its directory. */
  name: string
  /** Path in its exchange, as the agent reads it. */
  path: string
  scope: ExchangeScope
  /** Uploaded with the message, or picked from an exchange. */
  kind: 'attached' | 'referenced'
}

export interface AttachmentMentionSplit {
  /** What the user wrote. */
  body: string
  /** The files of the block, in order. */
  files: MentionedFile[]
  /** The block as it is in the content, to be put back with [joinAttachmentMentions]. Empty when there is none. */
  mentions: string
}

function scopeOfWord(word: string): ExchangeScope | null {
  return MENTION_SCOPES.find((scope) => mentionScopeWord(scope) === word) ?? null
}

/** The files of one line, or null when the line is not exactly one of the lines the composer writes. */
function parseMentionLine(line: string): MentionedFile[] | null {
  if (!line.endsWith(LINE_CLOSE)) return null
  if (line.startsWith(ATTACHED_OPEN)) {
    const inner = line.slice(ATTACHED_OPEN.length, -LINE_CLOSE.length)
    for (const scope of MENTION_SCOPES) {
      const prefix = `${mentionScopeWord(scope)}${ATTACHED_MIDDLE}`
      if (!inner.startsWith(prefix)) continue
      const names = inner.slice(prefix.length).split(ITEM_SEPARATOR)
      if (names.some((name) => name.length === 0)) return null
      return names.map((name) => ({ name, path: name, scope, kind: 'attached' as const }))
    }
    return null
  }
  if (line.startsWith(REFERENCED_OPEN)) {
    const files: MentionedFile[] = []
    for (const entry of line.slice(REFERENCED_OPEN.length, -LINE_CLOSE.length).split(ITEM_SEPARATOR)) {
      const colon = entry.indexOf(':')
      const scope = colon > 0 ? scopeOfWord(entry.slice(0, colon)) : null
      const path = entry.slice(colon + 1)
      if (!scope || !path) return null
      files.push({ name: path.split('/').pop() || path, path, scope, kind: 'referenced' })
    }
    return files
  }
  return null
}

/**
 * Separates the text a user wrote from the block of file lines the composer put at its end.
 *
 * Only the final block is read, and only when every one of its lines is exactly one of the lines above: a text
 * without such a block, or with a line that merely looks like one (in the middle of the text, or slightly different),
 * is returned as it is, with no file. So that `joinAttachmentMentions(body, mentions)` gives back [text] exactly,
 * which is checked before answering.
 *
 * Limits: a file name that holds the separator `, ` is read as several files, and what an older version wrote in
 * another format is not recognised (it stays in the text).
 */
export function splitAttachmentMentions(text: string): AttachmentMentionSplit {
  const unchanged: AttachmentMentionSplit = { body: text, files: [], mentions: '' }
  const separator = text.lastIndexOf(BLOCK_SEPARATOR)
  const body = separator < 0 ? '' : text.slice(0, separator)
  const mentions = separator < 0 ? text : text.slice(separator + BLOCK_SEPARATOR.length)
  if (!mentions) return unchanged
  const files: MentionedFile[] = []
  for (const line of mentions.split(LINE_SEPARATOR)) {
    const parsed = parseMentionLine(line)
    if (!parsed) return unchanged
    files.push(...parsed)
  }
  if (joinAttachmentMentions(body, mentions) !== text) return unchanged
  return { body, files, mentions }
}

export type AttachmentStatus = 'pending' | 'uploading' | 'uploaded' | 'error'

/** A file staged in the composer, not yet (or partially) uploaded to the exchange. */
export interface PendingAttachment {
  id: string
  file: File
  status: AttachmentStatus
  error?: string
  /** Scope the file was actually uploaded to; recorded at upload time, drives the mention. */
  uploadedScope?: ExchangeScope
}

/**
 * NAIVE namespace-intent heuristic: the standalone word "namespace" anywhere in the message
 * targets the namespace exchange (when the user can write it). False positives are accepted
 * in V1 — e.g. "do NOT put this in the namespace" still matches — the chip badge previews
 * the resolved target so the user sees it before submitting.
 */
export const NAMESPACE_INTENT = /\bnamespace\b/i

/** Upload target for the message: namespace when explicitly asked for AND writable, else case. */
export function resolveUploadScope(text: string, canWriteNamespace: boolean): ExchangeScope {
  return NAMESPACE_INTENT.test(text) && canWriteNamespace
    ? ExchangeFileEntryScopeEnum.NAMESPACE
    : ExchangeFileEntryScopeEnum.CASE
}

/** True when [resolveUploadScope] resolves to the namespace exchange (chip badge preview). */
export function isNamespaceTargeted(text: string, canWriteNamespace: boolean): boolean {
  return resolveUploadScope(text, canWriteNamespace) === ExchangeFileEntryScopeEnum.NAMESPACE
}

/**
 * Mention block appended to the outgoing message so the agent knows which files were just
 * attached and where — messages carry no attachment metadata, the exchange does.
 */
export function buildAttachmentMention(attachments: PendingAttachment[]): string {
  const namesFor = (scope: ExchangeScope): string[] =>
    attachments.filter((attachment) => attachment.uploadedScope === scope).map((attachment) => attachment.file.name)

  const lines: string[] = []
  const caseNames = namesFor(ExchangeFileEntryScopeEnum.CASE)
  const namespaceNames = namesFor(ExchangeFileEntryScopeEnum.NAMESPACE)
  if (caseNames.length > 0) lines.push(attachedMentionLine(ExchangeFileEntryScopeEnum.CASE, caseNames))
  if (namespaceNames.length > 0) {
    lines.push(attachedMentionLine(ExchangeFileEntryScopeEnum.NAMESPACE, namespaceNames))
  }
  return joinMentionLines(lines)
}

const PASTED_IMAGE_EXTENSIONS: Record<string, string> = {
  'image/png': 'png',
  'image/jpeg': 'jpg',
  'image/gif': 'gif',
  'image/webp': 'webp',
}

/**
 * Name given to an image pasted from the clipboard. Browsers name every clipboard screenshot
 * `image.png`, and uploading the same name twice is a 409 conflict, so the name carries the
 * local paste time: `pasted-image-YYYYMMDD-HHmmss.png`. [index] is the 0-based position of the
 * image inside one paste; the first keeps the bare name, the next ones get `-2`, `-3`…
 */
export function pastedImageName(date: Date, index: number, mime: string): string {
  const pad = (value: number): string => String(value).padStart(2, '0')
  const day = `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}`
  const time = `${pad(date.getHours())}${pad(date.getMinutes())}${pad(date.getSeconds())}`
  const suffix = index > 0 ? `-${index + 1}` : ''
  const extension = PASTED_IMAGE_EXTENSIONS[mime.toLowerCase()] ?? 'png'
  return `pasted-image-${day}-${time}${suffix}.${extension}`
}

/** True for an image file (drives the chip thumbnail). */
export function isImageFile(file: File): boolean {
  return file.type.startsWith('image/')
}

/** "a-very-long-file-name.pdf" cut in the middle with an ellipsis when above [max] characters. */
export function middleTruncate(name: string, max = 32): string {
  if (name.length <= max) return name
  const keep = max - 1
  const head = Math.ceil(keep / 2)
  const tail = Math.floor(keep / 2)
  return `${name.slice(0, head)}…${name.slice(name.length - tail)}`
}

/** True when a paste aimed at [target] belongs to a text field (input, textarea, contenteditable). */
export function isEditableTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  return target.isContentEditable || target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement
}

/** Short kind label for the chip, e.g. "PDF"; "FILE" when there is no extension. */
export function kindLabel(filename: string): string {
  const dot = filename.lastIndexOf('.')
  if (dot <= 0 || dot === filename.length - 1) return 'FILE'
  return filename.slice(dot + 1).toUpperCase()
}
