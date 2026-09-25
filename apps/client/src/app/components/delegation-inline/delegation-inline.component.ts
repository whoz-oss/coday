import { Component, Input, OnInit, OnDestroy, inject, ChangeDetectionStrategy } from '@angular/core'
import { CommonModule } from '@angular/common'
import { MatIconModule } from '@angular/material/icon'
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner'
import { ChatMessageComponent, ChatMessage } from '../chat-message/chat-message.component'
import { buildToolRequestFullContent } from '../chat-message/chat-message.utils'
import { CodayService } from '../../core/services/coday.service'
import { ThreadApiService } from '../../core/services/thread-api.service'
import { Subscription } from 'rxjs'
import { filter } from 'rxjs/operators'
import {
  AnswerEvent,
  buildCodayEvent,
  CodayEvent,
  DelegationEvent,
  DelegationStatus,
  DelegationStatusEvent,
  ErrorEvent,
  MessageEvent,
  TextEvent,
  ToolRequestEvent,
  ToolResponseEvent,
  TextChunkEvent,
} from '@coday/model'

@Component({
  selector: 'app-delegation-inline',
  standalone: true,
  imports: [CommonModule, MatIconModule, MatProgressSpinnerModule, ChatMessageComponent],
  templateUrl: './delegation-inline.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './delegation-inline.component.scss',
})
export class DelegationInlineComponent implements OnInit, OnDestroy {
  @Input() subThreadId!: string
  @Input() agentName!: string
  /**
   * Temporal window bounding this delegation occurrence.
   * windowStart: timestamp of this DelegationEvent (inclusive lower bound).
   * windowEnd: timestamp of the next DelegationEvent for the same subThreadId
   *            (exclusive upper bound), or undefined for the most recent occurrence.
   * When both are undefined (legacy / first occurrence with no resumption), all events pass.
   */
  @Input() windowStart?: string
  @Input() windowEnd?: string

  isExpanded = false
  isLoading = false
  subMessages: ChatMessage[] = []
  streamingText = ''

  /**
   * A block whose windowEnd is set is necessarily completed — it is not the last occurrence
   * of this subThreadId. For open-window blocks, status is updated from live DelegationStatusEvents.
   */
  get status(): DelegationStatus {
    return this._status
  }
  private _status: DelegationStatus = 'completed'

  private restLoaded = false
  private messageIds = new Set<string>()

  private eventSubscription?: Subscription
  private readonly codayService = inject(CodayService)
  private readonly threadApi = inject(ThreadApiService)

  get taskSummary(): string {
    const firstUser = this.subMessages.find((m) => m.role === 'user')
    if (firstUser) {
      const text = firstUser.content
        .filter((c) => c.type === 'text')
        .map((c) => c.content)
        .join(' ')
      return text.length > 120 ? text.substring(0, 120) + '...' : text
    }
    return ''
  }

  ngOnInit(): void {
    // A block whose windowEnd is already set is necessarily completed — it is not the
    // last occurrence of this subThreadId. Set status accordingly without waiting for
    // a DelegationStatusEvent (which is transient and not persisted).
    if (this.windowEnd !== undefined) {
      this._status = 'completed'
    }

    // Replay any events that arrived before this component was instantiated
    for (const event of this.codayService.getBufferedSubThreadEvents(this.subThreadId)) {
      this.handleSubThreadEvent(event)
    }

    // Subscribe to future events
    this.eventSubscription = this.codayService.subThreadEvents$
      .pipe(filter((event: CodayEvent) => event.threadId === this.subThreadId))
      .subscribe((event: CodayEvent) => this.handleSubThreadEvent(event))
  }

  ngOnDestroy(): void {
    this.eventSubscription?.unsubscribe()
  }

  toggle(): void {
    this.isExpanded = !this.isExpanded

    // Only load from REST on first expand — subsequent expands retain accumulated state
    if (this.isExpanded && !this.restLoaded) {
      this.loadSubThreadMessages()
    }
  }

  private loadSubThreadMessages(): void {
    this.isLoading = true

    this.threadApi.getThreadMessages(this.subThreadId).subscribe({
      next: (response) => {
        if (response.messages) {
          // Merge REST messages into existing state — deduplication via addMessage().
          // The window filter in handleSubThreadEvent() ensures that only events belonging
          // to this occurrence are shown, even though the REST call returns the complete
          // sub-thread history (all executions combined).
          for (const rawMsg of response.messages) {
            const event = buildCodayEvent(rawMsg)
            if (event) {
              this.handleSubThreadEvent(event)
            }
          }
          // Sort by timestamp to ensure chronological order after merge
          this.subMessages = [...this.subMessages].sort((a, b) => a.id.localeCompare(b.id))
        }
        this.restLoaded = true
        this.isLoading = false
      },
      error: (err) => {
        console.error('[DELEGATION-INLINE] Failed to load sub-thread messages:', err)
        this.restLoaded = true // Don't retry on error — SSE data is still available
        this.isLoading = false
      },
    })
  }

  /**
   * Returns true if this event falls within this block's temporal window.
   * The window is [windowStart, windowEnd):
   * - windowStart: inclusive lower bound (timestamp of this DelegationEvent)
   * - windowEnd: exclusive upper bound (timestamp of the next DelegationEvent for the
   *              same subThreadId), or undefined for the most recent occurrence (open window)
   *
   * When windowStart is undefined (legacy first-occurrence with no resumption), all events pass.
   * Comparison is lexicographic — timestamps are ISO + 5-char random suffix, so lex order
   * matches chronological order.
   */
  private isInWindow(event: CodayEvent): boolean {
    if (!this.windowStart) {
      // No window set — legacy mode, accept everything (backward-compatible)
      return true
    }
    if (event.timestamp.localeCompare(this.windowStart) < 0) {
      return false
    }
    if (this.windowEnd !== undefined && event.timestamp.localeCompare(this.windowEnd) >= 0) {
      return false
    }
    return true
  }

  private handleSubThreadEvent(event: CodayEvent): void {
    // Silent MessageEvents are kept in thread history for AI context but must not
    // be rendered as chat bubbles. This applies to nested delegation results:
    // when this sub-thread itself delegates and the result is injected back here,
    // it is already visible inside the nested DelegationInlineComponent.
    // Note: (event as any).silent is used because TypeScript's strict DOM lib may shadow
    // the @coday/model MessageEvent type in the instanceof check context.
    if (event instanceof MessageEvent && (event as any).silent) {
      return
    }

    // Filter events that fall outside this block's temporal window.
    // DelegationEvent (nested sub-delegation) is exempt: it has its own timestamp and
    // will be shown as a nested block — the nested component handles its own windowing.
    // DelegationStatusEvent is transient and not persisted; it is also exempt because
    // it does not carry a meaningful timestamp relative to the window.
    if (!(event instanceof DelegationEvent) && !(event instanceof DelegationStatusEvent) && !this.isInWindow(event)) {
      return
    }

    if (event instanceof AnswerEvent) {
      this.addMessage({
        id: event.timestamp,
        role: 'user',
        speaker: 'User',
        content: [{ type: 'text', content: event.answer }],
        timestamp: new Date(),
        type: 'text',
      })
    } else if (event instanceof TextChunkEvent) {
      if (this.isInWindow(event)) {
        this.streamingText += event.chunk
      }
    } else if (event instanceof MessageEvent) {
      if (event.role === 'assistant') {
        this.streamingText = ''
      }
      this.addMessage({
        id: event.timestamp,
        role: event.role,
        speaker: event.name,
        content: event.content,
        timestamp: new Date(),
        type: 'text',
      })
    } else if (event instanceof ToolRequestEvent) {
      this.addMessage({
        id: event.timestamp,
        role: 'system',
        speaker: 'System',
        content: [{ type: 'text', content: event.toSingleLineString() }],
        timestamp: new Date(),
        type: 'technical',
        eventId: event.timestamp,
        fullContent: buildToolRequestFullContent(event),
      })
    } else if (event instanceof ToolResponseEvent) {
      this.addMessage({
        id: event.timestamp,
        role: 'system',
        speaker: 'System',
        content: [{ type: 'text', content: event.toSingleLineString() }],
        timestamp: new Date(),
        type: 'technical',
        eventId: event.timestamp,
      })
    } else if (event instanceof DelegationStatusEvent) {
      // Only update status from live events if this block has an open window (it is the
      // last occurrence). Closed blocks (windowEnd set) are necessarily completed.
      if (this.windowEnd === undefined) {
        this._status = event.status
      }
    } else if (event instanceof DelegationEvent) {
      // Nested delegation: apply the same windowing logic recursively.
      // Find the last existing nested block for this subThreadId and close its window.
      const lastExistingIndex = this.subMessages.reduce(
        (lastIdx, m, i) => (m.subThreadId === event.subThreadId ? i : lastIdx),
        -1
      )
      if (lastExistingIndex !== -1) {
        const previous = this.subMessages[lastExistingIndex]
        const closed: ChatMessage = { ...previous, windowEnd: event.timestamp } as ChatMessage
        this.subMessages = [
          ...this.subMessages.slice(0, lastExistingIndex),
          closed,
          ...this.subMessages.slice(lastExistingIndex + 1),
        ]
      }
      this.addMessage({
        id: event.timestamp,
        role: 'system',
        speaker: event.agentName,
        content: [{ type: 'text', content: '' }],
        timestamp: new Date(),
        type: 'delegation',
        subThreadId: event.subThreadId,
        delegationAgentName: event.agentName,
        windowStart: event.timestamp,
        windowEnd: undefined,
      })
    } else if (event instanceof ErrorEvent) {
      this.addMessage({
        id: event.timestamp,
        role: 'system',
        speaker: 'System',
        content: [{ type: 'text', content: `Error: ${JSON.stringify(event.error)}` }],
        timestamp: new Date(),
        type: 'error',
      })
    } else if (event instanceof TextEvent) {
      this.addMessage({
        id: event.timestamp,
        role: 'system',
        speaker: event.speaker ?? 'System',
        content: [{ type: 'text', content: event.text }],
        timestamp: new Date(),
        type: 'technical',
      })
    }
  }

  private addMessage(message: ChatMessage): void {
    if (this.messageIds.has(message.id)) {
      return // Already have this message, skip
    }
    this.messageIds.add(message.id)
    this.subMessages = [...this.subMessages, message]
  }
}
