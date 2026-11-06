import {
  ExchangeFileEntryScopeEnum,
  ExchangeManifest,
  ExchangeManifestCapabilityEnum,
} from '@whoz-oss/agentos-api-client'
import {
  applyFileMention,
  directoryOf,
  FileSuggestion,
  fileMentionPrefix,
  fileReference,
  manifestToSuggestions,
  MAX_FILE_SUGGESTIONS,
  rankFiles,
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
