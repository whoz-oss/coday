import { ComponentRef, createComponent, EnvironmentInjector, signal } from '@angular/core'
import { fakeAsync, TestBed, tick } from '@angular/core/testing'
import { ActivatedRoute, Router } from '@angular/router'
import { CaseControllerService, ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { BehaviorSubject, of, Subject, throwError } from 'rxjs'
import { CaseStateService } from '../../services/case-state.service'
import { ExchangeStateService } from '../../services/exchange-state.service'
import { PromptStateService } from '../../services/prompt-state.service'
import { USER_PREFERENCES_PORT } from '../../services/user-preferences.service'
import { ComposerAttachmentsService } from '../composer-attachments/composer-attachments.service'
import { CaseHomeComponent } from './case-home.component'
import { CaseWorkspaceService } from '../../services/case-workspace.service'

/**
 * Created WITHOUT rendering (no attachView / detectChanges); ngOnInit is invoked manually
 * where the test needs it. The component-provided ComposerAttachmentsService is real,
 * backed by the mocked ExchangeStateService.
 */
describe('CaseHomeComponent — first message with attachments', () => {
  let api: { createCase: jest.Mock; addMessageCase: jest.Mock }
  let workspaces: { watch: jest.Mock }
  let router: { navigate: jest.Mock }
  let exchangeState: {
    uploadFile: jest.Mock
    initializeForNamespace: jest.Mock
    initializeForCase: jest.Mock
    canWriteNamespace: ReturnType<typeof signal<boolean>>
  }
  let queryParams$: Subject<Record<string, string>>
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
    queryParams$ = new Subject<Record<string, string>>()
    workspaces = { watch: jest.fn().mockReturnValue(of({ view: { equipped: false } })) }
    api = {
      createCase: jest.fn().mockImplementation(() => {
        calls.push('create-case')
        return of({ id: 'case-9' })
      }),
      addMessageCase: jest.fn().mockImplementation(() => {
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
        { provide: CaseControllerService, useValue: api },
        { provide: CaseWorkspaceService, useValue: workspaces },
        { provide: Router, useValue: router },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParams: { ns: 'ns-1' } }, queryParams: queryParams$ } },
        { provide: ExchangeStateService, useValue: exchangeState },
        { provide: CaseStateService, useValue: { addCase: jest.fn(), cases: signal([]) } },
        { provide: PromptStateService, useValue: { listEffective: jest.fn().mockReturnValue(of([])) } },
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
    expect(api.addMessageCase).toHaveBeenCalledWith('case-9', {
      content: 'summarize this\n\n[Files attached to the case exchange: report.pdf]',
    })
    expect(router.navigate).toHaveBeenCalledWith(['/agentos/home'], {
      queryParams: { ns: 'ns-1', case: 'case-9' },
    })
    expect(attachments(ref).attachments()).toEqual([])
    expect(ref.instance['inputValue']()).toBe('')
    expect(api.createCase.mock.calls[0][0]).not.toHaveProperty('parentCaseId')
    expect(api.createCase.mock.calls[0][0]).not.toHaveProperty('title')
  })

  it('creates a sub-case with the selected parent and reuses it if the first message must be retried', async () => {
    TestBed.inject(ActivatedRoute).snapshot.queryParams['parentCase'] = 'root'
    const ref = makeComponent()
    ref.instance.ngOnInit()
    ref.instance['inputValue'].set('@reviewer Analyse this change')
    api.createCase.mockReturnValue(of({ id: 'child', parentCaseId: 'root' }))
    api.addMessageCase.mockReturnValue(throwError(() => new Error('Message not accepted')))
    const errorSpy = jest.spyOn(console, 'error').mockImplementation(() => undefined)

    await ref.instance['submit']()
    expect(api.createCase).toHaveBeenCalledWith(
      expect.objectContaining({
        namespaceId: 'ns-1',
        parentCaseId: 'root',
      })
    )
    expect(ref.instance['pendingCaseId']()).toBe('child')
    expect(router.navigate).not.toHaveBeenCalled()

    api.addMessageCase.mockReturnValue(of({}))
    await ref.instance['submit']()
    expect(api.createCase).toHaveBeenCalledTimes(1)
    expect(router.navigate).toHaveBeenCalledWith(['/agentos/home'], {
      queryParams: { ns: 'ns-1', case: 'child' },
    })
    errorSpy.mockRestore()
  })

  it('clears a failed sub-case draft when choosing a different parent in the same namespace', async () => {
    const ref = makeComponent()
    ref.instance.ngOnInit()
    queryParams$.next({ ns: 'ns-1', parentCase: 'root-a' })
    ref.instance['inputValue'].set('Analyse')
    attachments(ref).addFiles([new File(['x'], 'notes.txt')])
    exchangeState.uploadFile.mockResolvedValueOnce({ success: false, error: 'Upload failed' })
    await ref.instance['submit']()

    queryParams$.next({ ns: 'ns-1', parentCase: 'root-b' })

    expect(ref.instance['parentCaseId']()).toBe('root-b')
    expect(ref.instance['pendingCaseId']()).toBeNull()
    expect(ref.instance['inputValue']()).toBe('')
    expect(attachments(ref).attachments()).toEqual([])
    ref.instance['inputValue'].set('Test')
    await ref.instance['submit']()
    expect(api.createCase.mock.calls.map(([body]) => body.parentCaseId)).toEqual(['root-a', 'root-b'])
  })

  it('ignores a late creation response after switching away from and back to the same parent', async () => {
    const ref = makeComponent()
    ref.instance.ngOnInit()
    queryParams$.next({ ns: 'ns-1', parentCase: 'root' })
    ref.instance['inputValue'].set('Analyse')
    const creation = new Subject<{ id: string }>()
    api.createCase.mockReturnValueOnce(creation)
    const submission = ref.instance['submit']()

    queryParams$.next({ ns: 'ns-1' })
    queryParams$.next({ ns: 'ns-1', parentCase: 'root' })
    creation.next({ id: 'stale-child' })
    creation.complete()
    await submission

    expect(ref.instance['pendingCaseId']()).toBeNull()
    expect(api.createCase).toHaveBeenCalledTimes(1)
    expect(api.addMessageCase).not.toHaveBeenCalled()
    expect(router.navigate).not.toHaveBeenCalled()
    expect(TestBed.inject(CaseStateService).addCase).not.toHaveBeenCalled()
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

  it('a namespace switch clears the staged files and the pending case id', async () => {
    const ref = makeComponent()
    ref.instance.ngOnInit()
    ref.instance['inputValue'].set('summarize this')
    attachments(ref).addFiles([new File(['x'], 'dup.pdf')])
    exchangeState.uploadFile.mockResolvedValueOnce({ success: false, error: 'A file with this name already exists.' })
    await ref.instance['submit']()
    expect(ref.instance['pendingCaseId']()).toBe('case-9')

    queryParams$.next({ ns: 'ns-2' })

    expect(exchangeState.initializeForNamespace).toHaveBeenCalledWith('ns-2')
    expect(attachments(ref).attachments()).toEqual([])
    expect(ref.instance['pendingCaseId']()).toBeNull()
  })

  it('routes the upload to the namespace when asked for with write rights', async () => {
    const ref = makeComponent()
    exchangeState.canWriteNamespace.set(true)
    ref.instance['inputValue'].set('add this to the namespace documents')
    attachments(ref).addFiles([new File(['x'], 'shared.md')])

    await ref.instance['submit']()

    expect(exchangeState.uploadFile).toHaveBeenCalledWith(ExchangeFileEntryScopeEnum.NAMESPACE, expect.any(File))
    const messageCall = api.addMessageCase.mock.calls[0]!
    expect((messageCall[1] as { content: string }).content).toContain(
      '[Files attached to the namespace exchange: shared.md]'
    )
  })
  it('keeps auto-naming enabled for prompt aliases and attachment-only cases', async () => {
    for (const message of ['/review', '']) {
      const ref = makeComponent()
      ref.instance['inputValue'].set(message)
      attachments(ref).addFiles([new File(['x'], 'notes.txt')])
      await ref.instance['submit']()
      expect(api.createCase.mock.calls.at(-1)![0]).not.toHaveProperty('title')
      ref.destroy()
    }
  })

  it('explains a long preparation wait and retries the same case with its text and files intact', fakeAsync(() => {
    const state = new BehaviorSubject({ view: { equipped: true, status: 'PREPARING' } })
    workspaces.watch.mockReturnValue(state)
    const errorSpy = jest.spyOn(console, 'error').mockImplementation(() => undefined)
    const ref = makeComponent()
    ref.instance['inputValue'].set('inspect this')
    attachments(ref).addFiles([new File(['x'], 'document.txt')])

    void ref.instance['submit']()
    tick(120_000)

    expect(ref.instance['submitError']()).toContain('Workspace preparation is still in progress')
    expect(ref.instance['inputValue']()).toBe('inspect this')
    expect(attachments(ref).attachments()).toHaveLength(1)
    expect(ref.instance['pendingCaseId']()).toBe('case-9')
    expect(ref.instance['isCreating']()).toBe(false)
    expect(api.addMessageCase).not.toHaveBeenCalled()
    expect(exchangeState.uploadFile).not.toHaveBeenCalled()

    state.next({ view: { equipped: true, status: 'READY' } })
    void ref.instance['submit']()
    tick()
    expect(api.createCase).toHaveBeenCalledTimes(1)
    expect(api.addMessageCase).toHaveBeenCalledTimes(1)
    expect(exchangeState.uploadFile).toHaveBeenCalledTimes(1)
    ref.destroy()
    errorSpy.mockRestore()
  }))

  it('cancels the shared preparation wait when the composer changes namespace', async () => {
    const state = new BehaviorSubject({ view: { equipped: true, status: 'PREPARING' } })
    workspaces.watch.mockReturnValue(state)
    const ref = makeComponent()
    ref.instance.ngOnInit()
    ref.instance['inputValue'].set('inspect this')
    attachments(ref).addFiles([new File(['x'], 'document.txt')])
    const submission = ref.instance['submit']()
    await Promise.resolve()
    expect(state.observed).toBe(true)
    queryParams$.next({ ns: 'ns-2' })
    await submission
    expect(state.observed).toBe(false)
    expect(api.addMessageCase).not.toHaveBeenCalled()
    expect(exchangeState.uploadFile).not.toHaveBeenCalled()
    ref.destroy()
  })
})
