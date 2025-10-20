import { Injectable } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { Observable, catchError, map, of } from 'rxjs'

export interface ImageUploadResult {
  success: boolean
  processedSize?: number
  dimensions?: { width: number; height: number }
  error?: string
}

export interface ImageUploadResponse {
  success: boolean
  processedSize: number
  dimensions: { width: number; height: number }
}

@Injectable({
  providedIn: 'root'
})
export class ImageUploadService {
  constructor(private readonly http: HttpClient) {}
  private readonly maxFileSize = 5 * 1024 * 1024 // 5MB
  private readonly supportedTypes = ['image/png', 'image/jpeg', 'image/gif', 'image/webp']

  /**
   * Validates an image file
   */
  validateFile(file: File): void {
    if (!this.supportedTypes.includes(file.type)) {
      throw new Error(`Unsupported file type: ${file.type}`)
    }
    
    if (file.size > this.maxFileSize) {
      throw new Error(`File too large: ${(file.size / 1024 / 1024).toFixed(1)}MB exceeds 5MB limit`)
    }
  }

  /**
   * Converts a File to base64 string
   */
  async fileToBase64(file: File): Promise<string> {
    return new Promise((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => {
        const result = reader.result as string
        if (!result) {
          reject(new Error('Failed to read file'))
          return
        }
        const base64 = result.split(',')[1]
        if (!base64) {
          reject(new Error('Invalid file format'))
          return
        }
        resolve(base64)
      }
      reader.onerror = reject
      reader.readAsDataURL(file)
    })
  }

  /**
   * Build upload URL for a thread
   */
  private getUploadUrl(projectName: string, threadId: string): string {
    return `/api/projects/${projectName}/threads/${threadId}/upload`
  }

  /**
   * Uploads an image file to the server
   * Returns an Observable that emits ImageUploadResult
   */
  uploadImage(file: File, projectName: string, threadId: string): Observable<ImageUploadResult> {
    console.log('[IMAGE-UPLOAD] uploadImage - file:', file.name, file.type, file.size)
    console.log('[IMAGE-UPLOAD] uploadImage - project:', projectName, 'thread:', threadId)
    
    // Validate file synchronously
    try {
      console.log('[IMAGE-UPLOAD] Validating file...')
      this.validateFile(file)
    } catch (error) {
      console.log('[IMAGE-UPLOAD] Validation failed:', error)
      return of({
        success: false,
        error: error instanceof Error ? error.message : 'Validation failed'
      })
    }

    // Convert file to base64 and upload
    return new Observable<ImageUploadResult>(observer => {
      console.log('[IMAGE-UPLOAD] Converting to base64...')
      this.fileToBase64(file)
        .then(content => {
          console.log('[IMAGE-UPLOAD] Base64 length:', content.length)
          console.log('[IMAGE-UPLOAD] Sending upload request...')
          
          this.http.post<ImageUploadResponse>(
            this.getUploadUrl(projectName, threadId),
            {
              content,
              mimeType: file.type,
              filename: file.name
            }
          ).pipe(
            map(response => {
              console.log('[IMAGE-UPLOAD] Upload success result:', response)
              return {
                success: true,
                processedSize: response.processedSize,
                dimensions: response.dimensions
              } as ImageUploadResult
            }),
            catchError(error => {
              console.log('[IMAGE-UPLOAD] Upload failed:', error)
              const errorMessage = error.error?.error || error.message || 'Upload failed'
              return of({
                success: false,
                error: errorMessage
              } as ImageUploadResult)
            })
          ).subscribe({
            next: result => {
              observer.next(result)
              observer.complete()
            },
            error: error => observer.error(error)
          })
        })
        .catch(error => {
          console.log('[IMAGE-UPLOAD] Base64 conversion failed:', error)
          observer.next({
            success: false,
            error: error instanceof Error ? error.message : 'Failed to read file'
          })
          observer.complete()
        })
    })
  }

  /**
   * Checks if the drag event contains image files
   */
  hasImageFiles(dataTransfer: DataTransfer | null): boolean {
    console.log('[IMAGE-UPLOAD] hasImageFiles - dataTransfer:', dataTransfer)
    if (!dataTransfer) {
      console.log('[IMAGE-UPLOAD] hasImageFiles - no dataTransfer')
      return false
    }
    
    console.log('[IMAGE-UPLOAD] hasImageFiles - types:', Array.from(dataTransfer.types))
    // Check if we have files
    if (!Array.from(dataTransfer.types).includes('Files')) {
      console.log('[IMAGE-UPLOAD] hasImageFiles - no Files type')
      return false
    }
    
    // Check if any of the files are images
    const files = Array.from(dataTransfer.files || [])
    console.log('[IMAGE-UPLOAD] hasImageFiles - files:', files.map(f => ({ name: f.name, type: f.type })))
    const hasImages = files.some(file => this.supportedTypes.includes(file.type))
    console.log('[IMAGE-UPLOAD] hasImageFiles - result:', hasImages)
    return hasImages
  }

  /**
   * Filters image files from a file list
   */
  filterImageFiles(files: FileList | File[]): File[] {
    const fileArray = Array.from(files)
    return fileArray.filter(file => this.supportedTypes.includes(file.type))
  }
}