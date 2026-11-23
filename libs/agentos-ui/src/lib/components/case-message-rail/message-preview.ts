/** Characters of a message shown in the rail tooltip. */
export const RAIL_PREVIEW_LENGTH = 40

/** Characters of a message read out as the accessible name of its rail dot. */
export const RAIL_LABEL_LENGTH = 120

const ELLIPSIS = '…'

/** A backslash escape: the character after it is literal (CommonMark's escapable punctuation). */
const ESCAPE = /\\([\\`*_{}[\]()#+\-.!~|>])/g

/**
 * Escaped characters are swapped for private-use stand-ins while the syntax rules run, so `\*star\*` is never read as
 * emphasis, and restored as the plain character at the end.
 */
const STAND_IN_BASE = 0xe000
const STAND_IN = /[\uE000-\uE07F]/g

/**
 * Markdown syntax to drop from a message, in order, keeping the words it wraps: the preview reads like the rendered
 * bubble (marked, GFM).
 *
 * Fences go before inline code (both use backticks), images before links (`![alt](url)` contains a link), strong
 * before emphasis (`**` is two `*`). Underscores never emphasise inside a word, so `__` and `_` need a non-word
 * character on both sides (`snake__case__name` stays as typed); `**`, `~~` and `*` may, as in the renderer. Emphasis
 * markers must touch their words, so `2 * 3 * 4` stays as typed.
 *
 * Raw HTML and autolinks (`<https://…>`) are out of scope: they are left as typed.
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
  [/(\*\*|~~)(?=\S)(.+?)(?<=\S)\1/g, '$2'],
  [/(^|[^\w])__(?=\S)(.+?)(?<=\S)__(?!\w)/g, '$1$2'],
  [/\*(?=[^\s*])([^*\n]*?[^\s*])\*/g, '$1'],
  [/(^|[^\w])_(?=\S)([^_\n]+?)(?<=\S)_(?!\w)/g, '$1$2'],
]

/**
 * A message as one line of plain text, cut to `maxLength` characters.
 *
 * Markdown syntax is dropped (the words stay), every run of whitespace — newlines included — becomes one space, and
 * a longer text ends with '…' within the limit. The cut counts user-perceived characters (grapheme clusters), so it
 * never splits an emoji, a joined emoji sequence or a flag.
 */
export function messagePreview(text: string, maxLength: number): string {
  const protectedText = text.replace(ESCAPE, (_, character: string) =>
    String.fromCharCode(STAND_IN_BASE + character.charCodeAt(0))
  )
  const plain = MARKDOWN_RULES.reduce(
    (current, [pattern, replacement]) => current.replace(pattern, replacement),
    protectedText
  )
    .replace(STAND_IN, (standIn) => String.fromCharCode(standIn.charCodeAt(0) - STAND_IN_BASE))
    .replace(/\s+/g, ' ')
    .trim()
  const characters = Array.from(new Intl.Segmenter().segment(plain), (part) => part.segment)
  if (characters.length <= maxLength) return plain
  return (
    characters
      .slice(0, maxLength - 1)
      .join('')
      .trimEnd() + ELLIPSIS
  )
}
