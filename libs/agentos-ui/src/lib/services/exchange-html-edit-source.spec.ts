import { EXCHANGE_EDIT_SOURCE } from './exchange-html-edit-source'
import { EXCHANGE_BRIDGE_SCRIPT_ID, EXCHANGE_SERIALIZE_SOURCE, withSelectionBridge } from './exchange-html-srcdoc'

interface Edit {
  apply(doc: Document, range: Range, html: string | null, text: string | null): boolean
  parse(doc: Document, html: string): DocumentFragment
  selectionHtml(doc: Document, range: Range, cap: number): string
  selectionContext(range: Range): { tag: string; class?: string; style?: string }[]
}

/** The very source the bridge runs in the iframe, run here on a jsdom document. */
const edit = new Function(EXCHANGE_EDIT_SOURCE)() as Edit
const serialize = new Function('doc', EXCHANGE_SERIALIZE_SOURCE) as (doc: Document) => string

function documentOf(body: string): Document {
  return new DOMParser().parseFromString(`<!DOCTYPE html><html><head></head><body>${body}</body></html>`, 'text/html')
}

/** A range from the first occurrence of [from] to the end of the first occurrence of [to], in text nodes. */
function rangeOver(doc: Document, from: string, to = from): Range {
  const walker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT)
  const range = doc.createRange()
  let started = false
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    const value = node.nodeValue ?? ''
    if (!started && value.includes(from)) {
      range.setStart(node, value.indexOf(from))
      started = true
    }
    if (started && value.includes(to)) {
      range.setEnd(node, value.indexOf(to) + to.length)
      return range
    }
  }
  throw new Error(`range ${from}..${to} not found`)
}

describe('bridge editing logic (the source that runs in the iframe)', () => {
  const STYLED = '<p style="color:red">Hello <b class="x">big</b> world</p>'

  describe('a plain-text proposal: a style-preserving text edit', () => {
    it('changes the word and keeps the style attribute and the <b class>', () => {
      const doc = documentOf(STYLED)
      const range = rangeOver(doc, 'Hello', ' world')
      expect(String(range)).toBe('Hello big world')

      expect(edit.apply(doc, range, null, 'Hello huge world')).toBe(true)

      expect(doc.body.innerHTML).toBe('<p style="color:red">Hello <b class="x">huge</b> world</p>')
    })

    it('keeps every element when words are added and removed', () => {
      const doc = documentOf(STYLED)
      edit.apply(doc, rangeOver(doc, 'Hello', ' world'), null, 'Well hello big new world')
      const p = doc.querySelector('p')!
      expect(p.getAttribute('style')).toBe('color:red')
      expect(p.querySelector('b.x')!.textContent).toBe('big')
      expect(p.textContent).toBe('Well hello big new world')
      expect(doc.querySelectorAll('p, b')).toHaveLength(2)
    })

    it('removes the text node that is left empty, never an element', () => {
      const doc = documentOf(STYLED)
      edit.apply(doc, rangeOver(doc, 'Hello', ' world'), null, 'Hello world')
      expect(doc.querySelector('p')!.textContent).toBe('Hello world')
      expect(doc.querySelector('b.x')).not.toBeNull()
    })

    it('is also the way a fragment with the same tags is applied', () => {
      const doc = documentOf(STYLED)
      edit.apply(doc, rangeOver(doc, 'Hello', ' world'), '<p style="color:blue">Hello <b>huge</b> world</p>', null)
      // The structure matches, so only the words are taken from the proposal: the page keeps its own attributes.
      expect(doc.body.innerHTML).toBe('<p style="color:red">Hello <b class="x">huge</b> world</p>')
    })
  })

  describe('an HTML proposal that changes the markup', () => {
    it('inserts the added <em> at the place of the passage, in the page', () => {
      const doc = documentOf(STYLED)
      edit.apply(doc, rangeOver(doc, 'big'), '<em>very</em> big', null)
      const p = doc.querySelector('p')!
      expect(p.getAttribute('style')).toBe('color:red')
      expect(p.querySelector('em')!.textContent).toBe('very')
      expect(p.textContent).toBe('Hello very big world')
    })

    it('inserts an inline fragment at the caret, inside the surrounding inline element', () => {
      const doc = documentOf('<p>one <span class="hl">two three</span> four</p>')
      edit.apply(doc, rangeOver(doc, 'two'), '<i>2</i>', null)
      expect(doc.querySelector('span.hl')!.innerHTML).toBe('<i>2</i> three')
    })

    it('removes script, iframe, on* attributes and javascript: / data: URLs', () => {
      const doc = documentOf('<p>say hello now</p>')
      edit.apply(
        doc,
        rangeOver(doc, 'hello'),
        '<a href="javascript:alert(1)" onclick="x()">ok</a><script>alert(1)</script>' +
          '<iframe src="https://e.com"></iframe><img src="data:text/html,x" onerror="y()"><a href="https://e.com" class="k">link</a>',
        null
      )
      const html = doc.body.innerHTML
      expect(html).not.toContain('<script')
      expect(html).not.toContain('<iframe')
      expect(html).not.toContain('onclick')
      expect(html).not.toContain('onerror')
      expect(html).not.toContain('javascript:')
      expect(html).not.toContain('data:text')
      expect(html).toContain('<a href="https://e.com" class="k">link</a>')
      expect(doc.body.textContent).toContain('ok')
    })

    it('parses inertly: nothing of the proposal runs, nor is attached to the document, before it is inserted', () => {
      const doc = documentOf('<p>x</p>')
      const fragment = edit.parse(doc, '<img src="x" onerror="window.ran=1"><script>window.ran=2</script>')
      expect(fragment.querySelector('script')).toBeNull()
      expect(fragment.querySelector('img')!.hasAttribute('onerror')).toBe(false)
      expect(doc.querySelector('img')).toBeNull()
    })

    it('removes an empty styled element wholly inside the range, even one with a class', () => {
      const doc = documentOf('<div>before <span class="a" style="x:y"></span><b>mid</b> after</div>')
      const range = doc.createRange()
      range.setStart(doc.querySelector('div')!.firstChild!, 3)
      range.setEnd(doc.querySelector('div')!.lastChild!, 3)
      edit.apply(doc, range, '<em>new</em>', null)
      expect(doc.querySelector('span.a')).toBeNull()
      expect(doc.querySelector('b')).toBeNull()
      expect(doc.querySelector('em')!.textContent).toBe('new')
    })

    it('keeps a partially covered element that is left empty but has a class, a style or an id; drops a bare one', () => {
      const doc = documentOf(
        '<div>a<span class="c">cut</span>b<span style="z:1">cut</span>c<span id="i">cut</span>d<span>cut</span>e</div>'
      )
      const spans = Array.from(doc.querySelectorAll('span'))
      const range = doc.createRange()
      range.setStart(spans[0]!.firstChild!, 0)
      range.setEnd(spans[3]!.firstChild!, 3)
      edit.apply(doc, range, '<em>x</em>', null)
      // The first span starts the range: it keeps its place, emptied; the last one, bare, goes with its text.
      expect(doc.querySelector('span.c')).not.toBeNull()
      expect(doc.querySelector('span[style]')).toBeNull()
      expect(doc.querySelector('em')!.textContent).toBe('x')
    })

    it('keeps a partially covered styled element that still has text', () => {
      const doc = documentOf('<p>start <b class="k">bold text</b> end</p>')
      const range = doc.createRange()
      range.setStart(doc.querySelector('p')!.firstChild!, 0)
      range.setEnd(doc.querySelector('b')!.firstChild!, 5)
      edit.apply(doc, range, '<em>new</em>', null)
      expect(doc.querySelector('b.k')!.textContent).toBe('text')
    })
  })

  describe('what the selection reports', () => {
    it('serializes the markup of the range, capped', () => {
      const doc = documentOf(STYLED)
      const range = rangeOver(doc, 'Hello', ' world')
      expect(edit.selectionHtml(doc, range, 50_000)).toBe('Hello <b class="x">big</b> world')
      expect(edit.selectionHtml(doc, range, 5)).toBe('Hello')
    })

    it('leaves the highlight marks out of the markup', () => {
      const doc = documentOf('<p>a <mark data-agentos-selection="" style="background:#ffe14d">big</mark> b</p>')
      const html = edit.selectionHtml(doc, rangeOver(doc, 'a ', ' b'), 50_000)
      expect(html).not.toContain('mark')
    })

    it('describes the ancestors from the selection up to body, with class and style', () => {
      const doc = documentOf('<div class="card" style="padding:4px"><p>text</p></div>')
      const context = edit.selectionContext(rangeOver(doc, 'text'))
      expect(context.map((c) => c.tag)).toEqual(['p', 'div', 'body'])
      expect(context[1]).toEqual({ tag: 'div', class: 'card', style: 'padding:4px' })
    })
  })

  describe('what is saved', () => {
    it('keeps the attributes of the edited element and has no bridge artefact', () => {
      const doc = new DOMParser().parseFromString(
        withSelectionBridge(`<!DOCTYPE html><html><head></head><body>${STYLED}</body></html>`),
        'text/html'
      )
      const style = doc.createElement('style')
      style.id = 'agentos-exchange-highlight-style'
      doc.head.appendChild(style)
      expect(doc.getElementById(EXCHANGE_BRIDGE_SCRIPT_ID)).not.toBeNull()

      edit.apply(doc, rangeOver(doc, 'Hello', ' world'), null, 'Hello huge world')
      const saved = serialize(doc)

      expect(saved).toContain('<p style="color:red">Hello <b class="x">huge</b> world</p>')
      expect(saved).not.toContain(EXCHANGE_BRIDGE_SCRIPT_ID)
      expect(saved).not.toContain('agentos-exchange-highlight-style')
      expect(saved).not.toContain('postMessage')
      expect(saved).not.toContain('data-agentos-selection')
    })
  })

  describe('inside the bridge script', () => {
    it('is the same source, and the replace message is read from the parent only', () => {
      const script = withSelectionBridge('<p>x</p>')
      expect(script).toContain('var E=(function(){')
      expect(script).toContain('function insertFragment(')
      expect(script).toContain('typeof d.selectionText==="string"')
      expect(script).toContain('e.source!==parent')
      expect(script).toContain('html:cur?selHtml(cur):""')
      expect(script).toContain('context:cur?selContext(cur):""')
    })
  })
})
