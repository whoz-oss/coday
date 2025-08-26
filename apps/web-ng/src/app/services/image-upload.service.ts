import { Injectable } from '@angular/core'

export interface ImageUploadResult {
  success: boolean
  processedSize?: number
  dimensions?: { width: number; height: number }
  error?: string
}

@Injectable({
  providedIn: 'root'
})
export class ImageUploadService {
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
   * Uploads an image file to the server
   */
  async uploadImage(file: File, clientId: string): Promise<ImageUploadResult> {
    console.log('[IMAGE-UPLOAD] uploadImage - file:', file.name, file.type, file.size)
    console.log('[IMAGE-UPLOAD] uploadImage - clientId:', clientId)
    
    try {
      console.log('[IMAGE-UPLOAD] Validating file...')
      this.validateFile(file)
      
      console.log('[IMAGE-UPLOAD] Converting to base64...')
      const content = await this.fileToBase64(file)
      console.log('[IMAGE-UPLOAD] Base64 length:', content.length)
      
      console.log('[IMAGE-UPLOAD] Sending upload request...')
      const response = await fetch(`/api/files/upload`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          clientId,
          content,
          mimeType: file.type,
          filename: file.name
        })
      })
      
      console.log('[IMAGE-UPLOAD] Response status:', response.status)
      
      if (!response.ok) {
        const error = await response.json()
        console.log('[IMAGE-UPLOAD] Upload error response:', error)
        throw new Error(error.error || 'Upload failed')
      }

      const result = await response.json()
      console.log('[IMAGE-UPLOAD] Upload success result:', result)
      return {
        success: true,
        processedSize: result.processedSize,
        dimensions: result.dimensions
      }
    } catch (error) {
      console.log('[IMAGE-UPLOAD] Upload failed:', error)
      return {
        success: false,
        error: error instanceof Error ? error.message : 'Upload failed'
      }
    }
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