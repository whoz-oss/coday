import {
  ApplicationRef,
  Component,
  ComponentRef,
  createComponent,
  EnvironmentInjector,
  input,
  output,
  signal,
  WritableSignal,
} from '@angular/core'
import { fakeAsync, TestBed, tick } from '@angular/core/testing'
import { ActivatedRoute, Router } from '@angular/router'
import {
  CaseControllerService,
  CaseWorkspaceViewStatusEnum,
  ExchangeFileEntryScopeEnum,
  IntegrationTypeControllerService,
  NamespaceGitControllerService,
  ReasoningEffort,
  VoiceMessageControllerService,
} from '@whoz-oss/agentos-api-client'
import { BehaviorSubject, Observable, of, Subject, throwError } from 'rxjs'
import { CaseStateService } from '../../services/case-state.service'
import { AiModelStateService } from '../../services/ai-model-state.service'
import { ExchangeFileRef, ExchangeStateService } from '../../services/exchange-state.service'
import { PromptStateService } from '../../services/prompt-state.service'
import { USER_PREFERENCES_PORT } from '../../services/user-preferences.service'
import { ComposerAttachmentsService } from '../composer-attachments/composer-attachments.service'
import { ComposerAutocompleteService } from '../composer-autocomplete/composer-autocomplete.service'
import { VOICE_MEDIA_PORT } from '../voice-recorder/voice-media.port'
import { VOICE_ERRORS } from '../voice-recorder/voice-recorder.utils'
import { CaseHomeComponent } from './case-home.component'
import { CaseWorkspaceService } from '../../services/case-workspace.service'
import { ExchangeReferenceToggle } from '../exchange-drawer/exchange-drawer.component'
import { ExchangeShellComponent } from '../exchange-shell/exchange-shell.component'

@Component({ selector: 'agentos-exchange-shell', standalone: true, template: '' })
class StubExchangeShellComponent {
  static last: StubExchangeShellComponent | undefined
  readonly active = input(false)
  readonly selectable = input(false)
  readonly referenced = input<ExchangeFileRef[]>([])
  readonly closeRequested = output<void>()
  readonly referenceToggled = output<ExchangeReferenceToggle>()

  constructor() {
    StubExchangeShellComponent.last = this
  }
}

/**
 * Created WITHOUT rendering (no attachView / detectChanges); ngOnInit is invoked manually
 * where the test needs it. The component-provided ComposerAttachmentsService is real,
 * backed by the mocked ExchangeStateService.
 */
describe('CaseHomeComponent — first message with attachments', () => {
  let api: { createCase: jest.Mock; addMessageCase: jest.Mock }
  let workspaces: { watch: jest.Mock; enabled$: Observable<boolean> }
  let gitApi: { getAssociationNamespaceGit: jest.Mock }
  let integrationTypes: { listTypesIntegrationType: jest.Mock }
  let router: { navigate: jest.Mock }
  let exchangeState: {
    uploadFile: jest.Mock
    initializeForNamespace: jest.Mock
    initializeForCase: jest.Mock
    canWriteNamespace: ReturnType<typeof signal<boolean>>
  }
  let queryParams$: Subject<Record<string, string>>
  let aiModels: { listAvailable: jest.Mock }
  let calls: string[]
  let voiceApi: { postVoiceMessage: jest.Mock; capabilityVoiceMessage: jest.Mock }
  const track = { stop: jest.fn() }

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
    workspaces = { watch: jest.fn().mockReturnValue(of({ view: { equipped: false } })), enabled$: of(true) }
    gitApi = { getAssociationNamespaceGit: jest.fn().mockReturnValue(of({ associated: false })) }
    integrationTypes = { listTypesIntegrationType: jest.fn().mockReturnValue(of([{ type: 'BASH' }, { type: 'GIT' }])) }
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
    aiModels = {
      listAvailable: jest.fn().mockReturnValue(
        of([
          { aiProviderId: 'p', apiModelName: 'claude-opus', alias: 'BIG', priority: 0 },
          { aiProviderId: 'p', apiModelName: 'claude-haiku', alias: 'SMALL', priority: 0 },
          { aiProviderId: 'p', apiModelName: 'whisper-1', alias: 'WHISPER', priority: 0 },
        ])
      ),
    }
    track.stop.mockClear()
    voiceApi = {
      postVoiceMessage: jest.fn().mockImplementation(() => {
        calls.push('post-voice')
        return of({ transcript: 'hello', path: 'voice-messages/v.webm' })
      }),
      capabilityVoiceMessage: jest.fn().mockReturnValue(of({ available: true })),
    }
    // MediaRecorder / getUserMedia do not exist in jsdom: a fake media port stands in for them.
    const media = {
      isTypeSupported: () => true,
      getUserMedia: () => Promise.resolve({ getTracks: () => [track] } as unknown as MediaStream),
      createRecorder: () => {
        const r = {
          state: 'inactive',
          ondataavailable: null as ((event: { data: Blob }) => void) | null,
          onstop: null as (() => void) | null,
          onerror: null as (() => void) | null,
          start: jest.fn(() => {
            r.state = 'recording'
          }),
          stop: jest.fn(() => {
            r.state = 'inactive'
            r.ondataavailable?.({ data: new Blob(['audio']) })
            r.onstop?.()
          }),
        }
        return r as unknown as MediaRecorder
      },
    }
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
        { provide: NamespaceGitControllerService, useValue: gitApi },
        { provide: IntegrationTypeControllerService, useValue: integrationTypes },
        { provide: AiModelStateService, useValue: aiModels },
        { provide: Router, useValue: router },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParams: { ns: 'ns-1' } }, queryParams: queryParams$ } },
        { provide: ExchangeStateService, useValue: exchangeState },
        { provide: CaseStateService, useValue: { addCase: jest.fn(), cases: signal([]) } },
        { provide: PromptStateService, useValue: { listEffective: jest.fn().mockReturnValue(of([])) } },
        { provide: VOICE_MEDIA_PORT, useValue: media },
        { provide: VoiceMessageControllerService, useValue: voiceApi },
        {
          provide: USER_PREFERENCES_PORT,
          useValue: {
            shouldSend: jest.fn().mockReturnValue(false),
            enterKeyBehavior: signal('send'),
          },
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

  describe('files added from the drawer', () => {
    const ns = (path: string) => ({ scope: ExchangeFileEntryScopeEnum.NAMESPACE, path })
    /** What the drawer reports when a checkbox is clicked. */
    const toggle = (ref: ComponentRef<CaseHomeComponent>, file: ExchangeFileRef, referenced = true): void =>
      ref.instance['onReferenceToggled']({ ref: file, referenced })
    const add = (ref: ComponentRef<CaseHomeComponent>, ...files: ExchangeFileRef[]): void =>
      files.forEach((file) => toggle(ref, file))

    it('adds a chip reference when a file is checked, once, and leaves the draft alone', () => {
      const ref = makeComponent()
      ref.instance['inputValue'].set('summarize')

      add(ref, ns('docs/readme.md'), ns('notes.md'))
      add(ref, ns('docs/readme.md'), ns('other.md'))

      expect(ref.instance['referencedFiles']()).toEqual([ns('docs/readme.md'), ns('notes.md'), ns('other.md')])
      expect(ref.instance['inputValue']()).toBe('summarize')
      ref.destroy()
    })

    it('removes a reference when its chip is removed', () => {
      const ref = makeComponent()
      add(ref, ns('a.md'), ns('b.md'))

      ref.instance['removeReference'](ns('a.md'))

      expect(ref.instance['referencedFiles']()).toEqual([ns('b.md')])
      ref.destroy()
    })

    it('removes the chip reference when the file is unchecked in the drawer', () => {
      const ref = makeComponent()
      add(ref, ns('a.md'), ns('b.md'))

      toggle(ref, ns('a.md'), false)
      toggle(ref, ns('missing.md'), false)

      expect(ref.instance['referencedFiles']()).toEqual([ns('b.md')])
      ref.destroy()
    })

    it('does not steal the focus from the drawer when a file is checked', () => {
      const ref = makeComponent()
      TestBed.inject(ApplicationRef).attachView(ref.hostView)
      ref.changeDetectorRef.detectChanges()
      const textarea = (ref.location.nativeElement as HTMLElement).querySelector('textarea')!
      const focus = jest.spyOn(textarea, 'focus')

      add(ref, ns('a.md'))
      ref.changeDetectorRef.detectChanges()

      expect(focus).not.toHaveBeenCalled()
      ref.destroy()
    })

    it('renders the references as chips, next to the staged uploads', () => {
      const ref = makeComponent()
      TestBed.inject(ApplicationRef).attachView(ref.hostView)
      add(ref, ns('docs/readme.md'))
      ref.changeDetectorRef.detectChanges()
      const host = ref.location.nativeElement as HTMLElement

      const chips = host.querySelectorAll('.composer-attachments__chip')
      expect(chips).toHaveLength(1)
      expect(chips[0]!.textContent).toContain('readme.md')
      expect(chips[0]!.textContent).toContain('namespace')
      expect((host.querySelector('textarea') as HTMLTextAreaElement).value).toBe('')

      host.querySelector<HTMLButtonElement>('.composer-attachments__chip button')!.click()
      ref.changeDetectorRef.detectChanges()
      expect(host.querySelectorAll('.composer-attachments__chip')).toHaveLength(0)
      ref.destroy()
    })

    it('can send with references only, and only then', () => {
      const ref = makeComponent()
      expect(ref.instance['canSend']).toBe(false)

      add(ref, ns('a.md'))

      expect(ref.instance['canSend']).toBe(true)
      ref.destroy()
    })

    it('is not offered the mic while references are present', () => {
      const ref = makeComponent()
      expect(ref.instance['canRecord']).toBe(true)

      add(ref, ns('a.md'))

      expect(ref.instance['canRecord']).toBe(false)
      ref.destroy()
    })

    it('sends a references line with the first message of the created case, then clears them', async () => {
      const ref = makeComponent()
      ref.instance['inputValue'].set('summarize')
      add(ref, ns('docs/readme.md'), ns('notes.md'))

      await ref.instance['submit']()

      expect(api.addMessageCase).toHaveBeenCalledWith('case-9', {
        content: 'summarize\n\n[Files referenced: namespace:docs/readme.md, namespace:notes.md]',
      })
      expect(exchangeState.uploadFile).not.toHaveBeenCalled()
      expect(exchangeState.initializeForCase).not.toHaveBeenCalled()
      expect(ref.instance['referencedFiles']()).toEqual([])
      ref.destroy()
    })

    it('sends the references alone when there is no text', async () => {
      const ref = makeComponent()
      add(ref, ns('a.md'))

      await ref.instance['submit']()

      expect(api.addMessageCase).toHaveBeenCalledWith('case-9', { content: '[Files referenced: namespace:a.md]' })
      ref.destroy()
    })

    it('combines the upload mention and the references line', async () => {
      const ref = makeComponent()
      ref.instance['inputValue'].set('compare')
      attachments(ref).addFiles([new File(['x'], 'report.pdf')])
      add(ref, ns('docs/readme.md'))

      await ref.instance['submit']()

      expect(api.addMessageCase).toHaveBeenCalledWith('case-9', {
        content:
          'compare\n\n[Files attached to the case exchange: report.pdf]\n[Files referenced: namespace:docs/readme.md]',
      })
      ref.destroy()
    })

    it('keeps the references when the send fails, and sends them on the retry with the same case', async () => {
      const errorSpy = jest.spyOn(console, 'error').mockImplementation(() => undefined)
      const ref = makeComponent()
      add(ref, ns('a.md'))
      api.addMessageCase.mockReturnValueOnce(throwError(() => new Error('Message not accepted')))

      await ref.instance['submit']()
      expect(ref.instance['referencedFiles']()).toEqual([ns('a.md')])
      expect(router.navigate).not.toHaveBeenCalled()

      await ref.instance['submit']()

      expect(api.createCase).toHaveBeenCalledTimes(1)
      expect(api.addMessageCase).toHaveBeenLastCalledWith('case-9', { content: '[Files referenced: namespace:a.md]' })
      expect(ref.instance['referencedFiles']()).toEqual([])
      errorSpy.mockRestore()
      ref.destroy()
    })

    it('drops the references on a namespace switch, like the staged files', () => {
      const ref = makeComponent()
      ref.instance.ngOnInit()
      add(ref, ns('a.md'))

      queryParams$.next({ ns: 'ns-2' })

      expect(ref.instance['referencedFiles']()).toEqual([])
      ref.destroy()
    })

    it('renders the drawer only while the folder button has opened it', () => {
      // The real shell reads many signals of the exchange state: a stub keeps this about the host.
      TestBed.overrideComponent(CaseHomeComponent, {
        remove: { imports: [ExchangeShellComponent] },
        add: { imports: [StubExchangeShellComponent] },
      })
      const ref = makeComponent()
      TestBed.inject(ApplicationRef).attachView(ref.hostView)
      ref.changeDetectorRef.detectChanges()
      const host = ref.location.nativeElement as HTMLElement
      expect(host.querySelector('agentos-exchange-shell')).toBeNull()

      host.querySelector<HTMLButtonElement>('button[title="Browse namespace files"]')!.click()
      ref.changeDetectorRef.detectChanges()

      expect(ref.instance['filesOpen']()).toBe(true)
      expect(host.querySelector('agentos-exchange-shell')).not.toBeNull()
      ref.destroy()
    })

    it('hands the referenced files to the drawer and follows its checkboxes', () => {
      TestBed.overrideComponent(CaseHomeComponent, {
        remove: { imports: [ExchangeShellComponent] },
        add: { imports: [StubExchangeShellComponent] },
      })
      const ref = makeComponent()
      TestBed.inject(ApplicationRef).attachView(ref.hostView)
      ref.instance['filesOpen'].set(true)
      ref.changeDetectorRef.detectChanges()
      const drawer = StubExchangeShellComponent.last!
      expect(drawer.selectable()).toBe(true)
      expect(drawer.referenced()).toEqual([])

      drawer.referenceToggled.emit({ ref: ns('docs/readme.md'), referenced: true })
      ref.changeDetectorRef.detectChanges()
      expect(drawer.referenced()).toEqual([ns('docs/readme.md')])
      expect((ref.location.nativeElement as HTMLElement).querySelectorAll('.composer-attachments__chip')).toHaveLength(
        1
      )

      // Removing the chip unchecks the file in the drawer: the same list feeds both.
      ;(ref.location.nativeElement as HTMLElement)
        .querySelector<HTMLButtonElement>('.composer-attachments__chip button')!
        .click()
      ref.changeDetectorRef.detectChanges()
      expect(drawer.referenced()).toEqual([])

      add(ref, ns('a.md'))
      drawer.referenceToggled.emit({ ref: ns('a.md'), referenced: false })
      ref.changeDetectorRef.detectChanges()
      expect(drawer.referenced()).toEqual([])
      ref.destroy()
    })
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
    const state = new BehaviorSubject({ view: { equipped: true, status: CaseWorkspaceViewStatusEnum.PREPARING } })
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

    state.next({ view: { equipped: true, status: CaseWorkspaceViewStatusEnum.READY } })
    void ref.instance['submit']()
    tick()
    expect(api.createCase).toHaveBeenCalledTimes(1)
    expect(api.addMessageCase).toHaveBeenCalledTimes(1)
    expect(exchangeState.uploadFile).toHaveBeenCalledTimes(1)
    ref.destroy()
    errorSpy.mockRestore()
  }))

  it('sends the attachments when the instance has no Git workspaces', async () => {
    workspaces.watch.mockReturnValue(of({ view: null, errorStatus: 404 }))
    const ref = makeComponent()
    ref.instance['inputValue'].set('inspect this')
    attachments(ref).addFiles([new File(['x'], 'document.txt')])
    await ref.instance['submit']()
    expect(ref.instance['submitError']()).toBeFalsy()
    expect(exchangeState.uploadFile).toHaveBeenCalledTimes(1)
    expect(api.addMessageCase).toHaveBeenCalledTimes(1)
    ref.destroy()
  })

  it('cancels the shared preparation wait when the composer changes namespace', async () => {
    const state = new BehaviorSubject({ view: { equipped: true, status: CaseWorkspaceViewStatusEnum.PREPARING } })
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

  it('offers the namespace worktree unchecked by default', () => {
    gitApi.getAssociationNamespaceGit.mockReturnValue(of({ associated: true, autoWorktreeForRootCases: true }))
    const ref = makeComponent()
    ref.instance.ngOnInit()
    expect(gitApi.getAssociationNamespaceGit).toHaveBeenCalledWith('ns-1')
    expect(ref.instance['worktreeOffered']()).toBe(true)
    expect(ref.instance['worktree']()).toBe(false)
    ref.destroy()
  })

  it('sends worktree false when the offered box is left unchecked', async () => {
    gitApi.getAssociationNamespaceGit.mockReturnValue(of({ associated: true, autoWorktreeForRootCases: true }))
    const ref = makeComponent()
    ref.instance.ngOnInit()
    ref.instance['inputValue'].set('what does this module do?')
    await ref.instance['submit']()

    expect(api.createCase).toHaveBeenCalledWith(expect.objectContaining({ namespaceId: 'ns-1', worktree: false }))
    ref.destroy()
  })

  it('sends worktree true when the offered box is checked', async () => {
    gitApi.getAssociationNamespaceGit.mockReturnValue(of({ associated: true, autoWorktreeForRootCases: true }))
    const ref = makeComponent()
    ref.instance.ngOnInit()
    ref.instance['onWorktreeChange']({ target: { checked: true } } as unknown as Event)
    expect(ref.instance['worktree']()).toBe(true)
    ref.instance['inputValue'].set('fix the export')
    await ref.instance['submit']()

    expect(api.createCase).toHaveBeenCalledWith(expect.objectContaining({ namespaceId: 'ns-1', worktree: true }))
    ref.destroy()
  })

  it('unchecks the box again when the composer switches namespace', () => {
    gitApi.getAssociationNamespaceGit.mockReturnValue(of({ associated: true, autoWorktreeForRootCases: true }))
    const ref = makeComponent()
    ref.instance.ngOnInit()
    ref.instance['onWorktreeChange']({ target: { checked: true } } as unknown as Event)
    queryParams$.next({ ns: 'ns-2' })

    expect(ref.instance['worktree']()).toBe(false)
    ref.destroy()
  })

  it.each([
    ['the namespace does not create one for every case', of({ associated: true, autoWorktreeForRootCases: false })],
    ['the namespace has just been associated', of({ associated: true })],
    [
      'the namespace association is unusable',
      of({ associated: true, autoWorktreeForRootCases: false, checkoutStatus: 'FAILED' }),
    ],
  ])('does not offer a worktree when %s', async (_, association) => {
    gitApi.getAssociationNamespaceGit.mockReturnValue(association)
    const ref = makeComponent()
    ref.instance.ngOnInit()
    ref.instance['inputValue'].set('hello')
    await ref.instance['submit']()

    expect(ref.instance['worktreeOffered']()).toBe(false)
    expect(api.createCase.mock.calls[0][0]).not.toHaveProperty('worktree')
    ref.destroy()
  })

  it.each([
    ['the instance has no Git workspaces', false, [{ type: 'GIT' }]],
    ['the GIT plugin is not loaded', true, [{ type: 'BASH' }]],
  ])('neither asks for the association nor offers a worktree when %s', async (_, enabled, types) => {
    workspaces.enabled$ = of(enabled)
    integrationTypes.listTypesIntegrationType.mockReturnValue(of(types))
    gitApi.getAssociationNamespaceGit.mockReturnValue(of({ associated: true, autoWorktreeForRootCases: true }))
    const ref = makeComponent()
    ref.instance.ngOnInit()
    ref.instance['inputValue'].set('hello')
    await ref.instance['submit']()

    expect(gitApi.getAssociationNamespaceGit).not.toHaveBeenCalled()
    expect(ref.instance['worktreeOffered']()).toBe(false)
    expect(api.createCase.mock.calls[0][0]).not.toHaveProperty('worktree')
    ref.destroy()
  })

  it.each([
    ['the namespace has no repository', of({ associated: false })],
    ['the association cannot be read', throwError(() => ({ status: 403 }))],
    ['the integration types cannot be read', null],
  ])('neither offers nor sends a worktree when %s', async (_, association) => {
    if (association) gitApi.getAssociationNamespaceGit.mockReturnValue(association)
    else integrationTypes.listTypesIntegrationType.mockReturnValue(throwError(() => ({ status: 500 })))
    const ref = makeComponent()
    ref.instance.ngOnInit()
    ref.instance['inputValue'].set('hello')
    await ref.instance['submit']()

    expect(ref.instance['worktreeOffered']()).toBe(false)
    expect(api.createCase.mock.calls[0][0]).not.toHaveProperty('worktree')
    ref.destroy()
  })

  it('neither asks for the association nor sends a worktree choice for a sub-case, which shares its root case worktree', async () => {
    gitApi.getAssociationNamespaceGit.mockReturnValue(of({ associated: true, autoWorktreeForRootCases: true }))
    TestBed.inject(ActivatedRoute).snapshot.queryParams['parentCase'] = 'root'
    const ref = makeComponent()
    ref.instance.ngOnInit()
    queryParams$.next({ ns: 'ns-1', parentCase: 'other-root' })
    ref.instance['inputValue'].set('@reviewer Analyse this change')
    await ref.instance['submit']()

    expect(gitApi.getAssociationNamespaceGit).not.toHaveBeenCalled()
    expect(api.createCase.mock.calls[0][0]).not.toHaveProperty('worktree')
    ref.destroy()
  })

  it('asks the new namespace again when the composer switches namespace', () => {
    gitApi.getAssociationNamespaceGit.mockReturnValue(of({ associated: true, autoWorktreeForRootCases: true }))
    const ref = makeComponent()
    ref.instance.ngOnInit()
    gitApi.getAssociationNamespaceGit.mockReturnValue(of({ associated: false }))
    queryParams$.next({ ns: 'ns-2' })

    expect(gitApi.getAssociationNamespaceGit).toHaveBeenLastCalledWith('ns-2')
    expect(ref.instance['worktreeOffered']()).toBe(false)
    ref.destroy()
  })

  it('asks the integration types again on the next namespace after a failed read', () => {
    integrationTypes.listTypesIntegrationType.mockReturnValueOnce(throwError(() => ({ status: 502 })))
    gitApi.getAssociationNamespaceGit.mockReturnValue(of({ associated: true, autoWorktreeForRootCases: true }))
    const ref = makeComponent()
    ref.instance.ngOnInit()
    expect(ref.instance['worktreeOffered']()).toBe(false)

    queryParams$.next({ ns: 'ns-2' })

    expect(integrationTypes.listTypesIntegrationType).toHaveBeenCalledTimes(2)
    expect(ref.instance['worktreeOffered']()).toBe(true)
    ref.destroy()
  })

  describe('model and effort of the new case', () => {
    const open = (): ComponentRef<CaseHomeComponent> => {
      const ref = makeComponent()
      ref.instance.ngOnInit()
      return ref
    }
    const submitText = async (ref: ComponentRef<CaseHomeComponent>, text: string): Promise<void> => {
      ref.instance['inputValue'].set(text)
      await ref.instance['submit']()
    }
    const autocomplete = (ref: ComponentRef<CaseHomeComponent>): ComposerAutocompleteService =>
      ref.injector.get(ComposerAutocompleteService)
    const MODELS = [null, 'BIG', 'SMALL', 'claude-haiku', 'claude-opus']

    it('lists the chat models of the namespace for /model, transcription model left out', () => {
      const ref = open()

      expect(aiModels.listAvailable).toHaveBeenCalledWith('ns-1')
      expect(ref.instance['modelOptions']().map((o) => o.value)).toEqual([
        'BIG',
        'SMALL',
        'claude-haiku',
        'claude-opus',
      ])
      autocomplete(ref).showModelChoices()
      expect(
        autocomplete(ref)
          .modelSuggestions()
          .map((c) => c.model)
      ).toEqual(MODELS)
      ref.destroy()
    })

    it('starts on the agents own model and effort, and sends neither', async () => {
      const ref = open()
      expect(ref.instance['modelName']()).toBeNull()
      expect(ref.instance['reasoningEffort']()).toBeNull()

      await submitText(ref, 'hello')

      const body = api.createCase.mock.calls[0][0]
      expect(body).not.toHaveProperty('modelName')
      expect(body).not.toHaveProperty('reasoningEffort')
      ref.destroy()
    })

    it('/model sets the held model, clears the command and creates no case', async () => {
      const ref = open()

      await submitText(ref, '/model big')

      expect(ref.instance['modelName']()).toBe('BIG')
      expect(ref.instance['inputValue']()).toBe('')
      expect(api.createCase).not.toHaveBeenCalled()
      expect(api.addMessageCase).not.toHaveBeenCalled()
      expect(router.navigate).not.toHaveBeenCalled()
      ref.destroy()
    })

    it('/effort sets the held effort, clears the command and creates no case', async () => {
      const ref = open()

      await submitText(ref, '/effort high')

      expect(ref.instance['reasoningEffort']()).toBe(ReasoningEffort.HIGH)
      expect(ref.instance['inputValue']()).toBe('')
      expect(api.createCase).not.toHaveBeenCalled()
      expect(api.addMessageCase).not.toHaveBeenCalled()
      ref.destroy()
    })

    it('/model agent and /effort default go back to each agent and model', async () => {
      const ref = open()
      await submitText(ref, '/model BIG')
      await submitText(ref, '/effort max')

      await submitText(ref, '/model agent')
      await submitText(ref, '/effort default')

      expect(ref.instance['modelName']()).toBeNull()
      expect(ref.instance['reasoningEffort']()).toBeNull()
      ref.destroy()
    })

    it('opens the picker, and sets nothing, on an unknown or missing name or level', async () => {
      const ref = open()

      for (const text of ['/model nope', '/model']) {
        autocomplete(ref).modelSuggestions.set([])
        await submitText(ref, text)
        expect(
          autocomplete(ref)
            .modelSuggestions()
            .map((c) => c.model)
        ).toEqual(MODELS)
      }
      for (const text of ['/effort hihg', '/effort']) {
        autocomplete(ref).effortSuggestions.set([])
        await submitText(ref, text)
        expect(autocomplete(ref).effortSuggestions().length).toBeGreaterThan(0)
      }
      expect(ref.instance['modelName']()).toBeNull()
      expect(ref.instance['reasoningEffort']()).toBeNull()
      expect(api.createCase).not.toHaveBeenCalled()
      ref.destroy()
    })

    it('a message that merely mentions a command is an ordinary first message', async () => {
      const ref = open()

      await submitText(ref, 'which /model should I use?')

      expect(api.createCase).toHaveBeenCalledTimes(1)
      expect(ref.instance['modelName']()).toBeNull()
      ref.destroy()
    })

    it('sends the held model and effort when the case is created', async () => {
      const ref = open()
      await submitText(ref, '/model SMALL')
      await submitText(ref, '/effort xhigh')

      await submitText(ref, '@reviewer look at this')

      expect(api.createCase).toHaveBeenCalledTimes(1)
      expect(api.createCase).toHaveBeenCalledWith(
        expect.objectContaining({ namespaceId: 'ns-1', modelName: 'SMALL', reasoningEffort: ReasoningEffort.XHIGH })
      )
      ref.destroy()
    })

    it('sends the held model and effort with a voice message as well', async () => {
      const ref = open()
      await submitText(ref, '/model BIG')
      await submitText(ref, '/effort low')
      ref.instance['startRecording']()
      for (let i = 0; i < 20; i++) await Promise.resolve()
      ref.instance['sendRecording']()
      for (let i = 0; i < 20; i++) await Promise.resolve()

      expect(api.createCase).toHaveBeenCalledWith(
        expect.objectContaining({ modelName: 'BIG', reasoningEffort: ReasoningEffort.LOW })
      )
      ref.destroy()
    })

    it('sends nothing for a value cleared before the case is created', async () => {
      const ref = open()
      await submitText(ref, '/model BIG')
      await submitText(ref, '/model agent')

      await submitText(ref, 'hello')

      expect(api.createCase.mock.calls[0][0]).not.toHaveProperty('modelName')
      ref.destroy()
    })

    it('keeps the choices once the case exists: a retry reuses it and nothing can change them', async () => {
      const errorSpy = jest.spyOn(console, 'error').mockImplementation(() => undefined)
      const ref = open()
      await submitText(ref, '/model BIG')
      api.addMessageCase.mockReturnValueOnce(throwError(() => new Error('Message not accepted')))
      await submitText(ref, 'hello')
      expect(ref.instance['pendingCaseId']()).toBe('case-9')
      expect(ref.instance['canChangeSettings']()).toBe(false)

      ref.instance['onModelSelected']({ model: 'SMALL', label: 'SMALL', description: '' })

      expect(ref.instance['modelName']()).toBe('BIG')
      await submitText(ref, 'hello')
      expect(api.createCase).toHaveBeenCalledTimes(1)
      errorSpy.mockRestore()
      ref.destroy()
    })

    it('is not offered while the case is being created', () => {
      const ref = open()
      ref.instance['isCreating'].set(true)

      autocomplete(ref).onInput('/model', ref.instance['inputValue'])

      expect(autocomplete(ref).modelSuggestions()).toEqual([])
      ref.destroy()
    })

    it('resets the choices and lists the models of the new namespace when the composer switches namespace', async () => {
      const ref = open()
      await submitText(ref, '/model BIG')
      await submitText(ref, '/effort high')
      aiModels.listAvailable.mockReturnValue(
        of([{ aiProviderId: 'p', apiModelName: 'gpt-4o', alias: 'OTHER', priority: 0 }])
      )

      queryParams$.next({ ns: 'ns-2' })

      expect(aiModels.listAvailable).toHaveBeenLastCalledWith('ns-2')
      expect(ref.instance['modelName']()).toBeNull()
      expect(ref.instance['reasoningEffort']()).toBeNull()
      expect(ref.instance['modelOptions']().map((o) => o.value)).toEqual(['OTHER', 'gpt-4o'])
      ref.destroy()
    })

    describe('chips', () => {
      const render = (): { ref: ComponentRef<CaseHomeComponent>; host: HTMLElement } => {
        const ref = makeComponent()
        TestBed.inject(ApplicationRef).attachView(ref.hostView)
        ref.instance.ngOnInit()
        ref.changeDetectorRef.detectChanges()
        return { ref, host: ref.location.nativeElement as HTMLElement }
      }
      const chip = (host: HTMLElement, kind: 'model' | 'effort'): HTMLButtonElement =>
        host.querySelector<HTMLButtonElement>(`.case-settings__chip--${kind}`)!

      it('shows the agent defaults, and no select', () => {
        const { ref, host } = render()

        expect(chip(host, 'model').textContent?.trim()).toBe('model: agent default')
        expect(chip(host, 'effort').textContent?.trim()).toBe('effort: default')
        expect(host.querySelector('select')).toBeNull()
        ref.destroy()
      })

      it('shows the held choices', async () => {
        const { ref, host } = render()
        await submitText(ref, '/model BIG')
        await submitText(ref, '/effort xhigh')
        ref.changeDetectorRef.detectChanges()

        expect(chip(host, 'model').textContent?.trim()).toBe('model: BIG')
        expect(chip(host, 'effort').textContent?.trim()).toBe('effort: xhigh')
        ref.destroy()
      })

      it('opens the same pickers as the commands, with the way back to the defaults', () => {
        const { ref, host } = render()

        chip(host, 'model').click()
        ref.changeDetectorRef.detectChanges()
        expect(
          autocomplete(ref)
            .modelSuggestions()
            .map((c) => c.model)
        ).toEqual(MODELS)
        expect(host.querySelector('agentos-model-autocomplete')).not.toBeNull()

        chip(host, 'effort').click()
        ref.changeDetectorRef.detectChanges()
        expect(autocomplete(ref).effortSuggestions()[0]?.effort).toBeNull()
        expect(host.querySelector('agentos-effort-autocomplete')).not.toBeNull()
        expect(host.querySelector('agentos-model-autocomplete')).toBeNull()
        ref.destroy()
      })

      it('holds the entry picked from a chip picker, the way back included', () => {
        const { ref, host } = render()
        chip(host, 'model').click()
        ref.changeDetectorRef.detectChanges()
        host.querySelectorAll<HTMLLIElement>('agentos-model-autocomplete li')[1]!.click()
        ref.changeDetectorRef.detectChanges()
        expect(ref.instance['modelName']()).toBe('BIG')

        chip(host, 'model').click()
        ref.changeDetectorRef.detectChanges()
        host.querySelectorAll<HTMLLIElement>('agentos-model-autocomplete li')[0]!.click()
        ref.changeDetectorRef.detectChanges()
        expect(ref.instance['modelName']()).toBeNull()
        ref.destroy()
      })

      it('leaves the chips inert once the case exists', async () => {
        const errorSpy = jest.spyOn(console, 'error').mockImplementation(() => undefined)
        const { ref, host } = render()
        api.addMessageCase.mockReturnValueOnce(throwError(() => new Error('Message not accepted')))
        await submitText(ref, 'hello')
        ref.changeDetectorRef.detectChanges()

        expect(chip(host, 'model').disabled).toBe(true)
        expect(chip(host, 'effort').disabled).toBe(true)
        errorSpy.mockRestore()
        ref.destroy()
      })
    })

    it('does not show the Enter key hint any more', () => {
      const ref = makeComponent()
      TestBed.inject(ApplicationRef).attachView(ref.hostView)
      ref.changeDetectorRef.detectChanges()
      const host = ref.location.nativeElement as HTMLElement

      expect(host.querySelector('.case-home__hint')).toBeNull()
      expect(host.textContent).not.toContain('hint')
      ref.destroy()
    })
  })

  describe('first message as a voice message', () => {
    const flush = async () => {
      for (let i = 0; i < 20; i++) await Promise.resolve()
    }

    async function record(ref: ComponentRef<CaseHomeComponent>): Promise<void> {
      ref.instance['startRecording']()
      await flush()
    }

    async function recordAndSend(ref: ComponentRef<CaseHomeComponent>): Promise<void> {
      await record(ref)
      ref.instance['sendRecording']()
      await flush()
    }

    it('asks for the capability when the composer opens', () => {
      const ref = makeComponent()
      ref.instance.ngOnInit()
      expect(voiceApi.capabilityVoiceMessage).toHaveBeenCalledTimes(1)
      ref.destroy()
    })

    it('has no mic until the capability says available: true', () => {
      voiceApi.capabilityVoiceMessage.mockReturnValue(of({ available: false }))
      const ref = makeComponent()
      ref.instance.ngOnInit()
      expect(ref.instance['voiceCapability'].available()).toBe(false)
      ref.destroy()
    })

    it.each([
      [true, 1],
      [false, 0],
    ])('renders the mic button only when the capability is available: %s', (available, expected) => {
      voiceApi.capabilityVoiceMessage.mockReturnValue(of({ available }))
      const ref = makeComponent()
      TestBed.inject(ApplicationRef).attachView(ref.hostView)
      ref.changeDetectorRef.detectChanges()
      const host = ref.location.nativeElement as HTMLElement

      expect(host.querySelectorAll('button[title="Record a voice message"]')).toHaveLength(expected)
      ref.destroy()
    })

    it('creates the case, then posts the voice to that case id, then navigates', async () => {
      const ref = makeComponent()
      await record(ref)
      expect(ref.instance['voice'].state()).toBe('recording')
      expect(api.createCase).not.toHaveBeenCalled()

      ref.instance['sendRecording']()
      await flush()

      expect(calls).toEqual(['create-case', 'post-voice'])
      expect(voiceApi.postVoiceMessage.mock.calls[0]![0]).toBe('case-9')
      expect(api.addMessageCase).not.toHaveBeenCalled()
      expect(router.navigate).toHaveBeenCalledWith(['/agentos/home'], { queryParams: { ns: 'ns-1', case: 'case-9' } })
      expect(ref.instance['pendingCaseId']()).toBeNull()
      expect(ref.instance['isCreating']()).toBe(false)
      expect(track.stop).toHaveBeenCalled()
      expect(TestBed.inject(CaseStateService).addCase).toHaveBeenCalledTimes(1)
    })

    it('creates the case exactly like the text flow: same namespace, no title, status, worktree choice', async () => {
      gitApi.getAssociationNamespaceGit.mockReturnValue(of({ associated: true, autoWorktreeForRootCases: true }))
      const ref = makeComponent()
      ref.instance.ngOnInit()
      ref.instance['onWorktreeChange']({ target: { checked: true } } as unknown as Event)
      await recordAndSend(ref)

      const body = api.createCase.mock.calls[0]![0]
      expect(body).toEqual(
        expect.objectContaining({
          namespaceId: 'ns-1',
          status: 'PENDING',
          favorite: false,
          removed: false,
          worktree: true,
        })
      )
      expect(body).not.toHaveProperty('title')
      expect(body).not.toHaveProperty('parentCaseId')
      ref.destroy()
    })

    it('creates a sub-case with the selected parent', async () => {
      TestBed.inject(ActivatedRoute).snapshot.queryParams['parentCase'] = 'root'
      api.createCase.mockReturnValue(of({ id: 'child', parentCaseId: 'root' }))
      const ref = makeComponent()
      await recordAndSend(ref)

      expect(api.createCase).toHaveBeenCalledWith(
        expect.objectContaining({ namespaceId: 'ns-1', parentCaseId: 'root' })
      )
      expect(voiceApi.postVoiceMessage.mock.calls[0]![0]).toBe('child')
      expect(router.navigate).toHaveBeenCalledWith(['/agentos/home'], { queryParams: { ns: 'ns-1', case: 'child' } })
    })

    it('marks the composer as creating while the recording is sent, so it cannot be sent twice', async () => {
      let respond!: (v: unknown) => void
      voiceApi.postVoiceMessage.mockReturnValue(
        new Observable((subscriber) => {
          respond = (v) => {
            subscriber.next(v)
            subscriber.complete()
          }
        })
      )
      const ref = makeComponent()
      await recordAndSend(ref)

      expect(ref.instance['isCreating']()).toBe(true)
      expect(ref.instance['canRecord']).toBe(false)
      expect(ref.instance['canStageFiles']).toBe(false)

      respond({ transcript: 't', path: 'p' })
      await flush()
      expect(ref.instance['isCreating']()).toBe(false)
    })

    it('on a case creation failure: nothing is posted, the error is the text flow one, the mic is released', async () => {
      api.createCase.mockReturnValue(throwError(() => ({ error: { message: 'Namespace is read-only' } })))
      const errorSpy = jest.spyOn(console, 'error').mockImplementation(() => undefined)
      const ref = makeComponent()
      await recordAndSend(ref)

      expect(voiceApi.postVoiceMessage).not.toHaveBeenCalled()
      expect(router.navigate).not.toHaveBeenCalled()
      expect(ref.instance['voice'].error()).toBe('Namespace is read-only')
      expect(ref.instance['pendingCaseId']()).toBeNull()
      expect(ref.instance['isCreating']()).toBe(false)
      expect(track.stop).toHaveBeenCalled()
      errorSpy.mockRestore()
    })

    it('on a voice post failure: keeps the created case, does not navigate, shows the case-chat message', async () => {
      voiceApi.postVoiceMessage.mockReturnValue(throwError(() => ({ status: 422 })))
      const ref = makeComponent()
      await recordAndSend(ref)

      expect(calls.filter((c) => c === 'create-case')).toHaveLength(1)
      expect(router.navigate).not.toHaveBeenCalled()
      expect(ref.instance['pendingCaseId']()).toBe('case-9')
      expect(ref.instance['voice'].error()).toBe(VOICE_ERRORS.noSpeech)
      expect(ref.instance['isCreating']()).toBe(false)
      expect(track.stop).toHaveBeenCalled()
    })

    it('a retry after a failed voice post reuses the created case', async () => {
      voiceApi.postVoiceMessage.mockReturnValueOnce(throwError(() => ({ status: 502 })))
      const ref = makeComponent()
      await recordAndSend(ref)
      expect(ref.instance['pendingCaseId']()).toBe('case-9')

      await recordAndSend(ref)

      expect(calls.filter((c) => c === 'create-case')).toHaveLength(1)
      expect(voiceApi.postVoiceMessage).toHaveBeenCalledTimes(2)
      expect(voiceApi.postVoiceMessage.mock.calls[1]![0]).toBe('case-9')
      expect(router.navigate).toHaveBeenCalledTimes(1)
    })

    it('a typed message after a failed voice post reuses the created case', async () => {
      voiceApi.postVoiceMessage.mockReturnValueOnce(throwError(() => ({ status: 502 })))
      const ref = makeComponent()
      await recordAndSend(ref)
      ref.instance['inputValue'].set('typed instead')

      await ref.instance['submit']()

      expect(calls.filter((c) => c === 'create-case')).toHaveLength(1)
      expect(api.addMessageCase).toHaveBeenCalledWith('case-9', { content: 'typed instead' })
      expect(router.navigate).toHaveBeenCalledTimes(1)
    })

    it('cancelling posts nothing and creates no case', async () => {
      const ref = makeComponent()
      await record(ref)

      ref.instance['cancelRecording']()

      expect(api.createCase).not.toHaveBeenCalled()
      expect(voiceApi.postVoiceMessage).not.toHaveBeenCalled()
      expect(router.navigate).not.toHaveBeenCalled()
      expect(track.stop).toHaveBeenCalled()
      expect(ref.instance['voice'].state()).toBe('idle')
      expect(ref.instance['isCreating']()).toBe(false)
    })

    it('is off while sending is: creating, uploading, recording', async () => {
      const ref = makeComponent()
      expect(ref.instance['canRecord']).toBe(true)

      ref.instance['isCreating'].set(true)
      expect(ref.instance['canRecord']).toBe(false)
      ref.instance['isCreating'].set(false)

      attachments(ref).isUploading.set(true)
      expect(ref.instance['canRecord']).toBe(false)
      attachments(ref).isUploading.set(false)

      await record(ref)
      expect(ref.instance['canRecord']).toBe(false)
      expect(ref.instance['canSend']).toBe(false)
      expect(ref.instance['canStageFiles']).toBe(false)
    })

    it('is off while files are staged, and while text is typed (the draft would be dropped on navigation)', () => {
      const ref = makeComponent()
      attachments(ref).addFiles([new File(['x'], 'a.pdf')])
      expect(ref.instance['canRecord']).toBe(false)
      attachments(ref).reset()
      expect(ref.instance['canRecord']).toBe(true)

      ref.instance['inputValue'].set('some draft')
      expect(ref.instance['canRecord']).toBe(false)
    })

    it('does not start while the composer is not usable', async () => {
      const ref = makeComponent()
      ref.instance['isCreating'].set(true)
      await record(ref)
      expect(ref.instance['voice'].state()).toBe('idle')
    })

    it('a namespace switch while recording releases the microphone and creates nothing', async () => {
      const ref = makeComponent()
      ref.instance.ngOnInit()
      await record(ref)

      queryParams$.next({ ns: 'ns-2' })

      expect(track.stop).toHaveBeenCalled()
      expect(ref.instance['voice'].state()).toBe('idle')
      expect(api.createCase).not.toHaveBeenCalled()
      expect(voiceApi.postVoiceMessage).not.toHaveBeenCalled()
      ref.destroy()
    })

    it('a namespace switch while the case is being created posts nothing and adds no case', async () => {
      const creation = new Subject<{ id: string }>()
      api.createCase.mockReturnValueOnce(creation)
      const ref = makeComponent()
      ref.instance.ngOnInit()
      await recordAndSend(ref)

      queryParams$.next({ ns: 'ns-2' })
      creation.next({ id: 'stale' })
      creation.complete()
      await flush()

      expect(voiceApi.postVoiceMessage).not.toHaveBeenCalled()
      expect(router.navigate).not.toHaveBeenCalled()
      expect(TestBed.inject(CaseStateService).addCase).not.toHaveBeenCalled()
      expect(ref.instance['pendingCaseId']()).toBeNull()
      expect(ref.instance['isCreating']()).toBe(false)
      ref.destroy()
    })

    it('releases the microphone when the composer is destroyed while recording', async () => {
      const ref = makeComponent()
      await record(ref)

      ref.destroy()

      expect(track.stop).toHaveBeenCalled()
      expect(api.createCase).not.toHaveBeenCalled()
    })

    describe('keys and footer while recording', () => {
      const keydown = (init: KeyboardEventInit = {}) => new KeyboardEvent('keydown', { cancelable: true, ...init })
      const shouldSend = () => TestBed.inject(USER_PREFERENCES_PORT).shouldSend as jest.Mock

      it('the send button of the footer sends the recording: it creates the case and posts the voice', async () => {
        const ref = makeComponent()
        await record(ref)
        const submit = jest.spyOn(ref.instance as never, 'submit')

        expect(ref.instance['canSendNow']).toBe(true)
        ref.instance['onSendClick']()
        await flush()

        expect(calls).toEqual(['create-case', 'post-voice'])
        expect(submit).not.toHaveBeenCalled()
        expect(api.addMessageCase).not.toHaveBeenCalled()
      })

      it('the cancel button of the footer discards the recording and creates nothing', async () => {
        const ref = makeComponent()
        await record(ref)

        ref.instance['cancelRecording']()

        expect(api.createCase).not.toHaveBeenCalled()
        expect(voiceApi.postVoiceMessage).not.toHaveBeenCalled()
        expect(ref.instance['voice'].state()).toBe('idle')
      })

      it('without a recording the send button sends the typed message, and only when there is one', async () => {
        const ref = makeComponent()
        expect(ref.instance['canSendNow']).toBe(false)

        ref.instance['inputValue'].set('typed')
        expect(ref.instance['canSendNow']).toBe(true)
        ref.instance['onSendClick']()
        await flush()

        expect(api.addMessageCase).toHaveBeenCalledWith('case-9', { content: 'typed' })
        expect(voiceApi.postVoiceMessage).not.toHaveBeenCalled()
      })

      it('Enter sends the recording when the preference says the key sends', async () => {
        shouldSend().mockReturnValue(true)
        const ref = makeComponent()
        await record(ref)
        const event = keydown({ key: 'Enter' })

        ref.instance['onRecordingKeydown'](event)
        await flush()

        expect(shouldSend()).toHaveBeenCalledWith(event)
        expect(event.defaultPrevented).toBe(true)
        expect(calls).toEqual(['create-case', 'post-voice'])
      })

      it('Enter does not send the recording when the preference says it is a new line (needs Ctrl/Cmd)', async () => {
        shouldSend().mockReturnValue(false)
        const ref = makeComponent()
        await record(ref)
        const event = keydown({ key: 'Enter' })

        ref.instance['onRecordingKeydown'](event)
        await flush()

        expect(event.defaultPrevented).toBe(false)
        expect(api.createCase).not.toHaveBeenCalled()
        expect(ref.instance['voice'].state()).toBe('recording')
      })

      it('Escape discards the recording', async () => {
        const ref = makeComponent()
        await record(ref)
        const event = keydown({ key: 'Escape' })

        ref.instance['onRecordingKeydown'](event)

        expect(event.defaultPrevented).toBe(true)
        expect(api.createCase).not.toHaveBeenCalled()
        expect(track.stop).toHaveBeenCalled()
        expect(ref.instance['voice'].state()).toBe('idle')
      })

      it('a second Enter while the recording is being sent does nothing: one case, one post', async () => {
        shouldSend().mockReturnValue(true)
        let respond!: (v: unknown) => void
        voiceApi.postVoiceMessage.mockReturnValue(
          new Observable((subscriber) => {
            respond = (v) => {
              subscriber.next(v)
              subscriber.complete()
            }
          })
        )
        const ref = makeComponent()
        await record(ref)

        ref.instance['onRecordingKeydown'](keydown({ key: 'Enter' }))
        await flush()
        expect(ref.instance['voice'].state()).toBe('sending')
        ref.instance['onRecordingKeydown'](keydown({ key: 'Enter' }))
        ref.instance['onRecordingKeydown'](keydown({ key: 'Escape' }))
        await flush()

        expect(api.createCase).toHaveBeenCalledTimes(1)
        expect(voiceApi.postVoiceMessage).toHaveBeenCalledTimes(1)
        expect(ref.instance['voice'].state()).toBe('sending')
        respond({ transcript: 't', path: 'p' })
        await flush()
      })

      it('a held-down Enter (key repeat) does not send', async () => {
        shouldSend().mockReturnValue(true)
        const ref = makeComponent()
        await record(ref)

        ref.instance['onRecordingKeydown'](keydown({ key: 'Enter', repeat: true }))
        await flush()

        expect(api.createCase).not.toHaveBeenCalled()
        expect(ref.instance['voice'].state()).toBe('recording')
      })

      it('keys do nothing when not recording: the textarea keeps its usual behaviour', () => {
        shouldSend().mockReturnValue(true)
        const ref = makeComponent()
        const enter = keydown({ key: 'Enter' })

        ref.instance['onRecordingKeydown'](enter)
        ref.instance['onRecordingKeydown'](keydown({ key: 'Escape' }))

        expect(enter.defaultPrevented).toBe(false)
        expect(api.createCase).not.toHaveBeenCalled()
      })

      it('the textarea Enter still sends the typed message (unchanged behaviour)', async () => {
        shouldSend().mockReturnValue(true)
        const ref = makeComponent()
        ref.instance['inputValue'].set('typed')
        const event = keydown({ key: 'Enter' })

        ref.instance['onKeydown'](event)
        await flush()

        expect(event.defaultPrevented).toBe(true)
        expect(api.addMessageCase).toHaveBeenCalledWith('case-9', { content: 'typed' })
        expect(voiceApi.postVoiceMessage).not.toHaveBeenCalled()
      })

      it('names the key of the recording hint after the Enter preference', () => {
        const ref = makeComponent()
        const preferences = TestBed.inject(USER_PREFERENCES_PORT) as unknown as {
          enterKeyBehavior: WritableSignal<string>
        }
        expect(ref.instance['sendKeyLabel']).toBe('Enter')
        preferences.enterKeyBehavior.set('newline')
        expect(ref.instance['sendKeyLabel']).toBe('Ctrl/Cmd+Enter')
      })
    })

    it('component-scoped: each composer gets its own recorder', () => {
      const a = makeComponent()
      const b = makeComponent()
      expect(a.instance['voice']).not.toBe(b.instance['voice'])
    })
  })
})
