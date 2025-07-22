export class ImageUploadHandler {
  private readonly maxFileSize = 5 * 1024 * 1024 // 5MB
  private readonly supportedTypes = ['image/png', 'image/jpeg', 'image/gif', 'image/webp']
  
  constructor(private clientId: string) {}

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
  async uploadImage(file: File): Promise<void> {
    this.validateFile(file)
    
    const content = await this.fileToBase64(file)
    
    const response = await fetch(`/api/files/upload`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        clientId: this.clientId,
        content,
        mimeType: file.type,
        filename: file.name
      })
    })
    
    if (!response.ok) {
      const error = await response.json()
        throw new Error(error.error || 'Upload failed')
    }
  }
}