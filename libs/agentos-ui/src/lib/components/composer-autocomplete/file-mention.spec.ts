import {
  ExchangeFileEntryScopeEnum,
  ExchangeManifest,
  ExchangeManifestCapabilityEnum,
} from '@whoz-oss/agentos-api-client'
import {
  appendFileReferences,
  applyFileMention,
  directoryOf,
  FileSuggestion,
  fileMentionPrefix,
  fileReference,
  manifestToSuggestions,
  MAX_FILE_SUGGESTIONS,
  rankFiles,
  scopeOfRef,
} from './file-mention'

const file = (path: string, scope: FileSuggestion['scope'] = 'namespace'): FileSuggestion => ({
  scope,
  path,
  filename: path.split('/').pop()!,
})

describe('fileMentionPrefix', () => {
  it('returns what follows a # at the start or after a whitespace', () => {
    expect(fileMentionPrefix('#')).toBe('')
    expect(fileMentionPrefix('#rea')).toBe('rea')
    expect(fileMentionPrefix('see #docs/rea')).toBe('docs/rea')
    expect(fileMentionPrefix('line one\n#rea')).toBe('rea')
  })

  it('is null when the word being typed is not a mention', () => {
    expect(fileMentionPrefix('hello')).toBeNull()
    expect(fileMentionPrefix('C#')).toBeNull()
    expect(fileMentionPrefix('a#b')).toBeNull()
    expect(fileMentionPrefix('see #readme now')).toBeNull()
    expect(fileMentionPrefix('##')).toBeNull()
  })

  it('leaves @ mentions alone', () => {
    expect(fileMentionPrefix('@alice')).toBeNull()
  })
})

describe('fileReference / applyFileMention', () => {
  it('qualifies the path with its scope and ends with a space', () => {
    expect(fileReference(file('docs/readme.md'))).toBe('namespace:docs/readme.md ')
    expect(fileReference(file('notes.md', 'case'))).toBe('case:notes.md ')
  })

  it('replaces only the trailing #prefix', () => {
    expect(applyFileMention('see #rea', 'namespace:docs/readme.md ')).toBe('see namespace:docs/readme.md ')
    expect(applyFileMention('#rea', 'case:a.md ')).toBe('case:a.md ')
    expect(applyFileMention('#one and #two', 'case:a.md ')).toBe('#one and case:a.md ')
  })

  it('does not read a $ of the path as a replacement pattern', () => {
    expect(applyFileMention('#a', 'case:$&-$1.md ')).toBe('case:$&-$1.md ')
  })
})

describe('scopeOfRef / appendFileReferences', () => {
  const ns = (path: string) => ({ scope: ExchangeFileEntryScopeEnum.NAMESPACE, path })
  const cs = (path: string) => ({ scope: ExchangeFileEntryScopeEnum.CASE, path })

  it('maps the exchange scope to the mention scope', () => {
    expect(scopeOfRef(ExchangeFileEntryScopeEnum.CASE)).toBe('case')
    expect(scopeOfRef(ExchangeFileEntryScopeEnum.NAMESPACE)).toBe('namespace')
  })

  it('writes the # mention format, one reference per file', () => {
    expect(appendFileReferences('', [ns('docs/readme.md'), cs('notes/todo.md')])).toBe(
      'namespace:docs/readme.md case:notes/todo.md '
    )
  })

  it('separates from the draft with one space, unless it already ends with whitespace', () => {
    expect(appendFileReferences('see', [ns('a.md')])).toBe('see namespace:a.md ')
    expect(appendFileReferences('see ', [ns('a.md')])).toBe('see namespace:a.md ')
    expect(appendFileReferences('see\n', [ns('a.md')])).toBe('see\nnamespace:a.md ')
  })

  it('skips a reference already in the draft, as a whole token only', () => {
    expect(appendFileReferences('read namespace:a.md now', [ns('a.md'), ns('b.md')])).toBe(
      'read namespace:a.md now namespace:b.md '
    )
    expect(appendFileReferences('namespace:a.md.bak', [ns('a.md')])).toBe('namespace:a.md.bak namespace:a.md ')
    expect(appendFileReferences('xnamespace:a.md', [ns('a.md')])).toBe('xnamespace:a.md namespace:a.md ')
  })

  it('does not confuse the two scopes', () => {
    expect(appendFileReferences('case:a.md ', [ns('a.md')])).toBe('case:a.md namespace:a.md ')
  })

  it('skips a file repeated inside the batch', () => {
    expect(appendFileReferences('', [ns('a.md'), ns('a.md')])).toBe('namespace:a.md ')
  })

  it('returns the value untouched when nothing is new', () => {
    expect(appendFileReferences('see namespace:a.md', [ns('a.md')])).toBe('see namespace:a.md')
    expect(appendFileReferences('draft', [])).toBe('draft')
  })
})

describe('directoryOf', () => {
  it('returns the directory of a path, empty at the root', () => {
    expect(directoryOf('docs/guides/readme.md')).toBe('docs/guides')
    expect(directoryOf('readme.md')).toBe('')
  })
})

describe('manifestToSuggestions', () => {
  const manifest = (capability: ExchangeManifestCapabilityEnum): ExchangeManifest => ({
    capability,
    files: [
      {
        path: 'docs/readme.md',
        filename: 'readme.md',
        scope: ExchangeFileEntryScopeEnum.NAMESPACE,
        size: 1,
        lastModified: '2026-01-01T00:00:00Z',
      },
    ],
  })

  it('maps the entries with the scope they were read from', () => {
    expect(manifestToSuggestions(manifest(ExchangeManifestCapabilityEnum.READ), 'case')).toEqual([
      { scope: 'case', path: 'docs/readme.md', filename: 'readme.md' },
    ])
  })

  it('contributes nothing without capability on the scope (fail-closed)', () => {
    expect(manifestToSuggestions(manifest(ExchangeManifestCapabilityEnum.NONE), 'namespace')).toEqual([])
  })
})

describe('rankFiles', () => {
  const files = [
    file('docs/readme.md'),
    file('src/read-model.ts'),
    file('notes/spread.txt'),
    file('readme/index.md'),
    file('src/main.ts'),
  ]

  it('ranks filename prefix, then filename contains, then path contains', () => {
    const names = rankFiles(files, 'read').map((f) => f.path)
    expect(names).toEqual(['docs/readme.md', 'src/read-model.ts', 'notes/spread.txt', 'readme/index.md'])
  })

  it('is case-insensitive', () => {
    expect(rankFiles(files, 'MAIN').map((f) => f.path)).toEqual(['src/main.ts'])
  })

  it('matches the path when the query holds a slash', () => {
    expect(rankFiles(files, 'docs/rea').map((f) => f.path)).toEqual(['docs/readme.md'])
  })

  it('orders equal ranks by path, so the list is stable', () => {
    const list = [file('b/a.md'), file('a/a.md', 'case')]
    expect(rankFiles(list, 'a.md').map((f) => `${f.scope}:${f.path}`)).toEqual(['case:a/a.md', 'namespace:b/a.md'])
  })

  it('returns nothing when no file matches', () => {
    expect(rankFiles(files, 'zzz')).toEqual([])
  })

  it('lists everything for an empty query, up to the cap', () => {
    const many = Array.from({ length: 120 }, (_, i) => file(`dir/file-${String(i).padStart(3, '0')}.txt`))
    const result = rankFiles(many, '')
    expect(result).toHaveLength(MAX_FILE_SUGGESTIONS)
    expect(result[0]!.path).toBe('dir/file-000.txt')
  })

  it('honours a custom limit', () => {
    expect(rankFiles(files, '', 2)).toHaveLength(2)
  })
})
