/**
 * Small pure word-level diff (longest common subsequence), used to show what an agent proposal changes in a
 * selected passage. No dependency: passages are short, and the quadratic table is capped.
 */

export interface WordDiffPart {
  type: 'equal' | 'insert' | 'delete'
  text: string
}

/** Above this many token pairs the LCS table is not built: the diff degrades to "all deleted, all inserted". */
const MAX_LCS_CELLS = 4_000_000

/** Words and the whitespace between them, as separate tokens, so that joining the tokens gives the text back. */
function tokenize(text: string): string[] {
  return text.match(/\s+|\S+/g) ?? []
}

function pushPart(parts: WordDiffPart[], type: WordDiffPart['type'], text: string): void {
  const last = parts[parts.length - 1]
  if (last?.type === type) last.text += text
  else parts.push({ type, text })
}

/** Word-level diff of [before] against [after]: deletions, insertions and the text they have in common. */
export function diffWords(before: string, after: string): WordDiffPart[] {
  if (before === after) return before ? [{ type: 'equal', text: before }] : []
  const a = tokenize(before)
  const b = tokenize(after)
  const parts: WordDiffPart[] = []
  if (a.length * b.length > MAX_LCS_CELLS) {
    if (before) parts.push({ type: 'delete', text: before })
    if (after) parts.push({ type: 'insert', text: after })
    return parts
  }

  // lengths[i][j] = length of the LCS of a[i..] and b[j..]
  const lengths: number[][] = Array.from({ length: a.length + 1 }, () => new Array<number>(b.length + 1).fill(0))
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      lengths[i]![j] = a[i] === b[j] ? lengths[i + 1]![j + 1]! + 1 : Math.max(lengths[i + 1]![j]!, lengths[i]![j + 1]!)
    }
  }

  let i = 0
  let j = 0
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) {
      pushPart(parts, 'equal', a[i]!)
      i++
      j++
    } else if (lengths[i + 1]![j]! >= lengths[i]![j + 1]!) {
      pushPart(parts, 'delete', a[i]!)
      i++
    } else {
      pushPart(parts, 'insert', b[j]!)
      j++
    }
  }
  while (i < a.length) pushPart(parts, 'delete', a[i++]!)
  while (j < b.length) pushPart(parts, 'insert', b[j++]!)
  return parts
}
