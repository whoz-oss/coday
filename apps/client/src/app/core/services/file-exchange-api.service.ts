import { Injectable, inject } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { Observable } from 'rxjs'
import { map } from 'rxjs/operators'
import { ProjectStateService } from './project-state.service'
import { ThreadStateService } from './thread-state.service'

/**
 * FileInfo interface matching backend response
 */
export interface FileInfo {
  filename: string
  size: number
  lastModified: Date
}

/**
 * FileExchangeApiService - HTTP layer for file exchange operations
 *
 * Provides direct 1:1 mapping to backend REST endpoints in thread.routes.ts:
 * - POST /api/projects/:projectName/threads/:threadId/files/upload
 * - GET /api/projects/:projectName/threads/:threadId/files
 * - GET /api/projects/:projectName/threads/:threadId/files/:filename
 * - DELETE /api/projects/:projectName/threads/:threadId/files/:filename
 *
 * This service handles only HTTP communication, no business logic.
 * Business logic belongs in FileExchangeStateService.
 */
@Injectable({
  providedIn: 'root',
})
export class FileExchangeApiService {
  private http = inject(HttpClient)
  private projectState = inject(ProjectStateService)
  private threadState = inject(ThreadStateService)

  /**
   * Get base URL for file operations
   */
  private getBaseUrl(): string {
    return `/api/projects/${this.projectState.getSelectedProjectIdOrThrow()}/threads/${this.threadState.getSelectedThreadIdOrThrow()}/files`
  }

  /**
   * Upload a file to the thread's exchange space
   *
   * @param file - File to upload
   * @returns Observable with upload result containing filename
   */
  uploadFile(file: File): Observable<{ success: boolean; filename: string }> {
    const projectName = this.projectState.getSelectedProjectIdOrThrow()
    const threadId = this.threadState.getSelectedThreadIdOrThrow()
    // Convert file to base64 (same format as image upload)
    return new Observable((observer) => {
      const reader = new FileReader()
      reader.onload = () => {
        const result = reader.result as string
        const base64Content = result.split(',')[1] // Remove data:...;base64, prefix

        // Send as JSON with base64 content (same as image upload)
        // Note: endpoint is /upload not /files/upload
        this.http
          .post<{ success: boolean; type: string; filename: string; size: number }>(
            `/api/projects/${projectName}/threads/${threadId}/upload`,
            {
              content: base64Content,
              mimeType: file.type || 'application/octet-stream',
              filename: file.name,
            }
          )
          .subscribe({
            next: (response) => {
              observer.next({ success: response.success, filename: response.filename })
              observer.complete()
            },
            error: (error) => observer.error(error),
          })
      }
      reader.onerror = () => observer.error(new Error('Failed to read file'))
      reader.readAsDataURL(file)
    })
  }

  /**
   * List all files in the thread's exchange space
   *
   * @returns Observable with array of file information
   */
  listFiles(): Observable<FileInfo[]> {
    return this.http.get<{ files: FileInfo[] }>(this.getBaseUrl()).pipe(map((response) => response.files))
  }

  /**
   * Download a file from the thread's exchange space
   *
   * @param filename - Name of the file to download
   * @returns Observable with file blob
   */
  downloadFile(filename: string): Observable<Blob> {
    // Encode filename properly for URL - encodeURIComponent handles spaces and special chars
    const encodedFilename = encodeURIComponent(filename)
    return this.http.get(`${this.getBaseUrl()}/${encodedFilename}`, {
      responseType: 'blob',
    })
  }

  /**
   * Delete a file from the thread's exchange space
   *
   * @param filename - Name of the file to delete
   * @returns Observable with success response
   */
  deleteFile(filename: string): Observable<{ success: boolean; message: string }> {
    // Encode filename properly for URL
    const encodedFilename = encodeURIComponent(filename)
    return this.http.delete<{ success: boolean; message: string }>(`${this.getBaseUrl()}/${encodedFilename}`)
  }
}
