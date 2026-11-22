/// <reference path="../types/turndown-plugin-gfm.d.ts" />
/**
 * Converts the edited, rendered markdown document back to markdown, at save time.
 *
 * `turndown` and its GFM plugin (tables, strikethrough, task lists) are lazy-imported, like `diff2html` in the
 * environment diff: they weigh on the bundle and are needed by a user who edits a markdown file and saves it only.
 * The conversion is not a perfect round trip (see the known limitations of the viewer): the rendered document is
 * the source of truth of what the user typed, and the markdown is regenerated from it in a normalised style (ATX
 * headings, `-` bullets, fenced code, single `*` emphasis).
 */

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

export async function htmlToMarkdown(root: HTMLElement): Promise<string> {
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
  const markdown = service.turndown(withoutSelectionMarks(root)).replace(/\n{3,}/g, '\n\n')
  return markdown ? `${markdown}\n` : ''
}
