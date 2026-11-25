import {
  EXCHANGE_BRIDGE_SCRIPT_ID,
  EXCHANGE_EDIT_MESSAGE_TYPE,
  EXCHANGE_HIGHLIGHT_MESSAGE_TYPE,
  EXCHANGE_HIGHLIGHT_STYLE_ID,
  EXCHANGE_HTML_MESSAGE_TYPE,
  EXCHANGE_RECT_SOURCE,
  EXCHANGE_ANCHOR_MESSAGE_TYPE,
  EXCHANGE_REPLACE_MESSAGE_TYPE,
  EXCHANGE_SELECTION_MESSAGE_TYPE,
  EXCHANGE_SERIALIZE_SOURCE,
  withSelectionBridge,
} from './exchange-html-srcdoc'

describe('withSelectionBridge', () => {
  const BRIDGE = new RegExp(
    `<script id="${EXCHANGE_BRIDGE_SCRIPT_ID}">\\(function\\(\\)\\{try\\{[^]*postMessage[^]*</script>`
  )

  it('injects the bridge before the closing body tag and keeps the original markup', () => {
    const html = '<html><body><h1>Report</h1><script>run()</script></body></html>'
    const result = withSelectionBridge(html)
    const at = result.lastIndexOf('</body>')
    expect(result.slice(0, at)).toContain('<h1>Report</h1><script>run()</script>')
    expect(result.slice(0, at)).toMatch(BRIDGE)
    expect(result.slice(at)).toBe('</body></html>')
    expect(result.replace(BRIDGE, '')).toBe(html.replace(BRIDGE, ''))
  })

  it('uses the last closing body tag, whatever its case', () => {
    const html = '<body>a</BODY ><pre>&lt;/body&gt;</pre></BODY>'
    const result = withSelectionBridge(html)
    expect(result.endsWith('</BODY>')).toBe(true)
    expect(result.indexOf(`<script id="${EXCHANGE_BRIDGE_SCRIPT_ID}"`)).toBeGreaterThan(result.indexOf('</BODY >'))
  })

  it('appends the bridge when there is no body tag', () => {
    const html = '<h1>Fragment</h1>'
    const result = withSelectionBridge(html)
    expect(result.startsWith(html)).toBe(true)
    expect(result.slice(html.length)).toMatch(BRIDGE)
  })

  it('posts the debounced selection with the agreed message type, guarded by try/catch', () => {
    const result = withSelectionBridge('<p>x</p>')
    expect(result).toContain(`type:"${EXCHANGE_SELECTION_MESSAGE_TYPE}"`)
    expect(result).toContain('selectionchange')
    expect(result).toContain('150')
    expect(result).toContain('catch')
  })

  it('speaks the edit, highlight, replace and html messages, and only listens to its parent', () => {
    const result = withSelectionBridge('<p>x</p>')
    expect(result).toContain(EXCHANGE_EDIT_MESSAGE_TYPE)
    expect(result).toContain(EXCHANGE_HIGHLIGHT_MESSAGE_TYPE)
    expect(result).toContain(EXCHANGE_REPLACE_MESSAGE_TYPE)
    expect(result).toContain(EXCHANGE_HTML_MESSAGE_TYPE)
    expect(result).toContain('designMode')
    expect(result).toContain('e.source!==parent')
  })

  it('posts the rect of the selection with it, and again on scroll and resize, throttled by requestAnimationFrame', () => {
    const result = withSelectionBridge('<p>x</p>')
    expect(result).toContain('rect:cur?rectOf(cur):null')
    expect(result).toContain(`type:"${EXCHANGE_ANCHOR_MESSAGE_TYPE}",rect:rectOf(r)`)
    expect(result).toContain('addEventListener("scroll",queueAnchor,true)')
    expect(result).toContain('addEventListener("resize",queueAnchor)')
    expect(result).toContain('requestAnimationFrame(postAnchor)')
  })

  it('does not report while the mouse is still down, only on its release', () => {
    const result = withSelectionBridge('<p>x</p>')
    expect(result).toContain('if(!down)report()')
    expect(result).toContain('"mouseup"')
  })

  it('contains no closing script tag besides its own', () => {
    const script = withSelectionBridge('<p>x</p>').slice('<p>x</p>'.length)
    expect(script.match(/<\/script>/gi)).toHaveLength(1)
  })
})

describe('bridge rect', () => {
  const rectOf = new Function('r', EXCHANGE_RECT_SOURCE) as (r: unknown) => unknown

  it('is the last client rect of the range', () => {
    const range = {
      getClientRects: () => [
        { left: 1, top: 2, right: 3, bottom: 4, width: 2 },
        { left: 5, top: 6, right: 7, bottom: 8, width: 2 },
      ],
      getBoundingClientRect: () => ({ left: 0, top: 0, right: 100, bottom: 100 }),
    }
    expect(rectOf(range)).toEqual({ left: 5, top: 6, right: 7, bottom: 8 })
  })

  it('falls back to the bounding rect, and to null when it cannot be measured', () => {
    expect(
      rectOf({ getClientRects: () => [], getBoundingClientRect: () => ({ left: 1, top: 2, right: 3, bottom: 4 }) })
    ).toEqual({ left: 1, top: 2, right: 3, bottom: 4 })
    expect(rectOf({})).toBeNull()
  })
})

describe('bridge serializer', () => {
  const serialize = new Function('doc', EXCHANGE_SERIALIZE_SOURCE) as (doc: Document) => string

  function documentOf(html: string): Document {
    return new DOMParser().parseFromString(html, 'text/html')
  }

  it('serializes the document with a doctype', () => {
    const result = serialize(documentOf('<html><head><title>T</title></head><body><p>Hi</p></body></html>'))
    expect(result.startsWith('<!DOCTYPE html>\n<html>')).toBe(true)
    expect(result).toContain('<p>Hi</p>')
  })

  it('removes its own script and highlight style, and keeps the scripts of the page', () => {
    const html = withSelectionBridge('<html><head></head><body><p>Hi</p><script>page()</script></body></html>')
    const doc = documentOf(html)
    const style = doc.createElement('style')
    style.id = EXCHANGE_HIGHLIGHT_STYLE_ID
    doc.head.appendChild(style)
    expect(doc.getElementById(EXCHANGE_BRIDGE_SCRIPT_ID)).not.toBeNull()
    const result = serialize(doc)
    expect(result).not.toContain(EXCHANGE_BRIDGE_SCRIPT_ID)
    expect(result).not.toContain(EXCHANGE_HIGHLIGHT_STYLE_ID)
    expect(result).not.toContain('postMessage')
    expect(result).toContain('<script>page()</script>')
    // The live document is left untouched: the serialization works on a clone.
    expect(doc.getElementById(EXCHANGE_BRIDGE_SCRIPT_ID)).not.toBeNull()
  })

  it('unwraps the marks of the highlighted selection', () => {
    const doc = documentOf(
      '<body><p>Say <mark data-agentos-selection="" style="background:#ffe14d">hello <b>big</b></mark> world</p></body>'
    )
    const result = serialize(doc)
    expect(result).not.toContain('<mark')
    expect(result).toContain('<p>Say hello <b>big</b> world</p>')
    expect(doc.querySelector('mark')).not.toBeNull()
  })

  it('keeps a mark of the page that is not the selection', () => {
    expect(serialize(documentOf('<body><mark>keep</mark></body>'))).toContain('<mark>keep</mark>')
  })
})
