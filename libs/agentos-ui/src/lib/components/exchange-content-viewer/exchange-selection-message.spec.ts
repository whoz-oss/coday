import { ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { ExchangeSelectionAction } from '../../services/exchange-state.service'
import { buildSelectionMessage, fenceFor } from './exchange-selection-message'

describe('exchange-selection-message', () => {
  const action = (overrides: Partial<ExchangeSelectionAction> = {}): ExchangeSelectionAction => ({
    ref: { scope: ExchangeFileEntryScopeEnum.CASE, path: 'notes/todo.md' },
    selection: 'Buy milk',
    instruction: 'Translate this to French',
    unsavedChanges: false,
    ...overrides,
  })

  it('puts the instruction first, then the fenced selection, then the file reference', () => {
    expect(buildSelectionMessage(action({ startLine: 3, endLine: 5 }))).toBe(
      [
        'Translate this to French',
        '[Selection from case:notes/todo.md, lines 3-5]\n```markdown\nBuy milk\n```',
        '[Files referenced: case:notes/todo.md]',
      ].join('\n\n')
    )
  })

  it('omits the lines when they are unknown, and says line for a single one', () => {
    expect(buildSelectionMessage(action())).toContain('[Selection from case:notes/todo.md]\n')
    expect(buildSelectionMessage(action({ startLine: 4, endLine: 4 }))).toContain('case:notes/todo.md, line 4]')
  })

  it('qualifies the path with the lowercase scope and leaves the fence untagged for plain text', () => {
    const message = buildSelectionMessage(
      action({ ref: { scope: ExchangeFileEntryScopeEnum.NAMESPACE, path: 'docs/a.txt' } })
    )
    expect(message).toContain('[Selection from namespace:docs/a.txt]\n```\nBuy milk\n```')
    expect(message).toContain('[Files referenced: namespace:docs/a.txt]')
  })

  it('notes that the selection comes from unsaved edits', () => {
    expect(buildSelectionMessage(action({ unsavedChanges: true }))).toContain('unsaved edits')
    expect(buildSelectionMessage(action())).not.toContain('unsaved')
  })

  it('uses a fence longer than any backtick run of the selection', () => {
    expect(fenceFor('no backtick')).toBe('```')
    expect(fenceFor('a ``` b')).toBe('````')
    expect(fenceFor('`` and ````` here')).toBe('``````')
    const message = buildSelectionMessage(action({ selection: '```js\ncode\n```' }))
    expect(message).toContain('````markdown\n```js\ncode\n```\n````')
  })

  it('trims the instruction', () => {
    expect(buildSelectionMessage(action({ instruction: '  fix  \n' })).startsWith('fix\n\n[Selection')).toBe(true)
  })
})
