import { ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import {
  buildAttachmentMention,
  kindLabel,
  middleTruncate,
  PendingAttachment,
  resolveUploadScope,
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
