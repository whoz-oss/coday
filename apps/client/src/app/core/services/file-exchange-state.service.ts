import { Injectable, inject, signal, computed } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { filter } from 'rxjs/operators'

import { FileExchangeApiService, FileInfo } from './file-exchange-api.service'
import { EventStreamService } from './event-stream.service'
import { FileEvent } from '@coday/coday-events'

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
    console.log('[FILE_EXCHANGE_STATE] Initializing for thread:', { projectName, threadId })

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
    console.log('[FILE_EXCHANGE_STATE] Clearing state')
    this.filesSignal.set([])
    this.currentProjectSignal.set(null)
    this.currentThreadSignal.set(null)
  }

  /**
   * Refresh the file list from backend
   */
  refreshFileList(): void {
    const projectName = this.currentProjectSignal()
    const threadId = this.currentThreadSignal()

    if (!projectName || !threadId) {
      console.warn('[FILE_EXCHANGE_STATE] Cannot refresh: no project or thread selected')
      return
    }

    console.log('[FILE_EXCHANGE_STATE] Refreshing file list')
    this.isLoadingSignal.set(true)

    this.fileApi.listFiles(projectName, threadId).subscribe({
      next: (files) => {
        console.log('[FILE_EXCHANGE_STATE] Files loaded:', files.length)
        // Convert lastModified strings to Date objects
        const processedFiles = files.map((file) => ({
          ...file,
          lastModified: new Date(file.lastModified),
        }))
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
    const projectName = this.currentProjectSignal()
    const threadId = this.currentThreadSignal()

    if (!projectName || !threadId) {
      console.error('[FILE_EXCHANGE_STATE] Cannot upload: no project or thread selected')
      return { success: false, error: 'No project or thread selected' }
    }

    console.log('[FILE_EXCHANGE_STATE] Uploading file:', file.name)

    return new Promise((resolve) => {
      this.fileApi.uploadFile(projectName, threadId, file).subscribe({
        next: (response) => {
          console.log('[FILE_EXCHANGE_STATE] Upload successful:', response.filename)
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
   * Download a file from the exchange space
   *
   * @param filename - Name of the file to download
   */
  downloadFile(filename: string): void {
    const projectName = this.currentProjectSignal()
    const threadId = this.currentThreadSignal()

    if (!projectName || !threadId) {
      console.error('[FILE_EXCHANGE_STATE] Cannot download: no project or thread selected')
      return
    }

    console.log('[FILE_EXCHANGE_STATE] Downloading file:', filename)

    this.fileApi.downloadFile(projectName, threadId, filename).subscribe({
      next: (blob) => {
        // Create download link
        const url = window.URL.createObjectURL(blob)
        const link = document.createElement('a')
        link.href = url
        link.download = filename
        link.click()
        window.URL.revokeObjectURL(url)
        console.log('[FILE_EXCHANGE_STATE] Download initiated:', filename)
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
    const projectName = this.currentProjectSignal()
    const threadId = this.currentThreadSignal()

    if (!projectName || !threadId) {
      console.error('[FILE_EXCHANGE_STATE] Cannot delete: no project or thread selected')
      return { success: false, error: 'No project or thread selected' }
    }

    console.log('[FILE_EXCHANGE_STATE] Deleting file:', filename)

    return new Promise((resolve) => {
      this.fileApi.deleteFile(projectName, threadId, filename).subscribe({
        next: (response) => {
          console.log('[FILE_EXCHANGE_STATE] Delete successful:', response.message)
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
   * Automatically refresh file list when FileEvent is received
   */
  private subscribeToFileEvents(): void {
    this.eventStream.events$
      .pipe(
        filter((event) => event instanceof FileEvent),
        takeUntilDestroyed()
      )
      .subscribe((fileEvent) => {
        console.log('[FILE_EXCHANGE_STATE] FileEvent received:', fileEvent)
        // Refresh file list when any file event occurs
        this.refreshFileList()
      })
  }
}
