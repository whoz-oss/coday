import { Injectable, inject, signal, computed } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { filter } from 'rxjs/operators'

import { FileExchangeApiService, FileInfo } from './file-exchange-api.service'
import { EventStreamService } from './event-stream.service'
import { FileEvent } from '@coday/model'

/**
 * FileExchangeStateService - Business logic and state management for file exchange
 *
 * Responsibilities:
 * - Maintain reactive state of files in the exchange space
 * - Subscribe to FileEvent from EventStreamService for real-time updates
 * - Coordinate API calls with UI state
 * - Provide high-level methods for components
 *
 * Components should use this service, never FileExchangeApiService directly.
 */
@Injectable({
  providedIn: 'root',
})
export class FileExchangeStateService {
  private fileApi = inject(FileExchangeApiService)
  private eventStream = inject(EventStreamService)

  // Reactive state
  private filesSignal = signal<FileInfo[]>([])
  private isLoadingSignal = signal(false)
  private currentProjectSignal = signal<string | null>(null)
  private currentThreadSignal = signal<string | null>(null)

  // Public observables
  files = computed(() => this.filesSignal())
  isLoading = computed(() => this.isLoadingSignal())
  fileCount = computed(() => this.filesSignal().length)

  constructor() {
    // Subscribe to FileEvent for real-time updates
    this.subscribeToFileEvents()
  }

  /**
   * Initialize file list for a specific thread
   *
   * @param projectName - Project name
   * @param threadId - Thread ID
   */
  initializeForThread(projectName: string, threadId: string): void {
    // Update current context
    this.currentProjectSignal.set(projectName)
    this.currentThreadSignal.set(threadId)

    // Load file list
    this.refreshFileList()
  }

  /**
   * Clear state (when leaving thread)
   */
  clear(): void {
    this.filesSignal.set([])
    this.currentProjectSignal.set(null)
    this.currentThreadSignal.set(null)
  }

  /**
   * Refresh the file list from backend
   */
  refreshFileList(): void {
    const threadId = this.currentThreadSignal()

    if (!threadId) {
      console.warn('[FILE_EXCHANGE_STATE] Cannot refresh: no thread selected')
      return
    }

    this.isLoadingSignal.set(true)

    this.fileApi.listFiles(threadId).subscribe({
      next: (files) => {
        // Convert lastModified strings to Date objects and sort by most recent first
        const processedFiles = files
          .map((file) => ({
            ...file,
            lastModified: new Date(file.lastModified),
          }))
          .sort((a, b) => b.lastModified.getTime() - a.lastModified.getTime())
        this.filesSignal.set(processedFiles)
        this.isLoadingSignal.set(false)
      },
      error: (error) => {
        console.error('[FILE_EXCHANGE_STATE] Error loading files:', error)
        this.isLoadingSignal.set(false)
      },
    })
  }

  /**
   * Upload a file to the exchange space
   *
   * @param file - File to upload
   * @returns Promise that resolves when upload completes
   */
  async uploadFile(file: File): Promise<{ success: boolean; error?: string }> {
    const threadId = this.currentThreadSignal()

    if (!threadId) {
      console.error('[FILE_EXCHANGE_STATE] Cannot upload: no thread selected')
      return { success: false, error: 'No thread selected' }
    }

    return new Promise((resolve) => {
      this.fileApi.uploadFile(threadId, file).subscribe({
        next: () => {
          // Refresh file list to show new file
          this.refreshFileList()
          resolve({ success: true })
        },
        error: (error) => {
          console.error('[FILE_EXCHANGE_STATE] Upload error:', error)
          resolve({ success: false, error: error.message || 'Upload failed' })
        },
      })
    })
  }

  /**
   * Download all files from the exchange space
   * Files are downloaded sequentially with a small delay to avoid overwhelming the browser
   */
  downloadAllFiles(): void {
    const files = this.filesSignal()
    if (files.length === 0) {
      console.warn('[FILE_EXCHANGE_STATE] No files to download')
      return
    }

    // Download files sequentially with small delay between each
    files.forEach((file, index) => {
      setTimeout(() => {
        this.downloadFile(file.filename)
      }, index * 300) // 300ms delay between downloads
    })
  }

  /**
   * Download a file from the exchange space
   *
   * @param filename - Name of the file to download
   */
  downloadFile(filename: string): void {
    const threadId = this.currentThreadSignal()

    if (!threadId) {
      console.error('[FILE_EXCHANGE_STATE] Cannot download: no thread selected')
      return
    }

    this.fileApi.downloadFile(threadId, filename).subscribe({
      next: (blob) => {
        // Create download link
        const url = window.URL.createObjectURL(blob)
        const link = document.createElement('a')
        link.href = url
        link.download = filename
        link.click()
        window.URL.revokeObjectURL(url)
      },
      error: (error) => {
        console.error('[FILE_EXCHANGE_STATE] Download error:', error)
      },
    })
  }

  /**
   * Delete a file from the exchange space
   *
   * @param filename - Name of the file to delete
   * @returns Promise that resolves when deletion completes
   */
  async deleteFile(filename: string): Promise<{ success: boolean; error?: string }> {
    const threadId = this.currentThreadSignal()

    if (!threadId) {
      console.error('[FILE_EXCHANGE_STATE] Cannot delete: no thread selected')
      return { success: false, error: 'No thread selected' }
    }

    return new Promise((resolve) => {
      this.fileApi.deleteFile(threadId, filename).subscribe({
        next: () => {
          // Refresh file list to remove deleted file
          this.refreshFileList()
          resolve({ success: true })
        },
        error: (error) => {
          console.error('[FILE_EXCHANGE_STATE] Delete error:', error)
          resolve({ success: false, error: error.message || 'Delete failed' })
        },
      })
    })
  }

  /**
   * Subscribe to FileEvent from EventStreamService
   * Update file list incrementally based on the event operation
   */
  private subscribeToFileEvents(): void {
    this.eventStream.events$
      .pipe(
        filter((event) => event instanceof FileEvent),
        takeUntilDestroyed()
      )
      .subscribe((fileEvent) => {
        this.handleFileEvent(fileEvent as FileEvent)
      })
  }

  /**
   * Handle file event incrementally without full refresh
   */
  private handleFileEvent(event: FileEvent): void {
    const currentFiles = this.filesSignal()

    switch (event.operation) {
      case 'created': {
        // Add new file at the beginning (most recent)
        // Extract just the ISO date part if timestamp has random suffix
        // Format: "2026-02-09T16:57:20.839Z-x81ku" (ISO + dash + random suffix)
        // We need to remove everything after the last dash if it looks like a random suffix
        let dateStr = event.timestamp
        const lastDashIndex = event.timestamp.lastIndexOf('-')

        // Check if there's a suffix after the last dash (5 chars random suffix)
        if (lastDashIndex > 0) {
          const afterLastDash = event.timestamp.substring(lastDashIndex + 1)
          // If it's a short alphanumeric string (random suffix), remove it
          if (afterLastDash.length === 5 && /^[a-z0-9]+$/.test(afterLastDash)) {
            dateStr = event.timestamp.substring(0, lastDashIndex)
          }
        }

        const newFile: FileInfo = {
          filename: event.filename,
          size: event.size || 0,
          lastModified: new Date(dateStr),
        }
        this.filesSignal.set([newFile, ...currentFiles])
        break
      }

      case 'updated': {
        // Update existing file and move it to the top
        const updatedFiles = currentFiles.filter((f) => f.filename !== event.filename)
        const existingFile = currentFiles.find((f) => f.filename === event.filename)

        // Extract just the ISO date part if timestamp has random suffix
        let dateStr = event.timestamp
        const lastDashIndex = event.timestamp.lastIndexOf('-')

        // Check if there's a suffix after the last dash (5 chars random suffix)
        if (lastDashIndex > 0) {
          const afterLastDash = event.timestamp.substring(lastDashIndex + 1)
          // If it's a short alphanumeric string (random suffix), remove it
          if (afterLastDash.length === 5 && /^[a-z0-9]+$/.test(afterLastDash)) {
            dateStr = event.timestamp.substring(0, lastDashIndex)
          }
        }

        const updatedFile: FileInfo = {
          filename: event.filename,
          size: event.size || existingFile?.size || 0,
          lastModified: new Date(dateStr),
        }
        this.filesSignal.set([updatedFile, ...updatedFiles])
        break
      }

      case 'deleted': {
        // Remove file from list
        const filteredFiles = currentFiles.filter((f) => f.filename !== event.filename)
        this.filesSignal.set(filteredFiles)
        break
      }
    }
  }
}
