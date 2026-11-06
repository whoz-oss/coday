import { ExchangeManifest, ExchangeManifestCapabilityEnum } from '@whoz-oss/agentos-api-client'

/**
 * Pure helpers for the `#` file mention of the chat composer (no Angular, no HTTP).
 *
 * A mention is plain text, qualified by the exchange the agent reads it from:
 * `namespace:docs/readme.md` (namespace-exchange tools) or `case:notes/todo.md` (case-exchange tools).
 */

/** The two exchanges an agent reaches through its file tools. */
export type FileMentionScope = 'case' | 'namespace'

/** A file the composer can reference. */
export interface FileSuggestion {
  scope: FileMentionScope
  /** Path relative to the root of its exchange, with forward slashes. */
  path: string
  filename: string
}

/** Maximum number of suggestions shown, whatever the size of the tree. */
export const MAX_FILE_SUGGESTIONS = 50

/** The word being typed at the end of the value, when it starts with `#` (at the start or after a whitespace). */
const FILE_MENTION_TOKEN = /(^|\s)#([^\s#]*)$/

/**
 * What follows the `#` of the word being typed at the end of the value, or null when that word is not a file
 * mention. A `#` inside a word (`C#`, `a#b`) is not one.
 */
export function fileMentionPrefix(value: string): string | null {
  const match = FILE_MENTION_TOKEN.exec(value)
  return match ? (match[2] ?? '') : null
}

/** Text inserted for a file, with the trailing space that ends the mention. */
export function fileReference(file: Pick<FileSuggestion, 'scope' | 'path'>): string {
  return `${file.scope}:${file.path} `
}

/** Replaces the trailing `#<prefix>` word of the value with [reference]. */
export function applyFileMention(value: string, reference: string): string {
  // A function replacer: a `$` in a path must not be read as a replacement pattern.
  return value.replace(FILE_MENTION_TOKEN, (_match, lead: string) => `${lead}${reference}`)
}

/** Directory part of a path, empty for a file at the root of its exchange. */
export function directoryOf(path: string): string {
  const slash = path.lastIndexOf('/')
  return slash < 0 ? '' : path.slice(0, slash)
}

/**
 * Files of a manifest as suggestions. A scope the caller has no capability on contributes nothing
 * (fail-closed), whatever the manifest lists.
 */
export function manifestToSuggestions(manifest: ExchangeManifest, scope: FileMentionScope): FileSuggestion[] {
  if (manifest.capability === ExchangeManifestCapabilityEnum.NONE) return []
  return (manifest.files ?? []).map((file) => ({
    scope,
    path: file.path,
    filename: file.filename || file.path.split('/').pop() || file.path,
  }))
}

/**
 * Files matching [query], best first, at most [limit]: filename starting with the query, then filename containing
 * it, then path containing it (case-insensitive). Ties are ordered by path. An empty query matches everything.
 */
export function rankFiles(files: FileSuggestion[], query: string, limit = MAX_FILE_SUGGESTIONS): FileSuggestion[] {
  const needle = query.toLowerCase()
  const ranked: Array<{ file: FileSuggestion; rank: number }> = []
  for (const file of files) {
    const name = file.filename.toLowerCase()
    let rank: number
    if (name.startsWith(needle)) rank = 0
    else if (name.includes(needle)) rank = 1
    else if (file.path.toLowerCase().includes(needle)) rank = 2
    else continue
    ranked.push({ file, rank })
  }
  return ranked
    .sort(
      (a, b) => a.rank - b.rank || a.file.path.localeCompare(b.file.path) || a.file.scope.localeCompare(b.file.scope)
    )
    .slice(0, limit)
    .map(({ file }) => file)
}
