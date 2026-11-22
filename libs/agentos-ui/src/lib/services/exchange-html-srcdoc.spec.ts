import { EXCHANGE_SELECTION_MESSAGE_TYPE, withSelectionBridge } from './exchange-html-srcdoc'

describe('withSelectionBridge', () => {
  const BRIDGE = /<script>\(function\(\)\{try\{[^]*postMessage[^]*<\/script>/

  it('injects the bridge before the closing body tag and keeps the original markup', () => {
    const html = '<html><body><h1>Report</h1><script>run()</script></body></html>'
    const result = withSelectionBridge(html)
    const at = result.indexOf('</body>')
    expect(result.slice(0, at)).toContain('<h1>Report</h1><script>run()</script>')
    expect(result.slice(0, at)).toMatch(BRIDGE)
    expect(result.slice(at)).toBe('</body></html>')
    expect(result.replace(BRIDGE, '')).toBe(html.replace(BRIDGE, ''))
  })

  it('uses the last closing body tag, whatever its case', () => {
    const html = '<body>a</BODY ><pre>&lt;/body&gt;</pre></BODY>'
    const result = withSelectionBridge(html)
    expect(result.endsWith('</BODY>')).toBe(true)
    expect(result.indexOf('<script>')).toBeGreaterThan(result.indexOf('</BODY >'))
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
})
