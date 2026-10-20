import { HttpClient } from '@angular/common/http'
import { ComponentRef, createComponent, EnvironmentInjector, signal } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { ActivatedRoute } from '@angular/router'
import {
  ActorRoleEnum,
  AnswerEvent,
  Configuration,
  ExchangeFileEntryScopeEnum,
  QuestionEvent,
  QuestionEventQuestionTypeEnum,
  ToolRequestEvent,
  ToolResponseEvent,
} from '@whoz-oss/agentos-api-client'
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
      ref.setInput('showTechnicalOverride', true)
      ref.instance['events'].set([question(), answer('a-1', 'u-1', 'blue')])

      expect(ref.instance['timeline']().map((item) => item.kind)).toEqual(['question'])
    })
  })
})
