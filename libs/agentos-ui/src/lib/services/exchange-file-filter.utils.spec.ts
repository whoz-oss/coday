import { filterFileTree, MAX_FILTER_MATCHES } from './exchange-file-filter.utils'

const view = (paths: string[], query: string) =>
  filterFileTree(paths, query).rows.map((r) => `${'  '.repeat(r.depth)}${r.kind === 'folder' ? r.name + '/' : r.name}`)

describe('filterFileTree', () => {
  const paths = [
    'README.md',
    'docs/api/guide.md',
    'docs/api/spec.yaml',
    'docs/intro.md',
    'src/app/main.ts',
    'notes.txt',
  ]

  it('returns nothing for a blank query', () => {
    expect(filterFileTree(paths, '')).toEqual({ rows: [], matchCount: 0, truncated: false })
    expect(filterFileTree(paths, '   ').rows).toEqual([])
  })

  it('keeps the matching files and every ancestor folder, all expanded', () => {
    expect(view(paths, 'guide')).toEqual(['docs/', '  api/', '    guide.md'])
  })

  it('matches case-insensitively on the file name', () => {
    expect(view(paths, 'readme')).toEqual(['README.md'])
    expect(view(paths, 'MAIN.TS')).toEqual(['src/', '  app/', '    main.ts'])
  })

  it('matches on the full relative path, so folder segments count', () => {
    expect(view(paths, 'docs/api')).toEqual(['docs/', '  api/', '    guide.md', '    spec.yaml'])
    expect(view(paths, 'docs')).toEqual(['docs/', '  api/', '    guide.md', '    spec.yaml', '  intro.md'])
  })

  it('lists folders first, then files, alphabetically at each level', () => {
    expect(view(['b.md', 'z/x.md', 'a/y.md', 'a.md'], '.md')).toEqual(['a/', '  y.md', 'z/', '  x.md', 'a.md', 'b.md'])
  })

  it('reports the path and depth of each row', () => {
    const { rows } = filterFileTree(paths, 'spec')
    expect(rows).toEqual([
      { kind: 'folder', path: 'docs', name: 'docs', depth: 0 },
      { kind: 'folder', path: 'docs/api', name: 'api', depth: 1 },
      { kind: 'file', path: 'docs/api/spec.yaml', name: 'spec.yaml', depth: 2 },
    ])
  })

  it('returns no rows when nothing matches', () => {
    expect(filterFileTree(paths, 'zzz')).toEqual({ rows: [], matchCount: 0, truncated: false })
  })

  it('tolerates duplicate, leading-slash and empty paths', () => {
    expect(view(['/a/b.md', 'a/b.md', ''], 'b')).toEqual(['a/', '  b.md'])
  })

  it('cuts the tree after the limit without leaving a folder that holds no file', () => {
    const many = Array.from(
      { length: MAX_FILTER_MATCHES + 20 },
      (_, i) => `dir${String(i % 2)}/f${String(i).padStart(4, '0')}.txt`
    )
    const result = filterFileTree(many, 'f0')
    expect(result.matchCount).toBe(MAX_FILTER_MATCHES + 20)
    expect(result.truncated).toBe(true)
    expect(result.rows.filter((r) => r.kind === 'file')).toHaveLength(MAX_FILTER_MATCHES)
    const last = result.rows[result.rows.length - 1]
    expect(last?.kind).toBe('file')
  })

  it('does not flag a result exactly at the limit as truncated', () => {
    const exact = Array.from({ length: 3 }, (_, i) => `f${i}.txt`)
    const result = filterFileTree(exact, 'f', 3)
    expect(result.truncated).toBe(false)
    expect(result.rows).toHaveLength(3)
  })
})
