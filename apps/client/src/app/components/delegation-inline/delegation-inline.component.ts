import { Component, Input, OnInit, OnDestroy, inject } from '@angular/core'
import { CommonModule } from '@angular/common'
import { MatIconModule } from '@angular/material/icon'
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner'
import { ChatMessageComponent, ChatMessage } from '../chat-message/chat-message.component'
import { CodayService } from '../../core/services/coday.service'
import { ThreadApiService } from '../../core/services/thread-api.service'
import { Subscription } from 'rxjs'
import { filter } from 'rxjs/operators'
import {
  AnswerEvent,
  buildCodayEvent,
  CodayEvent,
  MessageEvent,
  TextEvent,
  ToolRequestEvent,
  ToolResponseEvent,
  TextChunkEvent,
  DelegationEvent,
} from '@coday/model'

@Component({
  selector: 'app-delegation-inline',
  standalone: true,
  imports: [CommonModule, MatIconModule, MatProgressSpinnerModule, ChatMessageComponent],
  templateUrl: './delegation-inline.component.html',
  styleUrl: './delegation-inline.component.scss',
})
export class DelegationInlineComponent implements OnInit, OnDestroy {
  @Input() subThreadId!: string
  @Input() agentName!: string

  isExpanded = false
  isLoading = false
  subMessages: ChatMessage[] = []
  streamingText = ''

  private hasLoadedFromRest = false
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

    // On first expand, if no messages from live session, fetch from REST
    if (this.isExpanded && this.subMessages.length === 0 && !this.hasLoadedFromRest) {
      this.loadSubThreadMessages()
    }
  }

  private loadSubThreadMessages(): void {
    this.isLoading = true
    this.hasLoadedFromRest = true

    this.threadApi.getThreadMessages(this.subThreadId).subscribe({
      next: (response) => {
        // Only populate if still no messages (SSE might have arrived in the meantime)
        if (this.subMessages.length === 0 && response.messages) {
          for (const rawMsg of response.messages) {
            const event = buildCodayEvent(rawMsg)
            if (event) {
              this.handleSubThreadEvent(event)
            }
          }
        }
        this.isLoading = false
      },
      error: (err) => {
        console.error('[DELEGATION-INLINE] Failed to load sub-thread messages:', err)
        this.isLoading = false
      },
    })
  }

  private handleSubThreadEvent(event: CodayEvent): void {
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
      this.streamingText += event.chunk
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
    } else if (event instanceof DelegationEvent) {
      this.addMessage({
        id: event.timestamp,
        role: 'system',
        speaker: event.agentName,
        content: [{ type: 'text', content: '' }],
        timestamp: new Date(),
        type: 'delegation',
        subThreadId: event.subThreadId,
        delegationAgentName: event.agentName,
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
    this.subMessages = [...this.subMessages, message]
  }
}
