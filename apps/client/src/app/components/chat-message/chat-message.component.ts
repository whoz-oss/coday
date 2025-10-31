import { Component, Input, Output, EventEmitter, OnInit, OnDestroy, inject } from '@angular/core'
import { DomSanitizer, SafeHtml } from '@angular/platform-browser'
import { MessageContent } from '@coday/coday-events'
import { MessageContextMenuComponent, MenuAction } from '../message-context-menu/message-context-menu.component'
import { NgClass } from '@angular/common'
import { NotificationService } from '../../services/notification.service'
import { PreferencesService } from '../../services/preferences.service'
import { ProjectStateService } from '../../core/services/project-state.service'
import { ThreadStateService } from '../../core/services/thread-state.service'
import { MarkdownService } from '../../services/markdown.service'
import { Subject } from 'rxjs'
import { takeUntil } from 'rxjs/operators'

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
export class ChatMessageComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>()
  @Input() message!: ChatMessage
  @Input() canDelete: boolean = true // Can this message be deleted (not first message, not during thinking)
  @Output() copyRequested = new EventEmitter<ChatMessage>()
  @Output() deleteRequested = new EventEmitter<ChatMessage>()

  renderedContent: SafeHtml = ''
  shouldHideTechnical = false
  shouldHideWarning = false

  // Modern Angular dependency injection
  private sanitizer = inject(DomSanitizer)
  private notificationService = inject(NotificationService)
  private preferencesService = inject(PreferencesService)
  private projectState = inject(ProjectStateService)
  private threadState = inject(ThreadStateService)
  private markdownService = inject(MarkdownService)

  get messageClasses() {
    return {
      [this.message.role]: true,
      [this.message.type]: true,
      'hidden-technical': this.shouldHideTechnical && this.message.type === 'technical',
      'hidden-warning': this.shouldHideWarning && this.message.type === 'warning',
    }
  }

  async ngOnInit() {
    await this.renderMarkdown()

    // Subscribe to hide technical messages preference
    this.preferencesService.hideTechnicalMessages$.pipe(takeUntil(this.destroy$)).subscribe((hide) => {
      this.shouldHideTechnical = hide
    })

    // Subscribe to hide warning messages preference
    this.preferencesService.hideWarningMessages$.pipe(takeUntil(this.destroy$)).subscribe((hide) => {
      this.shouldHideWarning = hide
    })

    // Initialize with current values
    this.shouldHideTechnical = this.preferencesService.getHideTechnicalMessages()
    this.shouldHideWarning = this.preferencesService.getHideWarningMessages()
  }

  ngOnDestroy(): void {
    this.destroy$.next()
    this.destroy$.complete()
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
    const actions: MenuAction[] = []

    if (this.message.role === 'user' && this.canDelete) {
      actions.push({
        icon: 'delete',
        label: 'Delete from here',
        tooltip: 'Delete this message and all following messages',
        action: () => this.onDelete(),
        destructive: true,
      })
    }

    actions.push({
      icon: 'content_copy',
      label: 'Copy message',
      tooltip: 'Copy message content to clipboard',
      action: () => this.onCopy(),
    })

    return actions
  }

  get eventLink(): string | null {
    if (!this.message.eventId) return null

    // Get project and thread from state services
    const projectName = this.projectState.getSelectedProjectId()
    const threadId = this.threadState.getSelectedThreadId()

    if (!projectName || !threadId) return null

    return `/api/projects/${projectName}/threads/${threadId}/messages/${encodeURIComponent(this.message.eventId)}/formatted`
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
        // Parse markdown for text content using MarkdownService
        const html = await this.markdownService.parse(item.content)
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
