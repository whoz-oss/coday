import {
  buildIdeUrl,
  escapeHtml,
  parseFileReference,
  projectRootOf,
  renderFileCodespan,
  resolveFilePath,
} from './file-link'

describe('parseFileReference', () => {
  it.each([
    ['src/a.ts', { path: 'src/a.ts' }],
    ['a/b.ts:42', { path: 'a/b.ts', line: 42 }],
    ['libs/x/y.component.ts:42', { path: 'libs/x/y.component.ts', line: 42 }],
    ['Case.kt:21', { path: 'Case.kt', line: 21 }],
    ['/abs/x.kt:10:5', { path: '/abs/x.kt', line: 10, column: 5 }],
    ['./x/y.md', { path: './x/y.md' }],
    ['../lib/z.ts:3', { path: '../lib/z.ts', line: 3 }],
    ['@scope/pkg/index.ts', { path: '@scope/pkg/index.ts' }],
  ])('reads %s as a file reference', (text, expected) => {
    expect(parseFileReference(text)).toEqual(expected)
  })

  it.each([
    ['user.name'],
    ['foo'],
    ['foo.bar'],
    ['https://x.com/a.ts'],
    ['a b.ts'],
    ['npm run build'],
    ['application/json'],
    ['v1.2'],
    ['a/../b.ts'],
    ['../../a.ts'],
    ['a/./b.ts'],
    ['a//b.ts'],
    ['src/'],
    ['<script>alert(1)</script>'],
    ['a.ts<img src=x>'],
  ])('does not read %s as a file reference', (text) => {
    expect(parseFileReference(text)).toBeNull()
  })
})

describe('resolveFilePath', () => {
  it('returns an absolute path as it is', () => {
    expect(resolveFilePath('/root/proj', '/abs/x.kt')).toBe('/abs/x.kt')
  })

  it('joins a relative path to the project root', () => {
    expect(resolveFilePath('/root/proj', 'src/a.ts')).toBe('/root/proj/src/a.ts')
  })

  it('strips a leading ./ before joining', () => {
    expect(resolveFilePath('/root/proj', './x/y.md')).toBe('/root/proj/x/y.md')
  })

  it('joins with exactly one slash when the root ends with one', () => {
    expect(resolveFilePath('/root/proj/', 'src/a.ts')).toBe('/root/proj/src/a.ts')
  })
})

describe('projectRootOf', () => {
  it('is the parent directory of the namespace configPath', () => {
    expect(projectRootOf('/Users/ali/Workspace/app/version/coday')).toBe('/Users/ali/Workspace/app/version')
  })

  it('ignores trailing slashes of the configPath', () => {
    expect(projectRootOf('/Users/ali/app/coday//')).toBe('/Users/ali/app')
  })

  it('is the filesystem root for a configPath right under it', () => {
    expect(projectRootOf('/coday')).toBe('/')
  })

  it.each([undefined, '', '   ', '/', 'relative/coday'])('is null for the configPath %p', (configPath) => {
    expect(projectRootOf(configPath)).toBeNull()
  })
})

describe('buildIdeUrl', () => {
  const template = 'idea://open?file={file}&line={line}'

  it('URL-encodes the absolute path, including spaces and ampersands', () => {
    expect(buildIdeUrl(template, '/Users/ali/my project/src/a&b.ts', 42)).toBe(
      'idea://open?file=%2FUsers%2Fali%2Fmy%20project%2Fsrc%2Fa%26b.ts&line=42'
    )
  })

  it('defaults line and column to 1', () => {
    expect(buildIdeUrl('idea://open?file={file}&line={line}&column={column}', '/x.kt')).toBe(
      'idea://open?file=%2Fx.kt&line=1&column=1'
    )
  })

  it('fills the column when given', () => {
    expect(buildIdeUrl('idea://open?file={file}&line={line}&column={column}', '/x.kt', 10, 5)).toBe(
      'idea://open?file=%2Fx.kt&line=10&column=5'
    )
  })

  it('encodes a replacement pattern in the path instead of expanding it', () => {
    expect(buildIdeUrl(template, '/a$&b.kt', 1)).toBe('idea://open?file=%2Fa%24%26b.kt&line=1')
  })
})

describe('escapeHtml', () => {
  it('escapes markup characters so a span never becomes an element', () => {
    expect(escapeHtml(`<script>alert("x") & 'y'</script>`)).toBe(
      '&lt;script&gt;alert(&quot;x&quot;) &amp; &#39;y&#39;&lt;/script&gt;'
    )
  })
})

describe('renderFileCodespan', () => {
  it('renders a link around the code, with the path and no position for a bare reference', () => {
    expect(renderFileCodespan('src/a.ts', { path: 'src/a.ts' })).toBe(
      '<a class="agentos-file-link" href="#" data-file="src/a.ts" title="Open in IntelliJ IDEA"><code>src/a.ts</code></a>'
    )
  })

  it('adds the line and column data attributes when given', () => {
    expect(renderFileCodespan('/abs/x.kt:10:5', { path: '/abs/x.kt', line: 10, column: 5 })).toBe(
      '<a class="agentos-file-link" href="#" data-file="/abs/x.kt" data-line="10" data-column="5" ' +
        'title="Open in IntelliJ IDEA"><code>/abs/x.kt:10:5</code></a>'
    )
  })

  it('uses the escaped text as given, without escaping it again', () => {
    expect(renderFileCodespan('a&amp;b.ts:1', { path: 'a&b.ts', line: 1 })).toContain('<code>a&amp;b.ts:1</code>')
  })
})
