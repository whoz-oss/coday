import { diffLines } from 'diff'
import { configFileDiffRows } from './config-file-diff-rows'

describe('configFileDiffRows', () => {
  const rows = (before: string, after: string) =>
    configFileDiffRows(diffLines(before, after)).map((row) => `${row.kind}:${row.text}`)

  it('shows each change with two unchanged lines around it, and folds the rest', () => {
    const file = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'].join('\n') + '\n'
    const copy = ['a', 'b', 'c', 'd', 'E', 'f', 'g', 'h'].join('\n') + '\n'

    expect(rows(file, copy)).toEqual([
      'skipped:2 unchanged lines',
      'same:c',
      'same:d',
      'removed:e',
      'added:E',
      'same:f',
      'same:g',
      'skipped:1 unchanged line',
    ])
  })

  it('shows a file only in the copy as lines added', () => {
    expect(rows('', '# Review\n')).toEqual(['added:# Review'])
  })
})
