import { HttpClient } from '@angular/common/http'
import { Component, computed, inject, NgZone, OnDestroy, OnInit, signal } from '@angular/core'
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

export type TimelineItem = { kind: 'message'; event: CaseMessageEvent } | { kind: 'tool'; call: ToolCall }

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

  private readonly config = inject(Configuration)
  private readonly caseId = this.route.snapshot.params['caseId'] as string
  private readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  private eventSource: EventSource | null = null

  protected readonly events = signal<CaseEvent[]>([])
  protected inputValue = signal('')
  protected isRunning = signal(false)

  /** Collapsed state per toolRequestId */
  protected readonly collapsedTools = signal<Set<string>>(new Set())

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
    return items
  })

  protected get canSend(): boolean {
    return !!this.inputValue().trim() && !this.isRunning()
  }

  ngOnInit(): void {
    this.connectSse()
  }

  ngOnDestroy(): void {
    this.eventSource?.close()
  }

  private connectSse(): void {
    const url = `${this.config.basePath}/api/cases/${this.caseId}/events`
    this.eventSource = this.zone.runOutsideAngular(() => new EventSource(url))

    // NOTE: the backend sends named SSE events ("event: MessageEvent", "event: CaseStatusEvent", ...)
    // In that case, `onmessage` is NOT called. We must subscribe to named events.
    const handler = (msg: globalThis.MessageEvent<string>) => {
      try {
        const event = JSON.parse(msg.data) as CaseEvent
        this.zone.run(() => {
          this.events.update((prev) => [...prev, event])
          // minimal running heuristic: running unless we explicitly receive STOPPED
          if (event.type === 'CaseStatusEvent') {
            const status = (event as import('@whoz-oss/agentos-api-client').CaseStatusEvent).status
            this.isRunning.set(status === 'RUNNING')
          } else {
            this.isRunning.set(true)
          }
        })
      } catch {
        console.warn('[CaseChat] Failed to parse SSE event', msg.data)
      }
    }

    // handle the different event names we see in the SSE stream
    this.eventSource.addEventListener('MessageEvent', handler)
    this.eventSource.addEventListener('CaseStatusEvent', handler)
    this.eventSource.addEventListener('AgentSelectedEvent', handler)
    this.eventSource.addEventListener('AgentRunningEvent', handler)
    this.eventSource.addEventListener('AgentFinishedEvent', handler)
    this.eventSource.addEventListener('ThinkingEvent', handler)
    this.eventSource.addEventListener('TextChunkEvent', handler)
    this.eventSource.addEventListener('ToolRequestEvent', handler)
    this.eventSource.addEventListener('ToolResponseEvent', handler)

    this.eventSource.onerror = () => {
      this.zone.run(() => this.isRunning.set(false))
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

  protected submit(): void {
    if (!this.canSend) return
    const content = this.inputValue().trim()
    this.inputValue.set('')
    this.isRunning.set(true)

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

  protected stop(): void {
    this.http.post(`${this.config.basePath}/api/cases/${this.caseId}/stop`, {}).subscribe({
      next: () => this.isRunning.set(false),
      error: (err) => console.error('[CaseChat] Failed to stop case', err),
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

  protected readonly _namespaceId = this.namespaceId
}
