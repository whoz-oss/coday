import { HttpClient } from '@angular/common/http'
import { JsonPipe } from '@angular/common'
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
  signal,
  ViewChild,
} from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { DomSanitizer, SafeHtml } from '@angular/platform-browser'
import { ActivatedRoute } from '@angular/router'
import {
  AgentFinishedEvent,
  AgentRunningEvent,
  AgentSelectedEvent,
  CaseEvent,
  CaseStatusEvent,
  Configuration,
  EnrichmentPhaseTrace,
  ErrorEvent,
  IntentionGeneratedEvent,
  MessageEvent as CaseMessageEvent,
  ToolRequestEvent,
  ToolResponseEvent,
  WarnEvent,
} from '@whoz-oss/agentos-api-client'
import { IconButtonComponent } from '@whoz-oss/design-system'
import DOMPurify from 'dompurify'
import { marked, Renderer } from 'marked'
import { USER_PREFERENCES_PORT } from '../../services/user-preferences.service'

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
  | { kind: 'message'; event: CaseMessageEvent; html: SafeHtml }
  | { kind: 'tool'; call: ToolCall }
  | { kind: 'streaming'; text: string }
  | { kind: 'technical'; item: TechnicalItem; eventId: string }

/** Threshold (px) from the bottom of the scroll container below which we consider "at bottom". */
const SCROLL_BOTTOM_THRESHOLD = 64

/**
 * CaseChatComponent — real-time chat view for an active case.
 *
 * Connexion SSE directe sur /api/agentos/api/cases/:caseId/events.
 * Accumule tous les CaseEvent reçus, affiche les MessageEvent
 * et les ToolRequestEvent/ToolResponseEvent intercalés chronologiquement.
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
  imports: [IconButtonComponent, JsonPipe],
  templateUrl: './case-chat.component.html',
  styleUrl: './case-chat.component.scss',
})
export class CaseChatComponent implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute)
  private readonly http = inject(HttpClient)
  private readonly zone = inject(NgZone)
  private readonly destroyRef = inject(DestroyRef)
  private readonly domSanitizer = inject(DomSanitizer)
  private readonly config = inject(Configuration)
  protected readonly preferences = inject(USER_PREFERENCES_PORT)

  // Read from snapshot initially; updated reactively in ngOnInit via route.queryParams
  private caseId = this.route.snapshot.queryParams['case'] as string
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

  @ViewChild('composerInput') private composerInput?: ElementRef<HTMLTextAreaElement>
  @ViewChild('messagesContainer') private messagesContainer?: ElementRef<HTMLDivElement>

  protected readonly events = signal<CaseEvent[]>([])

  /**
   * Pre-computed markdown HTML per event id.
   * Populated at SSE ingestion time so the computed() timeline stays synchronous.
   */
  private readonly messageHtmlCache = new Map<string, SafeHtml>()

  protected inputValue = signal('')
  protected isRunning = signal(false)
  protected isTerminal = signal(false)

  private static readonly SHOW_TECHNICAL_KEY = 'agentos.case-chat.showTechnical'

  /** When true, technical events are shown in the timeline. Persisted in localStorage. */
  protected readonly showTechnical = signal<boolean>(
    localStorage.getItem(CaseChatComponent.SHOW_TECHNICAL_KEY) === 'true'
  )

  /**
   * External override from the parent shell.
   * When set, syncs the internal showTechnical signal so the shell toggle drives this component.
   */
  readonly showTechnicalOverride = input(false)

  /** Streaming assistant text assembled from TextChunkEvent during a RUNNING turn. */
  protected readonly streamingText = signal('')

  /** Collapsed state per toolRequestId */
  protected readonly collapsedTools = signal<Set<string>>(new Set())

  /** Expanded state per technical eventId — collapsed by default */
  protected readonly expandedTechnicals = signal<Set<string>>(new Set())

  /**
   * Whether the user is currently scrolled to (or near) the bottom of the messages area.
   * When true, new content triggers automatic scroll-to-bottom (magnetic behaviour).
   * Flips to false as soon as the user scrolls up past the threshold.
   */
  protected readonly isAtBottom = signal(true)

  /** Listener cleanup function registered on the messages container. */
  private scrollListenerCleanup: (() => void) | null = null

  constructor() {
    // Sync showTechnical from parent shell override
    effect(() => {
      this.showTechnical.set(this.showTechnicalOverride())
    })

    // Restore focus to the composer whenever we return to an interactive state.
    effect(() => {
      if (this.isRunning() || this.isTerminal()) return
      queueMicrotask(() => this.composerInput?.nativeElement.focus())
    })

    // Auto-scroll to bottom whenever the timeline or streaming text changes,
    // but only when the user is already at the bottom (magnetic behaviour).
    effect(() => {
      // Depend on timeline and streamingText so the effect re-runs on content changes.
      this.timeline()
      this.streamingText()

      if (this.isAtBottom()) {
        // Defer to next microtask so the DOM has updated before we measure.
        queueMicrotask(() => this.scrollToBottom())
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
   *    so TOOL_RESPONSE doesn’t create a second item — it’s already merged.
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
    for (const e of allEvents) {
      if (e.type === 'MessageEvent') {
        const msg = e as CaseMessageEvent
        items.push({
          kind: 'message',
          event: msg,
          html: this.messageHtmlCache.get(e.id) ?? '',
        })
      } else if (e.type === 'ToolRequestEvent' || e.type === 'ToolResponseEvent') {
        const requestId = e.toolRequestId ?? e.id
        if (!seenToolIds.has(requestId)) {
          seenToolIds.add(requestId)
          items.push({ kind: 'tool', call: toolCallMap.get(requestId)! })
        }
      } else if (showTechnical) {
        const technical = this.toTechnicalItem(e)
        if (technical) {
          items.push({ kind: 'technical', item: technical, eventId: e.id })
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
    return [...base, { kind: 'streaming', text: streamingText }]
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
    }
  }

  protected get canSend(): boolean {
    return !!this.inputValue().trim() && !this.isRunning() && !this.isTerminal()
  }

  ngOnInit(): void {
    this.connectSse()

    // Re-initialise when navigating between cases (same component instance, query param changes)
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
    const el = this.messagesContainer?.nativeElement
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
    const el = this.messagesContainer?.nativeElement
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

          if (event.type === 'CaseStatusEvent') {
            // Source of truth for running/terminal states.
            // Backend statuses: PENDING | RUNNING | IDLE | KILLED | ERROR
            const status = (event as CaseStatusEvent).status as string

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
            return
          }

          // For other events: don’t force isRunning=true.
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

  protected onInput(event: Event): void {
    this.inputValue.set((event.target as HTMLTextAreaElement).value)
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (this.preferences.shouldSend(event)) {
      event.preventDefault()
      this.submit()
    }
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
    this.streamingText.set('')
    this.collapsedTools.set(new Set())
    this.isAtBottom.set(true)
    this.connectSse()
  }

  protected submit(): void {
    if (!this.canSend) return
    const content = this.inputValue().trim()
    this.inputValue.set('')
    this.sendMessage(content)
  }

  private sendMessage(content: string): void {
    this.isRunning.set(true)
    this.streamingText.set('')

    this.http
      .post(`${this.config.basePath}/api/cases/${this.caseId}/messages`, {
        content,
        userId: 'default-user',
      })
      .subscribe({
        error: (err) => {
          console.error('[CaseChat] Failed to send message', err)
          this.isRunning.set(false)
        },
      })
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
    this.showTechnical.update((v) => {
      const next = !v
      localStorage.setItem(CaseChatComponent.SHOW_TECHNICAL_KEY, String(next))
      return next
    })
  }

  protected toggleTechnical(eventId: string): void {
    this.expandedTechnicals.update((set) => {
      const next = new Set(set)
      if (next.has(eventId)) next.delete(eventId)
      else next.add(eventId)
      return next
    })
  }

  protected isTechnicalExpanded(eventId: string): boolean {
    return this.expandedTechnicals().has(eventId)
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
}
