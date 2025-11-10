import { Component, Input, Output, EventEmitter, OnInit, OnDestroy, inject } from '@angular/core'
import { CommonModule } from '@angular/common'
import { MatIconModule } from '@angular/material/icon'
import { MatButtonModule } from '@angular/material/button'
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner'
import { Subject } from 'rxjs'
import { takeUntil, filter } from 'rxjs/operators'

import { ContentRendererComponent } from '../content-renderer/content-renderer.component'
import { ContentViewerService, type FileContent } from '../../core/services/content-viewer.service'
import type { FileInfo } from '../../core/services/file-exchange-api.service'
import { EventStreamService } from '../../core/services/event-stream.service'
import { FileEvent } from '@coday/coday-events'

/**
 * ContentViewerComponent - Displays file content with appropriate rendering
 *
 * Features:
 * - Header with back/close navigation
 * - Loading state during content fetch
 * - Error handling for large files or fetch failures
 * - Format-specific content rendering
 */
@Component({
  selector: 'app-content-viewer',
  standalone: true,
  imports: [CommonModule, MatIconModule, MatButtonModule, MatProgressSpinnerModule, ContentRendererComponent],
  templateUrl: './content-viewer.component.html',
  styleUrl: './content-viewer.component.scss',
})
export class ContentViewerComponent implements OnInit, OnDestroy {
  private readonly destroy$ = new Subject<void>()
  @Input({ required: true }) file!: FileInfo
  @Input({ required: true }) projectName!: string
  @Input({ required: true }) threadId!: string

  @Output() closeViewer = new EventEmitter<void>()

  private readonly contentService = inject(ContentViewerService)
  private readonly eventStream = inject(EventStreamService)

  fileContent: FileContent | null = null
  isLoading = false
  error = ''

  ngOnInit(): void {
    this.loadContent()
    this.subscribeToFileEvents()
  }

  ngOnDestroy(): void {
    this.destroy$.next()
    this.destroy$.complete()
  }

  private loadContent(): void {
    // Check file size before loading
    if (!this.contentService.isViewable(this.file.size)) {
      this.error = `File too large to view (${this.contentService.formatSize(this.file.size)}). Maximum size: 20 MB.`
      return
    }

    this.isLoading = true
    this.error = ''

    this.contentService.loadFileContent(this.projectName, this.threadId, this.file.filename).subscribe({
      next: (content) => {
        console.log('[CONTENT_VIEWER] Content loaded:', content.filename)
        this.fileContent = content
        this.isLoading = false
      },
      error: (error) => {
        console.error('[CONTENT_VIEWER] Error loading content:', error)
        this.error = error.message || 'Failed to load file content'
        this.isLoading = false
      },
    })
  }

  onClose(): void {
    this.closeViewer.emit()
  }

  download(): void {
    // Create a download link
    const blob = new Blob([this.fileContent?.content || ''], { type: 'text/plain' })
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = this.file.filename
    link.click()
    window.URL.revokeObjectURL(url)
  }

  formatSize(bytes: number): string {
    return this.contentService.formatSize(bytes)
  }

  /**
   * Subscribe to FileEvent to reload content when the current file is updated
   */
  private subscribeToFileEvents(): void {
    this.eventStream.events$
      .pipe(
        filter((event) => event instanceof FileEvent),
        filter((event: FileEvent) => event.filename === this.file.filename),
        filter((event: FileEvent) => event.operation === 'updated'),
        takeUntil(this.destroy$)
      )
      .subscribe((event: FileEvent) => {
        console.log('[CONTENT_VIEWER] File updated event received, reloading:', event.filename)
        // Update file size if provided
        if (event.size) {
          this.file.size = event.size
        }
        // Reload content
        this.loadContent()
      })
  }
}
