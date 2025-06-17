import { FileContent } from '../model/file-content'
import { readFileByPath } from './read-file-by-path'
import { readPdfFile } from './pdf-reader'
import { Interactor } from '../model'
import * as path from 'path'

interface FileReaderInput {
  relPath: string
  root: string
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

export const readFileUnified = async (input: FileReaderInput): Promise<FileContent> => {
  const extension = path.extname(input.relPath).toLowerCase()

  try {
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

// Backward compatibility helper that returns string
export const readFileUnifiedAsString = async (input: FileReaderInput): Promise<string> => {
  const result = await readFileUnified(input)

  if (result.type === 'error') {
    return result.content as string
  }
  if (result.type === 'text' && typeof result.content === 'string') {
    return result.content
  }
  return `[${result.type.toUpperCase()} CONTENT]`
}
