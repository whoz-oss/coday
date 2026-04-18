import { HttpClient } from '@angular/common/http'
import {
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  NgZone,
  OnDestroy,
  OnInit,
  signal,
  ViewChild,
} from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute } from '@angular/router'
import {
  CaseEvent,
  Configuration,
  MessageEvent as CaseMessageEvent,
  ToolRequestEvent,
  ToolResponseEvent,
} from '@whoz-oss/agentos-api-client'
import { IconButtonComponent } from '@whoz-oss/design-system'

export interface ToolCall {
  requestId: string
  toolName: string
  args: string | null
  /** undefined = pending, defined = done */
  response?: ToolResponseEvent
}

export type TimelineItem =
  | { kind: 'message'; event: CaseMessageEvent }
  | { kind: 'tool'; call: ToolCall }
  | { kind: 'streaming'; text: string }

/**
 * CaseChatComponent — real-time chat view for an active case.
 *
 * Connexion SSE directe sur /api/agentos/api/cases/:caseId/events.
 * Accumule tous les CaseEvent reçus, affiche les MessageEvent
 * et les ToolRequestEvent/ToolResponseEvent intercalés chronologiquement.
 */
@Component({
  selector: 'agentos-case-chat',
  standalone: true,
  imports: [IconButtonComponent],
  templateUrl: './case-chat.component.html',
  styleUrl: './case-chat.component.scss',
})
export class CaseChatComponent implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute)
  private readonly http = inject(HttpClient)
  private readonly zone = inject(NgZone)
  private readonly destroyRef = inject(DestroyRef)

  private readonly config = inject(Configuration)

  // Read from snapshot initially; updated reactively in ngOnInit via route.params
  private caseId = this.route.snapshot.params['caseId'] as string
  private readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  /** Display name used for the streaming assistant bubble (before final MessageEvent arrives). */
  protected readonly agentDisplayName = computed(() => {
    // Prefer the latest orchestration signal if present.
    // Fall back to a generic label.
    const all = this.events()
    for (let i = all.length - 1; i >= 0; i--) {
      const e = all[i]
      if (!e) continue

      if (e.type === 'AgentRunningEvent' || e.type === 'AgentSelectedEvent' || e.type === 'AgentFinishedEvent') {
        const name = (e as unknown as { agentName?: string }).agentName
        if (name && name.trim().length > 0) return name
      }

      if (e.type === 'MessageEvent') {
        const msg = e as unknown as { actor?: { role?: string; displayName?: string } }
        if (msg.actor?.role === 'AGENT' && msg.actor.displayName) return msg.actor.displayName
      }
    }
    return 'Assistant'
  })

  private eventSource: EventSource | null = null

  @ViewChild('composerInput') private composerInput?: ElementRef<HTMLTextAreaElement>

  protected readonly events = signal<CaseEvent[]>([])
  protected inputValue = signal('')
  protected isRunning = signal(false)
  protected isTerminal = signal(false)

  /** Streaming assistant text assembled from TextChunkEvent during a RUNNING turn. */
  protected readonly streamingText = signal('')

  /** Collapsed state per toolRequestId */
  protected readonly collapsedTools = signal<Set<string>>(new Set())

  constructor() {
    // Restore focus to the composer whenever we return to an interactive state.
    // (Agent turn finished → AgentFinishedEvent, or status becomes IDLE.)
    effect(() => {
      if (this.isRunning() || this.isTerminal()) return
      queueMicrotask(() => this.composerInput?.nativeElement.focus())
    })
  }

  /**
   * Unified chronological timeline: messages and tool calls interleaved
   * in the order they first appeared in the event stream.
   *
   * Two-pass approach:
   * 1. Build a complete ToolCall map (request merged with its response)
   * 2. Walk events in order to emit timeline items, deduplicating tool entries
   *    so TOOL_RESPONSE doesn't create a second item — it's already merged.
   *
   * The computed re-runs fully on every events() change, so the merged
   * ToolCall objects are always fresh — no mutation needed.
   */
  protected readonly timeline = computed<TimelineItem[]>(() => {
    const allEvents = this.events()
    const streamingText = this.streamingText()

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
        })
      }
    }

    // Pass 2: walk events in order, emit one timeline item per message or tool call
    const items: TimelineItem[] = []
    const seenToolIds = new Set<string>()
    for (const e of allEvents) {
      if (e.type === 'MessageEvent') {
        items.push({ kind: 'message', event: e as CaseMessageEvent })
      } else if (e.type === 'ToolRequestEvent' || e.type === 'ToolResponseEvent') {
        const raw = e as ToolRequestEvent | ToolResponseEvent
        const requestId = raw.toolRequestId ?? e.id
        if (!seenToolIds.has(requestId)) {
          seenToolIds.add(requestId)
          items.push({ kind: 'tool', call: toolCallMap.get(requestId)! })
        }
      }
    }

    // Append the streaming assistant message at the end while running.
    // We keep it separate from MessageEvent so we don't create dozens of timeline rows.
    if (streamingText.trim().length > 0) {
      items.push({ kind: 'streaming', text: streamingText })
    }

    return items
  })

  protected get canSend(): boolean {
    return !!this.inputValue().trim() && !this.isRunning() && !this.isTerminal()
  }

  ngOnInit(): void {
    this.connectSse()

    // Re-initialise when navigating between cases (same component instance reused by the router)
    this.route.params.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const newCaseId = params['caseId'] as string
      if (newCaseId && newCaseId !== this.caseId) {
        this.caseId = newCaseId
        this.reinitialise()
      }
    })
  }

  ngOnDestroy(): void {
    this.eventSource?.close()
  }

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
            const status = (event as import('@whoz-oss/agentos-api-client').CaseStatusEvent).status as string

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
      'AgentSelectedEvent',
      'AgentRunningEvent',
      'AgentFinishedEvent',
      'ThinkingEvent',
      'TextChunkEvent',
      'ToolRequestEvent',
      'ToolResponseEvent',
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
    if (event.key === 'Enter' && !event.shiftKey) {
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
    this.inputValue.set('')
    this.isRunning.set(false)
    this.isTerminal.set(false)
    this.streamingText.set('')
    this.collapsedTools.set(new Set())
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
}
