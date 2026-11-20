import { ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { buildReferenceMention } from '../composer-autocomplete/file-mention'
import {
  attachedMentionLine,
  buildAttachmentMention,
  joinAttachmentMentions,
  joinMentionLines,
  kindLabel,
  middleTruncate,
  pastedImageName,
  PendingAttachment,
  referencedMentionEntry,
  referencedMentionLine,
  resolveUploadScope,
  splitAttachmentMentions,
} from './composer-attachments.utils'

function uploaded(name: string, scope: ExchangeFileEntryScopeEnum): PendingAttachment {
  return { id: name, file: new File(['x'], name), status: 'uploaded', uploadedScope: scope }
}

describe('resolveUploadScope', () => {
  it('targets the case exchange when the message has no namespace intent', () => {
    expect(resolveUploadScope('please summarize this file', true)).toBe(ExchangeFileEntryScopeEnum.CASE)
  })

  it('targets the namespace when explicitly asked for and the user can write it', () => {
    expect(resolveUploadScope('put it in the namespace please', true)).toBe(ExchangeFileEntryScopeEnum.NAMESPACE)
  })

  it('falls back to the case when the user cannot write the namespace', () => {
    expect(resolveUploadScope('put it in the namespace please', false)).toBe(ExchangeFileEntryScopeEnum.CASE)
  })

  it('matches the standalone word only ("namespaces" does not trigger)', () => {
    expect(resolveUploadScope('list the namespaces', true)).toBe(ExchangeFileEntryScopeEnum.CASE)
  })

  it('is case-insensitive', () => {
    expect(resolveUploadScope('add to the NAMESPACE documents', true)).toBe(ExchangeFileEntryScopeEnum.NAMESPACE)
  })
})

describe('buildAttachmentMention', () => {
  it('lists case-exchange files on a single line', () => {
    const mention = buildAttachmentMention([
      uploaded('a.pdf', ExchangeFileEntryScopeEnum.CASE),
      uploaded('b.xlsx', ExchangeFileEntryScopeEnum.CASE),
    ])
    expect(mention).toBe('[Files attached to the case exchange: a.pdf, b.xlsx]')
  })

  it('lists namespace-exchange files on their own line', () => {
    const mention = buildAttachmentMention([uploaded('c.md', ExchangeFileEntryScopeEnum.NAMESPACE)])
    expect(mention).toBe('[Files attached to the namespace exchange: c.md]')
  })

  it('produces one line per scope for a mixed batch', () => {
    const mention = buildAttachmentMention([
      uploaded('a.pdf', ExchangeFileEntryScopeEnum.CASE),
      uploaded('c.md', ExchangeFileEntryScopeEnum.NAMESPACE),
    ])
    expect(mention).toBe(
      '[Files attached to the case exchange: a.pdf]\n[Files attached to the namespace exchange: c.md]'
    )
  })

  it('ignores attachments without an uploaded scope', () => {
    const pending: PendingAttachment = { id: 'p', file: new File(['x'], 'p.txt'), status: 'pending' }
    expect(buildAttachmentMention([pending])).toBe('')
  })
})

describe('pastedImageName', () => {
  // Local-time constructor on purpose: the helper formats in local time.
  const date = new Date(2026, 0, 5, 9, 7, 3)

  it('builds a timestamped name with the extension of the mime type', () => {
    expect(pastedImageName(date, 0, 'image/png')).toBe('pasted-image-20260105-090703.png')
    expect(pastedImageName(date, 0, 'image/jpeg')).toBe('pasted-image-20260105-090703.jpg')
    expect(pastedImageName(date, 0, 'image/gif')).toBe('pasted-image-20260105-090703.gif')
    expect(pastedImageName(date, 0, 'image/webp')).toBe('pasted-image-20260105-090703.webp')
  })

  it('defaults to png for an unknown mime type', () => {
    expect(pastedImageName(date, 0, 'image/x-weird')).toBe('pasted-image-20260105-090703.png')
  })

  it('suffixes the following images of the same paste', () => {
    expect(pastedImageName(date, 1, 'image/png')).toBe('pasted-image-20260105-090703-2.png')
    expect(pastedImageName(date, 2, 'image/png')).toBe('pasted-image-20260105-090703-3.png')
  })
})

describe('middleTruncate', () => {
  it('keeps short names untouched', () => {
    expect(middleTruncate('report.pdf')).toBe('report.pdf')
  })

  it('cuts long names in the middle, preserving the extension end', () => {
    const name = 'a-very-long-file-name-that-never-ends-2026.pdf'
    const truncated = middleTruncate(name)
    expect(truncated.length).toBeLessThanOrEqual(32)
    expect(truncated).toContain('…')
    expect(truncated.endsWith('2026.pdf')).toBe(true)
  })
})

describe('kindLabel', () => {
  it('returns the uppercased extension', () => {
    expect(kindLabel('report.pdf')).toBe('PDF')
    expect(kindLabel('data.xlsx')).toBe('XLSX')
  })

  it('falls back to FILE without an extension', () => {
    expect(kindLabel('README')).toBe('FILE')
    expect(kindLabel('.gitignore')).toBe('FILE')
    expect(kindLabel('archive.')).toBe('FILE')
  })
})

describe('the lines of a message for its files', () => {
  const CASE = ExchangeFileEntryScopeEnum.CASE
  const NAMESPACE = ExchangeFileEntryScopeEnum.NAMESPACE
  const ns = (path: string) => ({ scope: NAMESPACE, path })
  const cs = (path: string) => ({ scope: CASE, path })

  describe('format builders', () => {
    it('write the exact lines the agent reads', () => {
      expect(attachedMentionLine(CASE, ['a.pdf', 'b.png'])).toBe('[Files attached to the case exchange: a.pdf, b.png]')
      expect(attachedMentionLine(NAMESPACE, ['c.md'])).toBe('[Files attached to the namespace exchange: c.md]')
      expect(
        referencedMentionLine([
          referencedMentionEntry(NAMESPACE, 'docs/readme.md'),
          referencedMentionEntry(CASE, 'notes/todo.md'),
        ])
      ).toBe('[Files referenced: namespace:docs/readme.md, case:notes/todo.md]')
    })

    it('join the lines of a block with a newline', () => {
      expect(joinMentionLines(['a', 'b'])).toBe('a\nb')
      expect(joinMentionLines([])).toBe('')
    })
  })

  describe('joinAttachmentMentions', () => {
    it('separates the text from the block with a blank line', () => {
      expect(joinAttachmentMentions('hello', '[Files referenced: case:a.md]')).toBe(
        'hello\n\n[Files referenced: case:a.md]'
      )
    })

    it('is the block alone without text', () => {
      expect(joinAttachmentMentions('', '[Files referenced: case:a.md]')).toBe('[Files referenced: case:a.md]')
    })

    it('is the text alone without block', () => {
      expect(joinAttachmentMentions('hello', '')).toBe('hello')
      expect(joinAttachmentMentions('', '')).toBe('')
    })
  })

  describe('splitAttachmentMentions', () => {
    it('reads the files attached to the case exchange', () => {
      const split = splitAttachmentMentions('see this\n\n[Files attached to the case exchange: a.pdf, b.png]')

      expect(split.body).toBe('see this')
      expect(split.mentions).toBe('[Files attached to the case exchange: a.pdf, b.png]')
      expect(split.files).toEqual([
        { name: 'a.pdf', path: 'a.pdf', scope: CASE, kind: 'attached' },
        { name: 'b.png', path: 'b.png', scope: CASE, kind: 'attached' },
      ])
    })

    it('reads the files attached to the namespace exchange', () => {
      const split = splitAttachmentMentions('store it\n\n[Files attached to the namespace exchange: c.md]')

      expect(split.body).toBe('store it')
      expect(split.files).toEqual([{ name: 'c.md', path: 'c.md', scope: NAMESPACE, kind: 'attached' }])
    })

    it('reads the referenced files, with the name of the leaf and the full path', () => {
      const split = splitAttachmentMentions(
        'compare\n\n[Files referenced: namespace:docs/readme.md, case:notes/todo.md]'
      )

      expect(split.body).toBe('compare')
      expect(split.files).toEqual([
        { name: 'readme.md', path: 'docs/readme.md', scope: NAMESPACE, kind: 'referenced' },
        { name: 'todo.md', path: 'notes/todo.md', scope: CASE, kind: 'referenced' },
      ])
    })

    it('reads a block of several lines, in order', () => {
      const split = splitAttachmentMentions(
        'compare\n\n[Files attached to the case exchange: a.pdf]\n[Files attached to the namespace exchange: c.md]\n[Files referenced: namespace:docs/readme.md]'
      )

      expect(split.body).toBe('compare')
      expect(split.files.map((file) => `${file.kind}:${file.scope}:${file.path}`)).toEqual([
        `attached:${CASE}:a.pdf`,
        `attached:${NAMESPACE}:c.md`,
        `referenced:${NAMESPACE}:docs/readme.md`,
      ])
    })

    it('reads a message of files only: no text, the whole content is the block', () => {
      const split = splitAttachmentMentions('[Files attached to the case exchange: alone.pdf]')

      expect(split.body).toBe('')
      expect(split.files).toEqual([{ name: 'alone.pdf', path: 'alone.pdf', scope: CASE, kind: 'attached' }])
      expect(split.mentions).toBe('[Files attached to the case exchange: alone.pdf]')
    })

    it('keeps a multi-paragraph text whole, and takes only the final block', () => {
      const split = splitAttachmentMentions('first\n\nsecond\n\n[Files referenced: case:a.md]')

      expect(split.body).toBe('first\n\nsecond')
      expect(split.files).toHaveLength(1)
    })

    it('returns a text without such lines as it is', () => {
      expect(splitAttachmentMentions('just a text')).toEqual({ body: 'just a text', files: [], mentions: '' })
      expect(splitAttachmentMentions('')).toEqual({ body: '', files: [], mentions: '' })
      expect(splitAttachmentMentions('a\n\nb')).toEqual({ body: 'a\n\nb', files: [], mentions: '' })
    })

    it('does not touch a line that looks like one but is in the middle of the text', () => {
      const text = 'before\n\n[Files attached to the case exchange: a.pdf]\n\nafter'

      expect(splitAttachmentMentions(text)).toEqual({ body: text, files: [], mentions: '' })
    })

    it('does not touch a final block with one line that is not exactly the format', () => {
      for (const text of [
        'x\n\n[Files attached to the case exchange: a.pdf] and more',
        'x\n\n[Files attached to the case exchange: a.pdf]\nsomething else',
        'x\n\n[Files attached to the other exchange: a.pdf]',
        'x\n\n[Files attached to the case exchange: ]',
        'x\n\n[Files attached to the case exchange: a.pdf, ]',
        'x\n\n[Files referenced: elsewhere:a.md]',
        'x\n\n[Files referenced: case:]',
        'x\n\n[Files referenced: a.md]',
        'x\n\n[Files referenced: case:a.md',
        'x\n\n [Files referenced: case:a.md]',
      ]) {
        expect(splitAttachmentMentions(text)).toEqual({ body: text, files: [], mentions: '' })
      }
    })

    it('does not read a line separated from the text by a single newline', () => {
      const text = 'hello\n[Files referenced: case:a.md]'

      expect(splitAttachmentMentions(text)).toEqual({ body: text, files: [], mentions: '' })
    })

    it('does not read a text that ends with the blank line of an empty block', () => {
      const text = 'hello\n\n'

      expect(splitAttachmentMentions(text)).toEqual({ body: text, files: [], mentions: '' })
    })
  })

  describe('what the composers send is read back and put back as it was', () => {
    const upload = (name: string, scope: ExchangeFileEntryScopeEnum) => uploaded(name, scope)
    const attached = (...files: PendingAttachment[]) => buildAttachmentMention(files)
    const referenced = (...refs: Array<{ scope: ExchangeFileEntryScopeEnum; path: string }>) =>
      buildReferenceMention(refs)
    // As both composers assemble a content.
    const compose = (text: string, ...blocks: string[]) =>
      joinAttachmentMentions(text, joinMentionLines(blocks.filter((block) => block.length > 0)))

    const contents: Array<[string, string]> = [
      ['no file', compose('just a text')],
      ['no file and no text', compose('')],
      ['a text and attached files', compose('see this', attached(upload('a.pdf', CASE), upload('b.png', CASE)))],
      ['a text and referenced files', compose('see this', referenced(ns('docs/readme.md'), cs('notes/todo.md')))],
      [
        'a text, attached and referenced files',
        compose('see this', attached(upload('a.pdf', CASE)), referenced(ns('docs/readme.md'))),
      ],
      [
        'files of the case and of the namespace',
        compose('see this', attached(upload('a.pdf', CASE), upload('c.md', NAMESPACE))),
      ],
      ['attached files and no text', compose('', attached(upload('alone.pdf', CASE)))],
      ['referenced files and no text', compose('', referenced(cs('a.md')))],
      [
        'all of it and no text',
        compose(
          '',
          attached(upload('a.pdf', CASE), upload('c.md', NAMESPACE)),
          referenced(ns('docs/readme.md'), cs('notes/todo.md'))
        ),
      ],
      ['a multi-line text', compose('first\n\nsecond\nthird', attached(upload('a.pdf', CASE)))],
      ['a text with brackets of its own', compose('use [this] and [Files]', referenced(cs('a.md')))],
    ]

    it.each(contents)('join(split(x)) is x: %s', (_label, content) => {
      const { body, mentions } = splitAttachmentMentions(content)

      expect(joinAttachmentMentions(body, mentions)).toBe(content)
    })

    it('finds every file that was sent', () => {
      const content = compose(
        'see this',
        attached(upload('a.pdf', CASE), upload('c.md', NAMESPACE)),
        referenced(ns('docs/readme.md'), cs('notes/todo.md'))
      )

      const { body, files } = splitAttachmentMentions(content)

      expect(body).toBe('see this')
      expect(files.map((file) => `${file.scope}:${file.path}`)).toEqual([
        `${CASE}:a.pdf`,
        `${NAMESPACE}:c.md`,
        `${NAMESPACE}:docs/readme.md`,
        `${CASE}:notes/todo.md`,
      ])
    })

    it('finds no file in a content without any', () => {
      expect(splitAttachmentMentions(compose('just a text')).files).toEqual([])
      expect(splitAttachmentMentions(compose('')).files).toEqual([])
    })

    it('puts back an edited text with the same lines', () => {
      const content = compose('see this', attached(upload('a.pdf', CASE)), referenced(ns('docs/readme.md')))
      const { mentions } = splitAttachmentMentions(content)

      expect(joinAttachmentMentions('see that instead', mentions)).toBe(
        'see that instead\n\n[Files attached to the case exchange: a.pdf]\n[Files referenced: namespace:docs/readme.md]'
      )
      expect(joinAttachmentMentions('', mentions)).toBe(mentions)
    })
  })
})
