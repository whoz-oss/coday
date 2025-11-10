import { Injectable, inject } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { Observable, throwError } from 'rxjs'
import { catchError, map } from 'rxjs/operators'

export type ContentFormat = 'markdown' | 'json' | 'yaml' | 'text' | 'html'

export interface FileContent {
  content: string
  format: ContentFormat
  filename: string
  size: number
}

/**
 * ContentViewerService - Service for loading and processing file content
 *
 * Responsibilities:
 * - Load file content from exchange space
 * - Detect file format based on extension
 * - Validate file size limits
 */
@Injectable({
  providedIn: 'root',
})
export class ContentViewerService {
  private readonly http = inject(HttpClient)

  // Maximum viewable file size: 20 MB
  private readonly MAX_FILE_SIZE = 20 * 1024 * 1024

  /**
   * Check if a file is viewable based on size limit
   */
  isViewable(fileSize: number): boolean {
    return fileSize <= this.MAX_FILE_SIZE
  }

  /**
   * Check if a file format is viewable (text-based formats only)
   */
  isFormatViewable(filename: string): boolean {
    const ext = filename.split('.').pop()?.toLowerCase()

    // Explicitly list non-viewable binary formats
    const binaryExtensions = [
      'pdf',
      'png',
      'jpg',
      'jpeg',
      'gif',
      'webp',
      'zip',
      'tar',
      'gz',
      'exe',
      'bin',
      'doc',
      'docx',
      'xls',
      'xlsx',
      'ppt',
      'pptx',
    ]

    if (ext && binaryExtensions.includes(ext)) {
      return false
    }

    // All other formats are considered viewable as text
    return true
  }

  /**
   * Detect content format based on file extension
   */
  detectFormat(filename: string): ContentFormat {
    const ext = filename.split('.').pop()?.toLowerCase()
    switch (ext) {
      case 'md':
        return 'markdown'
      case 'json':
        return 'json'
      case 'yaml':
      case 'yml':
        return 'yaml'
      case 'html':
      case 'htm':
        return 'html'
      default:
        return 'text'
    }
  }

  /**
   * Load file content from thread files
   */
  loadFileContent(projectName: string, threadId: string, filename: string): Observable<FileContent> {
    const url = `/api/projects/${projectName}/threads/${threadId}/files/${encodeURIComponent(filename)}`
    const format = this.detectFormat(filename)

    return this.http
      .get(url, {
        responseType: 'text',
        observe: 'response',
      })
      .pipe(
        map((response) => {
          const content = response.body || ''
          const size = new Blob([content]).size

          return {
            content,
            format,
            filename,
            size,
          }
        }),
        catchError((error) => {
          console.error('[CONTENT_VIEWER] Error loading file:', filename, error)
          return throwError(() => new Error(`Failed to load file: ${error.message || 'Unknown error'}`))
        })
      )
  }

  /**
   * Format file size in human-readable format
   */
  formatSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  }
}
