import { processImageBuffer, getMimeTypeFromExtension, getProcessingDescription } from './image-processor'
import sharp from 'sharp'

describe('Image Processor', () => {
  describe('getMimeTypeFromExtension', () => {
    it('should return correct MIME types for common extensions', () => {
      expect(getMimeTypeFromExtension('.png')).toBe('image/png')
      expect(getMimeTypeFromExtension('.jpg')).toBe('image/jpeg')
      expect(getMimeTypeFromExtension('.jpeg')).toBe('image/jpeg')
      expect(getMimeTypeFromExtension('.gif')).toBe('image/gif')
      expect(getMimeTypeFromExtension('.webp')).toBe('image/webp')
      expect(getMimeTypeFromExtension('.unknown')).toBe('image/png') // fallback
    })
  })

  describe('processImageBuffer', () => {
    it('should handle small images without processing', async () => {
      // Create a small test image (100x100 PNG)
      const testBuffer = await sharp({
        create: {
          width: 100,
          height: 100,
          channels: 3,
          background: { r: 255, g: 0, b: 0 },
        },
      })
        .png()
        .toBuffer()

      const result = await processImageBuffer(testBuffer, 'image/png')

      expect(result.width).toBe(100)
      expect(result.height).toBe(100)
      expect(result.originalWidth).toBe(100)
      expect(result.originalHeight).toBe(100)
      expect(result.wasResized).toBe(false)
      expect(result.mimeType).toBe('image/png')
    })

    it('should resize large images', async () => {
      // Create a large test image (2048x1536)
      const testBuffer = await sharp({
        create: {
          width: 2048,
          height: 1536,
          channels: 3,
          background: { r: 0, g: 255, b: 0 },
        },
      })
        .png()
        .toBuffer()

      const result = await processImageBuffer(testBuffer, 'image/png')

      expect(result.originalWidth).toBe(2048)
      expect(result.originalHeight).toBe(1536)
      expect(result.width).toBeLessThanOrEqual(1024)
      expect(result.height).toBeLessThanOrEqual(1024)
      expect(result.wasResized).toBe(true)

      // Should maintain aspect ratio (2048/1536 = 4/3)
      const aspectRatio = result.width / result.height
      expect(aspectRatio).toBeCloseTo(4 / 3, 2)
    })

    it('should compress large images to JPEG', async () => {
      // Create a large PNG image that should trigger compression
      // Target area: 1024*1024 = 1,048,576 pixels
      // Threshold: 1,048,576 * 2 = 2,097,152 pixels
      // Create: 1800*1200 = 2,160,000 pixels (above threshold)
      const testBuffer = await sharp({
        create: {
          width: 1800,
          height: 1200, // 1800*1200 = 2,160,000 pixels > 2,097,152 (threshold)
          channels: 3,
          background: { r: 0, g: 0, b: 255 },
        },
      })
        .png()
        .toBuffer()

      const result = await processImageBuffer(testBuffer, 'image/png')

      expect(result.wasCompressed).toBe(true)
      expect(result.mimeType).toBe('image/jpeg')
      expect(result.processedSize).toBeLessThan(result.originalSize)
    })

    it('should respect area threshold factor for large image detection', async () => {
      // Create an image just above the threshold
      // Target area: 1024*1024 = 1,048,576 pixels
      // Threshold: 1,048,576 * 2 = 2,097,152 pixels
      // Create: 1500*1400 = 2,100,000 pixels (just above threshold)
      const testBuffer = await sharp({
        create: {
          width: 1500,
          height: 1400,
          channels: 3,
          background: { r: 128, g: 128, b: 128 },
        },
      })
        .png()
        .toBuffer()

      const result = await processImageBuffer(testBuffer, 'image/png')

      expect(result.wasCompressed).toBe(true) // Should be considered "large"
      expect(result.mimeType).toBe('image/jpeg')
    })

    it('should throw error for oversized files', async () => {
      // Create a very large valid image that exceeds 5MB limit
      // A large uncompressed image should easily exceed 5MB
      const largeBuffer = await sharp({
        create: {
          width: 3000,
          height: 3000, // 9 million pixels, uncompressed should be much larger than 5MB
          channels: 4, // RGBA for larger file size
          background: { r: 255, g: 255, b: 255, alpha: 1 },
        },
      })
        .png({ compressionLevel: 0 })
        .toBuffer() // No compression for maximum size

      // Verify the test image is actually larger than 5MB
      expect(largeBuffer.length).toBeGreaterThan(5 * 1024 * 1024)

      await expect(processImageBuffer(largeBuffer, 'image/png')).rejects.toThrow('Image file too large')
    })
  })

  describe('getProcessingDescription', () => {
    it('should describe no processing', () => {
      const result = {
        content: '',
        mimeType: 'image/png' as const,
        width: 100,
        height: 100,
        originalWidth: 100,
        originalHeight: 100,
        originalSize: 1000,
        processedSize: 1000,
        wasResized: false,
        wasCompressed: false,
      }

      expect(getProcessingDescription(result)).toBe('no processing needed')
    })

    it('should describe resizing and compression', () => {
      const result = {
        content: '',
        mimeType: 'image/jpeg' as const,
        width: 512,
        height: 384,
        originalWidth: 1024,
        originalHeight: 768,
        originalSize: 2000,
        processedSize: 800,
        wasResized: true,
        wasCompressed: true,
      }

      const description = getProcessingDescription(result)
      expect(description).toContain('resized from 1024x768 to 512x384')
      expect(description).toContain('compressed')
      expect(description).toContain('60.0% size reduction')
    })
  })
})
