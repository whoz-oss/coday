import { readFileSync } from 'fs'
import { Interactor } from '../model'
import path from 'path'

type ReadFileByPathInput = {
  relPath: string
  root: string
  interactor?: Interactor
}

export const readFileByPath = (input: ReadFileByPathInput) => {
  const { relPath, root, interactor } = input
  // need to prevent double slashes
  const fullPath = relPath ? path.resolve(root, relPath) : root
  try {
    interactor?.displayText(`reading file ${fullPath}`)
    return readFileSync(fullPath).toString()
  } catch (err) {
    interactor?.error(`Error reading file ${fullPath}`)
    console.error(err)
    return 'Error reading file'
  }
}
