import { HttpClient } from '@angular/common/http'
import { ComponentRef, createComponent, EnvironmentInjector, signal } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { ActivatedRoute } from '@angular/router'
import {
  ActorRoleEnum,
  AgentFinishedEvent,
  AgentRunningEvent,
  AnswerEvent,
  Case,
  Configuration,
  ExchangeFileEntryScopeEnum,
  MessageEvent as CaseMessageEvent,
  QuestionEvent,
  QuestionEventQuestionTypeEnum,
  ReasoningEffort,
  ToolRequestEvent,
  ToolResponseEvent,
  VoiceMessageControllerService,
  WarnEvent,
} from '@whoz-oss/agentos-api-client'
import { of, throwError } from 'rxjs'
import { CaseStateService } from '../../services/case-state.service'
import { VoiceCapabilityStateService } from '../../services/voice-capability-state.service'
import { VOICE_MEDIA_PORT } from '../voice-recorder/voice-media.port'
import { VoiceRecorderService } from '../voice-recorder/voice-recorder.service'
import { VOICE_ERRORS } from '../voice-recorder/voice-recorder.utils'
import { ExchangeStateService } from '../../services/exchange-state.service'
import { PromptStateService } from '../../services/prompt-state.service'
import { USER_PREFERENCES_PORT } from '../../services/user-preferences.service'
import { ComposerAttachmentsService } from '../composer-attachments/composer-attachments.service'
import { ComposerAutocompleteService } from '../composer-autocomplete/composer-autocomplete.service'
import { EFFORT_OPTIONS } from '../composer-autocomplete/effort-command'
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
  let showTechnical: ReturnType<typeof signal<boolean>>

  function makeComponent(): ComponentRef<CaseChatComponent> {
    const environmentInjector = TestBed.inject(EnvironmentInjector)
    return createComponent(CaseChatComponent, { environmentInjector })
  }

  function attachments(ref: ComponentRef<CaseChatComponent>): ComposerAttachmentsService {
    return ref.injector.get(ComposerAttachmentsService)
  }

  beforeEach(() => {
    calls = []
    showTechnical = signal(true)
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
          useValue: {
            cases: signal<import('@whoz-oss/agentos-api-client').Case[]>([]),
            addCase: jest.fn(),
            updateCaseTitle: jest.fn(),
            updateCaseStatus: jest.fn(),
            notifyAgentMessageEvent: jest.fn(),
            setReasoningEffort: jest.fn().mockReturnValue(of({})),
          },
        },
        { provide: PromptStateService, useValue: { listEffective: jest.fn().mockReturnValue(of([])) } },
        {
          provide: USER_PREFERENCES_PORT,
          useValue: { shouldSend: jest.fn().mockReturnValue(false), composerHint: () => 'hint', showTechnical },
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

    expect(http.post).toHaveBeenCalledWith('/api/cases/c-1/messages', { content: 'hello', userId: 'default-user' })
    expect(exchangeState.uploadFile).not.toHaveBeenCalled()
  })

  describe('/effort command', () => {
    const loadCase = (): void => {
      TestBed.inject(CaseStateService).cases.set([{ id: 'c-1', namespaceId: 'ns-1', status: 'IDLE' } as Case])
    }

    it('sets the case effort and sends nothing to the agent', async () => {
      loadCase()
      const ref = makeComponent()
      ref.instance['inputValue'].set('/effort high')

      await ref.instance['submit']()

      expect(TestBed.inject(CaseStateService).setReasoningEffort).toHaveBeenCalledWith('c-1', ReasoningEffort.HIGH)
      expect(http.post).not.toHaveBeenCalled()
      expect(ref.instance['inputValue']()).toBe('')
    })

    it('keeps the text and sends nothing when the case is not in the loaded list', async () => {
      const ref = makeComponent()
      ref.instance['inputValue'].set('/effort high')

      await ref.instance['submit']()

      expect(TestBed.inject(CaseStateService).setReasoningEffort).not.toHaveBeenCalled()
      expect(http.post).not.toHaveBeenCalled()
      expect(ref.instance['inputValue']()).toBe('/effort high')
    })

    it('opens the level picker on an unknown level instead of doing nothing', async () => {
      loadCase()
      const ref = makeComponent()
      ref.instance['inputValue'].set('/effort hihg')

      await ref.instance['submit']()

      expect(ref.injector.get(ComposerAutocompleteService).effortSuggestions()).toHaveLength(EFFORT_OPTIONS.length)
      expect(TestBed.inject(CaseStateService).setReasoningEffort).not.toHaveBeenCalled()
      expect(http.post).not.toHaveBeenCalled()
    })
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
    // isTerminal is now a computed derived from _sseStatus; drive it via the signal.
    ref.instance['_sseStatus'].set('KILLED')
    expect(ref.instance['canSend']).toBe(false)
  })

  describe('kill with confirmation', () => {
    it('clicking kill does not call the API until confirmed', () => {
      const ref = makeComponent()
      // Simulate clicking the kill button (sets confirmingKill to true, does NOT call API)
      ref.instance['confirmingKill'].set(true)
      expect(http.post).not.toHaveBeenCalled()
    })

    it('cancelling the kill dialog fires no request and closes the dialog', () => {
      const ref = makeComponent()
      ref.instance['confirmingKill'].set(true)
      // Simulate cancel (what the (cancelled) output does)
      ref.instance['confirmingKill'].set(false)
      expect(http.post).not.toHaveBeenCalled()
      expect(ref.instance['confirmingKill']()).toBe(false)
    })

    it('confirming kill POSTs to the kill endpoint and closes the dialog', () => {
      const ref = makeComponent()
      ref.instance['confirmingKill'].set(true)
      // Simulate confirming (what the (confirmed) output does)
      ref.instance['kill']()
      expect(http.post).toHaveBeenCalledWith('/api/cases/c-1/kill', {})
      expect(ref.instance['confirmingKill']()).toBe(false)
    })
  })

  describe('reopen', () => {
    let caseState: { addCase: jest.Mock; updateCaseTitle: jest.Mock; updateCaseStatus: jest.Mock }

    beforeEach(() => {
      caseState = TestBed.inject(CaseStateService) as typeof caseState
    })

    it('POSTs to the reopen endpoint, clears terminal state, and notifies CaseStateService', () => {
      const original = globalThis.EventSource
      const source = Object.assign(new EventTarget(), { close: jest.fn() })
      globalThis.EventSource = jest.fn(() => source) as unknown as typeof EventSource
      const ref = makeComponent()
      try {
        ref.instance['connectSse']()
        // Put the component in terminal state via _sseStatus (isTerminal is now a computed).
        ref.instance['_sseStatus'].set('KILLED')
        expect(ref.instance['isTerminal']()).toBe(true)

        ref.instance['reopen']()

        expect(http.post).toHaveBeenCalledWith('/api/cases/c-1/reopen', {})

        // http.post returns of({}) by default, so the next callback fires synchronously
        expect(caseState.updateCaseStatus).toHaveBeenCalledWith('c-1', 'IDLE')
        // reinitialise() resets _sseStatus to '', so isTerminal() becomes false.
        expect(ref.instance['isTerminal']()).toBe(false)
      } finally {
        ref.destroy()
        globalThis.EventSource = original
      }
    })

    describe('SSE replay regression — reopen after kill', () => {
      it('leaves isTerminal false and EventSource OPEN when KILLED is followed by IDLE in replay', () => {
        jest.useFakeTimers()
        const original = globalThis.EventSource
        const source = Object.assign(new EventTarget(), { close: jest.fn() })
        globalThis.EventSource = jest.fn(() => source) as unknown as typeof EventSource
        const ref = makeComponent()
        try {
          ref.instance['connectSse']()

          const emit = (type: string, id: string, fields: object = {}) =>
            source.dispatchEvent(new MessageEvent(type, { data: JSON.stringify({ type, id, ...fields }) }))

          // Replay: history contains KILLED then IDLE (written by reopen).
          emit('CaseStatusEvent', 'status-killed', { status: 'KILLED' })
          emit('CaseStatusEvent', 'status-idle', { status: 'IDLE' })

          // Before the debounce fires, isTerminal must already be false (computed is live).
          expect(ref.instance['isTerminal']()).toBe(false)

          // Advance past the debounce — the close must NOT fire because isTerminal() is false.
          jest.advanceTimersByTime(400)

          expect(source.close).not.toHaveBeenCalled()
          expect(ref.instance['eventSource']).not.toBeNull()
        } finally {
          ref.destroy()
          globalThis.EventSource = original
          jest.useRealTimers()
        }
      })

      it('closes the stream once the debounce elapses when a lone KILLED has no following IDLE', () => {
        jest.useFakeTimers()
        const original = globalThis.EventSource
        const source = Object.assign(new EventTarget(), { close: jest.fn() })
        globalThis.EventSource = jest.fn(() => source) as unknown as typeof EventSource
        const ref = makeComponent()
        try {
          ref.instance['connectSse']()

          const emit = (type: string, id: string, fields: object = {}) =>
            source.dispatchEvent(new MessageEvent(type, { data: JSON.stringify({ type, id, ...fields }) }))

          emit('CaseStatusEvent', 'status-killed', { status: 'KILLED' })

          // Before debounce: stream still open.
          expect(source.close).not.toHaveBeenCalled()

          jest.advanceTimersByTime(400)

          // After debounce: isTerminal() is true, so the stream must have been closed.
          expect(source.close).toHaveBeenCalledTimes(1)
          expect(ref.instance['eventSource']).toBeNull()
        } finally {
          ref.destroy()
          globalThis.EventSource = original
          jest.useRealTimers()
        }
      })

      it('superseded KILLED produces no terminal notice; only the latest CaseStatusEvent does', () => {
        const ref = makeComponent()
        try {
          const emit = (type: string, id: string, fields: object = {}) =>
            source.dispatchEvent(new MessageEvent(type, { data: JSON.stringify({ type, id, ...fields }) }))

          const original = globalThis.EventSource
          const source = Object.assign(new EventTarget(), { close: jest.fn() })
          globalThis.EventSource = jest.fn(() => source) as unknown as typeof EventSource
          ref.instance['connectSse']()
          showTechnical.set(true)

          // Replay: KILLED superseded by IDLE
          ref.instance['events'].set([
            {
              type: 'CaseStatusEvent',
              id: 'status-killed',
              caseId: 'c-1',
              namespaceId: 'ns-1',
              timestamp: '',
              metadata: { id: 'status-killed', created: '', modified: '', removed: false },
              status: 'KILLED',
            } as import('@whoz-oss/agentos-api-client').CaseStatusEvent,
            {
              type: 'CaseStatusEvent',
              id: 'status-idle',
              caseId: 'c-1',
              namespaceId: 'ns-1',
              timestamp: '',
              metadata: { id: 'status-idle', created: '', modified: '', removed: false },
              status: 'IDLE',
            } as import('@whoz-oss/agentos-api-client').CaseStatusEvent,
          ])
          ref.instance['_sseStatus'].set('IDLE')

          const timeline = ref.instance['timeline']()
          const terminalNotices = timeline.filter(
            (item) => item.kind === 'notice' && item.notice.severity === 'terminal'
          )
          // The superseded KILLED must NOT produce a terminal notice.
          expect(terminalNotices).toHaveLength(0)

          // Now set only the KILLED event as the sole CaseStatusEvent — it IS the latest.
          ref.instance['events'].set([
            {
              type: 'CaseStatusEvent',
              id: 'status-killed',
              caseId: 'c-1',
              namespaceId: 'ns-1',
              timestamp: '',
              metadata: { id: 'status-killed', created: '', modified: '', removed: false },
              status: 'KILLED',
            } as import('@whoz-oss/agentos-api-client').CaseStatusEvent,
          ])
          ref.instance['_sseStatus'].set('KILLED')

          const timeline2 = ref.instance['timeline']()
          const terminalNotices2 = timeline2.filter(
            (item) => item.kind === 'notice' && item.notice.severity === 'terminal'
          )
          expect(terminalNotices2).toHaveLength(1)

          globalThis.EventSource = original
        } finally {
          ref.destroy()
        }
      })
    })
  })

  it('replaces the generic delegate tool card when its real content-wrapped output contains delegation JSON', () => {
    const ref = makeComponent()
    const toolRequestId = 'tool-request-1'
    const output = JSON.stringify([
      {
        delegationId: 'delegation-1',
        toolRequestId,
        subCaseId: 'sub-case-1',
        agentName: 'Research',
        success: true,
        result: '**Done**',
      },
    ])
    const request: ToolRequestEvent = {
      id: 'request-event-id',
      type: 'ToolRequestEvent',
      caseId: 'c-1',
      namespaceId: 'ns-1',
      timestamp: '2026-01-01T00:00:00Z',
      metadata: { id: 'request-event-id', created: '', modified: '', removed: false },
      toolName: 'DELEGATE__delegate',
      toolRequestId,
      args: '{"delegations":[]}',
    }
    const response: ToolResponseEvent = {
      id: 'response-event-id',
      type: 'ToolResponseEvent',
      caseId: 'c-1',
      namespaceId: 'ns-1',
      timestamp: '2026-01-01T00:00:01Z',
      metadata: { id: 'response-event-id', created: '', modified: '', removed: false },
      toolName: 'DELEGATE__delegate',
      toolRequestId,
      output: { content: output },
      success: true,
      images: [],
      toolMetadata: {},
    }

    // This is the exact source used by the generic tool-card OUTPUT block. Compare
    // semantically because structured tool output is pretty-printed for display.
    const extractedOutput = ref.instance['extractToolOutput']({
      requestId: toolRequestId,
      toolName: request.toolName,
      args: request.args,
      response,
    })
    expect(extractedOutput).not.toBeNull()
    expect(JSON.parse(extractedOutput!)).toEqual(JSON.parse(output))

    ref.instance['events'].set([request, response])

    const timeline = ref.instance['timeline']()
    expect(timeline).toHaveLength(1)
    expect(timeline[0]).toEqual(
      expect.objectContaining({
        kind: 'delegation',
        delegation: expect.objectContaining({
          delegationId: 'delegation-1',
          toolRequestId,
          subCaseId: 'sub-case-1',
        }),
      })
    )
    expect(timeline.some((item) => item.kind === 'tool')).toBe(false)
  })

  describe('question timeline items', () => {
    const metadata = { id: '', created: '', modified: '', removed: false }
    const question = (userId?: string): QuestionEvent => ({
      id: 'q-1',
      type: 'QuestionEvent',
      caseId: 'c-1',
      namespaceId: 'ns-1',
      timestamp: '2026-01-01T00:00:00Z',
      metadata,
      agentId: 'agent-1',
      agentName: 'Agent',
      question: 'Which color?',
      questionType: QuestionEventQuestionTypeEnum.FREE_TEXT,
      userId,
    })
    const answer = (id: string, actorId: string, text: string): AnswerEvent => ({
      id,
      type: 'AnswerEvent',
      caseId: 'c-1',
      namespaceId: 'ns-1',
      timestamp: '2026-01-01T00:00:01Z',
      metadata,
      questionId: 'q-1',
      answer: text,
      actor: { id: actorId, role: ActorRoleEnum.USER, displayName: 'Someone' },
    })

    it('carries the matching answer on the question item', () => {
      const ref = makeComponent()
      ref.instance['events'].set([question(), answer('a-1', 'u-1', 'blue')])

      const timeline = ref.instance['timeline']()
      expect(timeline).toHaveLength(1)
      const item = timeline[0]
      expect(item).toEqual(expect.objectContaining({ kind: 'question' }))
      expect(item?.kind === 'question' && item.answer?.answer).toBe('blue')
    })

    it('leaves answer undefined while the question is unanswered', () => {
      const ref = makeComponent()
      ref.instance['events'].set([question()])

      const item = ref.instance['timeline']()[0]
      expect(item?.kind).toBe('question')
      expect(item?.kind === 'question' && item.answer).toBeUndefined()
    })

    it('keeps a question addressed to A pending on an answer from B, then picks the answer from A', () => {
      const ref = makeComponent()
      const fromB = answer('a-b', 'user-b', 'from B')
      ref.instance['events'].set([question('user-a'), fromB])

      let item = ref.instance['timeline']()[0]
      expect(item?.kind === 'question' && item.answer).toBeUndefined()

      const fromA = answer('a-a', 'user-a', 'from A')
      ref.instance['events'].set([question('user-a'), fromB, fromA])

      item = ref.instance['timeline']()[0]
      expect(item?.kind === 'question' && item.answer).toBe(fromA)
    })

    it('labels the answer with the respondent name, falling back to You for an unnamed user', () => {
      const ref = makeComponent()
      const named = answer('a-1', 'u-1', 'blue')
      const unnamed = { ...named, actor: { id: 'u-1', role: ActorRoleEnum.USER } }
      expect(ref.instance['answerLabel'](named)).toBe('Someone')
      expect(ref.instance['answerLabel'](unnamed)).toBe('You')
    })

    it('only treats an unanswered, non-OAuth question as pending (focus target)', () => {
      const ref = makeComponent()
      ref.instance['events'].set([question()])
      expect(ref.instance['pendingQuestionId']()).toBe('q-1')

      ref.instance['events'].set([question(), answer('a-1', 'u-1', 'blue')])
      expect(ref.instance['pendingQuestionId']()).toBeNull()

      ref.instance['events'].set([{ ...question(), questionType: QuestionEventQuestionTypeEnum.OAUTH_AUTHORIZE }])
      expect(ref.instance['pendingQuestionId']()).toBeNull()
    })

    it('does not post a second answer while one is in flight', () => {
      const ref = makeComponent()
      ref.instance['onQuestionAnswered'](question(), 'blue')
      ref.instance['onQuestionAnswered'](question(), 'blue')

      expect(http.post).toHaveBeenCalledTimes(1)
      expect(http.post).toHaveBeenCalledWith('/api/cases/c-1/messages', { content: 'blue', answerToEventId: 'q-1' })
    })

    it('does not render the AnswerEvent as its own timeline item, even in technical mode', () => {
      const ref = makeComponent()
      showTechnical.set(true)
      ref.instance['events'].set([question(), answer('a-1', 'u-1', 'blue')])

      expect(ref.instance['timeline']().map((item) => item.kind)).toEqual(['question'])
    })
  })

  describe('technical logs', () => {
    const metadata = { id: '', created: '', modified: '', removed: false }
    const running = (id: string, timestamp: string): AgentRunningEvent => ({
      id,
      type: 'AgentRunningEvent',
      caseId: 'c-1',
      namespaceId: 'ns-1',
      timestamp,
      metadata,
      agentId: 'agent-1',
      agentName: 'dev',
    })

    it('shows the technical log lines with the preference as stored by default', () => {
      const ref = makeComponent()
      ref.instance['events'].set([running('r-1', '2026-01-01T10:20:30Z')])

      expect(ref.instance['timeline']().map((item) => item.kind)).toEqual(['technical'])
    })

    it('hides the technical log lines when the preference is off', () => {
      showTechnical.set(false)
      const ref = makeComponent()
      ref.instance['events'].set([running('r-1', '2026-01-01T10:20:30Z')])

      expect(ref.instance['timeline']()).toEqual([])
    })

    it('reacts to the preference being toggled', () => {
      const ref = makeComponent()
      ref.instance['events'].set([running('r-1', '2026-01-01T10:20:30Z')])
      showTechnical.set(false)
      expect(ref.instance['timeline']()).toEqual([])

      showTechnical.set(true)
      expect(ref.instance['timeline']()).toHaveLength(1)
    })

    it('still shows a warning when the preference is off: it is not a diagnostic', () => {
      showTechnical.set(false)
      const ref = makeComponent()
      const warning: WarnEvent = {
        id: 'w-1',
        type: 'WarnEvent',
        caseId: 'c-1',
        namespaceId: 'ns-1',
        timestamp: '2026-01-01T10:20:31Z',
        metadata,
        message: 'Integration JIRA could not be loaded for this message.',
      }
      ref.instance['events'].set([running('r-1', '2026-01-01T10:20:30Z'), warning])

      expect(ref.instance['timeline']()).toEqual([
        {
          kind: 'notice',
          eventId: 'w-1',
          notice: {
            severity: 'warning',
            title: 'Warning',
            detail: 'Integration JIRA could not be loaded for this message.',
          },
        },
      ])
    })

    const warnEvent = (id: string, message: string): WarnEvent => ({
      id,
      type: 'WarnEvent',
      caseId: 'c-1',
      namespaceId: 'ns-1',
      timestamp: '2026-01-01T10:20:31Z',
      metadata,
      message,
    })
    const userMessage = (id: string): CaseMessageEvent => ({
      id,
      type: 'MessageEvent',
      caseId: 'c-1',
      namespaceId: 'ns-1',
      timestamp: '2026-01-01T10:20:32Z',
      metadata,
      actor: { id: 'u-1', role: ActorRoleEnum.USER, displayName: 'You' },
      content: [],
    })
    const notices = (ref: ComponentRef<CaseChatComponent>): string[] =>
      ref.instance['timeline']()
        .filter((item) => item.kind === 'notice')
        .map((item) => (item.kind === 'notice' ? (item.notice.detail ?? '') : ''))

    it('shows one warning per turn, however many agent runs repeat it', () => {
      // Every agent run of a message warns, delegates included, and a resume warns again.
      const ref = makeComponent()
      ref.instance['events'].set([
        warnEvent('w-1', 'Integration JIRA failed'),
        warnEvent('w-2', 'Integration JIRA failed'),
      ])

      expect(notices(ref)).toEqual(['Integration JIRA failed'])
    })

    it('warns again once the user has sent another message', () => {
      const ref = makeComponent()
      ref.instance['events'].set([
        warnEvent('w-1', 'Integration JIRA failed'),
        userMessage('m-1'),
        warnEvent('w-2', 'Integration JIRA failed'),
      ])

      expect(notices(ref)).toEqual(['Integration JIRA failed', 'Integration JIRA failed'])
    })

    it('keeps a second warning of the same turn when it says something else', () => {
      const ref = makeComponent()
      ref.instance['events'].set([warnEvent('w-1', 'Integration JIRA failed'), warnEvent('w-2', 'Agent dev not found')])

      expect(notices(ref)).toEqual(['Integration JIRA failed', 'Agent dev not found'])
    })

    it('carries the event timestamp on the technical item', () => {
      const ref = makeComponent()
      ref.instance['events'].set([running('r-1', '2026-01-01T10:20:30Z')])

      const item = ref.instance['timeline']()[0]
      expect(item?.kind === 'technical' && item.item).toEqual({
        label: 'Agent running: dev',
        timestamp: '2026-01-01T10:20:30Z',
      })
    })

    it('formats the timestamp as a date and a time, and falls back to nothing when unparsable', () => {
      const ref = makeComponent()
      const formatted: string = ref.instance['formatMessageDateTime']('2026-06-15T12:20:30Z')

      expect(formatted).toMatch(/2026/)
      expect(formatted).toMatch(/\d{2}:\d{2}:\d{2}/)
      expect(ref.instance['formatMessageDateTime']('not a date')).toBe('')
    })
  })

  describe('iteration limit', () => {
    const metadata = { id: '', created: '', modified: '', removed: false }
    const finished = (id: string, maxIterationsReached?: number): AgentFinishedEvent => ({
      id,
      type: 'AgentFinishedEvent',
      caseId: 'c-1',
      namespaceId: 'ns-1',
      timestamp: '2026-01-01T00:00:00Z',
      metadata,
      agentId: 'agent-1',
      agentName: 'dev',
      maxIterationsReached,
    })
    const userMessage = (id: string): CaseMessageEvent => ({
      id,
      type: 'MessageEvent',
      caseId: 'c-1',
      namespaceId: 'ns-1',
      timestamp: '2026-01-01T00:00:01Z',
      metadata,
      actor: { id: 'u-1', role: ActorRoleEnum.USER, displayName: 'You' },
      content: [],
    })

    it('shows a run stopped at its limit outside technical mode, unlike a run that completed', () => {
      showTechnical.set(false)
      const ref = makeComponent()
      ref.instance['events'].set([finished('f-1', 20), finished('f-2')])

      const notices = ref.instance['timeline']().filter((item) => item.kind === 'notice')
      expect(notices).toHaveLength(1)
      expect(notices[0]).toEqual(
        expect.objectContaining({
          eventId: 'f-1',
          notice: expect.objectContaining({ title: 'Iteration limit reached' }),
        })
      )
      expect(notices[0]?.kind === 'notice' && notices[0].notice.detail).toContain('dev stopped after 20 iterations')
    })

    it('offers to continue the latest stop until the user writes again', () => {
      const ref = makeComponent()
      ref.instance['events'].set([finished('f-1', 20)])
      expect(ref.instance['continuableStopId']()).toBe('f-1')

      ref.instance['events'].set([finished('f-1', 20), userMessage('m-1')])
      expect(ref.instance['continuableStopId']()).toBeNull()

      ref.instance['events'].set([finished('f-1', 20), userMessage('m-1'), finished('f-2')])
      expect(ref.instance['continuableStopId']()).toBeNull()
    })

    it('treats a limit of 0 as reached', () => {
      const ref = makeComponent()
      ref.instance['events'].set([finished('f-0', 0)])

      expect(ref.instance['timeline']().map((item) => item.kind)).toEqual(['notice'])
      expect(ref.instance['continuableStopId']()).toBe('f-0')
    })

    it('does not continue while a message with attachments is being sent', () => {
      const ref = makeComponent()
      ref.instance['submitting'] = true
      ref.instance['continueAfterLimit']()

      expect(http.post).not.toHaveBeenCalled()
    })

    it('continues by sending a message, once, and not while the agent runs', () => {
      const ref = makeComponent()
      ref.instance['continueAfterLimit']()
      ref.instance['continueAfterLimit']()

      expect(http.post).toHaveBeenCalledTimes(1)
      expect(http.post).toHaveBeenCalledWith('/api/cases/c-1/messages', { content: 'Continue', userId: 'default-user' })
    })
  })
  /**
   * The turn figures are attached to the answer the turn produced, which is where a reader looks
   * for them. The arithmetic itself lives in turn-metrics.spec; this covers the wiring.
   */
  describe('turn metrics on the timeline', () => {
    const metadata = { id: '', created: '', modified: '', removed: false }

    const agentMessage = (id: string, timestamp: string) =>
      ({
        id,
        type: 'MessageEvent',
        caseId: 'c-1',
        namespaceId: 'ns-1',
        timestamp,
        metadata,
        actor: { role: 'AGENT', id: 'agent-1', name: 'Agent' },
        content: [{ type: 'TEXT', content: 'done' }],
      }) as never

    const runningEvent = (timestamp: string) =>
      ({
        id: `r-${timestamp}`,
        type: 'AgentRunningEvent',
        caseId: 'c-1',
        namespaceId: 'ns-1',
        timestamp,
        metadata,
      }) as never

    const finishedEvent = (id: string, timestamp: string, cost?: number) =>
      ({
        id,
        type: 'AgentFinishedEvent',
        caseId: 'c-1',
        namespaceId: 'ns-1',
        timestamp,
        metadata,
        agentId: 'agent-1',
        agentName: 'Agent',
        llmUsage: {
          inputTokens: 900,
          outputTokens: 350,
          cacheReadTokens: 0,
          cacheWriteTokens: 0,
          totalTokens: 1250,
          estimatedCostUsd: cost,
        },
      }) as never

    it('carries the figures on the agent message the turn closed', () => {
      const ref = makeComponent()
      ref.instance['events'].set([
        runningEvent('2026-01-01T00:00:00.000Z'),
        agentMessage('m-1', '2026-01-01T00:00:02.000Z'),
        finishedEvent('f-1', '2026-01-01T00:00:03.000Z', 0.42),
      ])

      const message = ref.instance['timeline']().find((item) => item.kind === 'message')!
      expect(message.kind).toBe('message')
      const metrics = (message as { metrics?: { totalTokens: number; cost?: number; durationMs?: number } }).metrics!
      expect(metrics.totalTokens).toBe(1250)
      expect(metrics.cost).toBe(0.42)
      expect(metrics.durationMs).toBe(3000)
    })

    it('leaves a user message untouched', () => {
      const ref = makeComponent()
      const userMessage = {
        id: 'm-0',
        type: 'MessageEvent',
        caseId: 'c-1',
        namespaceId: 'ns-1',
        timestamp: '2026-01-01T00:00:00.000Z',
        metadata,
        actor: { role: 'USER', id: 'u-1', name: 'User' },
        content: [{ type: 'TEXT', content: 'go' }],
      } as never
      ref.instance['events'].set([
        userMessage,
        runningEvent('2026-01-01T00:00:01.000Z'),
        finishedEvent('f-1', '2026-01-01T00:00:02.000Z', 0.1),
      ])

      const messages = ref.instance['timeline']().filter((item) => item.kind === 'message')
      expect(messages).toHaveLength(1)
      // The turn produced no answer, so its figures have no bubble to sit under.
      expect((messages[0] as { metrics?: unknown }).metrics).toBeUndefined()
    })

    it('adds nothing when the turn reported no usage', () => {
      const ref = makeComponent()
      ref.instance['events'].set([
        runningEvent('2026-01-01T00:00:00.000Z'),
        agentMessage('m-1', '2026-01-01T00:00:02.000Z'),
        {
          id: 'f-1',
          type: 'AgentFinishedEvent',
          caseId: 'c-1',
          namespaceId: 'ns-1',
          timestamp: '2026-01-01T00:00:03.000Z',
          metadata,
          agentId: 'agent-1',
          agentName: 'Agent',
        } as never,
      ])

      const message = ref.instance['timeline']().find((item) => item.kind === 'message')!
      expect((message as { metrics?: unknown }).metrics).toBeUndefined()
    })

    it('gives each turn its own figures instead of restating the last one', () => {
      const ref = makeComponent()
      ref.instance['events'].set([
        runningEvent('2026-01-01T00:00:00.000Z'),
        agentMessage('m-1', '2026-01-01T00:00:01.000Z'),
        finishedEvent('f-1', '2026-01-01T00:00:02.000Z', 0.1),
        runningEvent('2026-01-01T00:01:00.000Z'),
        agentMessage('m-2', '2026-01-01T00:01:01.000Z'),
        finishedEvent('f-2', '2026-01-01T00:01:02.000Z', 0.25),
      ])

      const messages = ref.instance['timeline']().filter((item) => item.kind === 'message') as Array<{
        event: { id: string }
        metrics?: { cost?: number; cumulativeCost?: number }
      }>
      expect(messages.map((m) => m.metrics?.cost)).toEqual([0.1, 0.25])
      expect(messages.map((m) => m.metrics?.cumulativeCost)).toEqual([0.1, 0.35])
    })
  })

  /**
   * Voice messages. The template is not rendered here (like the rest of this spec): the composer wiring is
   * exercised through the real component-scoped VoiceRecorderService on a fake media port, and the timeline
   * through the events the component ingests.
   */
  describe('voice messages', () => {
    const metadata = { id: '', created: '', modified: '', removed: false }
    const track = { stop: jest.fn() }
    let voiceApi: { postVoiceMessage: jest.Mock; capabilityVoiceMessage: jest.Mock }
    let recorder: {
      state: string
      ondataavailable: ((event: { data: Blob }) => void) | null
      onstop: (() => void) | null
      onerror: (() => void) | null
      start: jest.Mock
      stop: jest.Mock
    }

    const flush = async () => {
      for (let i = 0; i < 20; i++) await Promise.resolve()
    }

    beforeEach(() => {
      track.stop.mockClear()
      voiceApi = {
        postVoiceMessage: jest.fn().mockReturnValue(of({ transcript: 'hello', path: 'voice-messages/v.webm' })),
        capabilityVoiceMessage: jest.fn().mockReturnValue(of({ available: true })),
      }
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
          recorder = r
          return r as unknown as MediaRecorder
        },
      }
      TestBed.configureTestingModule({
        providers: [
          { provide: VOICE_MEDIA_PORT, useValue: media },
          { provide: VoiceMessageControllerService, useValue: voiceApi },
        ],
      })
    })

    describe('mic availability', () => {
      it('is hidden until the capability endpoint says available: true', () => {
        const capability = TestBed.inject(VoiceCapabilityStateService)
        expect(capability.available()).toBe(false)

        capability.load()

        expect(capability.available()).toBe(true)
      })

      it('stays hidden when the capability is off or the call fails', () => {
        voiceApi.capabilityVoiceMessage.mockReturnValue(of({ available: false }))
        const capability = TestBed.inject(VoiceCapabilityStateService)
        capability.load()
        expect(capability.available()).toBe(false)
      })

      it('asks for the capability when the chat opens', () => {
        const original = globalThis.EventSource
        globalThis.EventSource = jest.fn(() =>
          Object.assign(new EventTarget(), { close: jest.fn() })
        ) as unknown as typeof EventSource
        const log = jest.spyOn(console, 'log').mockImplementation(() => undefined)
        const ref = makeComponent()
        try {
          ref.instance.ngOnInit()
          expect(voiceApi.capabilityVoiceMessage).toHaveBeenCalledTimes(1)
        } finally {
          ref.destroy()
          globalThis.EventSource = original
          log.mockRestore()
        }
      })
    })

    describe('canRecord', () => {
      it('is true on an idle, open case', () => {
        expect(makeComponent().instance['canRecord']).toBe(true)
      })

      it('is false while the agent is running', () => {
        const ref = makeComponent()
        ref.instance['isRunning'].set(true)
        expect(ref.instance['canRecord']).toBe(false)
      })

      it('is false on a terminal case', () => {
        const ref = makeComponent()
        ref.instance['_sseStatus'].set('KILLED')
        expect(ref.instance['canRecord']).toBe(false)
      })

      it('is false while attachments upload or a submit is in flight', () => {
        const ref = makeComponent()
        attachments(ref).isUploading.set(true)
        expect(ref.instance['canRecord']).toBe(false)
        attachments(ref).isUploading.set(false)
        ref.instance['submitting'] = true
        expect(ref.instance['canRecord']).toBe(false)
      })

      it('is false while recording, and a recording also blocks send and file staging', async () => {
        const ref = makeComponent()
        ref.instance['inputValue'].set('typed text')
        expect(ref.instance['canSend']).toBe(true)

        ref.instance['startRecording']()
        await flush()

        expect(ref.instance['canRecord']).toBe(false)
        expect(ref.instance['canSend']).toBe(false)
        expect(ref.instance['canStageFiles']()).toBe(false)
      })
    })

    describe('recording a voice message', () => {
      it('posts the recording to the current case and marks the case running, like a normal send', async () => {
        const ref = makeComponent()
        ref.instance['startRecording']()
        await flush()
        expect(ref.instance['voice'].state()).toBe('recording')
        expect(ref.instance['isRunning']()).toBe(false)

        ref.instance['sendRecording']()
        await flush()

        expect(voiceApi.postVoiceMessage).toHaveBeenCalledTimes(1)
        expect(voiceApi.postVoiceMessage.mock.calls[0]![0]).toBe('c-1')
        expect(ref.instance['isRunning']()).toBe(true)
        expect(ref.instance['voice'].state()).toBe('idle')
        expect(track.stop).toHaveBeenCalled()
      })

      it('shows the case as running while the recording is uploaded and transcribed', async () => {
        const ref = makeComponent()
        ref.instance['streamingText'].set('stale')
        ref.instance['startRecording']()
        await flush()

        let runningDuringPost = false
        voiceApi.postVoiceMessage.mockImplementation(() => {
          runningDuringPost = ref.instance['isRunning']()
          return of({ transcript: 't', path: 'p' })
        })
        ref.instance['sendRecording']()
        await flush()

        expect(runningDuringPost).toBe(true)
        expect(ref.instance['streamingText']()).toBe('')
      })

      it('puts the case back to idle and shows the error when the post fails, the composer otherwise untouched', async () => {
        voiceApi.postVoiceMessage.mockReturnValue(throwError(() => ({ status: 422 })))
        const ref = makeComponent()
        ref.instance['inputValue'].set('draft')
        ref.instance['startRecording']()
        await flush()

        ref.instance['sendRecording']()
        await flush()

        expect(ref.instance['isRunning']()).toBe(false)
        expect(ref.instance['voice'].error()).toBe(VOICE_ERRORS.noSpeech)
        expect(ref.instance['inputValue']()).toBe('draft')
        expect(track.stop).toHaveBeenCalled()
      })

      it('cancelling discards the recording and does not post', async () => {
        const ref = makeComponent()
        ref.instance['startRecording']()
        await flush()

        ref.instance['cancelRecording']()

        expect(voiceApi.postVoiceMessage).not.toHaveBeenCalled()
        expect(track.stop).toHaveBeenCalled()
        expect(ref.instance['voice'].state()).toBe('idle')
        expect(ref.instance['isRunning']()).toBe(false)
      })

      it('does not start while the composer is not usable', async () => {
        const ref = makeComponent()
        ref.instance['isRunning'].set(true)
        ref.instance['startRecording']()
        await flush()
        expect(ref.instance['voice'].state()).toBe('idle')
      })
    })

    describe('releasing the microphone', () => {
      it('on a case switch (reinitialise)', async () => {
        const original = globalThis.EventSource
        globalThis.EventSource = jest.fn(() =>
          Object.assign(new EventTarget(), { close: jest.fn() })
        ) as unknown as typeof EventSource
        const log = jest.spyOn(console, 'log').mockImplementation(() => undefined)
        const ref = makeComponent()
        try {
          ref.instance['startRecording']()
          await flush()
          expect(track.stop).not.toHaveBeenCalled()

          ref.instance['reinitialise']()

          expect(track.stop).toHaveBeenCalled()
          expect(ref.instance['voice'].state()).toBe('idle')
        } finally {
          ref.destroy()
          globalThis.EventSource = original
          log.mockRestore()
        }
      })

      it('when the component is destroyed', async () => {
        const ref = makeComponent()
        ref.instance['startRecording']()
        await flush()

        ref.destroy()

        expect(track.stop).toHaveBeenCalled()
      })
    })

    describe('voice message in the timeline', () => {
      const audio = { type: 'Audio', path: 'voice-messages/v.webm', mimeType: 'audio/webm', durationMs: 4_000 }
      const userMessage = (id: string, content: unknown[]): CaseMessageEvent =>
        ({
          id,
          type: 'MessageEvent',
          caseId: 'c-1',
          namespaceId: 'ns-1',
          timestamp: '2026-01-01T10:00:00Z',
          metadata,
          actor: { id: 'u-1', role: ActorRoleEnum.USER, displayName: 'You' },
          content,
        }) as unknown as CaseMessageEvent

      it('finds the Audio part of a voice message, and none on a text message', () => {
        const ref = makeComponent()
        const voice = userMessage('m-1', [audio, { type: 'Text', content: 'hello there' }])
        const plain = userMessage('m-2', [{ type: 'Text', content: 'just text' }])

        expect(ref.instance['audioOf'](voice)).toEqual(audio)
        expect(ref.instance['audioOf'](plain)).toBeUndefined()
      })

      it('keeps the Audio part out of the text: the transcript is what is shown and copied', () => {
        const ref = makeComponent()
        const voice = userMessage('m-1', [audio, { type: 'Text', content: 'hello there' }])

        expect(ref.instance['extractText'](voice)).toBe('hello there')
        ref.instance['events'].set([voice])
        const item = ref.instance['timeline']()[0]!
        expect(item.kind).toBe('message')
        expect(item.kind === 'message' && ref.instance['messageText'](item)).toBe('hello there')
      })

      it('renders the transcript of a voice message through the usual markdown path', () => {
        const original = globalThis.EventSource
        const source = Object.assign(new EventTarget(), { close: jest.fn() })
        globalThis.EventSource = jest.fn(() => source) as unknown as typeof EventSource
        const log = jest.spyOn(console, 'log').mockImplementation(() => undefined)
        const ref = makeComponent()
        try {
          ref.instance['connectSse']()
          const voice = userMessage('m-1', [audio, { type: 'Text', content: 'hello **there**' }])
          source.dispatchEvent(new MessageEvent('MessageEvent', { data: JSON.stringify(voice) }))

          expect(ref.instance['messageHtmlCache'].has('m-1')).toBe(true)
          const item = ref.instance['timeline']()[0]!
          expect(item.kind === 'message' && ref.instance['audioOf'](item.event)).toEqual(audio)
        } finally {
          ref.destroy()
          globalThis.EventSource = original
          log.mockRestore()
        }
      })

      it('leaves a plain text message unchanged', () => {
        const ref = makeComponent()
        const plain = userMessage('m-2', [{ type: 'Text', content: 'just text' }])
        ref.instance['events'].set([plain])

        const item = ref.instance['timeline']()[0]!
        expect(item.kind === 'message' && ref.instance['audioOf'](item.event)).toBeFalsy()
        expect(item.kind === 'message' && ref.instance['messageText'](item)).toBe('just text')
      })
    })

    it('component-scoped: each chat gets its own recorder', () => {
      const a = makeComponent()
      const b = makeComponent()
      expect(a.injector.get(VoiceRecorderService)).not.toBe(b.injector.get(VoiceRecorderService))
    })
  })
})
