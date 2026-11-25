/**
 * Pure helpers to find the frozen selection of a document again, after the document may have changed, and to map
 * text offsets to DOM ranges (the rendered markdown and the read-only previews are DOM, not a textarea).
 */

export interface PassageLocation {
  from: number
  to: number
}

/** The text without any whitespace: two passages that differ only by the line breaks between blocks are the same. */
export function squash(text: string): string {
  return text.replace(/\s+/g, '')
}

/**
 * Where the passage is now in [text]: at the offsets it was selected at when the text there is still the same,
 * otherwise wherever the same text occurs (the first occurrence at or after the old position wins, as the user
 * most likely edited elsewhere), otherwise null ("the passage changed").
 *
 * [candidates] are the texts that may stand for the passage, best first. [same] compares the text found at the old
 * offsets with a candidate: the default is strict equality, rendered documents pass a whitespace-insensitive one
 * (a selection spanning several blocks carries line breaks that the text of the document does not have).
 */
export function locatePassage(
  text: string,
  from: number,
  to: number,
  candidates: string[],
  same: (found: string, candidate: string) => boolean = (found, candidate) => found === candidate
): PassageLocation | null {
  for (const candidate of candidates) {
    if (!candidate) continue
    if (from >= 0 && to <= text.length && to > from && same(text.slice(from, to), candidate)) return { from, to }
  }
  for (const candidate of candidates) {
    if (!candidate) continue
    const after = text.indexOf(candidate, Math.max(0, from))
    const at = after >= 0 ? after : text.indexOf(candidate)
    if (at >= 0) return { from: at, to: at + candidate.length }
  }
  return null
}

/** Number of text characters between the start of [root] and the point (node, offset). */
export function textOffsetOf(root: Node, node: Node, offset: number): number {
  const range = document.createRange()
  range.selectNodeContents(root)
  range.setEnd(node, offset)
  return range.toString().length
}

/** Offsets of [range] in the text of [root], or null when the range is not inside it. */
export function rangeTextOffsets(root: Node, range: Range): PassageLocation | null {
  if (!root.contains(range.startContainer) || !root.contains(range.endContainer)) return null
  return {
    from: textOffsetOf(root, range.startContainer, range.startOffset),
    to: textOffsetOf(root, range.endContainer, range.endOffset),
  }
}

/** The DOM range of the text offsets [from, to) in [root], or null when they lie outside its text. */
export function rangeFromTextOffsets(root: Node, from: number, to: number): Range | null {
  if (from < 0 || to < from) return null
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  const nodes: { node: Node; start: number; length: number }[] = []
  let position = 0
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    const length = (node.nodeValue ?? '').length
    nodes.push({ node, start: position, length })
    position += length
  }
  if (to > position) return null
  // The start belongs to the node that contains it (the later one on a boundary); the end to the first node reaching it.
  const first = nodes.find((n) => from < n.start + n.length) ?? nodes[nodes.length - 1]
  const last = nodes.find((n) => to > n.start && to <= n.start + n.length) ?? first
  if (!first || !last) return null
  const range = document.createRange()
  range.setStart(first.node, Math.min(first.length, from - first.start))
  range.setEnd(last.node, Math.max(0, Math.min(last.length, to - last.start)))
  return range
}
