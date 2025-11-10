import { Component, Output, EventEmitter, Input, inject } from '@angular/core'
import { CommonModule } from '@angular/common'
import { MatIconModule } from '@angular/material/icon'
import { MatButtonModule } from '@angular/material/button'

import { FileExchangeStateService } from '../../core/services/file-exchange-state.service'
import type { FileInfo } from '../../core/services/file-exchange-api.service'
import { ContentViewerComponent } from '../content-viewer/content-viewer.component'

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
    console.log('[FILE_DRAWER] View file:', file.filename)
    this.currentFile = file
    this.viewerState = 'content'
  }

  /**
   * Back to file list
   */
  backToList(): void {
    console.log('[FILE_DRAWER] Back to list')
    this.viewerState = 'list'
    this.currentFile = null
  }

  downloadAll(): void {
    console.log('[FILE_DRAWER] Download all files')
    this.fileExchangeState.downloadAllFiles()
  }

  download(file: FileInfo): void {
    console.log('[FILE_DRAWER] Download file:', file.filename)
    this.fileExchangeState.downloadFile(file.filename)
  }

  async delete(file: FileInfo): Promise<void> {
    console.log('[FILE_DRAWER] Delete file:', file.filename)

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
   * Format date in relative time
   */
  formatDate(date: Date): string {
    const now = new Date()
    const diffMs = now.getTime() - date.getTime()
    const diffMins = Math.floor(diffMs / (1000 * 60))
    const diffHours = Math.floor(diffMs / (1000 * 60 * 60))
    const diffDays = Math.floor(diffMs / (1000 * 60 * 60 * 24))

    if (diffMins < 1) return 'just now'
    if (diffMins < 60) return `${diffMins} min ago`
    if (diffHours < 24) return `${diffHours} hour${diffHours > 1 ? 's' : ''} ago`
    if (diffDays === 1) return 'yesterday'
    if (diffDays < 7) return `${diffDays} days ago`

    // Fallback to date string
    return date.toLocaleDateString()
  }
}
