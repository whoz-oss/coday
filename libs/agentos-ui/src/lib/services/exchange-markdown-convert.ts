/// <reference path="../types/turndown-plugin-gfm.d.ts" />
/**
 * Converts the edited, rendered markdown document back to markdown, at save time, and the other way round for the
 * fragments the editor inserts (an accepted proposal, a paste).
 *
 * `turndown` and its GFM plugin (tables, strikethrough, task lists) are lazy-imported, like `diff2html` in the
 * environment diff: they weigh on the bundle and are needed by a user who edits a markdown file and saves it only.
 * The conversion is not a perfect round trip (see the known limitations of the viewer): the rendered document is
 * the source of truth of what the user typed, and the markdown is regenerated from it in a normalised style (ATX
 * headings, `-` bullets, fenced code, single `*` emphasis).
 *
 * Markdown goes back to DOM through the one sanitized pipeline of the app (`renderSanitizedMarkdown`: marked, then
 * DOMPurify): nothing is ever inserted in the editor as raw HTML or as markdown syntax typed as text.
 */
import { renderSanitizedMarkdown } from './markdown-renderer.service'

interface TurndownLike {
  use(plugin: unknown): void
  turndown(input: HTMLElement | string): string
}
type TurndownConstructor = new (options?: Record<string, unknown>) => TurndownLike

/** The element without what the viewer added to it for the selection highlight (`<mark class="agentos-selection">`). */
function withoutSelectionMarks(root: HTMLElement): HTMLElement {
  const clone = root.cloneNode(true) as HTMLElement
  for (const mark of Array.from(clone.querySelectorAll('mark.agentos-selection'))) {
    const parent = mark.parentNode
    if (!parent) continue
    while (mark.firstChild) parent.insertBefore(mark.firstChild, mark)
    parent.removeChild(mark)
  }
  return clone
}

/** The turndown service, configured once for the whole viewer (save, selection and paste use the same style). */
async function createTurndown(): Promise<TurndownLike> {
  const [turndownModule, gfmModule] = await Promise.all([import('turndown'), import('turndown-plugin-gfm')])
  const loaded = turndownModule as unknown as { default?: TurndownConstructor } & TurndownConstructor
  const Turndown: TurndownConstructor = loaded.default ?? loaded
  const service = new Turndown({
    headingStyle: 'atx',
    codeBlockStyle: 'fenced',
    bulletListMarker: '-',
    emDelimiter: '*',
    // The document is rendered with `breaks: true`: a single line break is a line break, no trailing spaces needed.
    br: '',
  })
  service.use((gfmModule as unknown as { gfm: unknown }).gfm)
  return service
}

export async function htmlToMarkdown(root: HTMLElement): Promise<string> {
  const service = await createTurndown()
  const markdown = service
    .turndown(withoutSelectionMarks(root))
    // The zero-width spaces that keep a caret inside an empty inline element (see exchange-markdown-commands).
    .replace(/\u200B/g, '')
    .replace(/\n{3,}/g, '\n\n')
  return markdown ? `${markdown}\n` : ''
}

/**
 * The markdown source of a selection of the rendered document: turndown, with the same configuration as the save, on
 * a copy of the range's contents wrapped in a container (so that a selection spanning an h1 and a paragraph gives
 * `# Title\n\nparagraph`, not the flattened text). Trimmed: the passage, not the document.
 */
export async function rangeToMarkdown(range: Range): Promise<string> {
  const container = document.createElement('div')
  container.appendChild(range.cloneContents())
  return (await htmlToMarkdown(container)).trim()
}

/**
 * The markdown of a piece of HTML that comes from outside (a paste). The HTML is parsed by `DOMParser`, which builds
 * an inert document (no script runs, no image loads), and only turndown reads it: it never reaches the editor.
 */
export async function htmlStringToMarkdown(html: string): Promise<string> {
  const parsed = new DOMParser().parseFromString(html, 'text/html')
  return (await htmlToMarkdown(parsed.body)).trim()
}

/**
 * Renders [markdown] to DOM nodes with the sanitized pipeline of the app (`renderSanitizedMarkdown`: marked, then
 * DOMPurify, same configuration as the chat), parsed in an inert `<template>`. The nodes are safe to insert in the
 * editor.
 */
export function renderMarkdownFragment(markdown: string): DocumentFragment {
  const template = document.createElement('template')
  template.innerHTML = renderSanitizedMarkdown(markdown)
  const fragment = template.content
  // marked puts a line break between the blocks it renders: between blocks it is not content.
  const blocks = Array.from(fragment.childNodes).some(
    (node) =>
      node.nodeType === Node.ELEMENT_NODE &&
      /^(P|H[1-6]|UL|OL|BLOCKQUOTE|PRE|TABLE|DIV|HR)$/.test((node as Element).tagName)
  )
  if (blocks) {
    for (const node of Array.from(fragment.childNodes)) {
      if (node.nodeType === Node.TEXT_NODE && !(node.textContent ?? '').trim()) fragment.removeChild(node)
    }
  }
  return fragment
}
