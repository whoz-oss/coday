import sharp from 'sharp'

export interface ProcessedImageResult {
  content: string // base64 encoded image data
  mimeType: 'image/jpeg' | 'image/png' | 'image/gif' | 'image/webp'
  width: number
  height: number
  originalWidth: number
  originalHeight: number
  originalSize: number // in bytes
  processedSize: number // in bytes
  wasResized: boolean
  wasCompressed: boolean
}

export interface ImageProcessingOptions {
  maxWidth: number
  maxHeight: number
  maxFileSize: number // in bytes
  quality: number // 0-100 for JPEG quality when converting
  preserveFormat: boolean // if false, converts large images to JPEG for better compression
  areaThresholdFactor: number // Factor to determine "large" images (e.g., 2.0 = 2x target area)
}

// Hardcoded sensible defaults - no configuration needed
const DEFAULT_OPTIONS: ImageProcessingOptions = {
  maxWidth: 1024,
  maxHeight: 1024,
  maxFileSize: 15 * 1024 * 1024, // 5MB
  quality: 80,
  preserveFormat: false, // Convert to JPEG for better compression by default
  areaThresholdFactor: 2.0 // Images 2x larger than target area are considered "large"
}

/**
 * Process raw image buffer with resizing and compression
 * Returns structured data with proper dimensions for AI providers
 */
export async function processImageBuffer(
  buffer: Buffer,
  originalMimeType: string,
  options: Partial<ImageProcessingOptions> = {}
): Promise<ProcessedImageResult> {
  const opts = { ...DEFAULT_OPTIONS, ...options }
  
  // Create Sharp instance once and reuse it
  let sharpInstance = sharp(buffer)
  
  // Get original metadata
  const originalMetadata = await sharpInstance.metadata()
  const originalWidth = originalMetadata.width || 0
  const originalHeight = originalMetadata.height || 0
  const originalSize = buffer.length

  // Check if file is too large
  if (originalSize > opts.maxFileSize) {
    throw new Error(
      `Image file too large: ${(originalSize / 1024 / 1024).toFixed(1)}MB exceeds ${(opts.maxFileSize / 1024 / 1024).toFixed(1)}MB limit`
    )
  }

  // Determine if resizing is needed
  const needsResize = originalWidth > opts.maxWidth || originalHeight > opts.maxHeight
  
  // Dynamic large image detection based on target dimensions
  // Calculate area threshold: target area * factor
  // This is more intelligent than a fixed file size threshold because:
  // - A 4K image (3840x2160) has 4x the pixels of 1080p, so should be compressed
  // - A small but uncompressed image might have large file size but few pixels
  // - This approach scales with our actual target dimensions
  const targetArea = opts.maxWidth * opts.maxHeight
  const originalArea = originalWidth * originalHeight
  const isLargeImage = originalArea > (targetArea * opts.areaThresholdFactor)
  
  const shouldConvertToJpeg = !opts.preserveFormat && isLargeImage && 
    (originalMimeType === 'image/png' || originalMimeType === 'image/webp')

  let processedBuffer = buffer
  let finalMimeType = originalMimeType as ProcessedImageResult['mimeType']
  let wasResized = false
  let wasCompressed = false

  // Process the image if needed
  if (needsResize || shouldConvertToJpeg) {
    // Reuse the same Sharp instance, clone it for processing
    sharpInstance = sharp(buffer)

    // Apply resizing if needed
    if (needsResize) {
      sharpInstance = sharpInstance.resize(opts.maxWidth, opts.maxHeight, {
        fit: 'inside', // Maintain aspect ratio
        withoutEnlargement: true // Don't upscale small images
      })
      wasResized = true
    }

    // Apply format conversion/compression if needed
    if (shouldConvertToJpeg) {
      sharpInstance = sharpInstance.jpeg({ quality: opts.quality })
      finalMimeType = 'image/jpeg'
      wasCompressed = true
    } else if (originalMimeType === 'image/jpeg' && (needsResize || isLargeImage)) {
      // Re-compress JPEG with quality setting if resizing or large
      sharpInstance = sharpInstance.jpeg({ quality: opts.quality })
      wasCompressed = true
    }

    processedBuffer = await sharpInstance.toBuffer()
  }

  // Get final metadata - create new Sharp instance for processed buffer if we processed it
  let finalWidth: number
  let finalHeight: number
  
  if (processedBuffer === buffer) {
    // No processing was done, use original metadata
    finalWidth = originalWidth
    finalHeight = originalHeight
  } else {
    // Image was processed, get new metadata
    const finalMetadata = await sharp(processedBuffer).metadata()
    finalWidth = finalMetadata.width || originalWidth
    finalHeight = finalMetadata.height || originalHeight
  }

  return {
    content: processedBuffer.toString('base64'),
    mimeType: finalMimeType,
    width: finalWidth,
    height: finalHeight,
    originalWidth,
    originalHeight,
    originalSize,
    processedSize: processedBuffer.length,
    wasResized,
    wasCompressed
  }
}

/**
 * Helper function to get MIME type from file extension
 */
export function getMimeTypeFromExtension(extension: string): string {
  switch (extension.toLowerCase()) {
    case '.png':
      return 'image/png'
    case '.jpg':
    case '.jpeg':
      return 'image/jpeg'
    case '.gif':
      return 'image/gif'
    case '.webp':
      return 'image/webp'
    default:
      return 'image/png' // fallback
  }
}

/**
 * Generate a human-readable description of the processing that was applied
 */
export function getProcessingDescription(result: ProcessedImageResult): string {
  const parts: string[] = []
  
  if (result.wasResized) {
    parts.push(`resized from ${result.originalWidth}x${result.originalHeight} to ${result.width}x${result.height}`)
  }
  
  if (result.wasCompressed) {
    parts.push('compressed')
  }
  
  const sizeReduction = result.originalSize - result.processedSize
  if (sizeReduction > 0) {
    const percentReduction = ((sizeReduction / result.originalSize) * 100).toFixed(1)
    parts.push(`${percentReduction}% size reduction`)
  }
  
  return parts.length > 0 ? parts.join(', ') : 'no processing needed'
}