import {
  insertLink,
  markdownActionFor,
  runMarkdownAction,
  sanitizeLinkUrl,
  setBlockType,
  toggleBlockquote,
  toggleInline,
  toggleList,
} from './exchange-markdown-commands'
import { htmlToMarkdown, rangeToMarkdown, renderMarkdownFragment } from './exchange-markdown-convert'
import { applyBlockInputRule, applyCodeFenceRule, applyInlineInputRule } from './exchange-markdown-input-rules'
import { replaceRangeWithFragment } from './exchange-markdown-insert'

/** A contenteditable-like root built from [html], attached to the document so that selections work. */
function editor(html: string): HTMLElement {
  const root = document.createElement('div')
  root.setAttribute('contenteditable', 'true')
  root.innerHTML = html
  document.body.appendChild(root)
  return root
}

function textNode(root: Node, text: string): Text {
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    if ((node.nodeValue ?? '').includes(text)) return node as Text
  }
  throw new Error(`no text node with "${text}"`)
}

/** Selects [text] inside the node that holds it. */
function select(root: HTMLElement, text: string): Selection {
  const node = textNode(root, text)
  const at = (node.nodeValue ?? '').indexOf(text)
  const range = document.createRange()
  range.setStart(node, at)
  range.setEnd(node, at + text.length)
  const selection = window.getSelection()!
  selection.removeAllRanges()
  selection.addRange(range)
  return selection
}

/** A caret at [offset] of the text node holding [text]. */
function caret(root: HTMLElement, text: string, offset: number): Selection {
  const node = textNode(root, text)
  const range = document.createRange()
  range.setStart(node, offset)
  range.collapse(true)
  const selection = window.getSelection()!
  selection.removeAllRanges()
  selection.addRange(range)
  return selection
}

afterEach(() => {
  window.getSelection()?.removeAllRanges()
  document.body.innerHTML = ''
})

describe('inline commands', () => {
  it.each([
    ['strong', 'strong'],
    ['em', 'em'],
    ['code', 'code'],
  ] as const)('wraps the selection in %s, and takes it off again', (_label, tag) => {
    const root = editor('<p>one two three</p>')
    const selection = select(root, 'two')
    expect(toggleInline(root, selection, tag)).toBe(true)
    expect(root.innerHTML).toBe(`<p>one <${tag}>two</${tag}> three</p>`)
    expect(selection.toString()).toBe('two')
    expect(toggleInline(root, selection, tag)).toBe(true)
    expect(root.innerHTML).toBe('<p>one two three</p>')
  })

  it('formats the word around a caret', () => {
    const root = editor('<p>one two three</p>')
    const selection = caret(root, 'one two three', 5)
    toggleInline(root, selection, 'strong')
    expect(root.innerHTML).toBe('<p>one <strong>two</strong> three</p>')
  })

  it('opens an empty element to type in when the caret is between words', () => {
    const root = editor('<p>one  two</p>')
    const selection = caret(root, 'one  two', 4)
    toggleInline(root, selection, 'em')
    expect(root.querySelector('em')).not.toBeNull()
    expect(selection.anchorNode?.parentElement?.tagName).toBe('EM')
  })

  it('merges with a neighbour of the same format', () => {
    const root = editor('<p><strong>one</strong> two</p>')
    const selection = select(root, ' two')
    toggleInline(root, selection, 'strong')
    expect(root.innerHTML).toBe('<p><strong>one two</strong></p>')
  })

  it('formats each block of a selection spanning several', () => {
    const root = editor('<p>one</p><p>two</p>')
    const range = document.createRange()
    range.setStart(textNode(root, 'one'), 0)
    range.setEnd(textNode(root, 'two'), 3)
    const selection = window.getSelection()!
    selection.removeAllRanges()
    selection.addRange(range)
    toggleInline(root, selection, 'strong')
    expect(root.innerHTML).toBe('<p><strong>one</strong></p><p><strong>two</strong></p>')
  })
})

describe('block commands', () => {
  it.each(['h1', 'h2', 'h3'] as const)('turns a paragraph into %s, keeping its children', (type) => {
    const root = editor('<p>a <em>b</em> c</p>')
    const selection = caret(root, 'a ', 1)
    expect(setBlockType(root, selection, type)).toBe(true)
    expect(root.innerHTML).toBe(`<${type}>a <em>b</em> c</${type}>`)
    // the caret stays in the same text
    expect(selection.anchorNode?.textContent).toBe('a ')
  })

  it('turns a heading back into a paragraph, when asked for it or for the same heading again', () => {
    const root = editor('<h1>Title</h1>')
    const selection = caret(root, 'Title', 2)
    setBlockType(root, selection, 'p')
    expect(root.innerHTML).toBe('<p>Title</p>')
    setBlockType(root, selection, 'h2')
    setBlockType(root, selection, 'h2')
    expect(root.innerHTML).toBe('<p>Title</p>')
  })

  it('converts every block of a selection', () => {
    const root = editor('<p>one</p><p>two</p>')
    const range = document.createRange()
    range.setStart(textNode(root, 'one'), 1)
    range.setEnd(textNode(root, 'two'), 2)
    const selection = window.getSelection()!
    selection.removeAllRanges()
    selection.addRange(range)
    setBlockType(root, selection, 'h1')
    expect(root.innerHTML).toBe('<h1>one</h1><h1>two</h1>')
  })

  it('takes a list item out of its list when it becomes a heading', () => {
    const root = editor('<ul><li>a</li><li>b</li><li>c</li></ul>')
    const selection = caret(root, 'b', 0)
    setBlockType(root, selection, 'h2')
    expect(root.innerHTML).toBe('<ul><li>a</li></ul><h2>b</h2><ul><li>c</li></ul>')
  })

  it('wraps consecutive paragraphs in one list, and unwraps them', () => {
    const root = editor('<p>one</p><p>two</p><p>three</p>')
    const range = document.createRange()
    range.setStart(textNode(root, 'one'), 0)
    range.setEnd(textNode(root, 'two'), 3)
    const selection = window.getSelection()!
    selection.removeAllRanges()
    selection.addRange(range)
    expect(toggleList(root, selection, 'ul')).toBe(true)
    expect(root.innerHTML).toBe('<ul><li>one</li><li>two</li></ul><p>three</p>')
    toggleList(root, selection, 'ul')
    expect(root.innerHTML).toBe('<p>one</p><p>two</p><p>three</p>')
  })

  it('switches a bullet list to a numbered one, and merges with the list next to it', () => {
    const root = editor('<ol><li>a</li></ol><ul><li>b</li></ul>')
    const selection = caret(root, 'b', 0)
    toggleList(root, selection, 'ol')
    expect(root.innerHTML).toBe('<ol><li>a</li><li>b</li></ol>')
  })

  it('wraps a block in a quote, and unwraps it', () => {
    const root = editor('<p>quoted</p>')
    const selection = caret(root, 'quoted', 2)
    toggleBlockquote(root, selection)
    expect(root.innerHTML).toBe('<blockquote><p>quoted</p></blockquote>')
    toggleBlockquote(root, selection)
    expect(root.innerHTML).toBe('<p>quoted</p>')
  })
})

describe('the link command', () => {
  it('links the selected text', () => {
    const root = editor('<p>see docs now</p>')
    const selection = select(root, 'docs')
    expect(insertLink(root, selection, 'https://example.com/docs')).toBe(true)
    expect(root.querySelector('a')!.getAttribute('href')).toBe('https://example.com/docs')
    expect(root.querySelector('a')!.textContent).toBe('docs')
  })

  it('updates the URL of a link, and removes it when the URL is empty', () => {
    const root = editor('<p>see <a href="https://a.example">docs</a></p>')
    const selection = select(root, 'docs')
    insertLink(root, selection, 'mailto:me@example.com')
    expect(root.querySelector('a')!.getAttribute('href')).toBe('mailto:me@example.com')
    insertLink(root, selection, '')
    expect(root.querySelector('a')).toBeNull()
  })

  it.each([
    'javascript:alert(1)',
    'JavaScript:alert(1)',
    ' java\tscript:alert(1)',
    'data:text/html,<script>alert(1)</script>',
    'vbscript:x',
    'file:///etc/passwd',
    '//evil.example',
  ])('rejects %s, and changes nothing', (url) => {
    const root = editor('<p>see docs now</p>')
    const selection = select(root, 'docs')
    expect(sanitizeLinkUrl(url)).toBeNull()
    expect(insertLink(root, selection, url)).toBe(false)
    expect(root.querySelector('a')).toBeNull()
  })

  it.each(['https://a.example/x', 'http://a.example', 'mailto:a@b.c', '/docs/a.md', 'a/b.md', '../a.md', '#section'])(
    'accepts %s',
    (url) => {
      expect(sanitizeLinkUrl(url)).toBe(url)
    }
  )
})

describe('markdownActionFor (shortcuts)', () => {
  const key = (init: KeyboardEventInit): KeyboardEvent => new KeyboardEvent('keydown', init)
  it.each([
    [
      { key: 'b', ctrlKey: true },
      { kind: 'inline', tag: 'strong' },
    ],
    [
      { key: 'I', metaKey: true },
      { kind: 'inline', tag: 'em' },
    ],
    [
      { key: 'e', ctrlKey: true },
      { kind: 'inline', tag: 'code' },
    ],
    [{ key: 'k', ctrlKey: true }, { kind: 'link' }],
    [
      { key: '1', code: 'Digit1', ctrlKey: true, altKey: true },
      { kind: 'block', block: 'h1' },
    ],
    [
      { key: '2', code: 'Digit2', ctrlKey: true, altKey: true },
      { kind: 'block', block: 'h2' },
    ],
    [
      { key: '3', code: 'Digit3', ctrlKey: true, altKey: true },
      { kind: 'block', block: 'h3' },
    ],
    [
      { key: '0', code: 'Digit0', ctrlKey: true, altKey: true },
      { kind: 'block', block: 'p' },
    ],
    [
      { key: '*', code: 'Digit8', ctrlKey: true, shiftKey: true },
      { kind: 'list', list: 'ul' },
    ],
    [
      { key: '&', code: 'Digit7', ctrlKey: true, shiftKey: true },
      { kind: 'list', list: 'ol' },
    ],
  ])('maps %j', (init, expected) => {
    expect(markdownActionFor(key(init))).toEqual(expected)
  })

  it('ignores plain keys and unknown combinations', () => {
    expect(markdownActionFor(key({ key: 'b' }))).toBeNull()
    expect(markdownActionFor(key({ key: 'z', ctrlKey: true }))).toBeNull()
  })

  it('runs an action on the selection', () => {
    const root = editor('<p>one two</p>')
    const selection = select(root, 'one')
    expect(runMarkdownAction(root, selection, { kind: 'inline', tag: 'strong' })).toBe(true)
    expect(root.innerHTML).toBe('<p><strong>one</strong> two</p>')
  })
})

describe('markdown input rules', () => {
  function typed(html: string, text: string, offset: number): { root: HTMLElement; selection: Selection } {
    const root = editor(html)
    return { root, selection: caret(root, text, offset) }
  }

  it.each([
    ['#', 'h1'],
    ['##', 'h2'],
    ['###', 'h3'],
  ])('"%s " becomes %s', (marker, tag) => {
    const { root, selection } = typed(`<p>${marker}</p>`, marker, marker.length)
    expect(applyBlockInputRule(root, selection)).toBe(true)
    expect(root.querySelector(tag)).not.toBeNull()
    expect(root.textContent).not.toContain('#')
  })

  it('"- " and "* " become a bullet list, "1. " a numbered one', () => {
    for (const marker of ['-', '*']) {
      const { root, selection } = typed(`<p>${marker}</p>`, marker, 1)
      expect(applyBlockInputRule(root, selection)).toBe(true)
      expect(root.querySelector('ul > li')).not.toBeNull()
      root.remove()
    }
    const { root, selection } = typed('<p>1.</p>', '1.', 2)
    expect(applyBlockInputRule(root, selection)).toBe(true)
    expect(root.querySelector('ol > li')).not.toBeNull()
  })

  it('"> " becomes a quote', () => {
    const { root, selection } = typed('<p>&gt;</p>', '>', 1)
    expect(applyBlockInputRule(root, selection)).toBe(true)
    expect(root.querySelector('blockquote > p')).not.toBeNull()
  })

  it('keeps the text that follows the marker', () => {
    const { root, selection } = typed('<p># Title</p>', '# Title', 1)
    expect(applyBlockInputRule(root, selection)).toBe(true)
    expect(root.innerHTML).toBe('<h1> Title</h1>')
  })

  it('does nothing in the middle of a paragraph, or on a marker that is not one', () => {
    const { root, selection } = typed('<p>a - b</p>', 'a - b', 3)
    expect(applyBlockInputRule(root, selection)).toBe(false)
    expect(root.innerHTML).toBe('<p>a - b</p>')
    const four = typed('<p>####</p>', '####', 4)
    expect(applyBlockInputRule(four.root, four.selection)).toBe(false)
  })

  it('a fence and Enter make a code block', () => {
    const { root, selection } = typed('<p>```js</p>', '```js', 5)
    expect(applyCodeFenceRule(root, selection)).toBe(true)
    expect(root.innerHTML).toBe('<pre><code class="language-js">\u200B</code></pre>')
  })

  it('**x** becomes strong, *x* em and `x` code, as the closing marker is typed', () => {
    for (const [typedText, tag, content] of [
      ['a **bold**', 'strong', 'bold'],
      ['a *it*', 'em', 'it'],
      ['a `code`', 'code', 'code'],
    ]) {
      const { root, selection } = typed(`<p>${typedText}</p>`, typedText, typedText.length)
      expect(applyInlineInputRule(root, selection)).toBe(true)
      expect(root.querySelector(tag)!.textContent).toBe(content)
      expect(root.textContent).not.toContain('*')
      // what is typed next is outside of the formatted element
      expect(selection.anchorNode?.parentElement?.tagName).toBe('P')
      root.remove()
    }
  })

  it('does not touch an unfinished marker, nor one inside a code block', () => {
    const open = typed('<p>a **bold</p>', 'a **bold', 8)
    expect(applyInlineInputRule(open.root, open.selection)).toBe(false)
    const pre = typed('<pre><code>a **b**</code></pre>', 'a **b**', 7)
    expect(applyInlineInputRule(pre.root, pre.selection)).toBe(false)
  })
})

describe('inserting rendered markdown in place of a range', () => {
  function rangeOf(root: HTMLElement, from: string, fromOffset: number, to: string, toOffset: number): Range {
    const range = document.createRange()
    range.setStart(textNode(root, from), fromOffset)
    range.setEnd(textNode(root, to), toOffset)
    return range
  }

  it('turns a multi-block proposal into h1 + p elements, never into "# " text', () => {
    const root = editor('<h1>Old title</h1><p>old paragraph</p><p>after</p>')
    const range = rangeOf(root, 'Old title', 0, 'old paragraph', 13)
    replaceRangeWithFragment(root, range, renderMarkdownFragment('# New title\n\nnew paragraph'))
    expect(root.innerHTML).toBe('<h1>New title</h1><p>new paragraph</p><p>after</p>')
    expect(root.textContent).not.toContain('# ')
  })

  it('keeps an inline proposal inline in the surrounding paragraph', () => {
    const root = editor('<p>one two three</p>')
    const range = rangeOf(root, 'one two three', 4, 'one two three', 7)
    replaceRangeWithFragment(root, range, renderMarkdownFragment('TWO'))
    expect(root.innerHTML).toBe('<p>one TWO three</p>')
  })

  it('renders the emphasis of an inline proposal, still inline', () => {
    const root = editor('<p>one two three</p>')
    const range = rangeOf(root, 'one two three', 4, 'one two three', 7)
    replaceRangeWithFragment(root, range, renderMarkdownFragment('**TWO**'))
    expect(root.innerHTML).toBe('<p>one <strong>TWO</strong> three</p>')
  })

  it('joins the rest of the last block when an inline proposal replaces a selection across blocks', () => {
    const root = editor('<p>one two</p><p>three four</p>')
    const range = rangeOf(root, 'one two', 4, 'three four', 5)
    replaceRangeWithFragment(root, range, renderMarkdownFragment('2-3'))
    expect(root.innerHTML).toBe('<p>one 2-3 four</p>')
  })

  it('splits the block around a multi-block proposal in the middle of a paragraph', () => {
    const root = editor('<p>one two three</p>')
    const range = rangeOf(root, 'one two three', 4, 'one two three', 7)
    replaceRangeWithFragment(root, range, renderMarkdownFragment('# T\n\nbody'))
    expect(root.innerHTML).toBe('<p>one </p><h1>T</h1><p>body</p><p> three</p>')
  })

  it('removes the empty blocks left by the deletion', () => {
    const root = editor('<p>first</p><p>second</p><p>third</p>')
    const range = rangeOf(root, 'second', 0, 'second', 6)
    replaceRangeWithFragment(root, range, renderMarkdownFragment('# A\n\nB'))
    expect(root.innerHTML).toBe('<p>first</p><h1>A</h1><p>B</p><p>third</p>')
  })

  it('inserts list items in a list, and normalises the text nodes', () => {
    const root = editor('<ul><li>alpha beta</li></ul>')
    const range = rangeOf(root, 'alpha beta', 6, 'alpha beta', 10)
    replaceRangeWithFragment(root, range, renderMarkdownFragment('- x\n- y'))
    expect(root.querySelectorAll('li')).toHaveLength(3)
    for (const li of Array.from(root.querySelectorAll('li'))) {
      expect(li.childNodes.length).toBeLessThanOrEqual(1)
    }
  })

  it('never inserts script or event handlers (the fragment is sanitised)', () => {
    const fragment = renderMarkdownFragment('<img src=x onerror=alert(1)>\n\n<script>alert(1)</script>\n\ntext')
    const holder = document.createElement('div')
    holder.appendChild(fragment)
    expect(holder.innerHTML).not.toContain('onerror')
    expect(holder.innerHTML).not.toContain('<script')
    expect(holder.textContent).toContain('text')
  })
})

describe('markdown source and round trip, with the real turndown', () => {
  it('computes the markdown of a range spanning an h1 and a paragraph', async () => {
    const root = editor('<h1>Title</h1><p>paragraph</p>')
    const range = document.createRange()
    range.setStart(textNode(root, 'Title'), 0)
    range.setEnd(textNode(root, 'paragraph'), 9)
    expect(await rangeToMarkdown(range)).toBe('# Title\n\nparagraph')
  })

  it('computes the markdown of an inline selection as inline markdown', async () => {
    const root = editor('<p>one <strong>two</strong> three</p>')
    const range = document.createRange()
    range.setStart(textNode(root, 'one '), 0)
    range.setEnd(textNode(root, ' three'), 6)
    expect(await rangeToMarkdown(range)).toBe('one **two** three')
  })

  it('saves headings, bold, italic, code, links and lists made by the commands as markdown', async () => {
    const root = editor('<p>Title</p><p>some bold italic code link</p><p>first</p><p>second</p><p>n1</p><p>n2</p>')
    setBlockType(root, caret(root, 'Title', 0), 'h1')
    toggleInline(root, select(root, 'bold'), 'strong')
    toggleInline(root, select(root, 'italic'), 'em')
    toggleInline(root, select(root, 'code'), 'code')
    insertLink(root, select(root, 'link'), 'https://example.com')
    const ul = document.createRange()
    ul.setStart(textNode(root, 'first'), 0)
    ul.setEnd(textNode(root, 'second'), 6)
    const selection = window.getSelection()!
    selection.removeAllRanges()
    selection.addRange(ul)
    toggleList(root, selection, 'ul')
    const ol = document.createRange()
    ol.setStart(textNode(root, 'n1'), 0)
    ol.setEnd(textNode(root, 'n2'), 2)
    selection.removeAllRanges()
    selection.addRange(ol)
    toggleList(root, selection, 'ol')

    expect(await htmlToMarkdown(root)).toBe(
      [
        '# Title',
        'some **bold** *italic* `code` [link](https://example.com)',
        '-   first\n-   second',
        '1.  n1\n2.  n2',
        '',
      ]
        .join('\n\n')
        .replace(/\n\n$/, '\n')
    )
  })

  it('does not leak the caret placeholders into the markdown', async () => {
    const root = editor('<p>one  two</p>')
    toggleInline(root, caret(root, 'one  two', 4), 'strong')
    expect(await htmlToMarkdown(root)).not.toContain('\u200B')
  })

  it('saves a blockquote and a code block', async () => {
    const root = editor('<p>quoted</p><p>```</p>')
    toggleBlockquote(root, caret(root, 'quoted', 1))
    applyCodeFenceRule(root, caret(root, '```', 3))
    const markdown = await htmlToMarkdown(root)
    expect(markdown).toContain('> quoted')
    expect(markdown).toContain('```')
  })
})
