/**
 * Lightweight formatting of the rendered markdown document, edited in place (contenteditable): inline wrap (strong,
 * em, code, link), block type (paragraph, h1-h3, blockquote, code block) and lists, as plain DOM operations on the
 * Selection / Range. No editor dependency, and `document.execCommand` (deprecated) is never used.
 *
 * Every command keeps the selection (or the caret) by its text offsets, merges what it leaves adjacent (`**a****b**`
 * is not valid markdown), and returns whether it changed the document, so that the caller marks the draft dirty.
 * The document is converted back to markdown on save only (`exchange-markdown-convert.ts`): what these commands
 * produce is the plain markup turndown knows (`strong`, `em`, `code`, `a`, `h1`-`h3`, `ul`/`ol`/`li`, `blockquote`,
 * `pre > code`). Because they rewrite the DOM directly, they do not feed the native undo stack of the browser.
 */
import { rangeFromTextOffsets, rangeTextOffsets } from './exchange-passage'

export type InlineTag = 'strong' | 'em' | 'code'
export type ParagraphType = 'p' | 'h1' | 'h2' | 'h3'
export type ListType = 'ul' | 'ol'

/** What a keyboard shortcut or a toolbar button asks for. */
export type MarkdownAction =
  | { kind: 'inline'; tag: InlineTag }
  | { kind: 'block'; block: ParagraphType }
  | { kind: 'list'; list: ListType }
  | { kind: 'link' }

/** A zero-width space keeps a caret inside an empty inline element; it is removed when the document is saved. */
export const ZERO_WIDTH_SPACE = '\u200B'

const TEXT_BLOCKS = new Set(['P', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'PRE', 'LI'])
const CONTAINER_TAGS = new Set(['UL', 'OL', 'BLOCKQUOTE', 'TABLE', 'THEAD', 'TBODY', 'TR'])

function isElement(node: Node | null | undefined): node is HTMLElement {
  return !!node && node.nodeType === Node.ELEMENT_NODE
}

function isBlockish(node: Node | null | undefined): boolean {
  return isElement(node) && (TEXT_BLOCKS.has(node.tagName) || CONTAINER_TAGS.has(node.tagName))
}

/** The closest element of [selector] around [node], never leaving [root]. */
function closestWithin(root: HTMLElement, node: Node | null, selector: string): HTMLElement | null {
  for (let current: Node | null = node; current && current !== root; current = current.parentNode) {
    if (isElement(current) && current.matches(selector)) return current
  }
  return null
}

/** The textblock (p, h1-h6, pre, li) that holds [node], inside [root]. */
export function enclosingBlock(root: HTMLElement, node: Node | null): HTMLElement | null {
  for (let current: Node | null = node; current && current !== root; current = current.parentNode) {
    if (isElement(current) && TEXT_BLOCKS.has(current.tagName)) return current
  }
  return null
}

/** The range of the selection when it lies inside [root], else null. */
function currentRange(root: HTMLElement, selection: Selection): Range | null {
  if (!selection.rangeCount) return null
  const range = selection.getRangeAt(0)
  return root.contains(range.startContainer) && root.contains(range.endContainer) ? range : null
}

function setSelection(selection: Selection, range: Range): void {
  selection.removeAllRanges()
  selection.addRange(range)
}

/** A collapsed range at a text offset of [root], on the side of the text that ends there (the caret of a block end). */
function caretAtTextOffset(root: HTMLElement, offset: number): Range | null {
  const range = rangeFromTextOffsets(root, offset, offset)
  if (!range) return null
  range.setStart(range.endContainer, range.endOffset)
  return range
}

interface MutationResult {
  changed: boolean
  /** The element the caret goes to when there is no text to anchor it (an empty block). */
  focus?: Node
}

/**
 * Runs [mutate] and puts the selection back where it was, by text offsets (the text does not change in a format
 * command, only the markup around it). [mutate] receives the range of the selection.
 */
function preservingSelection(
  root: HTMLElement,
  selection: Selection,
  mutate: (range: Range) => MutationResult
): boolean {
  const range = currentRange(root, selection)
  if (!range) return false
  const offsets = rangeTextOffsets(root, range)
  const collapsed = range.collapsed
  const result = mutate(range)
  if (!result.changed) return false
  root.normalize()
  let restored: Range | null = null
  const emptyFocus = result.focus && collapsed && (result.focus.textContent ?? '').replace(ZERO_WIDTH_SPACE, '') === ''
  if (emptyFocus && result.focus) {
    restored = document.createRange()
    restored.setStart(result.focus, 0)
    restored.collapse(true)
  } else if (offsets) {
    restored = collapsed ? caretAtTextOffset(root, offsets.to) : rangeFromTextOffsets(root, offsets.from, offsets.to)
  }
  if (!restored && result.focus) {
    restored = document.createRange()
    restored.setStart(result.focus, 0)
    restored.collapse(true)
  }
  if (restored) setSelection(selection, restored)
  return true
}

// ── Blocks of a selection ──────────────────────────────────────────────────────────────────────────────

/** The innermost textblocks the range touches, in document order (a block the range merely ends at the start of is not). */
function selectedBlocks(root: HTMLElement, range: Range): HTMLElement[] {
  const startBlock = enclosingBlock(root, range.startContainer)
  const endBlock = enclosingBlock(root, range.endContainer)
  if (startBlock && startBlock === endBlock) return [startBlock]
  const all = Array.from(root.querySelectorAll<HTMLElement>('p, h1, h2, h3, h4, h5, h6, pre, li'))
  return all.filter((block) => {
    if (!range.intersectsNode(block)) return false
    // Innermost only: a list item holding a nested list is represented by the items of that list.
    if (block.querySelector('p, h1, h2, h3, h4, h5, h6, pre, li')) return false
    if (block !== startBlock && !range.collapsed) {
      const head = document.createRange()
      head.selectNodeContents(block)
      head.setEnd(range.endContainer, range.endOffset)
      if (block.contains(range.endContainer) && head.toString() === '') return false
    }
    return true
  })
}

function retag(block: HTMLElement, tag: string): HTMLElement {
  if (block.tagName.toLowerCase() === tag) return block
  const next = document.createElement(tag)
  while (block.firstChild) next.appendChild(block.firstChild)
  block.replaceWith(next)
  return next
}

function isList(node: Node | null): node is HTMLElement {
  return isElement(node) && (node.tagName === 'UL' || node.tagName === 'OL')
}

/** Takes a list item out of its list (the list is split around it) and returns the paragraph that replaces it. */
function liftListItem(item: HTMLElement): HTMLElement {
  const paragraph = document.createElement('p')
  const list = item.parentElement
  const nested: HTMLElement[] = []
  while (item.firstChild) {
    const child = item.firstChild
    if (isList(child)) nested.push(child)
    paragraph.appendChild(child)
  }
  for (const list of nested) paragraph.removeChild(list)
  if (!isList(list)) {
    item.replaceWith(paragraph)
    return paragraph
  }
  const tail = list.cloneNode(false) as HTMLElement
  while (item.nextSibling) tail.appendChild(item.nextSibling)
  item.remove()
  list.after(paragraph)
  let anchor: HTMLElement = paragraph
  for (const child of nested) {
    anchor.after(child)
    anchor = child
  }
  if (tail.firstChild) anchor.after(tail)
  if (!list.firstChild) list.remove()
  return paragraph
}

/** Makes sure an empty block can hold a caret. */
function keepCaretable(block: HTMLElement): void {
  if (!block.firstChild) block.appendChild(document.createElement('br'))
}

/**
 * Converts the blocks of the selection to a paragraph or a heading, keeping their children. Asking again for the
 * heading the blocks already are turns them back into paragraphs. A list item leaves its list.
 */
export function setBlockType(root: HTMLElement, selection: Selection, type: ParagraphType): boolean {
  return preservingSelection(root, selection, (range) => {
    const blocks = selectedBlocks(root, range)
    if (!blocks.length) return { changed: false }
    const target = type !== 'p' && blocks.every((block) => block.tagName.toLowerCase() === type) ? 'p' : type
    let focus: HTMLElement | undefined
    for (const original of blocks) {
      let block = original
      if (block.tagName === 'LI') block = liftListItem(block)
      if (block.tagName === 'PRE') {
        const text = document.createTextNode(block.textContent ?? '')
        block.replaceChildren(text)
      }
      focus = retag(block, target)
      keepCaretable(focus)
    }
    return { changed: true, focus }
  })
}

/** Same-tag lists next to [list] become one list. */
function mergeLists(list: HTMLElement): void {
  const previous = list.previousElementSibling
  if (previous && previous.tagName === list.tagName) {
    while (list.firstChild) previous.appendChild(list.firstChild)
    list.remove()
    list = previous as HTMLElement
  }
  const next = list.nextElementSibling
  if (next && next.tagName === list.tagName) {
    while (next.firstChild) list.appendChild(next.firstChild)
    next.remove()
  }
}

/** Groups the blocks that follow each other as siblings, so that they end up in one list or one quote. */
function siblingGroups(blocks: HTMLElement[]): HTMLElement[][] {
  const groups: HTMLElement[][] = []
  for (const block of blocks) {
    const group = groups[groups.length - 1]
    const last = group?.[group.length - 1]
    if (group && last && last.nextElementSibling === block) group.push(block)
    else groups.push([block])
  }
  return groups
}

/**
 * Toggles a bullet or a numbered list on the blocks of the selection: wraps them in a list, switches a list of the
 * other kind, or takes the items out of the list when they already are in one of this kind.
 */
export function toggleList(root: HTMLElement, selection: Selection, listTag: ListType): boolean {
  return preservingSelection(root, selection, (range) => {
    const blocks = selectedBlocks(root, range)
    if (!blocks.length) return { changed: false }
    const items = blocks.map((block) => (block.tagName === 'LI' ? block : closestWithin(root, block, 'li')))
    let focus: Node | undefined
    if (items.every((item) => item && item.parentElement?.tagName.toLowerCase() === listTag)) {
      for (const item of items) if (item) focus = liftListItem(item)
      return { changed: true, focus }
    }
    if (items.every((item) => !!item)) {
      const lists = new Set(items.map((item) => item!.parentElement).filter(isList))
      for (const list of lists) {
        const switched = retag(list, listTag)
        mergeLists(switched)
        focus = switched
      }
      return { changed: true, focus }
    }
    const wrappable = blocks.filter((block, index) => !items[index] && block.tagName !== 'PRE')
    for (const group of siblingGroups(wrappable)) {
      const list = document.createElement(listTag)
      group[0]!.before(list)
      for (const block of group) {
        const item = document.createElement('li')
        while (block.firstChild) item.appendChild(block.firstChild)
        keepCaretable(item)
        list.appendChild(item)
        block.remove()
        focus = item
      }
      mergeLists(list)
    }
    return { changed: wrappable.length > 0, focus }
  })
}

/** Toggles a quote around the blocks of the selection (a quote that holds them is removed). */
export function toggleBlockquote(root: HTMLElement, selection: Selection): boolean {
  return preservingSelection(root, selection, (range) => {
    const blocks = selectedBlocks(root, range).filter((block) => block.tagName !== 'LI')
    if (!blocks.length) return { changed: false }
    const quotes = blocks.map((block) => closestWithin(root, block, 'blockquote'))
    let focus: Node | undefined
    if (quotes.every((quote) => !!quote)) {
      for (const quote of new Set(quotes)) {
        if (!quote) continue
        focus = quote.firstElementChild ?? undefined
        while (quote.firstChild) quote.before(quote.firstChild)
        quote.remove()
      }
      return { changed: true, focus }
    }
    for (const group of siblingGroups(blocks)) {
      const quote = document.createElement('blockquote')
      group[0]!.before(quote)
      for (const block of group) quote.appendChild(block)
      focus = group[group.length - 1]
    }
    return { changed: true, focus }
  })
}

/** Turns the paragraph that holds the caret into an empty code block (`pre > code`), with the caret inside. */
export function convertToCodeBlock(root: HTMLElement, selection: Selection, language = ''): boolean {
  const range = currentRange(root, selection)
  const block = range ? enclosingBlock(root, range.startContainer) : null
  if (!block || block.tagName === 'PRE' || block.tagName === 'LI') return false
  const pre = document.createElement('pre')
  const code = document.createElement('code')
  if (/^[\w-]+$/.test(language)) code.className = `language-${language}`
  const text = document.createTextNode(ZERO_WIDTH_SPACE)
  code.appendChild(text)
  pre.appendChild(code)
  block.replaceWith(pre)
  const caret = document.createRange()
  caret.setStart(text, text.length)
  caret.collapse(true)
  setSelection(selection, caret)
  return true
}

// ── Inline formatting ─────────────────────────────────────────────────────────────────────────────────

/** Same element (tag and attributes) as [element], not looking at its children. */
function sameShallow(a: Node | null, b: HTMLElement): boolean {
  return isElement(a) && (a.cloneNode(false) as HTMLElement).outerHTML === (b.cloneNode(false) as HTMLElement).outerHTML
}

/** `<strong>a</strong><strong>b</strong>` becomes `<strong>ab</strong>`: markdown has no way to say the former. */
function mergeSiblings(element: HTMLElement): HTMLElement {
  let current = element
  while (sameShallow(current.previousSibling, current)) {
    const previous = current.previousSibling as HTMLElement
    while (current.firstChild) previous.appendChild(current.firstChild)
    current.remove()
    current = previous
  }
  while (sameShallow(current.nextSibling, current)) {
    const next = current.nextSibling as HTMLElement
    while (next.firstChild) current.appendChild(next.firstChild)
    next.remove()
  }
  return current
}

/**
 * The text nodes of [range], split at its boundaries so that each one is wholly selected. Nodes that only separate
 * blocks, and anything inside a code block, are left out.
 */
function textPieces(root: HTMLElement, range: Range): Text[] {
  const ancestor = range.commonAncestorContainer
  const scope = ancestor.nodeType === Node.TEXT_NODE ? (ancestor.parentNode ?? root) : ancestor
  const walker = document.createTreeWalker(scope, NodeFilter.SHOW_TEXT)
  const found: { node: Text; start: number; end: number }[] = []
  for (let node = walker.nextNode() as Text | null; node; node = walker.nextNode() as Text | null) {
    if (!range.intersectsNode(node)) continue
    const start = node === range.startContainer ? range.startOffset : 0
    const end = node === range.endContainer ? range.endOffset : node.length
    if (end <= start) continue
    const block = enclosingBlock(root, node)
    if (!block || block.tagName === 'PRE' || closestWithin(root, node, 'pre')) continue
    if (!node.data.slice(start, end).trim() && (isBlockish(node.previousSibling) || isBlockish(node.nextSibling))) {
      continue
    }
    found.push({ node, start, end })
  }
  return found.map(({ node, start, end }) => {
    let piece = node
    if (end < piece.length) piece.splitText(end)
    if (start > 0) piece = piece.splitText(start)
    return piece
  })
}

function isWordChar(char: string | undefined): boolean {
  return !!char && /[\p{L}\p{N}_]/u.test(char)
}

/** The word around a caret (a collapsed range inside a text node), or null when the caret is not next to a word. */
function wordAround(range: Range): Range | null {
  const node = range.startContainer
  if (node.nodeType !== Node.TEXT_NODE) return null
  const data = (node as Text).data
  let start = range.startOffset
  let end = range.startOffset
  while (start > 0 && isWordChar(data[start - 1])) start--
  while (end < data.length && isWordChar(data[end])) end++
  if (start === end) return null
  const word = document.createRange()
  word.setStart(node, start)
  word.setEnd(node, end)
  return word
}

function wrapPiece(piece: Text, tag: string, attributes: Record<string, string> = {}): HTMLElement {
  const element = document.createElement(tag)
  for (const [name, value] of Object.entries(attributes)) element.setAttribute(name, value)
  piece.parentNode?.insertBefore(element, piece)
  element.appendChild(piece)
  return element
}

/** Takes [piece] out of [formatted] (splitting it around the piece), leaving the rest of its content formatted. */
function unwrapPiece(piece: Text, formatted: HTMLElement): void {
  const before = document.createRange()
  before.setStart(formatted, 0)
  before.setEndBefore(piece)
  const head = before.extractContents()
  const after = document.createRange()
  after.setStartAfter(piece)
  after.setEnd(formatted, formatted.childNodes.length)
  const tail = after.extractContents()
  if (head.textContent) {
    const copy = formatted.cloneNode(false)
    copy.appendChild(head)
    formatted.before(copy)
  }
  if (tail.textContent) {
    const copy = formatted.cloneNode(false)
    copy.appendChild(tail)
    formatted.after(copy)
  }
  while (formatted.firstChild) formatted.before(formatted.firstChild)
  formatted.remove()
}

/** An empty inline element that keeps the caret, to type formatted text in. */
function insertEmptyInline(selection: Selection, range: Range, tag: string): void {
  const element = document.createElement(tag)
  const text = document.createTextNode(ZERO_WIDTH_SPACE)
  element.appendChild(text)
  range.insertNode(element)
  const caret = document.createRange()
  caret.setStart(text, text.length)
  caret.collapse(true)
  setSelection(selection, caret)
}

/**
 * Toggles strong, emphasis or code on the selection: everything already formatted that way is unformatted, otherwise
 * the whole selection is. With a caret inside a word the word is the target; between words an empty element is
 * opened, to type the formatted text in.
 */
export function toggleInline(root: HTMLElement, selection: Selection, tag: InlineTag): boolean {
  const initial = currentRange(root, selection)
  if (!initial) return false
  if (initial.collapsed && !wordAround(initial)) {
    const inside = closestWithin(root, initial.startContainer, tag)
    if (inside) {
      // Leaving the formatted element: what is typed next is not formatted.
      const text = document.createTextNode(ZERO_WIDTH_SPACE)
      inside.after(text)
      const caret = document.createRange()
      caret.setStart(text, text.length)
      caret.collapse(true)
      setSelection(selection, caret)
    } else {
      insertEmptyInline(selection, initial, tag)
    }
    return true
  }
  return preservingSelection(root, selection, (range) => {
    const target = range.collapsed ? (wordAround(range) ?? range) : range
    const pieces = textPieces(root, target)
    if (!pieces.length) return { changed: false }
    const formattedAround = (piece: Text): HTMLElement | null => closestWithin(root, piece, tag)
    if (pieces.every((piece) => formattedAround(piece))) {
      for (const piece of pieces) {
        const formatted = formattedAround(piece)
        if (formatted) unwrapPiece(piece, formatted)
      }
    } else {
      for (const piece of pieces) {
        if (!formattedAround(piece)) mergeSiblings(wrapPiece(piece, tag))
      }
    }
    return { changed: true }
  })
}

// ── Links ─────────────────────────────────────────────────────────────────────────────────────────────────

const ALLOWED_SCHEMES = new Set(['http', 'https', 'mailto'])

/**
 * The URL to link to, or null when it is not acceptable: `http(s):`, `mailto:`, a relative path or a `#` anchor.
 * Any other scheme (`javascript:`, `data:`, `vbscript:`, `file:`...) is refused, also when it is spelled with
 * control characters or spaces inside (`java\tscript:`), and so is a protocol-relative `//host` URL.
 */
export function sanitizeLinkUrl(raw: string): string | null {
  const url = raw.trim()
  if (!url) return null
  const compact = url.replace(/[\u0000-\u0020\u007f-\u009f\u200b-\u200d\ufeff]/g, '')
  const scheme = /^([a-z][a-z0-9+.-]*):/i.exec(compact)
  if (scheme) return ALLOWED_SCHEMES.has(scheme[1]!.toLowerCase()) ? url : null
  if (compact.startsWith('//') || compact.startsWith('\\')) return null
  return url
}

function linkAttributes(href: string): Record<string, string> {
  return { href, rel: 'noopener noreferrer' }
}

/** The URL of the link the selection (or caret) is in, if any: to prefill the link input. */
export function linkAtSelection(root: HTMLElement, selection: Selection): string | null {
  const range = currentRange(root, selection)
  return range ? (closestWithin(root, range.startContainer, 'a')?.getAttribute('href') ?? null) : null
}

/**
 * Links the selection to [rawUrl]: a link the selection is in gets the new URL, otherwise the selected text becomes a
 * link (a caret inserts the URL as the link text). An empty URL removes the link. A URL that is not acceptable (see
 * `sanitizeLinkUrl`) changes nothing and returns false.
 */
export function insertLink(root: HTMLElement, selection: Selection, rawUrl: string): boolean {
  const initial = currentRange(root, selection)
  if (!initial) return false
  if (!rawUrl.trim()) return removeLinks(root, selection)
  const href = sanitizeLinkUrl(rawUrl)
  if (!href) return false
  if (initial.collapsed) {
    const existing = closestWithin(root, initial.startContainer, 'a')
    if (existing) {
      existing.setAttribute('href', href)
      return true
    }
    const link = document.createElement('a')
    for (const [name, value] of Object.entries(linkAttributes(href))) link.setAttribute(name, value)
    link.textContent = href
    initial.insertNode(link)
    const caret = document.createRange()
    caret.setStartAfter(link)
    caret.collapse(true)
    setSelection(selection, caret)
    return true
  }
  return preservingSelection(root, selection, (range) => {
    const pieces = textPieces(root, range)
    if (!pieces.length) return { changed: false }
    for (const piece of pieces) {
      const existing = closestWithin(root, piece, 'a')
      if (existing) existing.setAttribute('href', href)
      else mergeSiblings(wrapPiece(piece, 'a', linkAttributes(href)))
    }
    return { changed: true }
  })
}

/** Unlinks what the selection (or the caret) touches. */
export function removeLinks(root: HTMLElement, selection: Selection): boolean {
  return preservingSelection(root, selection, (range) => {
    const links = new Set<HTMLElement>()
    const around = closestWithin(root, range.startContainer, 'a')
    if (around) links.add(around)
    if (!range.collapsed) {
      for (const piece of textPieces(root, range)) {
        const link = closestWithin(root, piece, 'a')
        if (link) links.add(link)
      }
    }
    for (const link of links) {
      while (link.firstChild) link.before(link.firstChild)
      link.remove()
    }
    return { changed: links.size > 0 }
  })
}

// ── Dispatch ─────────────────────────────────────────────────────────────────────────────────────────────

/** What a key combination asks for in the markdown editor, or null when it is not one of the shortcuts. */
export function markdownActionFor(event: KeyboardEvent): MarkdownAction | null {
  if (!(event.ctrlKey || event.metaKey)) return null
  const key = event.key.toLowerCase()
  const digit = (n: string): boolean => event.code === `Digit${n}` || event.key === n
  if (event.altKey && !event.shiftKey) {
    if (digit('1')) return { kind: 'block', block: 'h1' }
    if (digit('2')) return { kind: 'block', block: 'h2' }
    if (digit('3')) return { kind: 'block', block: 'h3' }
    if (digit('0')) return { kind: 'block', block: 'p' }
    return null
  }
  if (event.shiftKey && !event.altKey) {
    if (event.code === 'Digit8' || key === '8' || key === '*') return { kind: 'list', list: 'ul' }
    if (event.code === 'Digit7' || key === '7' || key === '&') return { kind: 'list', list: 'ol' }
    return null
  }
  if (event.altKey || event.shiftKey) return null
  switch (key) {
    case 'b':
      return { kind: 'inline', tag: 'strong' }
    case 'i':
      return { kind: 'inline', tag: 'em' }
    case 'e':
      return { kind: 'inline', tag: 'code' }
    case 'k':
      return { kind: 'link' }
    default:
      return null
  }
}

/** Runs a formatting action (the link one needs a URL: see `insertLink`). Returns whether the document changed. */
export function runMarkdownAction(root: HTMLElement, selection: Selection, action: MarkdownAction): boolean {
  switch (action.kind) {
    case 'inline':
      return toggleInline(root, selection, action.tag)
    case 'block':
      return setBlockType(root, selection, action.block)
    case 'list':
      return toggleList(root, selection, action.list)
    case 'link':
      return false
  }
}
