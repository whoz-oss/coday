import { ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { ExchangeScope } from '../../services/exchange-state.service'

/**
 * Pure helpers for the composer file-attachment feature (no Angular, no HTTP),
 * mirroring the style of `services/exchange-content.utils.ts`.
 */

/** Maximum number of files attachable to a single message. */
export const MAX_ATTACHMENTS = 10

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
  if (caseNames.length > 0) lines.push(`[Files attached to the case exchange: ${caseNames.join(', ')}]`)
  if (namespaceNames.length > 0) {
    lines.push(`[Files attached to the namespace exchange: ${namespaceNames.join(', ')}]`)
  }
  return lines.join('\n')
}

/** "a-very-long-file-name.pdf" cut in the middle with an ellipsis when above [max] characters. */
export function middleTruncate(name: string, max = 32): string {
  if (name.length <= max) return name
  const keep = max - 1
  const head = Math.ceil(keep / 2)
  const tail = Math.floor(keep / 2)
  return `${name.slice(0, head)}…${name.slice(name.length - tail)}`
}

/** Short kind label for the chip, e.g. "PDF"; "FILE" when there is no extension. */
export function kindLabel(filename: string): string {
  const dot = filename.lastIndexOf('.')
  if (dot <= 0 || dot === filename.length - 1) return 'FILE'
  return filename.slice(dot + 1).toUpperCase()
}
