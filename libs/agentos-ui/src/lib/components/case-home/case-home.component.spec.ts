import { HttpClient } from '@angular/common/http'
import { ComponentRef, createComponent, EnvironmentInjector, signal } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { ActivatedRoute, Router } from '@angular/router'
import { Configuration, ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { of } from 'rxjs'
import { CaseStateService } from '../../services/case-state.service'
import { ExchangeStateService } from '../../services/exchange-state.service'
import { PromptStateService } from '../../services/prompt-state.service'
import { USER_PREFERENCES_PORT } from '../../services/user-preferences.service'
import { UserStateService } from '../../services/user-state.service'
import { ComposerAttachmentsService } from '../composer-attachments/composer-attachments.service'
import { CaseHomeComponent } from './case-home.component'

/**
 * Created WITHOUT rendering (no attachView / detectChanges); ngOnInit is invoked manually
 * where the test needs it. The component-provided ComposerAttachmentsService is real,
 * backed by the mocked ExchangeStateService.
 */
describe('CaseHomeComponent — first message with attachments', () => {
  let http: { post: jest.Mock }
  let router: { navigate: jest.Mock }
  let exchangeState: {
    uploadFile: jest.Mock
    initializeForNamespace: jest.Mock
    initializeForCase: jest.Mock
    canWriteNamespace: ReturnType<typeof signal<boolean>>
  }
  let calls: string[]

  function makeComponent(): ComponentRef<CaseHomeComponent> {
    const environmentInjector = TestBed.inject(EnvironmentInjector)
    return createComponent(CaseHomeComponent, { environmentInjector })
  }

  function attachments(ref: ComponentRef<CaseHomeComponent>): ComposerAttachmentsService {
    return ref.injector.get(ComposerAttachmentsService)
  }

  beforeEach(() => {
    calls = []
    http = {
      post: jest.fn().mockImplementation((url: string) => {
        if (url.endsWith('/api/cases')) {
          calls.push('create-case')
          return of({ id: 'case-9' })
        }
        calls.push('send-message')
        return of({})
      }),
    }
    router = { navigate: jest.fn() }
    exchangeState = {
      uploadFile: jest.fn().mockImplementation(async () => {
        calls.push('upload')
        return { success: true }
      }),
      initializeForNamespace: jest.fn(),
      initializeForCase: jest.fn().mockImplementation(() => calls.push('init-case')),
      canWriteNamespace: signal(false),
    }
    TestBed.configureTestingModule({
      providers: [
        { provide: HttpClient, useValue: http },
        { provide: Router, useValue: router },
        { provide: Configuration, useValue: { basePath: '' } },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParams: { ns: 'ns-1' } }, queryParams: of({}) } },
        { provide: ExchangeStateService, useValue: exchangeState },
        { provide: CaseStateService, useValue: { addCase: jest.fn() } },
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

  it('initialises the namespace-only exchange on init so the badge gating can work', () => {
    const ref = makeComponent()
    ref.instance.ngOnInit()
    expect(exchangeState.initializeForNamespace).toHaveBeenCalledWith('ns-1')
  })

  it('creates the case, uploads, sends the mention-bearing message, then navigates', async () => {
    const ref = makeComponent()
    ref.instance['inputValue'].set('summarize this')
    attachments(ref).addFiles([new File(['x'], 'report.pdf')])

    await ref.instance['submit']()

    expect(calls).toEqual(['create-case', 'init-case', 'upload', 'send-message'])
    expect(exchangeState.initializeForCase).toHaveBeenCalledWith('ns-1', 'case-9')
    expect(http.post).toHaveBeenCalledWith('/api/cases/case-9/messages', {
      content: 'summarize this\n\n[Files attached to the case exchange: report.pdf]',
      userId: 'default-user',
    })
    expect(router.navigate).toHaveBeenCalledWith(['/agentos/home'], {
      queryParams: { ns: 'ns-1', case: 'case-9' },
    })
    expect(attachments(ref).attachments()).toEqual([])
    expect(ref.instance['inputValue']()).toBe('')
  })

  it('on upload failure: keeps the created case, does not send nor navigate, and a retry reuses it', async () => {
    const ref = makeComponent()
    ref.instance['inputValue'].set('summarize this')
    attachments(ref).addFiles([new File(['x'], 'dup.pdf')])
    exchangeState.uploadFile.mockResolvedValueOnce({ success: false, error: 'A file with this name already exists.' })

    await ref.instance['submit']()

    expect(calls.filter((c) => c === 'create-case')).toHaveLength(1)
    expect(calls).not.toContain('send-message')
    expect(router.navigate).not.toHaveBeenCalled()
    expect(ref.instance['isCreating']()).toBe(false)
    expect(ref.instance['inputValue']()).toBe('summarize this')
    expect(attachments(ref).attachments()[0]!.status).toBe('error')

    exchangeState.uploadFile.mockImplementation(async () => {
      calls.push('upload')
      return { success: true }
    })
    await ref.instance['submit']()

    expect(calls.filter((c) => c === 'create-case')).toHaveLength(1)
    expect(calls).toContain('send-message')
    expect(router.navigate).toHaveBeenCalled()
  })

  it('routes the upload to the namespace when asked for with write rights', async () => {
    const ref = makeComponent()
    exchangeState.canWriteNamespace.set(true)
    ref.instance['inputValue'].set('add this to the namespace documents')
    attachments(ref).addFiles([new File(['x'], 'shared.md')])

    await ref.instance['submit']()

    expect(exchangeState.uploadFile).toHaveBeenCalledWith(ExchangeFileEntryScopeEnum.NAMESPACE, expect.any(File))
    const messageCall = http.post.mock.calls.find(([url]) => (url as string).includes('/messages'))!
    expect((messageCall[1] as { content: string }).content).toContain(
      '[Files attached to the namespace exchange: shared.md]'
    )
  })
})
