import { ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { ExchangeSelectionAction } from '../../services/exchange-state.service'
import { buildSelectionMessage, fenceFor, proposalInstructions } from './exchange-selection-message'

describe('exchange-selection-message', () => {
  const action = (overrides: Partial<ExchangeSelectionAction> = {}): ExchangeSelectionAction => ({
    ref: { scope: ExchangeFileEntryScopeEnum.CASE, path: 'notes/todo.md' },
    selection: 'Buy milk',
    instruction: 'Translate this to French',
    unsavedChanges: false,
    ...overrides,
  })
  const build = (overrides: Partial<ExchangeSelectionAction> = {}) => buildSelectionMessage(action(overrides), 'req1')

  it('puts the instruction first, then the fenced selection, the request rules, then the file reference', () => {
    expect(build({ startLine: 3, endLine: 5 })).toBe(
      [
        'Translate this to French',
        '[Selection from case:notes/todo.md, lines 3-5]\n```markdown\nBuy milk\n```',
        proposalInstructions('req1'),
        '[Files referenced: case:notes/todo.md]',
      ].join('\n\n')
    )
  })

  it('carries the request marker and forbids modifying the file', () => {
    const message = build()
    expect(message).toContain('[selection-request:req1]')
    expect(message).toContain('Do NOT modify the file')
    expect(message).toContain('exactly one fenced block whose info string is `proposal`')
    expect(message).toContain('If no change is appropriate, answer without that block.')
  })

  it('omits the lines when they are unknown, and says line for a single one', () => {
    expect(build()).toContain('[Selection from case:notes/todo.md]\n')
    expect(build({ startLine: 4, endLine: 4 })).toContain('case:notes/todo.md, line 4]')
  })

  it('qualifies the path with the lowercase scope and leaves the fence untagged for plain text', () => {
    const message = build({ ref: { scope: ExchangeFileEntryScopeEnum.NAMESPACE, path: 'docs/a.txt' } })
    expect(message).toContain('[Selection from namespace:docs/a.txt]\n```\nBuy milk\n```')
    expect(message).toContain('[Files referenced: namespace:docs/a.txt]')
  })

  it('notes that the selection comes from unsaved edits', () => {
    expect(build({ unsavedChanges: true })).toContain('unsaved edits')
    expect(build()).not.toContain('unsaved')
  })

  it('uses a fence longer than any backtick run of the selection', () => {
    expect(fenceFor('no backtick')).toBe('```')
    expect(fenceFor('a ``` b')).toBe('````')
    expect(fenceFor('`` and ````` here')).toBe('``````')
    const message = build({ selection: '```js\ncode\n```' })
    expect(message).toContain('````markdown\n```js\ncode\n```\n````')
  })

  it('sends the markdown source of a selection made in the rendered document, and says the proposal is markdown', () => {
    const message = build({ selection: 'Title paragraph', selectionMarkdown: '# Title\n\nparagraph' })
    expect(message).toContain('```markdown\n# Title\n\nparagraph\n```')
    expect(message).not.toContain('Title paragraph')
    expect(message).toContain('the proposal must be markdown too')
    expect(message).toContain('headings, lists')
    expect(message).toBe(
      [
        'Translate this to French',
        '[Selection from case:notes/todo.md]\n```markdown\n# Title\n\nparagraph\n```',
        proposalInstructions('req1', true),
        '[Files referenced: case:notes/todo.md]',
      ].join('\n\n')
    )
  })

  it('does not claim markdown for a selection without markdown source', () => {
    expect(build()).not.toContain('must be markdown')
  })

  describe('selection made in an HTML page', () => {
    const htmlAction = (): Partial<ExchangeSelectionAction> => ({
      ref: { scope: ExchangeFileEntryScopeEnum.CASE, path: 'report.html' },
      selection: 'Hello big world',
      selectionHtml: '<p style="color:red">Hello <b class="x">big</b> world</p>',
      selectionContext: [{ tag: 'div', class: 'card', style: 'padding:4px' }, { tag: 'body' }],
    })

    it('sends the markup in an html fence, with its context, and asks to keep it', () => {
      const message = build(htmlAction())
      expect(message).toContain('```html\n<p style="color:red">Hello <b class="x">big</b> world</p>\n```')
      expect(message).not.toContain('\nHello big world\n')
      expect(message).toContain('<div class="card" style="padding:4px"> inside <body>')
      expect(message).toContain('an HTML fragment')
      expect(message).toContain('keep the existing tags, classes, inline styles and attributes')
      expect(message).toContain('Do not add `<script>`, event handler attributes')
    })

    it('is exactly the message with the html instructions', () => {
      expect(build(htmlAction())).toBe(
        [
          'Translate this to French',
          '[Selection from case:report.html]\n```html\n<p style="color:red">Hello <b class="x">big</b> world</p>\n```',
          '(The selection sits in: <div class="card" style="padding:4px"> inside <body>.)',
          proposalInstructions('req1', false, true),
          '[Files referenced: case:report.html]',
        ].join('\n\n')
      )
    })

    it('falls back to the flattened text when the page gave no markup', () => {
      const message = build({ ...htmlAction(), selectionHtml: undefined, selectionContext: undefined })
      expect(message).toContain('```html\nHello big world\n```')
      expect(message).not.toContain('keep the existing tags')
    })
  })

  it('trims the instruction', () => {
    expect(build({ instruction: '  fix  \n' }).startsWith('fix\n\n[Selection')).toBe(true)
  })
})
