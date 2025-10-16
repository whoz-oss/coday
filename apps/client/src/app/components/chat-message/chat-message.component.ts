import { Component, Input, Output, EventEmitter, OnInit, inject } from '@angular/core'
import { DomSanitizer, SafeHtml } from '@angular/platform-browser'
import { marked } from 'marked'
import { MessageContent } from '@coday/coday-events'
import { MessageContextMenuComponent, MenuAction } from '../message-context-menu/message-context-menu.component'
import { NgClass } from '@angular/common'
import { NotificationService } from '../../services/notification.service'

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant' | 'system'
  speaker: string
  content: MessageContent[] // Always rich content now
  timestamp: Date
  type: 'text' | 'error' | 'warning' | 'technical'
  eventId?: string // For event detail links
}

@Component({
  selector: 'app-chat-message',
  standalone: true,
  imports: [MessageContextMenuComponent, NgClass],
  templateUrl: './chat-message.component.html',
  styleUrl: './chat-message.component.scss',
})
export class ChatMessageComponent implements OnInit {
  @Input() message!: ChatMessage
  @Input() canDelete: boolean = true // Can this message be deleted (not first message, not during thinking)
  @Output() copyRequested = new EventEmitter<ChatMessage>()
  @Output() deleteRequested = new EventEmitter<ChatMessage>()

  renderedContent: SafeHtml = ''

  // Modern Angular dependency injection
  private sanitizer = inject(DomSanitizer)
  private notificationService = inject(NotificationService)

  get messageClasses() {
    return {
      [this.message.role]: true,
      [this.message.type]: true,
    }
  }

  async ngOnInit() {
    await this.renderMarkdown()
  }

  get shouldShowSpeaker(): boolean {
    // Show speaker for user and assistant, not for others
    return false
  }

  get shouldShowActions(): boolean {
    // Show actions only for user and assistant
    return this.message.role === 'user' || this.message.role === 'assistant'
  }

  get isSimplified(): boolean {
    // Simplified messages for everything except user/assistant
    return this.message.role !== 'user' && this.message.role !== 'assistant'
  }

  get isLongMessage(): boolean {
    const textContent = this.extractTextContent()
    return textContent.length > 1000 || textContent.split('\n').length > 20
  }

  get actions(): MenuAction[] {
    const actions: MenuAction[] = [
      {
        icon: 'content_copy',
        label: 'Copy message',
        tooltip: 'Copy message content to clipboard',
        action: () => this.onCopy(),
      },
    ]

    if (this.message.role === 'user' && this.canDelete) {
      actions.push({
        icon: 'delete',
        label: 'Delete from here',
        tooltip: 'Delete this message and all following messages',
        action: () => this.onDelete(),
        destructive: true,
      })
    }

    return actions
  }

  get eventLink(): string | null {
    if (!this.message.eventId) return null

    // Get clientId from URL
    const params = new URLSearchParams(window.location.search)
    const clientId = params.get('clientId')
    if (!clientId) return null

    return `/api/event/${this.message.eventId}?clientId=${clientId}`
  }

  private async renderMarkdown() {
    try {
      // Always use rich content now
      const html = await this.renderRichContent(this.message.content)
      this.renderedContent = this.sanitizer.bypassSecurityTrustHtml(html)
    } catch (error) {
      console.error('Error parsing rich content:', error)
      // Fallback: try to extract text and display it
      const textContent = this.extractTextContent()
      this.renderedContent = this.sanitizer.bypassSecurityTrustHtml(textContent)
    }
  }

  private async renderRichContent(content: MessageContent[]): Promise<string> {
    const htmlParts: string[] = []

    for (const item of content) {
      if (item.type === 'text') {
        // Parse markdown for text content
        const html = await marked.parse(item.content)
        htmlParts.push(`<div class="text-part">${html}</div>`)
      } else if (item.type === 'image') {
        // Create image element
        const imgSrc = `data:${item.mimeType};base64,${item.content}`
        const imgAlt = item.source || 'Image'
        const imgHtml = `
          <div class="image-content">
            <img src="${imgSrc}"
                 alt="${imgAlt}"
                 class="message-image"
                 style="max-width: 100%; height: auto; margin: 8px 0; border-radius: 8px; cursor: pointer; box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1); transition: transform 0.2s ease, box-shadow 0.2s ease;"
                 onclick="window.open(this.src, '_blank')" />
          </div>
        `
        htmlParts.push(imgHtml)
      }
    }

    return htmlParts.join('')
  }

  onCopy() {
    const textContent = this.extractTextContent()

    if (textContent.trim()) {
      navigator.clipboard
        .writeText(textContent)
        .then(() => {
          console.log('[CHAT-MESSAGE] Message copied to clipboard')
          this.notificationService.success('Message copied to clipboard')
        })
        .catch((err) => {
          console.error('[CHAT-MESSAGE] Failed to copy message:', err)
          this.notificationService.error('Failed to copy message')
        })
    }

    // Emit event for compatibility
    this.copyRequested.emit(this.message)
  }

  onDelete() {
    console.log('[CHAT-MESSAGE] Delete requested for message:', this.message.id)
    this.deleteRequested.emit(this.message)
  }

  /**
   * Extract text content from message content
   */
  private extractTextContent(): string {
    return this.message.content
      .filter((content) => content.type === 'text')
      .map((content) => content.content)
      .join('\n\n')
  }
}
