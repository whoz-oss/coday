import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { Interactor } from '../../model'

type WriteFileChunkAbsoluteInput = {
  absolutePath: string
  interactor: Interactor
  replacements: { oldPart: string; newPart: string }[]
}

const MINIMUM_CHUNK_LENGTH = 15

export const writeFileChunkAbsolute = ({ absolutePath, interactor, replacements }: WriteFileChunkAbsoluteInput) => {
  if (!replacements || !Array.isArray(replacements) || !replacements.length) {
    return 'File not edited, `replacements` needs to be an array of `{oldPart: string; newPart: string}`.'
  }

  try {
    // Check if the file exists
    if (!existsSync(absolutePath)) {
      return `No file found at ${absolutePath}`
    }

    // Read the entire file content as a string
    let fileContent: string = readFileSync(absolutePath, { encoding: 'utf8' })
    let chunksNotFound: string[] = []
    let duplicateChunks: string[] = []
    let tooShortChunks: string[] = []

    // Perform replacements for each pair of old and new parts
    replacements.forEach(({ oldPart, newPart }) => {
      if (oldPart.length < MINIMUM_CHUNK_LENGTH) {
        tooShortChunks.push(oldPart)
        return
      }
      const occurrences = fileContent.split(oldPart).length - 1
      if (occurrences === 0) {
        chunksNotFound.push(oldPart)
      } else if (occurrences > 1) {
        duplicateChunks.push(oldPart)
      } else {
        fileContent = fileContent.replace(oldPart, newPart)
      }
    })

    // Write the modified content back to the file
    writeFileSync(absolutePath, fileContent, { encoding: 'utf8' })

    let message: string = ``

    if (chunksNotFound.length > 0) {
      message += `\nChunks not found: \n` + formatChunks(chunksNotFound)
    }
    if (duplicateChunks.length > 0) {
      message += `\nDuplicate chunks found: \n` + formatChunks(duplicateChunks)
    }
    if (tooShortChunks.length > 0) {
      message += `\nChunks too short: \n` + formatChunks(tooShortChunks)
    }
    if (!message) {
      message = `File write successfully edited by chunks`
    } else {
      message = `File edited with the following results:\n${message}`
    }

    return message
  } catch (err) {
    interactor.error(`Error processing file ${absolutePath}`)
    console.error(err)
    return `Error processing file: ${err}`
  }
}

function formatChunks(chunks: string[]): string {
  return chunks.map((chunk) => `\n- """${chunk}"""`).join(' ') + `\n`
}
