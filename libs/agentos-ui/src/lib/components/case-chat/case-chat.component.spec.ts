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
        {
          provide: CaseStateService,
          useValue: { addCase: jest.fn(), updateCaseTitle: jest.fn(), updateCaseStatus: jest.fn() },
        },
        { provide: PromptStateService, useValue: { listEffective: jest.fn().mockReturnValue(of([])) } },
        {
          provide: USER_PREFERENCES_PORT,
          useValue: { shouldSend: jest.fn().mockReturnValue(false), composerHint: () => 'hint' },
        },
      ],
    })
  })

  afterEach(() => TestBed.resetTestingModule())

  it('does not refresh Files again when a reconnection replays tool and completion events', () => {
    const original = globalThis.EventSource
    const source = Object.assign(new EventTarget(), { close: jest.fn() })
    globalThis.EventSource = jest.fn(() => source) as unknown as typeof EventSource
    const log = jest.spyOn(console, 'log').mockImplementation(() => undefined)
    const ref = makeComponent()
    try {
      ref.instance['connectSse']()
      const emit = (type: string, id: string, fields: object = {}) =>
        source.dispatchEvent(new MessageEvent(type, { data: JSON.stringify({ type, id, ...fields }) }))

      emit('ToolResponseEvent', 'tool-1', { toolName: 'case-exchange__editFiles' })
      emit('AgentFinishedEvent', 'finished-1')
      expect(exchangeState.refreshCase).toHaveBeenCalledTimes(1)
      expect(exchangeState.refreshManifest).toHaveBeenCalledTimes(1)

      for (let reconnect = 0; reconnect < 3; reconnect++) {
        emit('ToolResponseEvent', 'tool-1', { toolName: 'case-exchange__editFiles' })
        emit('AgentFinishedEvent', 'finished-1')
      }
      expect(exchangeState.refreshCase).toHaveBeenCalledTimes(1)
      expect(exchangeState.refreshManifest).toHaveBeenCalledTimes(1)

      // Fresh activity still updates Files after the replay.
      emit('ToolResponseEvent', 'tool-2', { toolName: 'case-exchange__editFiles' })
      emit('AgentFinishedEvent', 'finished-2')
      expect(exchangeState.refreshCase).toHaveBeenCalledTimes(2)
      expect(exchangeState.refreshManifest).toHaveBeenCalledTimes(2)
    } finally {
      ref.destroy()
      globalThis.EventSource = original
      log.mockRestore()
    }
  })

  it('restores RUNNING after a transport error without replaying chunks, files or older statuses', () => {
    const original = globalThis.EventSource
    const source = Object.assign(new EventTarget(), {
      close: jest.fn(),
      onerror: null as ((event: Event) => void) | null,
    })
    globalThis.EventSource = jest.fn(() => source) as unknown as typeof EventSource
    const log = jest.spyOn(console, 'log').mockImplementation(() => undefined)
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => undefined)
    const ref = makeComponent()
    try {
      ref.instance['connectSse']()
      const emit = (type: string, id: string, fields: object = {}) =>
        source.dispatchEvent(new MessageEvent(type, { data: JSON.stringify({ type, id, ...fields }) }))
      emit('CaseStatusEvent', 'idle-1', { status: 'IDLE' })
      emit('CaseStatusEvent', 'running-1', { status: 'RUNNING' })
      emit('ToolResponseEvent', 'tool-1', { toolName: 'case-exchange__editFiles' })
      emit('TextChunkEvent', 'chunk-1', { chunk: 'Hello' })
      expect(ref.instance['isRunning']()).toBe(true)

      source.onerror!(new Event('error'))
      expect(ref.instance['isRunning']()).toBe(false)
      emit('CaseStatusEvent', 'idle-1', { status: 'IDLE' })
      expect(ref.instance['streamingText']()).toBe('Hello')
      emit('CaseStatusEvent', 'running-1', { status: 'RUNNING' })
      emit('ToolResponseEvent', 'tool-1', { toolName: 'case-exchange__editFiles' })
      emit('TextChunkEvent', 'chunk-1', { chunk: 'Hello' })
      expect(ref.instance['isRunning']()).toBe(true)
      expect(ref.instance['streamingText']()).toBe('Hello')
      expect(ref.instance['events']()).toHaveLength(4)
      expect(exchangeState.refreshCase).toHaveBeenCalledTimes(1)

      emit('AgentFinishedEvent', 'finished-1')
      emit('CaseStatusEvent', 'running-1', { status: 'RUNNING' })
      expect(ref.instance['isRunning']()).toBe(false)
      expect(exchangeState.refreshManifest).toHaveBeenCalledTimes(1)
    } finally {
      ref.destroy()
      globalThis.EventSource = original
      log.mockRestore()
      warn.mockRestore()
    }
  })

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

    expect(http.post).toHaveBeenCalledWith('/api/cases/c-1/messages', {
      content: 'hello',
      userId: 'default-user',
    })
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

/**
 * A notice is the only thing the user sees when a turn produces no message. It used to be
 * rendered only under the technical toggle, so a case that stopped for want of an agent
 * selection looked exactly like an agent that never answered.
 */
describe('CaseChatComponent — timeline notices', () => {
  function makeComponent(): ComponentRef<CaseChatComponent> {
    return createComponent(CaseChatComponent, { environmentInjector: TestBed.inject(EnvironmentInjector) })
  }

  function warn(message: string) {
    return { id: 'w-1', type: 'WarnEvent', message, timestamp: '2026-09-21T18:41:13Z' }
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        { provide: HttpClient, useValue: { post: jest.fn().mockReturnValue(of({})) } },
        { provide: Configuration, useValue: { basePath: '' } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParams: { case: 'c-1', ns: 'ns-1' } }, queryParams: of({}) },
        },
        {
          provide: ExchangeStateService,
          useValue: {
            uploadFile: jest.fn(),
            canWriteCase: signal(true),
            canWriteNamespace: signal(false),
            fileCount: signal(0),
            refreshManifest: jest.fn(),
            refreshCase: jest.fn(),
            refreshNamespace: jest.fn(),
          },
        },
        {
          provide: CaseStateService,
          useValue: { addCase: jest.fn(), updateCaseTitle: jest.fn(), updateCaseStatus: jest.fn() },
        },
        { provide: PromptStateService, useValue: { listEffective: jest.fn().mockReturnValue(of([])) } },
        {
          provide: USER_PREFERENCES_PORT,
          useValue: { shouldSend: jest.fn().mockReturnValue(false), composerHint: () => 'hint' },
        },
      ],
    })
  })

  afterEach(() => TestBed.resetTestingModule())

  it('shows a warning in the conversation even when technical details are hidden', () => {
    const ref = makeComponent()
    ref.setInput('showTechnicalOverride', false)
    ref.instance['events'].set([warn('No default agent configured for this namespace.')] as never)

    const notices = ref.instance['timeline']().filter((i: { kind: string }) => i.kind === 'notice')

    expect(notices).toHaveLength(1)
    expect(notices[0]).toMatchObject({
      notice: { severity: 'warning', detail: 'No default agent configured for this namespace.' },
    })
  })

  it('keeps an unrecognised event behind the technical toggle', () => {
    const ref = makeComponent()
    ref.setInput('showTechnicalOverride', false)
    ref.instance['events'].set([{ id: 'x-1', type: 'SomeInternalEvent' }] as never)

    expect(ref.instance['timeline']()).toHaveLength(0)

    ref.setInput('showTechnicalOverride', true)
    expect(ref.instance['timeline']()).toHaveLength(1)
  })
})
