import { Component, Input, OnInit, OnDestroy, inject } from '@angular/core'
import { CommonModule } from '@angular/common'
import { MatIconModule } from '@angular/material/icon'
import { ChatMessageComponent, ChatMessage } from '../chat-message/chat-message.component'
import { CodayService } from '../../core/services/coday.service'
import { Subscription } from 'rxjs'
import { filter } from 'rxjs/operators'
import {
  AnswerEvent,
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
  imports: [CommonModule, MatIconModule, ChatMessageComponent],
  templateUrl: './delegation-inline.component.html',
  styleUrl: './delegation-inline.component.scss',
})
export class DelegationInlineComponent implements OnInit, OnDestroy {
  @Input() subThreadId!: string
  @Input() agentName!: string

  isExpanded = false
  subMessages: ChatMessage[] = []
  streamingText = ''

  private eventSubscription?: Subscription
  private readonly codayService = inject(CodayService)

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
