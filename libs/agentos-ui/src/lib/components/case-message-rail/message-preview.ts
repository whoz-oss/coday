/** Characters of a message shown in the rail tooltip. */
export const RAIL_PREVIEW_LENGTH = 40

/** Characters of a message read out as the accessible name of its rail dot. */
export const RAIL_LABEL_LENGTH = 120

const ELLIPSIS = '…'

/**
 * Markdown syntax to drop from a message, in order, keeping the words it wraps.
 *
 * Fences go before inline code (both use backticks), images before links (`![alt](url)` contains a link), bold
 * before italic (`**` is two `*`). Italic needs a non-word character on both sides, so `snake_case_name` and
 * `2 * 3 * 4` stay as typed.
 */
const MARKDOWN_RULES: readonly [RegExp, string][] = [
  [/^\s*(```|~~~).*$/gm, ''],
  [/!\[([^\]]*)\]\([^)]*\)/g, '$1'],
  [/\[([^\]]+)\]\([^)]*\)/g, '$1'],
  [/\[([^\]]+)\]\[[^\]]*\]/g, '$1'],
  [/`([^`]+)`/g, '$1'],
  [/^\s{0,3}#{1,6}\s+/gm, ''],
  [/^\s{0,3}>\s?/gm, ''],
  [/^\s*([-*+]|\d+[.)])\s+(\[[ xX]\]\s+)?/gm, ''],
  [/^\s{0,3}([-*_])(\s*\1){2,}\s*$/gm, ''],
  [/(\*\*|__|~~)(?=\S)(.+?)(?<=\S)\1/g, '$2'],
  [/(^|[^\w*])\*(?=\S)([^*\n]+?)(?<=\S)\*(?!\w)/g, '$1$2'],
  [/(^|[^\w])_(?=\S)([^_\n]+?)(?<=\S)_(?!\w)/g, '$1$2'],
]

/**
 * A message as one line of plain text, cut to `maxLength` characters.
 *
 * Markdown syntax is dropped (the words stay), every run of whitespace — newlines included — becomes one space, and
 * a longer text ends with '…' within the limit. The cut counts characters, not UTF-16 units, so it never splits an
 * emoji in two.
 */
export function messagePreview(text: string, maxLength: number): string {
  const plain = MARKDOWN_RULES.reduce((current, [pattern, replacement]) => current.replace(pattern, replacement), text)
    .replace(/\s+/g, ' ')
    .trim()
  const characters = Array.from(plain)
  if (characters.length <= maxLength) return plain
  return (
    characters
      .slice(0, maxLength - 1)
      .join('')
      .trimEnd() + ELLIPSIS
  )
}
