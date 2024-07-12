import {existsSync, readFileSync, writeFileSync} from "node:fs"
import {Interactor} from "../../model/interactor"
import path from "path"

type WriteFileChunkInput = {
  relPath: string
  root: string
  interactor: Interactor
  replacements: { oldPart: string, newPart: string }[]
}

export const writeFileChunk = ({relPath, root, interactor, replacements}: WriteFileChunkInput) => {
  // need to prevent double slashes
  const fullPath = relPath ? path.resolve(root, relPath) : root
  
  try {
    interactor.displayText(`Partial write on file ${fullPath}`)
    if (!existsSync(fullPath)) {
      const errorMessage = `No file found at ${fullPath}`
      interactor.error(errorMessage)
      return errorMessage
    }
    
    // Read the entire file content
    let fileContent: string = readFileSync(fullPath, {encoding: "utf8"})
    let chunksNotFound: string[] = []
    
    // Perform replacements
    replacements.forEach(({oldPart, newPart}) => {
      if (!fileContent.includes(oldPart)) {
        chunksNotFound.push(oldPart)
      }
      fileContent = fileContent.replaceAll(oldPart, newPart)
    })
    
    // Write the modified content back to the file
    writeFileSync(fullPath, fileContent, {encoding: "utf8"})
    
    let message: string
    
    if (chunksNotFound.length === 0) {
      message = `File write successfully edited by chunks`
    } else if (chunksNotFound.length === replacements.length) {
      message = `None of the chunks were found`
    } else {
      message = `File edited, some chunks were not found`
    }
    
    interactor.displayText(message)
    
    return `${message}${chunksNotFound.length ? ` : ${chunksNotFound.toString()}` : ""}`
  } catch (err) {
    interactor.error(`Error processing file ${fullPath}`)
    console.error(err)
    return `Error processing file: ${err}`
  }
}
