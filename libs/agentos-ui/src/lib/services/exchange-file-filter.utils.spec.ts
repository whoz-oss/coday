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

// Preserve the independently added base-branch examples as well as the PR boundary tests.
describe('filterFileTree base branch examples', () => {
  const paths = ['README.md', 'docs/api/guide.md', 'docs/api/spec.yaml', 'docs/intro.md', 'src/app/main.ts', 'notes.txt']
  const view = (items: string[], query: string) => filterFileTree(items, query).rows.map(
    (row) => `${'  '.repeat(row.depth)}${row.kind === 'folder' ? row.name + '/' : row.name}`
  )

  it('returns nothing for blank queries', () => {
    expect(filterFileTree(paths, '')).toEqual({ rows: [], matchCount: 0, truncated: false })
    expect(filterFileTree(paths, '   ').rows).toEqual([])
  })
  it('keeps matching files and expanded ancestors', () => {
    expect(view(paths, 'guide')).toEqual(['docs/', '  api/', '    guide.md'])
  })
  it('matches filenames case-insensitively', () => {
    expect(view(paths, 'readme')).toEqual(['README.md'])
    expect(view(paths, 'MAIN.TS')).toEqual(['src/', '  app/', '    main.ts'])
  })
  it('matches full paths and folder segments', () => {
    expect(view(paths, 'docs/api')).toEqual(['docs/', '  api/', '    guide.md', '    spec.yaml'])
    expect(view(paths, 'docs')).toEqual(['docs/', '  api/', '    guide.md', '    spec.yaml', '  intro.md'])
  })
  it('lists folders first and sorts each level', () => {
    expect(view(['b.md', 'z/x.md', 'a/y.md', 'a.md'], '.md')).toEqual(['a/', '  y.md', 'z/', '  x.md', 'a.md', 'b.md'])
  })
  it('reports path and depth for each row', () => {
    expect(filterFileTree(paths, 'spec').rows).toEqual([
      { kind: 'folder', path: 'docs', name: 'docs', depth: 0 },
      { kind: 'folder', path: 'docs/api', name: 'api', depth: 1 },
      { kind: 'file', path: 'docs/api/spec.yaml', name: 'spec.yaml', depth: 2 },
    ])
  })
  it('returns no rows for an unmatched query', () => {
    expect(filterFileTree(paths, 'zzz')).toEqual({ rows: [], matchCount: 0, truncated: false })
  })
  it('tolerates duplicates, leading slashes and empty paths', () => {
    expect(view(['/a/b.md', 'a/b.md', ''], 'b')).toEqual(['a/', '  b.md'])
  })
  it('cuts a large tree without a trailing orphan folder', () => {
    const many = Array.from({ length: MAX_FILTER_MATCHES + 20 }, (_, i) => `dir${i % 2}/f${String(i).padStart(4, '0')}.txt`)
    const result = filterFileTree(many, 'f0')
    expect(result.matchCount).toBe(MAX_FILTER_MATCHES + 20)
    expect(result.truncated).toBe(true)
    expect(result.rows.filter((row) => row.kind === 'file')).toHaveLength(MAX_FILTER_MATCHES)
    expect(result.rows.at(-1)?.kind).toBe('file')
  })
  it('does not mark exactly the limit as truncated', () => {
    const result = filterFileTree(['f0.txt', 'f1.txt', 'f2.txt'], 'f', 3)
    expect(result.truncated).toBe(false)
    expect(result.rows).toHaveLength(3)
  })
})
