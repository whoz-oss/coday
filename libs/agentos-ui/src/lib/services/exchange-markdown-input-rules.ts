/**
 * Markdown input rules of the rendered document edited in place: typing the markdown shortcut converts it to the
 * formatted element right away (the way editors do), instead of leaving the syntax as text.
 *
 * - at the start of a paragraph, on the space key: `# ` `## ` `### ` (headings), `- ` or `* ` (bullet list), `1. `
 *   (numbered list), `> ` (quote);
 * - on Enter, a paragraph that holds only a fence (three backticks and an optional language) becomes a code block;
 * - while typing, `**text**` and `*text*` (and `` `code` ``) become strong, em and code as the closing marker is typed.
 *
 * Each rule is applied from the keydown (block rules, which replace the key) or the input event (inline rules, which
 * look at what has just been typed) and reports whether it changed the document.
 */
import {
  convertToCodeBlock,
  enclosingBlock,
  setBlockType,
  toggleBlockquote,
  toggleList,
  ZERO_WIDTH_SPACE,
} from './exchange-markdown-commands'

/** The text of [block] before the caret, or null when the caret is not collapsed inside [block]. */
function textBeforeCaret(block: HTMLElement, selection: Selection): string | null {
  if (!selection.rangeCount) return null
  const range = selection.getRangeAt(0)
  if (!range.collapsed || !block.contains(range.startContainer)) return null
  const head = document.createRange()
  head.selectNodeContents(block)
  head.setEnd(range.startContainer, range.startOffset)
  return head.toString()
}

/** Deletes the first [count] characters of the text of [block] (the markdown marker that was typed). */
function deleteLeadingText(block: HTMLElement, count: number): void {
  const walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT)
  let remaining = count
  for (let node = walker.nextNode() as Text | null; node && remaining > 0; node = walker.nextNode() as Text | null) {
    const taken = Math.min(remaining, node.length)
    node.deleteData(0, taken)
    remaining -= taken
  }
}

function collapseInto(selection: Selection, block: HTMLElement): void {
  if (!block.firstChild) block.appendChild(document.createElement('br'))
  const range = document.createRange()
  range.setStart(block, 0)
  range.collapse(true)
  selection.removeAllRanges()
  selection.addRange(range)
}

const BLOCK_RULES: {
  pattern: RegExp
  apply: (root: HTMLElement, selection: Selection, match: RegExpExecArray) => boolean
}[] = [
  {
    pattern: /^(#{1,3})$/,
    apply: (root, selection, m) => setBlockType(root, selection, `h${m[1]!.length}` as 'h1' | 'h2' | 'h3'),
  },
  { pattern: /^[-*]$/, apply: (root, selection) => toggleList(root, selection, 'ul') },
  { pattern: /^1[.)]$/, apply: (root, selection) => toggleList(root, selection, 'ol') },
  { pattern: /^>$/, apply: (root, selection) => toggleBlockquote(root, selection) },
]

/**
 * The space key at the start of a block: when what precedes the caret is a block marker (`#`, `-`, `1.`, `>`), the
 * marker is removed and the block gets its format. Returns true when the rule applied (the caller cancels the key).
 * Inside a code block, a list item that already is a list, or anywhere but the start of the block, nothing happens.
 */
export function applyBlockInputRule(root: HTMLElement, selection: Selection): boolean {
  if (!selection.rangeCount) return false
  const block = enclosingBlock(root, selection.getRangeAt(0).startContainer)
  if (!block || block.tagName === 'PRE' || block.tagName === 'LI') return false
  const before = textBeforeCaret(block, selection)
  if (before === null) return false
  for (const rule of BLOCK_RULES) {
    const match = rule.pattern.exec(before)
    if (!match || before.length !== match[0].length) continue
    deleteLeadingText(block, before.length)
    if (!block.textContent) collapseInto(selection, block)
    else {
      const range = document.createRange()
      range.setStart(block, 0)
      range.collapse(true)
      selection.removeAllRanges()
      selection.addRange(range)
    }
    return rule.apply(root, selection, match)
  }
  return false
}

/**
 * Enter on a paragraph that holds only a code fence (three backticks, optionally with a language): it becomes an
 * empty code block. Returns true when the rule applied (the caller cancels the key).
 */
export function applyCodeFenceRule(root: HTMLElement, selection: Selection): boolean {
  if (!selection.rangeCount) return false
  const block = enclosingBlock(root, selection.getRangeAt(0).startContainer)
  if (!block || block.tagName !== 'P') return false
  const match = /^```([\w-]*)$/.exec((block.textContent ?? '').trim())
  if (!match) return false
  return convertToCodeBlock(root, selection, match[1] ?? '')
}

const INLINE_RULES: { pattern: RegExp; tag: 'strong' | 'em' | 'code'; markerLength: number }[] = [
  // **text** (checked before *text*, which would eat half of it)
  { pattern: /\*\*([^*\s](?:[^*]*[^*\s])?)\*\*$/, tag: 'strong', markerLength: 2 },
  { pattern: /(?<![*\w])\*([^*\s](?:[^*]*[^*\s])?)\*$/, tag: 'em', markerLength: 1 },
  { pattern: /`([^`]+)`$/, tag: 'code', markerLength: 1 },
]

/**
 * Typing the closing marker of `**text**`, `*text*` or `` `text` ``: the markers go and the text is wrapped in
 * strong, em or code. The caret is left after the formatted element, in an unformatted text node, so that the text
 * typed next is not formatted. Returns true when the document changed.
 */
export function applyInlineInputRule(root: HTMLElement, selection: Selection): boolean {
  if (!selection.rangeCount) return false
  const range = selection.getRangeAt(0)
  const node = range.startContainer
  if (!range.collapsed || node.nodeType !== Node.TEXT_NODE || !root.contains(node)) return false
  const block = enclosingBlock(root, node)
  if (!block || block.tagName === 'PRE') return false
  const parent = node.parentElement
  if (parent && parent.closest('code, a')) return false
  const text = node as Text
  const before = text.data.slice(0, range.startOffset)
  for (const rule of INLINE_RULES) {
    const match = rule.pattern.exec(before)
    if (!match) continue
    const start = match.index
    const content = match[1]!
    text.splitText(range.startOffset)
    const tail = text.splitText(start)
    // `tail` now holds the whole `**text**`; its content replaces it.
    const element = document.createElement(rule.tag)
    element.textContent = content
    tail.replaceWith(element)
    const caretText = document.createTextNode(ZERO_WIDTH_SPACE)
    element.after(caretText)
    const caret = document.createRange()
    caret.setStart(caretText, caretText.length)
    caret.collapse(true)
    selection.removeAllRanges()
    selection.addRange(caret)
    return true
  }
  return false
}
