import { filterFileTree, MAX_FILTER_MATCHES } from './exchange-file-filter.utils'

describe('filterFileTree', () => {
  it('matches the full path case-insensitively and retains ancestors in order', () => {
    const result = filterFileTree(['z/readme.md', 'docs/api/Guide.md', 'docs/api/spec.txt'], 'DOCS/api')
    expect(result.rows.map((row) => [row.kind, row.path, row.depth])).toEqual([
      ['folder', 'docs', 0], ['folder', 'docs/api', 1], ['file', 'docs/api/Guide.md', 2],
      ['file', 'docs/api/spec.txt', 2],
    ])
  })
  it('deduplicates paths and limits results without empty folders', () => {
    const paths = Array.from({ length: 520 }, (_, i) => `dir/file-${i}.txt`)
    const result = filterFileTree([...paths, '/dir/file-1.txt'], 'file')
    expect(result.matchCount).toBe(520)
    expect(result.truncated).toBe(true)
    expect(result.rows.filter((row) => row.kind === 'file')).toHaveLength(MAX_FILTER_MATCHES)
  })
  it('sorts folders before files at every level and reports exact limits', () => {
    const result = filterFileTree(['z.txt', 'docs/z.txt', 'docs/sub/a.txt', 'a.txt'], '.txt', 4)
    expect(result.rows.map((row) => row.path)).toEqual(['docs', 'docs/sub', 'docs/sub/a.txt', 'docs/z.txt', 'a.txt', 'z.txt'])
    expect(result.truncated).toBe(false)
    const limited = filterFileTree(['x/a.txt', 'y/b.txt'], '.txt', 1)
    expect(limited.rows.map((row) => row.path)).toEqual(['x', 'x/a.txt'])
    expect(limited.truncated).toBe(true)
  })
  it.each([499, 500, 501])('caps %i matches in stable order without orphan folders', (count) => {
    const paths = Array.from({ length: count }, (_, i) => `folder-${String(i).padStart(3, '0')}/needle.txt`)
    const result = filterFileTree([...paths].reverse(), 'NEEDLE')
    const files = result.rows.filter((row) => row.kind === 'file')
    expect(files.map((row) => row.path)).toEqual(paths.slice(0, Math.min(count, MAX_FILTER_MATCHES)))
    expect(result.matchCount).toBe(count)
    expect(result.truncated).toBe(count > MAX_FILTER_MATCHES)
    expect(result.rows.filter((row) => row.kind === 'folder')).toHaveLength(files.length)
  })

  it('returns an empty tree for a blank or unmatched query', () => {
    expect(filterFileTree(['a.txt'], ' ')).toEqual({ rows: [], matchCount: 0, truncated: false })
    expect(filterFileTree(['a.txt'], 'missing').rows).toEqual([])
  })
})
