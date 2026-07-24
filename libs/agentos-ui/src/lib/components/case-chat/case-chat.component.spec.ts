import { HttpClient } from '@angular/common/http'
import { ComponentRef, createComponent, EnvironmentInjector, signal } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { ActivatedRoute } from '@angular/router'
import { Configuration, ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { of, throwError } from 'rxjs'
import { CaseStateService } from '../../services/case-state.service'
import { ExchangeStateService } from '../../services/exchange-state.service'
import { PromptStateService } from '../../services/prompt-state.service'
import { USER_PREFERENCES_PORT } from '../../services/user-preferences.service'
import { UserStateService } from '../../services/user-state.service'
import { ComposerAttachmentsService } from '../composer-attachments/composer-attachments.service'
import { CaseChatComponent } from './case-chat.component'

/**
 * The component is created WITHOUT rendering (no attachView / detectChanges): ngOnInit never
 * runs, so no SSE connection is opened and the template tree (drawer, exchange shell) stays
 * out of the picture. The submit orchestration is exercised directly on the instance; the
 * component-provided ComposerAttachmentsService is the real one, backed by the mocked
 * ExchangeStateService.
 */
describe('CaseChatComponent — submit with attachments', () => {
  let http: { post: jest.Mock }
  let exchangeState: {
    uploadFile: jest.Mock
    canWriteCase: ReturnType<typeof signal<boolean>>
    canWriteNamespace: ReturnType<typeof signal<boolean>>
    fileCount: ReturnType<typeof signal<number>>
    refreshManifest: jest.Mock
    refreshCase: jest.Mock
    refreshNamespace: jest.Mock
  }
  let calls: string[]

  function makeComponent(): ComponentRef<CaseChatComponent> {
    const environmentInjector = TestBed.inject(EnvironmentInjector)
    return createComponent(CaseChatComponent, { environmentInjector })
  }

  function attachments(ref: ComponentRef<CaseChatComponent>): ComposerAttachmentsService {
    return ref.injector.get(ComposerAttachmentsService)
  }

  beforeEach(() => {
    calls = []
    http = {
      post: jest.fn().mockImplementation(() => {
        calls.push('post')
        return of({})
      }),
    }
    exchangeState = {
      uploadFile: jest.fn().mockImplementation(async () => {
        calls.push('upload')
        return { success: true }
      }),
      canWriteCase: signal(true),
      canWriteNamespace: signal(false),
      fileCount: signal(0),
      refreshManifest: jest.fn(),
      refreshCase: jest.fn(),
      refreshNamespace: jest.fn(),
    }
    TestBed.configureTestingModule({
      providers: [
        { provide: HttpClient, useValue: http },
        { provide: Configuration, useValue: { basePath: '' } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParams: { case: 'c-1', ns: 'ns-1' } }, queryParams: of({}) },
        },
        { provide: ExchangeStateService, useValue: exchangeState },
        { provide: CaseStateService, useValue: { addCase: jest.fn(), updateCaseTitle: jest.fn() } },
        { provide: PromptStateService, useValue: { listEffective: jest.fn().mockReturnValue(of([])) } },
        { provide: UserStateService, useValue: { currentUser: () => ({ id: 'u-1' }) } },
        {
          provide: USER_PREFERENCES_PORT,
          useValue: { shouldSend: jest.fn().mockReturnValue(false), composerHint: () => 'hint' },
        },
      ],
    })
  })

  afterEach(() => TestBed.resetTestingModule())

  it('uploads the attachments before sending, and appends the mention to the message', async () => {
    const ref = makeComponent()
    ref.instance['inputValue'].set('analyse this file')
    attachments(ref).addFiles([new File(['x'], 'report.pdf')])

    await ref.instance['submit']()

    expect(calls).toEqual(['upload', 'post'])
    expect(exchangeState.uploadFile).toHaveBeenCalledWith(ExchangeFileEntryScopeEnum.CASE, expect.any(File))
    expect(http.post).toHaveBeenCalledWith('/api/cases/c-1/messages', {
      content: 'analyse this file\n\n[Files attached to the case exchange: report.pdf]',
      userId: 'default-user',
    })
    expect(ref.instance['inputValue']()).toBe('')
    expect(attachments(ref).attachments()).toEqual([])
  })

  it('blocks the send when an upload fails, keeping the input and the failed chip', async () => {
    const ref = makeComponent()
    ref.instance['inputValue'].set('analyse this file')
    attachments(ref).addFiles([new File(['x'], 'dup.pdf')])
    exchangeState.uploadFile.mockResolvedValue({ success: false, error: 'A file with this name already exists.' })

    await ref.instance['submit']()

    expect(http.post).not.toHaveBeenCalled()
    expect(ref.instance['inputValue']()).toBe('analyse this file')
    expect(attachments(ref).attachments()[0]!.status).toBe('error')
  })

  it('routes the upload to the namespace when the message asks for it and the user can write it', async () => {
    const ref = makeComponent()
    exchangeState.canWriteNamespace.set(true)
    ref.instance['inputValue'].set('please store this in the namespace')
    attachments(ref).addFiles([new File(['x'], 'shared.md')])

    await ref.instance['submit']()

    expect(exchangeState.uploadFile).toHaveBeenCalledWith(ExchangeFileEntryScopeEnum.NAMESPACE, expect.any(File))
    const content = (http.post.mock.calls[0]![1] as { content: string }).content
    expect(content).toContain('[Files attached to the namespace exchange: shared.md]')
  })

  it('keeps the case target when the namespace is mentioned without write rights', async () => {
    const ref = makeComponent()
    exchangeState.canWriteNamespace.set(false)
    ref.instance['inputValue'].set('please store this in the namespace')
    attachments(ref).addFiles([new File(['x'], 'shared.md')])

    await ref.instance['submit']()

    expect(exchangeState.uploadFile).toHaveBeenCalledWith(ExchangeFileEntryScopeEnum.CASE, expect.any(File))
  })

  it('allows an attachment-only send: the content is the mention block', async () => {
    const ref = makeComponent()
    attachments(ref).addFiles([new File(['x'], 'alone.pdf')])

    await ref.instance['submit']()

    expect(http.post).toHaveBeenCalledWith('/api/cases/c-1/messages', {
      content: '[Files attached to the case exchange: alone.pdf]',
      userId: 'default-user',
    })
  })

  it('sends a plain message untouched when nothing is attached', async () => {
    const ref = makeComponent()
    ref.instance['inputValue'].set('hello')

    await ref.instance['submit']()

    expect(http.post).toHaveBeenCalledWith('/api/cases/c-1/messages', { content: 'hello', userId: 'default-user' })
    expect(exchangeState.uploadFile).not.toHaveBeenCalled()
  })

  it('a failed message send keeps the text and the uploaded chips for a retry', async () => {
    const ref = makeComponent()
    ref.instance['inputValue'].set('analyse this file')
    attachments(ref).addFiles([new File(['x'], 'report.pdf')])
    http.post.mockImplementationOnce(() => {
      calls.push('post')
      return throwError(() => new Error('network down'))
    })

    await ref.instance['submit']()

    expect(ref.instance['inputValue']()).toBe('analyse this file')
    expect(attachments(ref).attachments()[0]!.status).toBe('uploaded')

    await ref.instance['submit']()

    // The retry does not re-upload (chip already uploaded) and sends the same content.
    expect(exchangeState.uploadFile).toHaveBeenCalledTimes(1)
    expect(http.post).toHaveBeenCalledTimes(2)
    expect(ref.instance['inputValue']()).toBe('')
    expect(attachments(ref).attachments()).toEqual([])
  })

  it('a case switch during the upload aborts the send', async () => {
    const ref = makeComponent()
    ref.instance['inputValue'].set('draft written for the old case')
    attachments(ref).addFiles([new File(['x'], 'a.pdf')])
    exchangeState.uploadFile.mockImplementation(async () => {
      // Simulates reinitialise() firing on a sidebar case switch mid-upload.
      ref.instance['caseId'] = 'c-2'
      attachments(ref).reset()
      return { success: true }
    })

    await ref.instance['submit']()

    expect(http.post).not.toHaveBeenCalled()
  })

  it('is not re-entrant: a second submit while one is in flight does not double-send', async () => {
    const ref = makeComponent()
    ref.instance['inputValue'].set('analyse this file')
    attachments(ref).addFiles([new File(['x'], 'report.pdf')])
    let resolveUpload!: (v: { success: boolean }) => void
    exchangeState.uploadFile.mockReturnValue(new Promise<{ success: boolean }>((resolve) => (resolveUpload = resolve)))

    const first = ref.instance['submit']()
    await ref.instance['submit']() // fired while the first is still awaiting the upload
    resolveUpload({ success: true })
    await first

    expect(exchangeState.uploadFile).toHaveBeenCalledTimes(1)
    expect(http.post).toHaveBeenCalledTimes(1)
  })

  it('canSend is false while uploading or on a terminal case, even with attachments staged', () => {
    const ref = makeComponent()
    attachments(ref).addFiles([new File(['x'], 'a.pdf')])
    expect(ref.instance['canSend']).toBe(true)

    attachments(ref).isUploading.set(true)
    expect(ref.instance['canSend']).toBe(false)

    attachments(ref).isUploading.set(false)
    ref.instance['isTerminal'].set(true)
    expect(ref.instance['canSend']).toBe(false)
  })
})
