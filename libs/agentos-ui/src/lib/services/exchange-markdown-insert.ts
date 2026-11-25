/**
 * Inserts rendered markdown (a DocumentFragment from `renderMarkdownFragment`) in the contenteditable document, in
 * place of a Range: an accepted agent proposal, or a pasted markdown.
 *
 * The Range may start or end inside a block (p, h1-h6, li). A fragment that is a single paragraph is inserted INLINE
 * in the surrounding block (typing over a selection: no empty `<p>` is created); anything else is inserted as blocks,
 * the block holding the range being split around it. The empty blocks `deleteContents` leaves behind are removed.
 * Nothing here writes markdown syntax as text: the fragment is already DOM.
 */
import { enclosingBlock } from './exchange-markdown-commands'

const BLOCK_SELECTOR = 'p, h1, h2, h3, h4, h5, h6, pre, li'

function isElement(node: Node | null | undefined): node is HTMLElement {
  return !!node && node.nodeType === Node.ELEMENT_NODE
}

/** A block without text or any embedded content (image, rule, table): what a deletion leaves behind. */
function isEmptyBlock(block: HTMLElement): boolean {
  return (block.textContent ?? '').replace(/[\s\u200B]/g, '') === '' && !block.querySelector('img, hr, table, pre')
}

/** Removes [element] when it is empty, and the lists / quotes it was the last child of. */
function removeIfEmpty(root: HTMLElement, element: HTMLElement | null): void {
  let current: HTMLElement | null = element
  while (current && current !== root && isEmptyBlock(current)) {
    const parent: HTMLElement | null = current.parentElement
    current.remove()
    current = parent && ['UL', 'OL', 'BLOCKQUOTE'].includes(parent.tagName) ? parent : null
  }
}

/** The only element of [fragment] when it is a paragraph (surrounding whitespace text ignored), else null. */
function singleParagraph(fragment: DocumentFragment): HTMLElement | null {
  const meaningful = Array.from(fragment.childNodes).filter(
    (node) => !(node.nodeType === Node.TEXT_NODE && !(node.textContent ?? '').trim())
  )
  if (meaningful.length === 1 && isElement(meaningful[0]) && meaningful[0].tagName === 'P') return meaningful[0]
  // Inline-only content (no block at all) is inline too.
  return null
}

function isInlineOnly(fragment: DocumentFragment): boolean {
  return Array.from(fragment.childNodes).every(
    (node) =>
      node.nodeType === Node.TEXT_NODE ||
      (isElement(node) && !node.matches(`${BLOCK_SELECTOR}, ul, ol, blockquote, table, div, hr`))
  )
}

/** The top-level blocks of [fragment] made into list items (what a fragment becomes inside a list). */
function asListItems(fragment: DocumentFragment): HTMLElement[] {
  const items: HTMLElement[] = []
  for (const node of Array.from(fragment.childNodes)) {
    if (node.nodeType === Node.TEXT_NODE && !(node.textContent ?? '').trim()) continue
    if (isElement(node) && (node.tagName === 'UL' || node.tagName === 'OL')) {
      items.push(...(Array.from(node.children) as HTMLElement[]))
      continue
    }
    const item = document.createElement('li')
    if (isElement(node) && ['P', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6'].includes(node.tagName)) {
      while (node.firstChild) item.appendChild(node.firstChild)
    } else {
      item.appendChild(node)
    }
    items.push(item)
  }
  return items
}

/** The caret at the end of what was inserted: [last] is its last node. */
function collapseAfter(range: Range, last: Node | null): void {
  if (!last) return
  if (last.nodeType === Node.TEXT_NODE) range.setStart(last, (last as Text).length)
  else range.setStartAfter(last)
  range.collapse(true)
}

/** The deepest last descendant: where the caret goes at the end of a block. */
function deepestLast(node: Node): Node {
  let current = node
  while (current.lastChild && current.lastChild.nodeName !== 'BR') current = current.lastChild
  return current
}

/**
 * Replaces the contents of [range] with [fragment], following the merge rules above. On return [range] is collapsed
 * at the end of the inserted content. The caller marks the draft dirty.
 */
export function replaceRangeWithFragment(root: HTMLElement, range: Range, fragment: DocumentFragment): void {
  const startBlock = enclosingBlock(root, range.startContainer)
  const endBlock = enclosingBlock(root, range.endContainer)
  // `deleteContents` collapses [range] to the closest common ancestor when the selection spans blocks: this live
  // point stays where the selection started, inside the block that keeps its head.
  const start = range.cloneRange()
  start.collapse(true)
  range.deleteContents()

  const paragraph = singleParagraph(fragment)
  if (paragraph || isInlineOnly(fragment)) {
    // Inline: typed over the selection, inside the surrounding block.
    const inline = document.createDocumentFragment()
    const source: Node = paragraph ?? fragment
    while (source.firstChild) inline.appendChild(source.firstChild)
    const last = inline.lastChild
    start.insertNode(inline)
    range.setStart(start.startContainer, start.startOffset)
    collapseAfter(range, last)
    if (
      startBlock &&
      endBlock &&
      startBlock !== endBlock &&
      !startBlock.contains(endBlock) &&
      !endBlock.contains(startBlock)
    ) {
      // A selection across blocks: the rest of the last block joins the first one, as when typing over it.
      while (endBlock.firstChild) startBlock.appendChild(endBlock.firstChild)
      removeIfEmpty(root, endBlock)
    }
    root.normalize()
    return
  }

  const block = startBlock ?? endBlock
  if (!block || !root.contains(start.startContainer) || start.startContainer === root) {
    // Not inside a text block (the root, a table cell...): the blocks go in as they are.
    const last = fragment.lastChild
    start.insertNode(fragment)
    range.setStart(start.startContainer, start.startOffset)
    collapseAfter(range, last && deepestLast(last))
    root.normalize()
    return
  }

  // Split the block around the point: the head stays, the tail goes in a copy of the block, after the fragment.
  const tailRange = document.createRange()
  tailRange.setStart(start.startContainer, start.startOffset)
  tailRange.setEnd(block, block.childNodes.length)
  const tail = block.cloneNode(false) as HTMLElement
  tail.appendChild(tailRange.extractContents())

  const inserted: Node[] = block.tagName === 'LI' ? asListItems(fragment) : Array.from(fragment.childNodes)
  let anchor: Node = block
  for (const node of inserted) {
    anchor.parentNode?.insertBefore(node, anchor.nextSibling)
    anchor = node
  }
  anchor.parentNode?.insertBefore(tail, anchor.nextSibling)

  const lastInserted = inserted[inserted.length - 1] ?? null
  const caretTarget = lastInserted ? deepestLast(lastInserted) : null
  // The remnants of the deletion go: the head of the block when nothing is left of it, the tail likewise.
  removeIfEmpty(root, block)
  if (endBlock && endBlock !== block) {
    // What was after the selection in the last block stays: it joins the tail when the tail has nothing of its own.
    if (isEmptyBlock(tail) && !isEmptyBlock(endBlock) && endBlock.tagName === tail.tagName) {
      while (endBlock.firstChild) tail.appendChild(endBlock.firstChild)
    }
    removeIfEmpty(root, endBlock)
  }
  removeIfEmpty(root, tail)
  root.normalize()
  if (caretTarget && caretTarget.isConnected) {
    range.setStart(caretTarget, 0)
    collapseAfter(range, caretTarget)
  } else if (tail.isConnected) {
    range.setStart(tail, 0)
    range.collapse(true)
  }
}
