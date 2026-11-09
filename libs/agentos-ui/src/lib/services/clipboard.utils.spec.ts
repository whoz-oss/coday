import { Component } from '@angular/core'
import { fakeAsync, TestBed, tick } from '@angular/core/testing'
import { COPY_FEEDBACK_MS, copyText, injectCopyFeedback } from './clipboard.utils'

function mockClipboard(writeText?: jest.Mock): void {
  Object.defineProperty(navigator, 'clipboard', { value: writeText ? { writeText } : undefined, configurable: true })
}

function mockExecCommand(impl: jest.Mock): void {
  Object.defineProperty(document, 'execCommand', { value: impl, configurable: true, writable: true })
}

describe('copyText', () => {
  afterEach(() => {
    mockClipboard(undefined)
    // jsdom has no execCommand: leave the document as it was found.
    delete (document as unknown as Record<string, unknown>)['execCommand']
  })

  it('writes through the async clipboard API when available', async () => {
    const writeText = jest.fn().mockResolvedValue(undefined)
    mockClipboard(writeText)
    expect(await copyText('docs/a.md')).toBe(true)
    expect(writeText).toHaveBeenCalledWith('docs/a.md')
  })

  it('falls back to a hidden textarea and execCommand without the clipboard API (non-secure context)', async () => {
    mockClipboard(undefined)
    let copied: string | undefined
    const exec = jest.fn().mockImplementation(() => {
      copied = document.querySelector('textarea')?.value
      return true
    })
    mockExecCommand(exec)
    expect(await copyText('docs/a.md')).toBe(true)
    expect(exec).toHaveBeenCalledWith('copy')
    expect(copied).toBe('docs/a.md')
    expect(document.querySelector('textarea')).toBeNull()
  })

  it('falls back when the clipboard API refuses the write', async () => {
    mockClipboard(jest.fn().mockRejectedValue(new Error('denied')))
    const exec = jest.fn().mockReturnValue(true)
    mockExecCommand(exec)
    expect(await copyText('x')).toBe(true)
    expect(exec).toHaveBeenCalledWith('copy')
  })

  it('reports failure when neither path works, and cleans up', async () => {
    mockClipboard(jest.fn().mockRejectedValue(new Error('denied')))
    mockExecCommand(jest.fn().mockReturnValue(false))
    expect(await copyText('x')).toBe(false)
    expect(document.querySelector('textarea')).toBeNull()
  })

  it('reports failure when execCommand is not available at all', async () => {
    mockClipboard(undefined)
    expect(await copyText('x')).toBe(false)
  })
})

describe('injectCopyFeedback', () => {
  @Component({ selector: 'agentos-copy-host', template: '' })
  class HostComponent {
    readonly feedback = injectCopyFeedback()
  }

  afterEach(() => mockClipboard(undefined))

  it('goes copied then back to idle after the feedback delay', fakeAsync(() => {
    mockClipboard(jest.fn().mockResolvedValue(undefined))
    const host = TestBed.createComponent(HostComponent).componentInstance
    expect(host.feedback.state()).toBe('idle')
    void host.feedback.copy('a')
    tick(0)
    expect(host.feedback.state()).toBe('copied')
    tick(COPY_FEEDBACK_MS - 1)
    expect(host.feedback.state()).toBe('copied')
    tick(1)
    expect(host.feedback.state()).toBe('idle')
  }))

  it('goes failed when nothing could be copied', fakeAsync(() => {
    mockClipboard(undefined)
    const host = TestBed.createComponent(HostComponent).componentInstance
    void host.feedback.copy('a')
    tick(0)
    expect(host.feedback.state()).toBe('failed')
    tick(COPY_FEEDBACK_MS)
    expect(host.feedback.state()).toBe('idle')
  }))
})
