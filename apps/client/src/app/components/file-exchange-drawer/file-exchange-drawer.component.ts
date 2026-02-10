import { Component, Output, EventEmitter, Input, inject } from '@angular/core'
import { CommonModule } from '@angular/common'
import { MatIconModule } from '@angular/material/icon'
import { MatButtonModule } from '@angular/material/button'

import { FileExchangeStateService } from '../../core/services/file-exchange-state.service'
import type { FileInfo } from '../../core/services/file-exchange-api.service'
import { ContentViewerComponent } from '../content-viewer/content-viewer.component'
import { ContentViewerService } from '../../core/services/content-viewer.service'
import { formatDateWithTime } from '../../utils/date-format.utils'

type ViewerState = 'list' | 'content'

/**
 * FileExchangeDrawerComponent - Displays files in the thread's exchange space
 *
 * Two-state system:
 * - 'list': Shows file list with actions (download, delete, view)
 * - 'content': Shows file content viewer with back navigation
 *
 * This is the content component displayed inside the Material drawer.
 */
@Component({
  selector: 'app-file-exchange-drawer',
  standalone: true,
  imports: [CommonModule, MatIconModule, MatButtonModule, ContentViewerComponent],
  templateUrl: './file-exchange-drawer.component.html',
  styleUrl: './file-exchange-drawer.component.scss',
})
export class FileExchangeDrawerComponent {
  @Input({ required: true }) projectName!: string
  @Input({ required: true }) threadId!: string
  @Output() closeDrawer = new EventEmitter<void>()

  private readonly fileExchangeState = inject(FileExchangeStateService)
  private readonly contentViewerService = inject(ContentViewerService)

  // Connect to state service
  files = this.fileExchangeState.files
  isLoading = this.fileExchangeState.isLoading

  // Viewer state management
  viewerState: ViewerState = 'list'
  currentFile: FileInfo | null = null

  close(): void {
    // If in content view, go back to list
    if (this.viewerState === 'content') {
      this.backToList()
    } else {
      this.closeDrawer.emit()
    }
  }

  /**
   * View file content
   */
  viewFile(file: FileInfo): void {
    this.currentFile = file
    this.viewerState = 'content'
  }

  /**
   * Back to file list
   */
  backToList(): void {
    this.viewerState = 'list'
    this.currentFile = null
  }

  downloadAll(): void {
    this.fileExchangeState.downloadAllFiles()
  }

  download(file: FileInfo): void {
    this.fileExchangeState.downloadFile(file.filename)
  }

  async delete(file: FileInfo): Promise<void> {
    // Simple confirmation (we'll improve this later with a proper dialog)
    const confirmed = confirm(`Delete file "${file.filename}"?`)
    if (!confirmed) {
      return
    }

    const result = await this.fileExchangeState.deleteFile(file.filename)
    if (!result.success) {
      alert(`Failed to delete file: ${result.error}`)
    }
  }

  /**
   * Get Material icon name based on file extension
   */
  getFileIcon(filename: string): string {
    const ext = filename.split('.').pop()?.toLowerCase()
    switch (ext) {
      case 'pdf':
        return 'picture_as_pdf'
      case 'csv':
      case 'xlsx':
      case 'xls':
        return 'table_chart'
      case 'txt':
      case 'md':
        return 'description'
      case 'jpg':
      case 'jpeg':
      case 'png':
      case 'gif':
      case 'webp':
        return 'image'
      case 'zip':
      case 'tar':
      case 'gz':
        return 'folder_zip'
      default:
        return 'insert_drive_file'
    }
  }

  /**
   * Format file size in human-readable format
   */
  formatSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  }

  /**
   * Format date with relative time and precise time
   */
  formatDate(date: Date): string {
    return formatDateWithTime(date)
  }

  /**
   * Check if a file can be viewed (text-based formats and within size limit)
   */
  canViewFile(file: FileInfo): boolean {
    return this.contentViewerService.isFormatViewable(file.filename) && this.contentViewerService.isViewable(file.size)
  }
}
