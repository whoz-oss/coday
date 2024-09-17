import {readFileSync} from "fs"
import {existsSync} from "node:fs"
import {Interactor} from "../model"
import path from "path"

type ReadFileByPathInput = {
  relPath: string
  root: string
  interactor?: Interactor
}

export const readFileByPath = (input: ReadFileByPathInput) => {
  const {relPath, root, interactor} = input
  // need to prevent double slashes
  const fullPath = relPath ? path.resolve(root, relPath) : root
  try {
    interactor?.displayText(`reading file ${fullPath}`)
    if (existsSync(fullPath)) {
      return readFileSync(fullPath).toString()
    } else {
      return "File does not exist or path incorrect"
    }
    
  } catch (err) {
    interactor?.error(`Error reading file ${fullPath}`)
    console.error(err)
    return "Error reading file"
  }
}