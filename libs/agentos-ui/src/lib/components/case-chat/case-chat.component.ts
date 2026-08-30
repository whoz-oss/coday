import { HttpClient } from '@angular/common/http'
import { JsonPipe } from '@angular/common'
import { firstValueFrom } from 'rxjs'
import {
  afterNextRender,
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  input,
  NgZone,
  OnDestroy,
  OnInit,
  output,
  signal,
  viewChild,
  ChangeDetectionStrategy,
} from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { DomSanitizer, SafeHtml } from '@angular/platform-browser'
import { ActivatedRoute } from '@angular/router'
import {
  AgentFinishedEvent,
  AgentRunningEvent,
  AgentSelectedEvent,
  AnswerEvent,
  CaseEvent,
  CaseStatusEnum,
  CaseStatusEvent,
  CaseUpdatedEvent,
  Configuration,
  EnrichmentPhaseTrace,
  ErrorEvent,
  IntentionGeneratedEvent,
  MessageEvent as CaseMessageEvent,
  QuestionEvent,
  QuestionEventQuestionTypeEnum,
  ToolRequestEvent,
  ToolResponseEvent,
  WarnEvent,
} from '@whoz-oss/agentos-api-client'
import { AgentConfig, Prompt } from '@whoz-oss/agentos-api-client'
import { BlueprintDirective, CopyButtonComponent, DrawerComponent, IconButtonComponent } from '@whoz-oss/design-system'
import { CaseStatusGlyphComponent } from '../case-status-glyph/case-status-glyph.component'
import { CaseStateService } from '../../services/case-state.service'
import { OAuthAgentosService } from '../../services/oauth-agentos.service'
import { QuestionPanelComponent } from '../question-panel/question-panel.component'
import DOMPurify from 'dompurify'
import { marked, Renderer } from 'marked'
import { PromptAutocompleteComponent } from '../prompt-autocomplete/prompt-autocomplete.component'
import { AgentAutocompleteComponent } from '../agent-autocomplete/agent-autocomplete.component'
import { ComposerAutocompleteService } from '../composer-autocomplete/composer-autocomplete.service'
import { USER_PREFERENCES_PORT } from '../../services/user-preferences.service'
import { ExchangeStateService } from '../../services/exchange-state.service'
import { exchangeMutationScope } from '../../services/exchange-content.utils'
import { ExchangeShellComponent } from '../exchange-shell/exchange-shell.component'
import { CaseMembersComponent } from '../case-members/case-members.component'
import { ComposerAttachmentsService } from '../composer-attachments/composer-attachments.service'
import { ComposerAttachmentsComponent } from '../composer-attachments/composer-attachments.component'
import { isNamespaceTargeted, resolveUploadScope } from '../composer-attachments/composer-attachments.utils'

export interface ToolCall {
  requestId: string
  toolName: string
  args: string | null
  /** undefined = pending, defined = done */
  response?: ToolResponseEvent
  /** Enrichment phase traces from multi-step parameter generation (null when no enrichment). */
  enrichmentPhases?: EnrichmentPhaseTrace[] | null
}

/** A technical event displayed only when showTechnical is enabled. */
export interface TechnicalItem {
  type:
    | 'WarnEvent'
    | 'ErrorEvent'
    | 'CaseStatusEvent'
    | 'AgentRunningEvent'
    | 'AgentFinishedEvent'
    | 'AgentSelectedEvent'
    | 'IntentionGeneratedEvent'
  label: string
  detail?: string
}

export type TimelineItem =
  | { kind: 'message'; event: CaseMessageEvent; html: SafeHtml; isFirstInGroup: boolean }
  | { kind: 'tool'; call: ToolCall }
  | { kind: 'streaming' }
  | { kind: 'technical'; item: TechnicalItem; eventId: string }
  | { kind: 'question'; event: QuestionEvent; answered: boolean }

/** Threshold (px) from the bottom of the scroll container below which we consider "at bottom". */
const SCROLL_BOTTOM_THRESHOLD = 64

/** True when the user has an active text selection (e.g. preparing to copy). */
function hasActiveSelection(): boolean {
  const selection = window.getSelection()
  return !!selection && selection.toString().length > 0
}

/**
 * CaseChatComponent — real-time chat view for an active case.
 *
 * Direct SSE connection to /api/cases/:caseId/events.
 * Accumulates all CaseEvents, renders MessageEvents and ToolRequest/Response items in order.
 *
 * Scroll behaviour:
 * - The messages area fills available height and scrolls independently.
 * - The composer (input + actions) is always visible at the bottom.
 * - "Magnetic" auto-scroll: while the user is at the bottom, new content
 *   automatically scrolls the view down. Scrolling up breaks the magnet.
 * - A floating "scroll to bottom" button appears when not at the bottom.
 */
@Component({
  selector: 'agentos-case-chat',
  imports: [
    IconButtonComponent,
    JsonPipe,
    DrawerComponent,
    ExchangeShellComponent,
    CaseMembersComponent,
    PromptAutocompleteComponent,
    AgentAutocompleteComponent,
    BlueprintDirective,
    CaseStatusGlyphComponent,
    CopyButtonComponent,
    ComposerAttachmentsComponent,
    QuestionPanelComponent,
  ],
  providers: [ComposerAttachmentsService, ComposerAutocompleteService],
  templateUrl: './case-chat.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './case-chat.component.scss',
})
export class CaseChatComponent implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute)
  private readonly http = inject(HttpClient)
  private readonly zone = inject(NgZone)
  private readonly destroyRef = inject(DestroyRef)
  private readonly domSanitizer = inject(DomSanitizer)
  private readonly exchangeState = inject(ExchangeStateService)

  private readonly config = inject(Configuration)
  protected readonly preferences = inject(USER_PREFERENCES_PORT)
  private readonly caseState = inject(CaseStateService)
  private readonly oauthService = inject(OAuthAgentosService)

  /** Right-side file-exchange drawer open state + entry-point badge count. */
  protected readonly exchangeOpen = signal(false)
  protected readonly exchangeFileCount = this.exchangeState.fileCount

  /** Files staged on the next message (component-scoped instance, see providers). */
  protected readonly attachments = inject(ComposerAttachmentsService)
  protected readonly autocomplete = inject(ComposerAutocompleteService)
  /** Attaching goes through the case exchange: same write gate as the drawer's upload button. */
  protected readonly canAttach = this.exchangeState.canWriteCase
  /**
   * Files can be staged (picker + drop) only when the composer itself is usable: staging on
   * a terminal case would be a dead end, and staging during an upload batch would be
   * silently skipped by the in-flight loop.
   */
  protected readonly canStageFiles = computed(
    () => this.canAttach() && !this.isRunning() && !this.isTerminal() && !this.attachments.isUploading()
  )

  protected toggleExchange(): void {
    this.exchangeOpen.update((v) => !v)
  }

  // caseId and namespaceId are read from query params (?case=...&ns=...).
  // The case-shell renders this component directly (not via router-outlet),
  // so route params are empty — all context comes through query params.
  protected caseId = this.route.snapshot.queryParams['case'] as string
  private readonly namespaceId = this.route.snapshot.queryParams['ns'] as string

  /** Markdown renderer shared across all message pre-computations. */
  private readonly markdownRenderer = this.buildMarkdownRenderer()

  /** Display name used for the streaming assistant bubble (before final MessageEvent arrives). */
  protected readonly agentDisplayName = computed(() => {
    // Prefer the latest orchestration signal if present.
    // Fall back to a generic label.
    const all = this.events()
    for (let i = all.length - 1; i >= 0; i--) {
      const e = all[i]
      if (!e) continue

      if (e.type === 'AgentRunningEvent' || e.type === 'AgentSelectedEvent' || e.type === 'AgentFinishedEvent') {
        const name = e.agentName
        if (name && name.trim().length > 0) return name
      }

      if (e.type === 'MessageEvent' && e.actor.role === 'AGENT' && e.actor.displayName) {
        return e.actor.displayName
      }
    }
    return 'Assistant'
  })

  private eventSource: EventSource | null = null

  private readonly composerInput = viewChild<ElementRef<HTMLTextAreaElement>>('composerInput')
  private readonly messagesContainer = viewChild<ElementRef<HTMLDivElement>>('messagesContainer')
  private readonly promptAutocompleteRef = viewChild(PromptAutocompleteComponent)
  private readonly agentAutocompleteRef = viewChild(AgentAutocompleteComponent)

  protected readonly events = signal<CaseEvent[]>([])

  /**
   * Pre-computed markdown HTML per event id.
   * Populated at SSE ingestion time so the computed() timeline stays synchronous.
   */
  private readonly messageHtmlCache = new Map<string, SafeHtml>()

  protected inputValue = signal('')
  protected isRunning = signal(false)
  protected isTerminal = signal(false)

  /** Active case from the shared case list (title + stored status). */
  protected readonly activeCase = computed(() => this.caseState.cases().find((c) => c.id === this.caseId) ?? null)

  /**
   * Raw SSE status — empty string until a CaseStatusEvent arrives for this case.
   * Resets to '' on case switch so the stored status takes over immediately.
   */
  private readonly _sseStatus = signal<string>('')

  /**
   * Effective status driving the header glyph + badge.
   * Priority: SSE event > stored case status > 'IDLE'.
   */
  protected readonly caseStatus = computed(() => this._sseStatus() || this.activeCase()?.status || 'IDLE')

  /** Whether the delete confirmation inline is showing. */
  protected readonly confirmingDelete = signal(false)

  /** Which panel is active in the right drawer: files exchange or case members. */
  protected readonly drawerPanel = signal<'files' | 'members'>('files')

  protected openFilesPanel(): void {
    this.drawerPanel.set('files')
    this.exchangeOpen.set(true)
  }

  protected openMembersPanel(): void {
    this.drawerPanel.set('members')
    this.exchangeOpen.set(true)
  }

  // Header action outputs — handled by CaseShellComponent
  readonly starToggled = output<{ id: string; starred: boolean }>()
  readonly deleteRequested = output<string>()
  readonly logsToggled = output<void>()

  readonly showTechnicalOverride = input(false)
  protected readonly showTechnical = computed(() => this.showTechnicalOverride())

  /** Streaming assistant text assembled from TextChunkEvent during a RUNNING turn. */
  protected readonly streamingText = signal('')

  /** Markdown-rendered SafeHtml of the streaming text — updated on every chunk. */
  protected readonly streamingHtml = computed<SafeHtml>(() => {
    const text = this.streamingText()
    if (!text) return ''
    return this.renderMarkdown(text)
  })

  /** True when the message text targets the namespace exchange (previewed on the chips). */
  protected readonly namespaceTargeted = computed(() =>
    isNamespaceTargeted(this.inputValue(), this.exchangeState.canWriteNamespace())
  )

  /** Collapsed state per toolRequestId */
  protected readonly collapsedTools = signal<Set<string>>(new Set())

  /**
   * Whether the user is currently scrolled to (or near) the bottom of the messages area.
   * When true, new content triggers automatic scroll-to-bottom (magnetic behaviour).
   * Flips to false as soon as the user scrolls up past the threshold.
   */
  protected readonly isAtBottom = signal(true)

  /** Listener cleanup function registered on the messages container. */
  private scrollListenerCleanup: (() => void) | null = null

  constructor() {
    this.autocomplete.init(this.namespaceId)

    // Restore focus to the composer whenever we return to an interactive state,
    // but only when the user has no active text selection (avoid clearing copy intent).
    effect(() => {
      if (this.isRunning() || this.isTerminal()) return
      queueMicrotask(() => {
        if (hasActiveSelection()) return
        this.composerInput()?.nativeElement.focus()
      })
    })

    // Auto-scroll to bottom whenever the timeline or streaming text changes,
    // but only when the user is already at the bottom (magnetic behaviour).
    // Skip when the user has an active text selection to avoid disrupting copy intent.
    effect(() => {
      this.timeline()
      this.streamingText()

      if (this.isAtBottom()) {
        queueMicrotask(() => {
          if (hasActiveSelection()) return
          this.scrollToBottom()
        })
      }
    })

    // Register scroll listener after the first render so the ViewChild is available.
    afterNextRender(() => {
      this.attachScrollListener()
    })
  }

  /**
   * Static timeline derived from persisted events only — does NOT depend on streamingText.
   * Splitting from `timeline` avoids rebuilding the full reconciliation on every TextChunkEvent
   * during a streaming turn (dozens per second).
   *
   * Two-pass approach:
   * 1. Build a complete ToolCall map (request merged with its response)
   * 2. Walk events in order to emit timeline items, deduplicating tool entries
   *    so TOOL_RESPONSE doesn't create a second item — it's already merged.
   */
  private readonly baseTimeline = computed<TimelineItem[]>(() => {
    const allEvents = this.events()
    const showTechnical = this.showTechnical()

    // Pass 1: build complete tool call map (request + optional response)
    const toolCallMap = new Map<string, ToolCall>()
    for (const e of allEvents) {
      if (e.type === 'ToolRequestEvent') {
        const req = e as ToolRequestEvent
        const requestId = req.toolRequestId ?? e.id
        const existing = toolCallMap.get(requestId)
        toolCallMap.set(requestId, {
          requestId,
          toolName: req.toolName ?? 'unknown',
          args: req.args ?? null,
          response: existing?.response,
          enrichmentPhases: (req as ToolRequestEvent).enrichmentPhases ?? null,
        })
      } else if (e.type === 'ToolResponseEvent') {
        const res = e as ToolResponseEvent
        const requestId = res.toolRequestId ?? e.id
        const existing = toolCallMap.get(requestId)
        toolCallMap.set(requestId, {
          requestId,
          toolName: existing?.toolName ?? res.toolName ?? 'unknown',
          args: existing?.args ?? null,
          response: res,
          enrichmentPhases: existing?.enrichmentPhases ?? null,
        })
      }
    }

    const items: TimelineItem[] = []
    const seenToolIds = new Set<string>()
    // Track the last role to detect group boundaries (consecutive same-role messages).
    // Any non-message item (tool call, technical event) resets the group.
    let lastMessageRole: string | null = null
    for (const e of allEvents) {
      if (e.type === 'MessageEvent') {
        const msg = e as CaseMessageEvent
        const role = msg.actor.role
        const isFirstInGroup = role !== lastMessageRole
        lastMessageRole = role
        items.push({
          kind: 'message',
          event: msg,
          html: this.messageHtmlCache.get(e.id) ?? '',
          isFirstInGroup,
        })
      } else if (e.type === 'ToolRequestEvent' || e.type === 'ToolResponseEvent') {
        const requestId = e.toolRequestId ?? e.id
        if (!seenToolIds.has(requestId)) {
          seenToolIds.add(requestId)
          items.push({ kind: 'tool', call: toolCallMap.get(requestId)! })
        }
        lastMessageRole = null
      } else if (e.type === 'QuestionEvent') {
        const qe = e as QuestionEvent
        // A question is answered when there is a corresponding AnswerEvent in the stream.
        const answered = allEvents.some((ae) => ae.type === 'AnswerEvent' && (ae as AnswerEvent).questionId === qe.id)
        items.push({ kind: 'question', event: qe, answered })
      } else if (showTechnical) {
        const technical = this.toTechnicalItem(e)
        if (technical) {
          items.push({ kind: 'technical', item: technical, eventId: e.id })
          lastMessageRole = null
        }
      }
    }

    return items
  })

  /** Final timeline: base + trailing streaming assistant bubble during a RUNNING turn. */
  protected readonly timeline = computed<TimelineItem[]>(() => {
    const base = this.baseTimeline()
    const streamingText = this.streamingText()
    if (streamingText.trim().length === 0) return base
    return [...base, { kind: 'streaming' }]
  })

  protected trackTimelineItem(_index: number, item: TimelineItem): string {
    switch (item.kind) {
      case 'message':
        return item.event.id
      case 'tool':
        return item.call.requestId
      case 'technical':
        return item.eventId
      case 'streaming':
        return 'streaming'
      case 'question':
        return `question-${item.event.id}`
    }
  }

  /** Exposed so the template can check if the OAuth panel should be suppressed after popup opens. */
  protected readonly oauthPendingQuestion = this.oauthService.pendingQuestion

  /** Called by question-panel when the user cancels a non-OAuth question. */
  protected onQuestionCancelled(): void {
    // For OAUTH_AUTHORIZE, cancelRequest() was already called inside the panel.
    // Nothing else to do here for non-OAuth types (no backend call needed for cancel).
  }

  /**
   * Called by question-panel when the user submits a non-OAuth answer.
   * Posts to POST /api/cases/{caseId}/messages with answerToEventId.
   */
  protected onQuestionAnswered(questionEvent: QuestionEvent, answer: string): void {
    this.isRunning.set(true)
    this.http
      .post(`${this.config.basePath}/api/cases/${this.caseId}/messages`, {
        content: answer,
        answerToEventId: questionEvent.id,
      })
      .subscribe({
        error: (err) => {
          console.error('[CaseChat] Failed to post answer', err)
          this.isRunning.set(false)
        },
      })
  }

  protected get canSend(): boolean {
    return (
      (!!this.inputValue().trim() || this.attachments.hasAttachments()) &&
      !this.isRunning() &&
      !this.isTerminal() &&
      !this.attachments.isUploading()
    )
  }

  ngOnInit(): void {
    this.connectSse()

    // Re-initialise when the ?case query param changes (case-shell navigates with queryParams).
    this.route.queryParams.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const newCaseId = params['case'] as string
      if (newCaseId && newCaseId !== this.caseId) {
        this.caseId = newCaseId
        this.reinitialise()
      }
    })
  }

  ngOnDestroy(): void {
    this.eventSource?.close()
    this.scrollListenerCleanup?.()
  }

  // ---------------------------------------------------------------------------
  // Scroll management
  // ---------------------------------------------------------------------------

  /**
   * Attach a scroll listener to the messages container.
   * Updates `isAtBottom` as the user scrolls.
   * Called once after the first render.
   */
  private attachScrollListener(): void {
    const el = this.messagesContainer()?.nativeElement
    if (!el) return

    const onScroll = () => {
      const distanceFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight
      this.isAtBottom.set(distanceFromBottom <= SCROLL_BOTTOM_THRESHOLD)
    }

    el.addEventListener('scroll', onScroll, { passive: true })
    this.scrollListenerCleanup = () => el.removeEventListener('scroll', onScroll)
  }

  /** Programmatically scroll the messages container to the very bottom. */
  protected scrollToBottom(): void {
    const el = this.messagesContainer()?.nativeElement
    if (!el) return
    el.scrollTop = el.scrollHeight
  }

  /**
   * Called by the floating "scroll to bottom" button.
   * Re-enables the magnetic behaviour and scrolls down.
   */
  protected onScrollToBottomClick(): void {
    this.isAtBottom.set(true)
    this.scrollToBottom()
  }

  // ---------------------------------------------------------------------------

  private connectSse(): void {
    const url = `${this.config.basePath}/api/cases/${this.caseId}/events`

    console.log('[AgentOS SSE] connecting', {
      url,
      basePath: this.config.basePath,
      caseId: this.caseId,
      namespaceId: this.namespaceId,
      now: new Date().toISOString(),
    })

    this.eventSource = this.zone.runOutsideAngular(() => new EventSource(url))

    // NOTE: the backend sends named SSE events ("event: MessageEvent", "event: CaseStatusEvent", ...)
    // In that case, `onmessage` is NOT called. We must subscribe to named events.
    const handler = (msg: globalThis.MessageEvent<string>) => {
      const receivedAt = performance.now()
      const sseEventName = (msg as unknown as { type?: string }).type

      // Log first bytes + sizes to detect batching.
      const raw = msg.data
      console.log('[AgentOS SSE] frame received', {
        sseEventName,
        dataLength: raw?.length ?? 0,
        dataPreview: raw?.slice(0, 120),
        receivedAtMs: receivedAt,
      })

      try {
        const event = JSON.parse(raw) as CaseEvent
        this.zone.run(() => {
          const beforeLen = this.events().length

          // Pre-compute markdown HTML for MessageEvent before adding to signal.
          if (event.type === 'MessageEvent') {
            const msg = event as CaseMessageEvent
            const text = this.extractText(msg)
            if (!this.messageHtmlCache.has(event.id)) {
              this.messageHtmlCache.set(event.id, this.renderMarkdown(text))
            }
          }

          this.events.update((prev) => (prev.some((e) => e.id === event.id) ? prev : [...prev, event]))
          const afterLen = this.events().length

          console.log('[AgentOS SSE] event processed', {
            sseEventName,
            eventType: event.type,
            eventId: event.id,
            beforeLen,
            afterLen,
            running: this.isRunning(),
            terminal: this.isTerminal(),
          })

          if (event.type === 'TextChunkEvent') {
            const chunk = (event as unknown as { chunk?: string }).chunk
            if (chunk) {
              this.streamingText.update((prev) => prev + chunk)
            }
            return
          }

          if (event.type === 'CaseUpdatedEvent') {
            const updated = event as CaseUpdatedEvent
            if (updated.title) {
              this.caseState.updateCaseTitle(event.caseId, updated.title)
            }
            return
          }

          if (event.type === 'CaseStatusEvent') {
            // Source of truth for running/terminal states.
            // Backend statuses: PENDING | RUNNING | IDLE | KILLED | ERROR
            const status = (event as CaseStatusEvent).status as string
            this._sseStatus.set(status)
            // Sync the drawer list so both header and drawer show the same status
            this.caseState.updateCaseStatus(this.caseId, status)

            const isTerminal = status === 'KILLED' || status === 'ERROR'
            this.isTerminal.set(isTerminal)

            if (isTerminal) {
              this.isRunning.set(false)
              // Terminal: close SSE connection.
              this.eventSource?.close()
              this.eventSource = null
            } else {
              const running = status === 'RUNNING'
              this.isRunning.set(running)
              if (!running) {
                // End of turn / idle: reset streaming buffer.
                this.streamingText.set('')
              }
            }
            return
          }

          // In practice, the SSE stream currently does NOT emit CaseStatusEvent.
          // So we treat AgentFinishedEvent as the end-of-turn signal.
          if (event.type === 'AgentFinishedEvent') {
            this.isRunning.set(false)
            // End-of-turn: reset streaming buffer.
            this.streamingText.set('')
            // Safety net: refresh both scopes at end-of-turn ONLY if a tool ran (covers a mutation the
            // per-op regex may have missed); pure-conversation turns skip the manifest fetch.
            if (this.anyToolResponseThisTurn) {
              this.exchangeState.refreshManifest()
            }
            this.anyToolResponseThisTurn = false
            return
          }

          if (event.type === 'ToolResponseEvent') {
            this.anyToolResponseThisTurn = true
            // The agent mutated the exchange filesystem → refresh the affected scope's drawer + badge live.
            const mutatedScope = exchangeMutationScope((event as ToolResponseEvent).toolName)
            if (mutatedScope === 'case') {
              this.exchangeState.refreshCase()
            } else if (mutatedScope === 'namespace') {
              this.exchangeState.refreshNamespace()
            }
            return
          }

          if (event.type === 'QuestionEvent') {
            const qe = event as QuestionEvent
            if (qe.questionType === QuestionEventQuestionTypeEnum.OAUTH_AUTHORIZE) {
              // Delegate OAuth popup management to the service.
              // The panel is shown via the timeline (question kind) and the service
              // exposes pendingQuestion so the panel knows when to hide after popup opens.
              this.oauthService.setPendingQuestion(qe)
            }
            return
          }

          // For other events: don't force isRunning=true.
          // submit() sets isRunning=true, and we flip it back on AgentFinishedEvent.
        })
      } catch (err) {
        console.warn('[AgentOS SSE] failed to parse event data', {
          sseEventName,
          error: err,
          dataPreview: raw?.slice(0, 500),
        })
      }
    }

    const eventNames = [
      'MessageEvent',
      'CaseStatusEvent',
      'CaseUpdatedEvent',
      'AgentSelectedEvent',
      'AgentRunningEvent',
      'AgentFinishedEvent',
      'ThinkingEvent',
      'TextChunkEvent',
      'ToolRequestEvent',
      'ToolResponseEvent',
      'PendingConfirmationEvent',
      'ConfirmationResolvedEvent',
      'ErrorEvent',
      'WarnEvent',
      'IntentionGeneratedEvent',
      'QuestionEvent',
      'AnswerEvent',
    ] as const

    // handle the different event names we see in the SSE stream
    for (const name of eventNames) {
      console.log('[AgentOS SSE] addEventListener', name)
      this.eventSource.addEventListener(name, handler)
    }

    this.eventSource.onopen = () => {
      console.log('[AgentOS SSE] connection open', {
        readyState: this.eventSource?.readyState,
        at: new Date().toISOString(),
      })
    }

    // Note: onmessage only fires for unnamed events. Keep it for debugging.
    this.eventSource.onmessage = (msg) => {
      console.log('[AgentOS SSE] onmessage (unnamed event) received', {
        dataLength: msg.data?.length ?? 0,
        dataPreview: msg.data?.slice(0, 120),
      })
    }

    this.eventSource.onerror = (err) => {
      console.warn('[AgentOS SSE] connection error', {
        err,
        readyState: this.eventSource?.readyState,
        at: new Date().toISOString(),
      })
      this.zone.run(() => {
        this.isRunning.set(false)
        // Do not mark terminal on transport error: EventSource may reconnect.
      })
    }
  }

  /** Whether any tool ran this turn — gates the end-of-turn exchange refresh (skips pure-chat turns). */
  private anyToolResponseThisTurn = false

  protected onInput(event: Event): void {
    const value = (event.target as HTMLTextAreaElement).value
    this.autocomplete.onInput(value, this.inputValue)
  }

  protected onKeydown(event: KeyboardEvent): void {
    const consumed = this.autocomplete.onKeydown(event, this.promptAutocompleteRef, this.agentAutocompleteRef)
    if (consumed) return
    if (this.preferences.shouldSend(event)) {
      event.preventDefault()
      this.submit()
    }
  }

  protected onPromptSelected(prompt: Prompt): void {
    this.autocomplete.onPromptSelected(prompt, this.promptAutocompleteRef, this.composerInput, this.inputValue)
  }

  protected onAgentSelected(agent: AgentConfig): void {
    this.autocomplete.onAgentSelected(agent, this.agentAutocompleteRef, this.composerInput, this.inputValue)
  }

  protected closeSlashAutocomplete(): void {
    this.autocomplete.slashSuggestions.set([])
  }

  protected closeAtAutocomplete(): void {
    this.autocomplete.atSuggestions.set([])
  }

  /**
   * Reset all state and reconnect SSE for a new caseId.
   * Called when the router reuses this component instance for a different case.
   */
  private reinitialise(): void {
    this.eventSource?.close()
    this.eventSource = null
    this.events.set([])
    this.messageHtmlCache.clear()
    this.inputValue.set('')
    this.isRunning.set(false)
    this.isTerminal.set(false)
    this._sseStatus.set('')
    this.confirmingDelete.set(false)
    this.streamingText.set('')
    this.collapsedTools.set(new Set())
    this.isAtBottom.set(true)
    this.drawerPanel.set('files')
    this.autocomplete.reset()
    this.attachments.reset()
    this.connectSse()
  }

  /**
   * Re-entrancy guard for the async attachment path. canSend alone is not enough: it goes
   * false during the upload (isUploading), but that flag is cleared by the service before
   * postMessage sets isRunning, leaving a window where a second submit() could pass the
   * guard and post the same message twice. Set synchronously, cleared in a finally.
   */
  private submitting = false

  protected async submit(): Promise<void> {
    if (this.submitting || !this.canSend) return
    const content = this.inputValue().trim()
    if (this.attachments.hasAttachments()) {
      this.submitting = true
      try {
        const caseIdAtSubmit = this.caseId
        const scope = resolveUploadScope(content, this.exchangeState.canWriteNamespace())
        const mention = await this.attachments.uploadAllAndBuildMention(scope)
        // An upload failure or a mid-flight case switch blocks the send: failed chips carry
        // the mapped errors (or the switch reset the batch), the input stays intact.
        if (mention === null || this.caseId !== caseIdAtSubmit) return
        const sent = await this.postMessage(content ? `${content}\n\n${mention}` : mention)
        // Only a confirmed send clears the composer: on failure the text and the uploaded
        // chips stay, and a retry rebuilds the mention without re-uploading anything.
        if (sent && this.caseId === caseIdAtSubmit) {
          this.inputValue.set('')
          this.attachments.reset()
        }
      } finally {
        this.submitting = false
      }
      return
    }
    this.inputValue.set('')
    this.sendMessage(content)
  }

  private sendMessage(content: string): void {
    void this.postMessage(content)
  }

  private postMessage(content: string): Promise<boolean> {
    this.isRunning.set(true)
    this.streamingText.set('')

    return firstValueFrom(
      this.http.post(`${this.config.basePath}/api/cases/${this.caseId}/messages`, {
        content,
        userId: 'default-user',
      })
    ).then(
      () => true,
      (err) => {
        console.error('[CaseChat] Failed to send message', err)
        this.isRunning.set(false)
        return false
      }
    )
  }

  protected interrupt(): void {
    this.http.post(`${this.config.basePath}/api/cases/${this.caseId}/interrupt`, {}).subscribe({
      // Server transitions to IDLE; SSE stays open. We'll update isRunning on CaseStatusEvent.
      error: (err) => console.error('[CaseChat] Failed to interrupt case', err),
    })
  }

  protected kill(): void {
    this.http.post(`${this.config.basePath}/api/cases/${this.caseId}/kill`, {}).subscribe({
      // Server transitions to KILLED; SSE handler will close the EventSource.
      error: (err) => console.error('[CaseChat] Failed to kill case', err),
    })
  }

  protected extractText(event: CaseMessageEvent): string {
    return (
      event.content
        ?.filter((c): c is import('@whoz-oss/agentos-api-client').Text => 'content' in c)
        .map((c) => c.content)
        .join('') ?? ''
    )
  }

  protected extractToolOutput(call: ToolCall): string | null {
    if (!call.response) return null
    const output = call.response.output as { content?: string } | null
    if (!output) return null
    return output.content ?? null
  }

  protected toggleToolCall(requestId: string): void {
    this.collapsedTools.update((set) => {
      const next = new Set(set)
      if (next.has(requestId)) {
        next.delete(requestId)
      } else {
        next.add(requestId)
      }
      return next
    })
  }

  /** Collapsed by default: a tool call is expanded only when its id is in the set */
  protected isToolCallExpanded(requestId: string): boolean {
    return this.collapsedTools().has(requestId)
  }

  protected toggleShowTechnical(): void {
    this.logsToggled.emit()
  }

  /** Extract plain text from a message item for clipboard copy. */
  protected messageText(item: TimelineItem & { kind: 'message' }): string {
    return this.extractText(item.event)
  }

  // ---------------------------------------------------------------------------
  // Markdown rendering
  // ---------------------------------------------------------------------------

  /**
   * Render markdown to sanitized SafeHtml synchronously.
   * Called once per MessageEvent at SSE ingestion time.
   */
  private renderMarkdown(text: string): SafeHtml {
    if (!text) return ''
    const rawHtml = marked.parse(text, {
      renderer: this.markdownRenderer,
      breaks: true,
      gfm: true,
      async: false,
    }) as string

    const clean = DOMPurify.sanitize(rawHtml, {
      ADD_TAGS: ['span'],
      ADD_ATTR: ['aria-hidden', 'aria-label', 'target', 'rel'],
      ALLOWED_URI_REGEXP: /^(?:(?:(?:f|ht)tps?|mailto|tel|callto|sms|cid|xmpp):|[^a-z]|[a-z+.-]+(?:[^a-z+.-:]|$))/i,
    })

    return this.domSanitizer.bypassSecurityTrustHtml(clean)
  }

  private buildMarkdownRenderer(): Renderer {
    const renderer = new Renderer()
    const originalLink = renderer.link.bind(renderer)
    renderer.link = (token): string => {
      let html = originalLink(token)
      if (this.isExternalLink(token.href)) {
        html = html
          .replace('<a ', '<a target="_blank" rel="noopener noreferrer" ')
          .replace('</a>', '<span class="external-link-icon" aria-hidden="true">↗</span></a>')
      }
      return html
    }
    return renderer
  }

  private isExternalLink(href: string): boolean {
    if (!href || href.startsWith('/') || href.startsWith('#') || href.startsWith('?')) return false
    if (href.startsWith('//')) return true
    try {
      return new URL(href, window.location.href).hostname !== window.location.hostname
    } catch {
      return false
    }
  }

  // ---------------------------------------------------------------------------
  // Technical event mapping
  // ---------------------------------------------------------------------------

  private toTechnicalItem(event: CaseEvent): TechnicalItem | null {
    switch (event.type) {
      case 'WarnEvent': {
        const e = event as WarnEvent
        return { type: 'WarnEvent', label: '⚠️ Warn', detail: e.message }
      }
      case 'ErrorEvent': {
        const e = event as ErrorEvent
        return { type: 'ErrorEvent', label: '❌ Error', detail: e.message }
      }
      case 'CaseStatusEvent': {
        const e = event as CaseStatusEvent
        return { type: 'CaseStatusEvent', label: `🟡 Status: ${e.status}` }
      }
      case 'AgentRunningEvent': {
        const e = event as AgentRunningEvent
        return { type: 'AgentRunningEvent', label: `▶️ Agent running: ${e.agentName}` }
      }
      case 'AgentFinishedEvent': {
        const e = event as AgentFinishedEvent
        return { type: 'AgentFinishedEvent', label: `✅ Agent finished: ${e.agentName}` }
      }
      case 'AgentSelectedEvent': {
        const e = event as AgentSelectedEvent
        return { type: 'AgentSelectedEvent', label: `🎯 Agent selected: ${e.agentName}` }
      }
      case 'IntentionGeneratedEvent': {
        const e = event as IntentionGeneratedEvent
        return { type: 'IntentionGeneratedEvent', label: `🧠 Intention → ${e.toolName}`, detail: e.intention }
      }
      default:
        return null
    }
  }

  protected readonly CaseStatusEnum = CaseStatusEnum
}
