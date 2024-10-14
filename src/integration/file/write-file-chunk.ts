import {existsSync, readFileSync, writeFileSync} from "node:fs"
import {Interactor} from "../../model"
import path from "path"

// Type definition for input parameters of the writeFileChunk function

type WriteFileChunkInput = {
  relPath: string
  root: string
  interactor: Interactor
  replacements: { oldPart: string, newPart: string }[]
}

// A threshold for the minimum length of the chunks to be replaced

const MINIMUM_CHUNK_LENGTH = 15

export const writeFileChunk = ({relPath, root, interactor, replacements}: WriteFileChunkInput) => {
  // need to prevent double slashes
  // Construct the absolute path ensuring no double slashes
  const fullPath = relPath ? path.resolve(root, relPath) : root
  
  try {
    // Notify the beginning of the write operation
    interactor.displayText(`Partial write on file ${fullPath}`)
    // Check if the file exists
    if (!existsSync(fullPath)) {
      const errorMessage = `No file found at ${fullPath}`
      interactor.error(errorMessage)
      return errorMessage
    }
    
    // Read the entire file content as a string
    let fileContent: string = readFileSync(fullPath, {encoding: "utf8"})
    let chunksNotFound: string[] = []
    let duplicateChunks: string[] = []
    let tooShortChunks: string[] = []
    
    // Perform replacements for each pair of old and new parts
    replacements.forEach(({oldPart, newPart}) => {
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
    writeFileSync(fullPath, fileContent, {encoding: "utf8"})
    
    let message: string = `` // Initialize a message to communicate the result // Initialize a message to communicate the result
    
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
    
    interactor.displayText(message)
    
    // Return the final outcome message
    return message
  } catch (err) {
    interactor.error(`Error processing file ${fullPath}`)
    console.error(err)
    return `Error processing file: ${err}`
  }
}

function formatChunks(chunks: string[]): string {
  return chunks.map(chunk => `\n- """${chunk}"""`).join(" ") + `\n`
}
