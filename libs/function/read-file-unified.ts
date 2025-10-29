import { FileContent } from '../model/file-content'
import { ImageContent, MessageContent } from '@coday/coday-events'
import { readFileByPath } from './read-file-by-path'
import { readPdfFile } from './pdf-reader'
import { Interactor } from '../model'
import { processImageBuffer, getMimeTypeFromExtension, getProcessingDescription } from './image-processor'
import * as path from 'path'
import * as fs from 'fs/promises'

interface FileReaderInput {
  relPath: string
  root: string
  interactor?: Interactor
}

interface FileReaderAbsoluteInput {
  absolutePath: string
  interactor?: Interactor
}

// Create wrapper functions that match the FileReaderInput interface
const readPdfWrapper = (input: FileReaderInput): Promise<FileContent> => {
  return readPdfFile({
    relPath: input.relPath,
    root: input.root,
    interactor: input.interactor!,
  })
}

// Helper function to read and process image files
const readImageFile = async (input: FileReaderInput): Promise<ImageContent> => {
  const fullPath = path.join(input.root, input.relPath)
  const extension = path.extname(input.relPath).toLowerCase()
  const fileName = path.basename(input.relPath)

  try {
    const buffer = await fs.readFile(fullPath)
    const originalMimeType = getMimeTypeFromExtension(extension)

    // Process the image with resizing and compression
    const processedImage = await processImageBuffer(buffer, originalMimeType)

    // Generate source description with processing info
    const originalSizeKB = (processedImage.originalSize / 1024).toFixed(1)
    const finalSizeKB = (processedImage.processedSize / 1024).toFixed(1)
    const processingDesc = getProcessingDescription(processedImage)

    let sourceDesc = `${fileName} (${finalSizeKB} KB`
    if (processedImage.originalSize !== processedImage.processedSize) {
      sourceDesc += `, was ${originalSizeKB} KB`
    }
    if (processingDesc !== 'no processing needed') {
      sourceDesc += `, ${processingDesc}`
    }
    sourceDesc += ')'

    return {
      type: 'image',
      content: processedImage.content,
      mimeType: processedImage.mimeType,
      source: sourceDesc,
      width: processedImage.width,
      height: processedImage.height,
    }
  } catch (error) {
    throw new Error(`Failed to read image file ${input.relPath}: ${error}`)
  }
}

export const readFileUnified = async (input: FileReaderInput): Promise<FileContent> => {
  const extension = path.extname(input.relPath).toLowerCase()

  try {
    // Handle image files
    if (['.png', '.jpg', '.jpeg', '.gif', '.webp'].includes(extension)) {
      return await readImageFile(input) // Store ImageContent in the content field
    }

    // Handle PDF files
    if (extension === '.pdf') {
      if (!input.interactor) {
        const errorMsg = `Interactor is required for PDF reading: ${input.relPath}`
        // Cannot call error on undefined interactor
        return {
          type: 'error',
          content: errorMsg,
        }
      }
      return await readPdfWrapper(input)
    }

    // Default to text file reading
    return await readFileByPath(input)
  } catch (error) {
    const errorMsg = `Failed to read file ${input.relPath}: ${error}`
    return {
      type: 'error',
      content: errorMsg,
    }
  }
}

// Enhanced helper that can return either string or MessageContent[]
export const readFileUnifiedAsString = async (input: FileReaderInput): Promise<string> => {
  const result: FileContent = await readFileUnified(input)

  if (result.type === 'error') {
    return result.content as string
  }
  if (result.type === 'text' && typeof result.content === 'string') {
    return result.content
  }
  if (result.type === 'image') {
    // For images, return the that it is an image to not overflow the caller
    return '[IMAGE CONTENT]'
  }
  return `[${result.type.toUpperCase()} CONTENT]`
}

// New helper that returns MessageContent for rich content tools
export const readFileUnifiedAsMessageContent = async (
  input: FileReaderInput | FileReaderAbsoluteInput
): Promise<string | MessageContent> => {
  // Convert absolute path to relPath + root if needed
  const fileReaderInput: FileReaderInput =
    'absolutePath' in input
      ? {
          relPath: path.basename(input.absolutePath),
          root: path.dirname(input.absolutePath),
          interactor: input.interactor,
        }
      : input

  const result = await readFileUnified(fileReaderInput)

  if (result.type === 'error') {
    return result.content as string
  }
  if (result.type === 'text') {
    return {
      type: 'text',
      content: typeof result.content === 'string' ? result.content : result.content.toString(),
    }
  }
  if (result.type === 'image') {
    return result
  }
  return `[${(result.type as string).toUpperCase()} CONTENT]`
}
